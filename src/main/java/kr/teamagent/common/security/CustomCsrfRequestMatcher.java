package kr.teamagent.common.security;

import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import javax.servlet.http.HttpServletRequest;

public class CustomCsrfRequestMatcher implements RequestMatcher {
	private final RequestMatcher apiMatcher = new AntPathRequestMatcher("/api/apiTest.do");

	@Override
	public boolean matches(HttpServletRequest request) {
		/*
		 * POST 요청이면서 url이 /api/apiTest.do가 아닌 경우 csrf 체크 (true면 체크, false면 체크 하지 않음)
		 */
		//boolean checkCsrf = (request.getMethod().equals("POST") && !apiMatcher.matches(request));
		//return checkCsrf;
		return false;
	}
}
