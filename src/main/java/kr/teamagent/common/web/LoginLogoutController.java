/*************************************************************************
 * CLASS 명	: LoginLogoutController
 * 작 업 자	: 2unki
 * 작 업 일	: 2019. 11. 20.
 * 기	능	: 로그인/로그아웃 컨트롤러
 * 주의사항	:
 * ---------------------------- 변 경 이 력 --------------------------------
 * 번호	작 업 자		작	업	일			변 경 내 용				비고
 * ----	---------	----------------	---------------------	-----------
 *	1	2unki		2019. 11. 20.		최 초 작 업
 **************************************************************************/
package kr.teamagent.common.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;

import egovframework.com.cmm.util.EgovUserDetailsHelper;
import kr.teamagent.common.CommonVO;
import kr.teamagent.common.security.CustomLoginSuccessHandler;
import kr.teamagent.common.security.JwtTokenProvider;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.security.service.impl.LoginServiceImpl;
import kr.teamagent.common.security.service.impl.OtpServiceImpl;
import kr.teamagent.common.system.service.impl.CommonServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.UserType;

@Controller
public class LoginLogoutController extends BaseController {

	public final Logger log = LoggerFactory.getLogger(this.getClass());

	@Autowired
	private CommonServiceImpl commonService;

	@Autowired
	private LoginServiceImpl loginService;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private CustomLoginSuccessHandler customLoginSuccessHandler;

	@Autowired
	StandardPasswordEncoder passwordEncoder;

	@Autowired
	private OtpServiceImpl otpService;


	private void initUserSessionAttribute(HttpServletRequest request){
		SecurityContextHolder.getContext().setAuthentication(null);
		request.getSession().setAttribute("compId", null);
		request.getSession().setAttribute("connectionId", null);
		request.getSession().setAttribute("masterDbId", null);
		request.getSession().setAttribute("templateDbId", null);
		request.getSession().setAttribute("templateCompId", null);
		request.getSession().setAttribute("compDbId", null);
		request.getSession().setAttribute("userId", null);
		request.getSession().setAttribute(SessionLocaleResolver.LOCALE_SESSION_ATTRIBUTE_NAME, null);
		request.getSession().setAttribute("loginVO", null);
		request.getSession().setAttribute("menuList", null);
		request.getSession().setAttribute("menuMap", null);
		request.getSession().setAttribute("langList", null);
		request.getSession().setAttribute("userScDeptMap", null);
		request.getSession().setAttribute("accessOtpError", null);
		request.getSession().setAttribute("accessOtpErrorCnt", null);
		request.getSession().setAttribute("isOnlyInsa", null);
		request.getSession().setAttribute("jwtToken", null);
	}

	@RequestMapping("/")
	public String login() throws Exception {
		SessionUtil.logoutUser();
		return "redirect:/login.do";
	}


