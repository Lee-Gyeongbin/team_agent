package kr.teamagent.common.system.service.impl;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import kr.teamagent.common.CommonVO;
import kr.teamagent.common.security.service.UserVO;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class CommonDAO extends EgovComAbstractDAO {

	@SuppressWarnings("unchecked")
	public List<String> selectAdminPageAccessIpList(String masterDbId) throws Exception {
		Map<String, Object> map = new HashMap<>();
		map.put("masterDbId", masterDbId);
		return (List<String>) list("common.selectAdminPageAccessIpList", map);
	}

	public String selectUserStrAreaList(UserVO userVO) throws Exception {
		return (String) selectByPk("common.selectUserStrAreaList", userVO);
	}

	public String selectDbId(EgovMap emap) throws Exception {
		return (String) selectByPk("common.selectDbId", emap);
	}

	@SuppressWarnings("unchecked")
	public List<CommonVO> selectDbList(String masterDbId) throws Exception {
		Map<String, Object> map = new HashMap<>();
		map.put("masterDbId", masterDbId);
		return (List<CommonVO>) list("common.selectDbList", map);
	}

	@SuppressWarnings("unchecked")
	public List<EgovMap> selectMenuTreeList() throws Exception {
		return (List<EgovMap>) list("common.selectMenuTreeList", null);
	}
}
