package egovframework.com.sym.log.clg.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import egovframework.com.sym.log.clg.service.EgovLoginLogService;
import egovframework.com.sym.log.clg.service.LoginLog;
import egovframework.rte.fdl.cmmn.EgovAbstractServiceImpl;

@Service("EgovLoginLogService")
public class EgovLoginLogServiceImpl extends EgovAbstractServiceImpl implements
	EgovLoginLogService {

	@Resource(name="loginLogDAO")
	private LoginLogDAO loginLogDAO;

	@Override
	public void logInsertLoginLog(LoginLog loinLog) throws Exception {
		loginLogDAO.logInsertLoginLog(loinLog);
	}

	@Override
	public LoginLog selectLoginLog(LoginLog loginLog) throws Exception{
		return loginLogDAO.selectLoginLog(loginLog);
	}

	@Override
	public Map selectLoginLogInf(LoginLog loinLog) throws Exception {
		List _result = loginLogDAO.selectLoginLogInf(loinLog);
		int _cnt = loginLogDAO.selectLoginLogInfCnt(loinLog);

		Map<String, Object> _map = new HashMap();
		_map.put("resultList", _result);
		_map.put("resultCnt", Integer.toString(_cnt));

		return _map;
	}

}
