package kr.teamagent.common.security;

import egovframework.com.sym.log.clg.service.EgovLoginLogService;
import egovframework.com.sym.log.clg.service.LoginLog;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import kr.teamagent.common.secure.service.JwtSecureServiceImpl;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.security.service.impl.LoginServiceImpl;
import kr.teamagent.common.security.service.impl.OtpServiceImpl;
import kr.teamagent.common.system.service.impl.CommonServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.DataSourceType;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang.LocaleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;

import javax.annotation.Resource;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class CustomLoginSuccessHandler implements AuthenticationSuccessHandler {
	private static final Logger log = LoggerFactory.getLogger(CustomLoginSuccessHandler.class);

	@Autowired
	private LoginServiceImpl loginService;

	@Autowired
	private CommonServiceImpl commonService;

	@Autowired
	private OtpServiceImpl otpService;

	@Autowired
	private  JwtSecureServiceImpl jwtSecureServiceImpl;
	@Resource(name="EgovLoginLogService")
	private EgovLoginLogService loginLogService;

	private String DEFAULT_URL;
	private String DEFAULT_OTP_URL;
	private String NO_SERVICE_URL;
	private String NO_CONNECTION_URL;

	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

	private void clearAuthenticationAttributes(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if(session == null) {
			return;
		}
		session.removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
			throws IOException, ServletException {
		clearAuthenticationAttributes(request);

		log.debug("\n#### CustomLoginSuccessHandler.onAuthenticationSuccess ###");
		log.debug("login success");

		UserVO userVO = (UserVO)authentication.getPrincipal();

		final String compUrlPath = "";
		final String defaultUrl 	= compUrlPath + DEFAULT_URL;
		final String defaultOtpUrl 	= compUrlPath + DEFAULT_OTP_URL;
		final String noServiceUrl 	= compUrlPath + NO_SERVICE_URL;
		final String noConnectionUrl= compUrlPath + NO_CONNECTION_URL;

		try {

			Locale locale = LocaleUtils.toLocale(userVO.getLang());
			SessionUtil.setAttribute("compId", CommonUtil.nullToBlank(userVO.getCompId()));
			SessionUtil.setAttribute("templateDbId", PropertyUtil.getProperty("Globals.Template.db"));
			SessionUtil.setAttribute("templateCompId", PropertyUtil.getProperty("Template.CompId"));
			SessionUtil.setAttribute("masterDbId", PropertyUtil.getProperty("Globals.Master.db"));
			SessionUtil.setAttribute("masterCompId", PropertyUtil.getProperty("Master.CompId"));
			SessionUtil.setAttribute("connectionId", CommonUtil.nullToBlank(userVO.getConnectionId()));
			SessionUtil.setAttribute("compDbId", CommonUtil.nullToBlank(userVO.getDbId()));
			SessionUtil.setAttribute("userId", CommonUtil.nullToBlank(userVO.getUserId()));
			SessionUtil.setAttribute("uniqueUserId", CommonUtil.nullToBlank(userVO.getCompId()) + "-" + CommonUtil.nullToBlank(userVO.getUserId()));
			SessionUtil.setAttribute("onbChatGbn", "");
			SessionUtil.setAttribute("onbChatTarget", "");
			SessionUtil.setAttribute("onbChatId", "");
			SessionUtil.setAttribute(SessionLocaleResolver.LOCALE_SESSION_ATTRIBUTE_NAME, locale);
			SessionUtil.setAttribute("stAreaCds", commonService.selectUserStrAreaList(userVO));
			LocaleContextHolder.setLocale(locale);

			//회사 아이콘 존재여부 조회
			String fileStorePath = PropertyUtil.getProperty("Globals.fileStorePath");
			String compIconImg = String.format("%scompIcon/%s/%s_compIcon.png", fileStorePath, CommonUtil.nullToBlank(userVO.getCompId()), CommonUtil.nullToBlank(userVO.getCompId()));

			if (new File(compIconImg).exists()){
				SessionUtil.setAttribute("compIconYn", "Y");
			}else {
				SessionUtil.setAttribute("compIconYn", "N");
			}

			EgovMap loginSuccessHandlerData = loginService.selectLoginSuccessHandlerData(userVO);

			// 서비스 사용여부 조회
			String serviceUseYn = CommonUtil.removeNull((String)loginSuccessHandlerData.get("serviceYn"), "N");
			if(serviceUseYn.equals("N")) {
				redirectStrategy.sendRedirect(request, response, noServiceUrl);
				return;
			}

			if("".equals(CommonUtil.nullToBlank(userVO.getConnectionId()))) {
				redirectStrategy.sendRedirect(request, response, noConnectionUrl);
				return;
			}

			if("".equals(DataSourceType.valueOf(userVO.getConnectionId()))) {
				redirectStrategy.sendRedirect(request, response, noConnectionUrl);
				return;
			}

			SessionUtil.setAttribute("lang", "ko");
			userVO.setIp(CommonUtil.getUserIP(request));

			SessionUtil.setAttribute("loginVO", userVO);

			// 검색조건 쿠키 초기화
			Cookie[] cookies = request.getCookies();
			for(Cookie cookie : cookies) {
				if(!cookie.getName().replaceAll("\r", "").replaceAll("\n", "").startsWith("find")) continue;

				cookie.setValue("");
				cookie.setMaxAge(0);
				cookie.setPath("");
				response.addCookie(cookie);
			}

			//수시과제 제외한 인사평가 서비스를 이용하는지에 대한 값 (Y,N) 세션 저장
			//String isOnlyInsa = loginService.selectIsOnlyInsa(userVO);
			String isOnlyInsa = String.valueOf(loginSuccessHandlerData.get("isOnlyInsa"));
			SessionUtil.setAttribute("isOnlyInsa",isOnlyInsa);


			// 로그인 로그
			if(CommonUtil.isProdServer()){
				LoginLog loginLog = new LoginLog();
				loginLog.setDbId(userVO.getDbId());
				loginLog.setCompId(userVO.getCompId());
				loginLog.setLoginId(userVO.getUserId());
				loginLog.setLoginIp(userVO.getIp());
				loginLog.setLoginMthd("I");	// 로그인:I, 로그아웃:O
				loginLog.setErrOccrrAt("N");
				loginLog.setErrorCode("");
				loginLogService.logInsertLoginLog(loginLog);
			}


			log.debug("#######################\n");

		} catch (IOException ie) {
			log.error("error : "+ie.getCause());
		} catch (Exception e) {
			log.error("error : "+e.getCause());
		}

		//로그인 성공후 이동 페이지
		String targetUrl = defaultUrl;

		/**
		 * 메뉴 바로가기(/main.do?token=) 요청시
		 * 업무 바로가기(/share/plan) 요청시
		 * 세션이 없는 경우 -> 로그인 성공 -> 원래 요청 페이지로 바로 이동
		 */
		HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
		SavedRequest savedRequest = requestCache.getRequest(request, response);// 로그인 성공 핸들러에서 SavedRequest 가져오기
		if (savedRequest != null) {
		    String redirectUrl = savedRequest.getRedirectUrl();
			List<String> validRequestUrlList = Arrays.asList(
					CommonUtil.getDomainWithPort(request) + "/main.do?token=",
					CommonUtil.getDomainWithPort(request) + "/share/plan/");
			boolean validRequest = validRequestUrlList.stream().anyMatch(url->{
				return redirectUrl.startsWith(url);
			});
			if (validRequest){
				targetUrl = redirectUrl;
				requestCache.removeRequest(request, response);// SavedRequest 제거
			}
		}
//		redirectStrategy.sendRedirect(request, response, targetUrl);
		
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_OK);

        String phoneNumber = CommonUtil.nullToBlank(userVO.getPhone());
        // phoneNumber가 있으면 포함, 없으면 phoneNumber 필드 자체를 제외
        String jsonResponse;
        if (CommonUtil.isNotEmpty(phoneNumber)) {
            // 전화번호 마스킹 처리 (010-0000-0000 → 010-****-0000)
            String maskedPhoneNumber = maskPhoneNumber(phoneNumber);
            jsonResponse = String.format("{\"result\":\"OTP_REQUIRED\",\"phoneNumber\":\"%s\"}", maskedPhoneNumber);
        } else {
        	jsonResponse = "{\"result\":\"OTP_REQUIRED\"}";
        }
        response.getWriter().write(jsonResponse);
        
        // OTP체크 권한부여
        setCustomOtpAuthentication(authentication);
	}
	
	/**
	 * 전화번호 중간 부분 마스킹 처리
	 * 예: 010-0000-0000 → 010-****-0000
	 * 
	 * @param phoneNumber 원본 전화번호
	 * @return 마스킹된 전화번호
	 */
	private String maskPhoneNumber(String phoneNumber) {
		if (CommonUtil.isEmpty(phoneNumber)) {
			return phoneNumber;
		}
		
		// 하이픈으로 구분된 전화번호 형식인지 확인 (예: 010-0000-0000)
		if (phoneNumber.contains("-")) {
			String[] parts = phoneNumber.split("-");
			if (parts.length == 3) {
				// 세 부분으로 나뉜 경우: 중간 부분만 마스킹
				// 예: 010-0000-0000 → 010-****-0000
				return parts[0] + "-****-" + parts[2];
			}
		}
		
		// 하이픈이 없거나 형식이 맞지 않는 경우 그대로 반환
		return phoneNumber;
	}
	
	private boolean isOtpRequired(Authentication authentication) {
        return true; // 일단 무조건 OTP 대상
    }
	
	private void setCustomOtpAuthentication(Authentication authentication) {
        List<GrantedAuthority> authorities =  new ArrayList<>(authentication.getAuthorities());
        boolean hasPreOtp = authorities.stream().anyMatch(a -> "ROLE_PRE_OTP".equals(a.getAuthority()));
        if (!hasPreOtp) {
        	authorities.add(new SimpleGrantedAuthority("ROLE_PRE_OTP"));
        }

	    UsernamePasswordAuthenticationToken newAuth =
	            new UsernamePasswordAuthenticationToken(
	            		authentication.getPrincipal(),
	            		authentication.getCredentials(),
	            		authorities
	            );

	    SecurityContextHolder.getContext().setAuthentication(newAuth);
	}
	
	private void setCustomBasicAuthentication(Authentication authentication) {
        
        List<GrantedAuthority> authorities =  new ArrayList<>(authentication.getAuthorities());
	   
        // 1. 기존 권한 복사 (PRE_OTP 제외)
	    List<GrantedAuthority> newAuthorities = new ArrayList<>();
	    for (GrantedAuthority auth : authorities) {
	        if (!"ROLE_PRE_OTP".equals(auth.getAuthority())) {
	            newAuthorities.add(auth);
	        }
	    }
	    
	    // 2. ROLE_USER 추가 (중복 방지)
	    boolean hasUserRole = newAuthorities.stream().anyMatch(a -> "ROLE_USER".equals(a.getAuthority()));

	    if (!hasUserRole) {
	        newAuthorities.add( new SimpleGrantedAuthority("ROLE_USER"));
	    }

	    UsernamePasswordAuthenticationToken newAuth =
	            new UsernamePasswordAuthenticationToken(
	            		authentication.getPrincipal(),
	            		authentication.getCredentials(),
	                    newAuthorities
	            );

	    SecurityContextHolder.getContext().setAuthentication(newAuth);
	}

	public String getDefaultUrl() {
		return DEFAULT_URL;
	}

	public void setDefaultUrl(String defaultUrl) {
		this.DEFAULT_URL = defaultUrl;
	}

	public String getDefaultOtpUrl()
	{
		return DEFAULT_OTP_URL;
	}

	public void setDefaultOtpUrl(String defaultOtpUrl)
	{
		this.DEFAULT_OTP_URL = defaultOtpUrl;
	}

	public String getNoServiceUrl() {
		return NO_SERVICE_URL;
	}

	public void setNoServiceUrl(String noServiceUrl) {
		this.NO_SERVICE_URL = noServiceUrl;
	}

	public String getNoConnectionUrl() {
		return NO_CONNECTION_URL;
	}

	public void setNoConnectionUrl(String noConnectionUrl) {
		this.NO_CONNECTION_URL = noConnectionUrl;
	}


}
