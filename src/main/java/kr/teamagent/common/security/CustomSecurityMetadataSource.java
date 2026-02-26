package kr.teamagent.common.security;

import egovframework.com.cmm.util.EgovUserDetailsHelper;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.security.service.impl.LoginServiceImpl;
import kr.teamagent.common.util.SessionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.access.SecurityConfig;
import org.springframework.security.web.FilterInvocation;
import org.springframework.security.web.access.intercept.FilterInvocationSecurityMetadataSource;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;


public class CustomSecurityMetadataSource implements FilterInvocationSecurityMetadataSource {

	private final Logger log = LoggerFactory.getLogger(CustomSecurityMetadataSource.class);

	@Resource(name = "afterAuthPublicWhitelist")
    protected List<String> afterAuthPublicWhitelist;//인증후 메뉴 권한 상관 없이 호출 할 수 있는Url

	@Autowired
	LoginServiceImpl loginService;

	private final List<RequestMatcher> permittedUrlList;
	private final List<RequestMatcher> permittedUrlAfterLoginList;

	public CustomSecurityMetadataSource(List<String> permittedUrl, List<String> permittedUrlAfterLogin) {
		permittedUrlList = new ArrayList<RequestMatcher>();
		permittedUrlAfterLoginList = new ArrayList<RequestMatcher>();
		//인증 상관 없이 호출 할 수 있는 url
		if(permittedUrl != null) {
			for(String url : permittedUrl) {
				log.debug("permittedUrl : "+url);
				permittedUrlList.add(new AntPathRequestMatcher(url));
			}
		}
		//로그인 후 허용되는 URL
		if(permittedUrlAfterLogin != null) {
			for(String url : permittedUrlAfterLogin) {
				log.debug("permittedUrlAfterLogin : "+url);
				permittedUrlAfterLoginList.add(new AntPathRequestMatcher(url));
			}
		}
	}

	@Override
	public Collection<ConfigAttribute> getAttributes(Object object) throws IllegalArgumentException {
		log.debug("\n\n#### CustomSecurityMetadataSource.getAttributes ###");
		log.debug("\n" + ((FilterInvocation)object).getRequest().getServletPath());

//		Boolean isAuthenticated = EgovUserDetailsHelper.isAuthenticated();//인증 여부
		List<String> authorities = EgovUserDetailsHelper.getAuthorities(); // 권한 목록
		Boolean isAuthenticated = authorities != null && authorities.contains("ROLE_USER");// otp 통과 여부
		HttpServletRequest request = ((FilterInvocation) object).getRequest();

		// 권한 체크 예외 URL 여부 확인
		boolean doCheck = !permittedUrlList.stream().anyMatch(req -> req.matches(request));//권한 체크가 필요 없는 URL
		boolean doCheckAfterAuth = !permittedUrlAfterLoginList.stream().anyMatch(req -> req.matches(request));//권한 체크가 필요한 URL

		//ROOT 요청
		if ("/".equals(request.getRequestURI().replaceFirst(request.getContextPath(), ""))) {
			return null;
		}

		/*** 이후 권한 체크 없는 URL ***/
		if (!doCheck) {// 권한 체크가 필요 없음
			return null;
		}

		/*** 이후 권한 체크 필요 URL ***/
		// 권한 체크가 필요
		if (!isAuthenticated) {// 인증 안됨
			return accessDenied();
		}

		//권한 체크가 필요한 URL
		if (doCheckAfterAuth) {
			// 로그인 사용자의 URL별 권한 목록을 가져와서 비교
			UserVO userVO = SessionUtil.getUserVO();
			if (userVO == null || userVO.getRequestMap() == null || userVO.getRequestMap().isEmpty()) {
				return accessDenied();
			}

			// 요청 URL과 일치하는 권한을 검색
			Collection<ConfigAttribute> result = userVO.getRequestMap().entrySet().stream()
			        .filter(entry -> {
						//System.out.println("(new AntPathRequestMatcher(entry.getKey())).getPattern() : " + (new AntPathRequestMatcher(entry.getKey())).getPattern());
						return new AntPathRequestMatcher(entry.getKey()).matches(request);
					}).map(Map.Entry::getValue)
			        .findFirst()
			        .orElse(null);


			//요청 권한 없음
			if (result == null) {
				return accessDenied();
			}
		}

		//return null -> 해당 페이지 접근 가능
		return null;
	}


	private Collection<ConfigAttribute> accessDenied()
	{
		List<ConfigAttribute> configList = new LinkedList<ConfigAttribute>();
		configList.add(new SecurityConfig("ACCESS_DENIED"));
		return configList;
	}

	@Override
	public Collection<ConfigAttribute> getAllConfigAttributes() {
		return null;
	}

	@Override
	public boolean supports(Class<?> clazz) {
		return FilterInvocation.class.isAssignableFrom(clazz);
	}
}
