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
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.library.service.impl.LibraryDAO;

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

	@Autowired
	private LibraryDAO libraryDAO;

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
	 * 3. 원본 카드 svcTy='S'인 경우 TB_KNOW_CARD_CHART 차트 설정 복사
	 * 4. TB_KNOW_CARD_SHARE의 SAVE_YN, SAVE_CARD_ID, SAVE_CATEGORY_ID 업데이트
	 * @param notifyVO refId, categoryId 필수
	 * @return successYn, returnMsg
	 * @throws Exception
	 */
	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> insertReceiveKnowledge(CommonVO.NotifyVO notifyVO) throws Exception {
		Map<String, Object> resultMap = new HashMap<>();

		String userId     = SessionUtil.getUserId();
		String shareId    = notifyVO.getRefId();
		String categoryId = notifyVO.getCategoryId();

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

		// 3. 통계(svcTy='S') 카드만 차트 설정 복사 (TB_KNOW_CARD_CHART)
		CommonVO.SharedCardVO srcCard = commonDAO.selectSharedCardInfo(srcCardId);
		if (srcCard != null && "S".equals(srcCard.getSvcTy())) {
			copyKnowChartsForCard(srcCardId, newCardId, userId);
		}

		// 4. TB_KNOW_CARD_SHARE 저장 정보 업데이트
		Map<String, Object> shareParam = new HashMap<>();
		shareParam.put("shareId",        shareId);
		shareParam.put("saveCardId",     newCardId);
		shareParam.put("saveCategoryId", categoryId);
		commonDAO.updateKnowledgeShareSave(shareParam);

		resultMap.put("successYn", true);
		resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
		return resultMap;
	}

	/**
	 * 공유 수신 시 원본 카드의 차트 설정을 신규 카드로 복사한다.
	 * @param srcCardId 원본 CARD_ID
	 * @param newCardId 복사본 CARD_ID
	 * @param userId 수신자(생성자) ID
	 */
	private void copyKnowChartsForCard(String srcCardId, String newCardId, String userId) throws Exception {
		if (CommonUtil.isEmpty(srcCardId) || CommonUtil.isEmpty(newCardId) || CommonUtil.isEmpty(userId)) {
			return;
		}
		LibraryVO chartParam = new LibraryVO();
		chartParam.setCardId(srcCardId);
		List<LibraryVO.KnowChartItem> charts = libraryDAO.selectKnowChartList(chartParam);
		if (charts == null || charts.isEmpty()) {
			return;
		}
		List<String> chartIds = assignKnowChartIdsForCopy(charts.size());
		for (int i = 0; i < charts.size(); i++) {
			LibraryVO.KnowChartItem chart = charts.get(i);
			LibraryVO.KnowChartSavePayload insertVO = new LibraryVO.KnowChartSavePayload();
			insertVO.setChartId(chartIds.get(i));
			insertVO.setCardId(newCardId);
			insertVO.setChartType(chart.getChartType());
			insertVO.setChartTargetKey(chart.getChartTargetKey());
			insertVO.setYAxisKeysJson(chart.getYAxisKeys());
			insertVO.setSeriesKey(CommonUtil.nullToBlank(chart.getSeriesKey()));
			insertVO.setStatIdFilter(CommonUtil.nullToBlank(chart.getStatIdFilter()));
			insertVO.setStackYn("Y".equals(chart.getStackYn()) ? "Y" : "N");
			insertVO.setDualAxisYn("Y".equals(chart.getDualAxisYn()) ? "Y" : "N");
			insertVO.setYlChartType(chart.getYlChartType());
			insertVO.setYrChartType(chart.getYrChartType());
			insertVO.setSortOrd(chart.getSortOrd() != null ? chart.getSortOrd() : 0);
			insertVO.setCreateUserId(userId);
			libraryDAO.insertKnowChart(insertVO);
		}
	}

	/**
	 * 동일 트랜잭션 내 차트 복수 건 INSERT 시 CHART_ID 중복 방지용 로컬 시퀀스
	 */
	private List<String> assignKnowChartIdsForCopy(int count) throws Exception {
		List<String> ids = new ArrayList<>();
		if (count <= 0) {
			return ids;
		}
		String seedKey = keyGenerate.generateTableKey("KH", "TB_KNOW_CARD_CHART", "CHART_ID");
		ids.add(seedKey);
		int nextSeq = Integer.parseInt(seedKey.substring(2));
		for (int i = 1; i < count; i++) {
			nextSeq++;
			ids.add("KH" + String.format("%06d", nextSeq));
		}
		return ids;
	}

	/**
	 * 알림 등록 공통 메서드
	 * notifyId는 내부에서 자동 생성하며, 나머지 필드(userId, sendUserId, notifyTyCd, title, content, refId)는 호출부에서 조립해서 전달한다.
	 * @param notifyVO userId(수신자), sendUserId(발신자), notifyTyCd, title, content, refId 필수
	 * @throws Exception
	 */
	public void insertNotify(CommonVO.NotifyVO notifyVO) throws Exception {
		String notifyId = keyGenerate.generateTableKey("NI", "TB_NOTIFY", "NOTIFY_ID");
		notifyVO.setNotifyId(notifyId);
		commonDAO.insertNotify(notifyVO);
	}

	/**
	 * 알림 읽음 처리 (READ_YN='Y', READ_DT=NOW())
	 * @param notifyId 알림 ID
	 * @return successYn, returnMsg
	 * @throws Exception
	 */
	public Map<String, Object> updateNotifyRead(String notifyId) throws Exception {
		Map<String, Object> resultMap = new HashMap<>();
		commonDAO.updateNotifyRead(notifyId);
		resultMap.put("successYn", true);
		resultMap.put("returnMsg", "읽음 처리가 완료되었습니다.");
		return resultMap;
	}

	/**
	 * 알림 삭제 (USE_YN='N')
	 * @param notifyId 알림 ID
	 * @return successYn, returnMsg
	 * @throws Exception
	 */
	public Map<String, Object> deleteNotify(String notifyId) throws Exception {
		Map<String, Object> resultMap = new HashMap<>();
		commonDAO.deleteNotify(notifyId);
		resultMap.put("successYn", true);
		resultMap.put("returnMsg", "알림이 삭제되었습니다.");
		return resultMap;
	}

	/**
	 * 알림 전체 읽음 처리 (세션 userId 기준 READ_YN='N' 전체)
	 * @return successYn, returnMsg
	 * @throws Exception
	 */
	public Map<String, Object> updateNotifyAllRead() throws Exception {
		Map<String, Object> resultMap = new HashMap<>();
		String userId = SessionUtil.getUserId();
		commonDAO.updateNotifyAllRead(userId);
		resultMap.put("successYn", true);
		resultMap.put("returnMsg", "전체 읽음 처리가 완료되었습니다.");
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
