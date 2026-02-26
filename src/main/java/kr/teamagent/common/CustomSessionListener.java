package kr.teamagent.common;

import egovframework.com.sym.log.clg.service.EgovLoginLogService;
import egovframework.com.sym.log.clg.service.LoginLog;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.impl.CommonServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.servlet.http.HttpSessionListener;
import java.sql.SQLException;

public class CustomSessionListener implements HttpSessionListener{

	protected Logger logger = LoggerFactory.getLogger(this.getClass());

	@Override
	public void sessionCreated(javax.servlet.http.HttpSessionEvent se) {
//		logger.info("************************************************************");
//		logger.info("sessionCreated session ID :" + se.getSession().getId());
//		logger.info("sessionCreated getCreationTime :" + se.getSession().getCreationTime());
//		logger.info("************************************************************");
	}

    @Override
	public void sessionDestroyed(javax.servlet.http.HttpSessionEvent se) {
//		logger.info("************************************************************");
//		logger.info("sessionDestroyed session ID :" + se.getSession().getId());
//		logger.info("sessionDestroyed getLastAccessedTime :" + se.getSession().getLastAccessedTime());
//		logger.info("sessionDestroyed getMaxInactiveInterval :" + se.getSession().getMaxInactiveInterval());
//		logger.info("************************************************************");
		UserVO vo = (UserVO)se.getSession().getAttribute("loginVO");

		if (!CommonUtil.isProdServer()) {
			return;
		}

		if(vo!=null)
		{
			try {
				//fcm push token 초기화
//				getCommonDaoService(se).initFcmTokenWeb(vo);

				LoginLog loginLog = new LoginLog();
				loginLog.setDbId(vo.getDbId());
				loginLog.setCompId(vo.getCompId());
				loginLog.setLoginId(vo.getUserId());
				loginLog.setLoginIp(vo.getIp());
				loginLog.setLoginMthd("O"); // 로그인:I, 로그아웃:O
				loginLog.setErrOccrrAt("N");
				loginLog.setErrorCode("");
				getLoginDaoService(se).logInsertLoginLog(loginLog);
			}catch (SQLException sqle) {
				sqle.getCause();
			}catch (Exception e) {
				e.getCause();
			}
		}
	}

	private EgovLoginLogService getLoginDaoService(javax.servlet.http.HttpSessionEvent se) {
	    WebApplicationContext context =
	      WebApplicationContextUtils.getWebApplicationContext(
	        se.getSession().getServletContext());
	    return (EgovLoginLogService) context.getBean("EgovLoginLogService");
	}

	private CommonServiceImpl getCommonDaoService(javax.servlet.http.HttpSessionEvent se) {
	    WebApplicationContext context =
	      WebApplicationContextUtils.getWebApplicationContext(
	        se.getSession().getServletContext());
	    return (CommonServiceImpl) context.getBean("CommonServiceImpl");
	}

}
