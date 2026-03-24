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

	/**
	 * 테이블 마지막 ID 조회
	 * @param tableName 테이블명 (예: TB_KNOW_CAT)
	 * @param idColumn ID 컬럼명 (예: CATEGORY_ID)
	 * @return
	 */
	public String selectMaxId(String tableName, String idColumn) throws Exception {
		Map<String, String> param = new HashMap<>();
		param.put("tableName", tableName);
		param.put("idColumn", idColumn);
		return (String) selectOne("common.selectMaxId", param);
	}

	/**
	 * 테이블 컬럼 MAX 정수값 조회
	 * @param tableName 테이블명 (예: TB_AGT)
	 * @param columnName 컬럼명 (예: SORT_ORD)
	 * @return MAX값, 데이터 없으면 0
	 */
	public int selectMaxInt(String tableName, String columnName) throws Exception {
		Map<String, String> param = new HashMap<>();
		param.put("tableName", tableName);
		param.put("columnName", columnName);
		return (int) selectOne("common.selectMaxInt", param);
	}
}
