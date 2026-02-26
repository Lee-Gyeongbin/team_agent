package kr.teamagent.common.interceptor;

import egovframework.com.cmm.EgovMessageSource;
import kr.teamagent.common.util.CommonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Set;

/**
 * 레퍼러 체크
 *
 */


public class RefererInterceptor extends HandlerInterceptorAdapter {

	protected final Logger log = LoggerFactory.getLogger(this.getClass());

	@Resource(name="egovMessageSource")
    EgovMessageSource egovMessageSource;

	private PathMatcher pathMatcher;

	private Set<String> permittedAntPathURL;

	public void setPermittedAntPathURL(Set<String> permittedAntPathURL) {
		this.permittedAntPathURL = permittedAntPathURL;
	}


	/**
	 * 제외된 URL이 아닌 URL에 대해서 refer체크를 한다.
	 * refer
	 */
	@Override
	public boolean preHandle(HttpServletRequest request,
			HttpServletResponse response, Object handler) throws Exception {

		String requestURI = request.getRequestURI(); //요청 URI
		boolean isPermittedAntPathURL = false;
		pathMatcher = new AntPathMatcher();

		for(Iterator<String> it = this.permittedAntPathURL.iterator(); it.hasNext();){
			String urlPattern = request.getContextPath() + it.next();
			if(pathMatcher.matchStart(urlPattern, requestURI)){// antpath를 사용하여 경로를 확인
				isPermittedAntPathURL = true;
			}
		}

		if(!isPermittedAntPathURL)
		{
			/**
			 * 현재도메인과 레퍼러의 도메인을 비교함
			 * serverName : www.strategygate.biz
			 * headerReferer : https://www.strategygate.biz/main.do
			 */

			String serverName = CommonUtil.nullToBlank(request.getServerName());///www.strategygate.biz
			String headerReferer = CommonUtil.nullToBlank(request.getHeader("referer"));
			headerReferer = headerReferer.replace("http://","").replace("https://","");

//			System.out.println("serverName : " + serverName);
//			System.out.println("headerReferer : " + headerReferer);

			//https://www.strategygate.biz/xxxxxx
			/*
			TODO - 운영 환경에서 로그인 버튼 눌러서 로그인 하면 referer 값이 없음.
			if(!headerReferer.startsWith(serverName))
			{
				String redirectUrl = "/error/accessDenied.do";
				redirectUrl = request.getContextPath() + redirectUrl;
				response.sendRedirect(redirectUrl);
				return false;
			}
			*/
		}

		return true;
	}
}
