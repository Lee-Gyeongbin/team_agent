package kr.teamagent.common.interceptor;

import java.util.Iterator;
import java.util.Set;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import egovframework.com.cmm.EgovMessageSource;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.SessionUtil;

/**
 * 레퍼러 체크
 *
 */

public class OtpInterceptor extends HandlerInterceptorAdapter {

	protected final Logger log = LoggerFactory.getLogger(this.getClass());

	@Resource(name="egovMessageSource")
    EgovMessageSource egovMessageSource;

	private PathMatcher pathMatcher;

	private Set<String> otpCheckAntPathURL;

	public void setOtpCheckAntPathURL(Set<String> otpCheckAntPathURL) {
		this.otpCheckAntPathURL = otpCheckAntPathURL;
	}

	/**
	 * 제외된 URL이 아닌 URL에 대해서 refer체크를 한다.
	 * refer
	 */
	@Override
	public boolean preHandle(HttpServletRequest request,
			HttpServletResponse response, Object handler) throws Exception {

		String requestURI = request.getRequestURI(); //요청 URI
		boolean isPermittedAntPathURL = true;

		pathMatcher = new AntPathMatcher();

		for(Iterator<String> it = this.otpCheckAntPathURL.iterator(); it.hasNext();){
			String urlPattern = request.getContextPath() + it.next();
			if(pathMatcher.matchStart(urlPattern, requestURI)){// antpath를 사용하여 경로를 확인
				if("Y".equals(CommonUtil.nullToBlank(SessionUtil.getAttribute("otpUseYn")))
						&& !"Y".equals(CommonUtil.nullToBlank(SessionUtil.getAttribute("otpYn"))))
				{
					isPermittedAntPathURL = false;
				}
			}
		}

		if(!isPermittedAntPathURL){
			String redirectUrl = request.getContextPath()+"/error/accessDenied.do";
			response.sendRedirect(redirectUrl);
		}

		return true;
	}
}