	@RequestMapping("/sso/login/login_sso.do")
	public String ssoLoginNice(Model model, HttpServletRequest request, HttpServletResponse response, HttpSession session) throws Exception {
		try {
			String ssoId = request.getParameter("ssoId");
//			log.info("=====> ssoLogin getTocken: " + ssoId);

			model.addAttribute("ssoId", ssoId);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return "/common/login_sso.simple";
	}

	@RequestMapping("/login.do")
	public String login(Model model, CommonVO searchVO, HttpServletRequest request, HttpServletResponse response, HttpSession session) throws Exception {
		response.setHeader("isAuthenticated", String.valueOf(EgovUserDetailsHelper.isAuthenticated()));
		Optional<String> authToken = jwtTokenProvider.resolveToken(request);
		if (authToken.isPresent()) {
			if (jwtTokenProvider.validateToken(authToken.get())) {
				model.addAttribute("authToken", authToken.get());
				return "/common/login_by_token.simple";
			} else {
				return "redirect:/login.do?invalidToken";
			}
		}

		SessionUtil.setAttribute("userType", UserType.USER);
		
		SessionUtil.removeAttribute("ROLE_PRE_OTP");
		SessionUtil.removeAttribute("ROLE_USER");
		
		return "/common/login.simple";
	}

	/**
	 * 슈퍼관리자 로그인
	 *
	 * @param model
	 * @param searchVO
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@RequestMapping("/adm.login.do")
	public String admlogin(Model model, CommonVO searchVO, HttpServletRequest request) throws Exception {
		searchVO.setMasterDbId(PropertyUtil.getProperty("Globals.Master.db"));
		//initUserSessionAttribute(request);
		//관리자페이지 IP접근제어
		final String userIp = CommonUtil.getUserIP(request);
		List<String> accessIpList = commonService.selectAdminPageAccessIpList();
		if (CommonUtil.isNotEmpty(accessIpList)) {
			if (!accessIpList.contains(userIp)) {
				return "redirect:/error/accessDenied.do";
			}
		}

		Optional<String> jwtToken = jwtTokenProvider.resolveToken(request);
		if (jwtToken.isPresent()) {
			if (jwtTokenProvider.validateToken(jwtToken.get())) {
				model.addAttribute("jwtToken", jwtToken.get());
				SessionUtil.setAttribute("userType", UserType.ADMIN);
				return "/common/login_by_token.simple";
			} else {
				return "redirect:/login.do?invalidToken";
			}
		}

		SessionUtil.setAttribute("userType", UserType.ADMIN);

		return "/common/login.simple";
	}



	/**
	 * 슈퍼관리자 로그아웃
	 *
	 * @param model
	 * @return
	 * @throws Exception
	 */
	@RequestMapping("/adm.logout.do")
	public String admlogout(Model model, CommonVO searchVO, HttpServletRequest request) throws Exception {
		request.getSession().invalidate();
		return "redirect:/adm.login.do";
	}

	/**
	 * 로그인거절
	 *
	 * @param model
	 * @return
	 * @throws Exception
	 */
	@RequestMapping("/loginDenied.do")
	public String loginDenied(Model model, CommonVO searchVO) throws Exception {
		return "/common/loginDenied.simple";
	}

	/**
	 * OTP 확인
	 *
	 * @param model
	 * @return
	 * @throws Exception
	 */
	@RequestMapping("/otpProcess.do")
	public ModelAndView verify(Model model, String otp) throws Exception {
		HashMap<String, Object> resultMap = new HashMap<>();
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		UserVO user = (UserVO) EgovUserDetailsHelper.getAuthenticatedUser();
//		boolean success = otpService.verifyOtp(user, otp);
		String successCode = otpService.verifyOtp(user, otp);

//		if (!success) {
		if (!"999".equals(successCode)) {
			resultMap.put("success", false);
			if("001".equals(successCode)) {
				resultMap.put("refreshYn", true);
				resultMap.put("message", "OTP 정보가 만료되었습니다.");
				loginService.insertAccessCertificationOtpData(user, "S", "E");
			} else if("002".equals(successCode)) {
				resultMap.put("message", "OTP가 올바르지 않습니다.");
				loginService.insertAccessCertificationOtpData(user, "S", "F");
//				loginService.insertAccessCertificationOtpData(user, "F", "F", otpService.getFailCount(user));
			} else if("003".equals(successCode)) {
				resultMap.put("refreshYn", true);
				resultMap.put("message", "OTP 인증 횟수를 초과하였습니다.");
				loginService.insertAccessCertificationOtpData(user, "S", "F");
//				loginService.insertAccessCertificationOtpData(user, "F", "F", otpService.getFailCount(user));
			}
			return new ModelAndView("jsonView", resultMap);
		}

		// ROLE_USER로 승격
		promoteToUser(authentication);

		otpService.clearOtp(user.getUserId());
		
		loginService.insertAccessCertificationOtpData(user, "S", "S");

		resultMap.put("success", true);
		resultMap.put("redirectUrl", "/main.do");
		return new ModelAndView("jsonView", resultMap);
	}
	
	private void promoteToUser(Authentication oldAuth) {
		
		List<GrantedAuthority> authorities =  new ArrayList<>(oldAuth.getAuthorities());
	   
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
						oldAuth.getPrincipal(),
						oldAuth.getCredentials(),
						newAuthorities
				);

		SecurityContextHolder.getContext().setAuthentication(newAuth);
	}
	
	@RequestMapping("/initOtp.do")
	public ModelAndView initOtp(Model model, HttpServletRequest request, HttpServletResponse response) throws Exception {
		HashMap<String, Object> resultMap = new HashMap<>();
		try {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			UserVO userVO = (UserVO) EgovUserDetailsHelper.getAuthenticatedUser();
			String phoneNumber = CommonUtil.nullToBlank(userVO.getPhone());
  
			// phoneNumber가 있으면 포함, 없으면 phoneNumber 필드 자체를 제외
			String jsonResponse;
			if (CommonUtil.isNotEmpty(phoneNumber)) {
				// 전화번호 마스킹 처리 (010-0000-0000 → 010-****-0000)
				phoneNumber = maskPhoneNumber(phoneNumber);
			} 
			resultMap.put("phoneNumber", phoneNumber);
  
			otpService.issueOtp(userVO);
  
			// OTP체크 권한부여
			setCustomOtpAuthentication(authentication);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return new ModelAndView("jsonView", resultMap);
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
}
