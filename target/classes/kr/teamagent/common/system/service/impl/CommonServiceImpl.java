package kr.teamagent.common.system.service.impl;

import egovframework.rte.fdl.cmmn.EgovAbstractServiceImpl;
import egovframework.rte.psl.dataaccess.util.EgovMap;
import kr.teamagent.common.CommonVO;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CommonServiceImpl extends EgovAbstractServiceImpl {

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	@Autowired
	private CommonDAO commonDAO;

	public List<String> selectAdminPageAccessIpList() throws Exception {
		return commonDAO.selectAdminPageAccessIpList(PropertyUtil.getProperty("Globals.Master.db"));
	}

	public String selectUserStrAreaList(UserVO userVO) throws Exception {
		return commonDAO.selectUserStrAreaList(userVO);
	}

	public String selectDbId(EgovMap emap) throws Exception {
		return commonDAO.selectDbId(emap);
	}

	public List<CommonVO> selectDbList() throws Exception {
		return commonDAO.selectDbList(PropertyUtil.getProperty("Globals.Master.db"));
	}
}
