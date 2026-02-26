package kr.teamagent.common.security;

import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import egovframework.com.cmm.EgovMessageSource;

import javax.annotation.Resource;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class CustomLoginFailHandler implements AuthenticationFailureHandler {

    @Resource(name="egovMessageSource")
	public EgovMessageSource egovMessageSource;
    
    public final Logger log = LoggerFactory.getLogger(this.getClass());

    private String errorLOGINPage;
    private String errorDeniedPage;
    private String errorInvalidTokenPage;
    private String errorAccessAlertPage;
    private String errorInActiveUserPage;
    private String errorAdminIpDeniedPage;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        request.setAttribute("exception", exception);
        String accessError = String.valueOf(SessionUtil.getAttribute("accessError"));
        String accessErrorUrl = getRedirectUrl(errorLOGINPage);
        //System.out.println("accessError -> " + accessError);
        boolean ipChkFail = false;
        String errCnt = SessionUtil.getAttribute("accessErrorCnt") != null ? SessionUtil.getAttribute("accessErrorCnt").toString() : "0";
        switch (accessError) {
            case "passwordFail":
                accessErrorUrl = getRedirectUrl(errorAccessAlertPage) + "&errCnt=" + errCnt;
                break;
            case "accessDenied":
                accessErrorUrl = getRedirectUrl(errorDeniedPage);
                break;
            case "invalidToken":
                accessErrorUrl = getRedirectUrl(errorInvalidTokenPage);
                break;
            case "inActiveUser":
                accessErrorUrl = getRedirectUrl(errorInActiveUserPage);
                break;
            case "accessAdminDenied":
                accessErrorUrl = getRedirectUrl(errorAdminIpDeniedPage);
                break;
            case "ipChkFail":
                accessErrorUrl = getRedirectUrl(errorAccessAlertPage) + "&ipChkFail=true";
                ipChkFail = true;
                break;
            default:
                break;
        }

//        response.sendRedirect(request.getContextPath() + accessErrorUrl);
		response.getWriter().write(
			"{"
				+ "\"accessError\":\"" + accessError + "\","
				+ "\"errCnt\":\"" + egovMessageSource.getMessageArgs("errors.accessFailHtml", new String[] {errCnt}) + "\","
				+ "\"ipChkFail\":" + ipChkFail
			+ "}"
		);
        SessionUtil.logoutUser();
    }

    public void setErrorLOGINPage(String errorPage) {
        this.errorLOGINPage = errorPage;
    }

    public void setErrorDeniedPage(String errorPage) {
        this.errorDeniedPage = errorPage;
    }

    public void setErrorInvalidTokenPage(String errorPage) {
        this.errorInvalidTokenPage = errorPage;
    }

    public void setErrorAccessAlertPage(String errorPage) {
        this.errorAccessAlertPage = errorPage;
    }

    public void setErrorInActiveUserPage(String errorPage) {
        this.errorInActiveUserPage = errorPage;
    }
    public void setErrorAdminIpDeniedPage(String errorPage) {
        this.errorAdminIpDeniedPage = errorPage;
    }

    private String getRedirectUrl(String url) {
        String userType = String.valueOf(SessionUtil.getAttribute("userType"));
        if (UserType.ADMIN.equals(userType)) {
            return url.replaceAll("/login.do", "/adm.login.do");
        }
        return url;
    }
}
