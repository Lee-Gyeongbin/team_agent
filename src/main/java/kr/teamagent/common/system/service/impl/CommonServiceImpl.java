package kr.teamagent.common.system.service.impl;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import kr.teamagent.common.CommonVO;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	/**
	 * 메뉴 트리 목록 조회 (PARN_MENU_ID 기준 계층 구조)
	 * @return 루트 메뉴 목록, 각 메뉴의 children에 하위 메뉴 포함
	 * @throws Exception
	 */
	public List<EgovMap> selectMenuTreeList() throws Exception {
		List<EgovMap> flatList = commonDAO.selectMenuTreeList();
		return buildMenuTree(flatList);
	}

	/**
	 * 테마 옵션 조회
	 * @return
	 * @throws Exception
	 */
	public HashMap<String, Object> comSelectThemeOptions() throws Exception {
		HashMap<String, Object> resultMap = new HashMap<>();
		resultMap.put("iconList", commonDAO.comSelectIconList());
		resultMap.put("colorList", commonDAO.comSelectColorList());
		return resultMap;
	}

	/**
	 * 평면 메뉴 목록을 PARN_MENU_ID 기준 계층 구조로 변환
	 * @param flatList DB 조회 결과 (SORT_PATH 순)
	 * @return 루트 메뉴 목록, 하위 메뉴는 children 리스트에 포함
	 */
	private List<EgovMap> buildMenuTree(List<EgovMap> flatList) {
		Map<String, EgovMap> mapById = new LinkedHashMap<>();
		List<EgovMap> rootList = new ArrayList<>();

		for (EgovMap item : flatList) {
			EgovMap node = new EgovMap();
			node.putAll(item);
			node.put("children", new ArrayList<EgovMap>());
			mapById.put((String) item.get("menuId"), node);
		}

		for (EgovMap item : flatList) {
			String parnMenuId = (String) item.get("parnMenuId");
			EgovMap node = mapById.get(item.get("menuId"));
			if (parnMenuId == null || parnMenuId.isEmpty()) {
				rootList.add(node);
			} else {
				EgovMap parent = mapById.get(parnMenuId);
				if (parent != null) {
					@SuppressWarnings("unchecked")
					List<EgovMap> children = (List<EgovMap>) parent.get("children");
					children.add(node);
				} else {
					rootList.add(node);
				}
			}
		}
		return rootList;
	}
}
