package kr.teamagent.common.system.service.impl;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import kr.teamagent.common.CommonVO;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
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

	@Autowired
	private KeyGenerate keyGenerate;

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
	 * 알림 목록 조회 (세션 userId 기준)
	 * @return
	 * @throws Exception
	 */
	public List<CommonVO.NotifyVO> selectNotifyList() throws Exception {
		String userId = SessionUtil.getUserId();
		return commonDAO.selectNotifyList(userId);
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
	 * 공유 지식 카드 단건 조회
	 * 1. refId(SHARE_ID)로 TB_KNOW_CARD_SHARE에서 원본 CARD_ID 조회
	 * 2. 조회된 CARD_ID로 TB_KNOW_CARD 상세 조회
	 * @param refId 공유 ID (TB_KNOW_CARD_SHARE.SHARE_ID)
	 * @return SharedCardVO
	 * @throws Exception
	 */
	public CommonVO.SharedCardVO selectSharedCardInfo(String refId) throws Exception {
		String cardId = commonDAO.selectCardIdByShareId(refId);
		if (cardId == null || cardId.isEmpty()) {
			return null;
		}
		return commonDAO.selectSharedCardInfo(cardId);
	}

	/**
	 * 공유 받은 지식 카드 저장
	 * 1. refId(SHARE_ID)로 원본 CARD_ID 조회
	 * 2. 원본 카드를 세션 userId / payload categoryId로 복사 등록 (맨 앞 순서 1)
	 * 3. TB_KNOW_CARD_SHARE의 SAVE_YN, SAVE_CARD_ID, SAVE_CATEGORY_ID 업데이트
	 * 4. TB_NOTIFY 읽음 처리 (READ_YN='Y', READ_DT=NOW())
	 * @param notifyVO notifyId, refId, categoryId 필수
	 * @return successYn, returnMsg
	 * @throws Exception
	 */
	public Map<String, Object> insertReceiveKnowledge(CommonVO.NotifyVO notifyVO) throws Exception {
		Map<String, Object> resultMap = new HashMap<>();

		String userId     = SessionUtil.getUserId();
		String shareId    = notifyVO.getRefId();
		String categoryId = notifyVO.getCategoryId();
		String notifyId   = notifyVO.getNotifyId();

		// 1. SHARE_ID로 원본 CARD_ID 조회
		String srcCardId = commonDAO.selectCardIdByShareId(shareId);

		// 2. 원본 카드 복사 등록
		String newCardId = keyGenerate.generateTableKey("KD", "TB_KNOW_CARD", "CARD_ID");

		commonDAO.updateKnowledgeSortOrdForPrepend(userId, categoryId);

		Map<String, Object> cardParam = new HashMap<>();
		cardParam.put("cardId",     newCardId);
		cardParam.put("userId",     userId);
		cardParam.put("categoryId", categoryId);
		cardParam.put("srcCardId",  srcCardId);
		cardParam.put("sortOrd",    1);
		commonDAO.insertReceiveKnowledgeCard(cardParam);

		// 3. TB_KNOW_CARD_SHARE 저장 정보 업데이트
		Map<String, Object> shareParam = new HashMap<>();
		shareParam.put("shareId",        shareId);
		shareParam.put("saveCardId",     newCardId);
		shareParam.put("saveCategoryId", categoryId);
		commonDAO.updateKnowledgeShareSave(shareParam);

		// 4. TB_NOTIFY 읽음 처리
		commonDAO.updateNotifyRead(notifyId);

		resultMap.put("successYn", true);
		resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
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
