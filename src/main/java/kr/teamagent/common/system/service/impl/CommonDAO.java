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

	@SuppressWarnings("unchecked")
	public List<CommonVO.ColorVO> comSelectColorList() throws Exception {
		return (List<CommonVO.ColorVO>) list("common.comSelectColorList", null);
	}

	@SuppressWarnings("unchecked")
	public List<CommonVO.IconVO> comSelectIconList() throws Exception {
		return (List<CommonVO.IconVO>) list("common.comSelectIconList", null);
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

	/**
	 * 알림 목록 조회 (세션 userId 기준)
	 * @param userId 조회 대상 사용자 ID
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public List<CommonVO.NotifyVO> selectNotifyList(String userId) throws Exception {
		Map<String, Object> param = new HashMap<>();
		param.put("userId", userId);
		return (List<CommonVO.NotifyVO>) list("common.selectNotifyList", param);
	}

	/**
	 * 공유 지식 카드 단건 조회
	 * @param cardId 카드 ID
	 * @return SharedCardVO
	 * @throws Exception
	 */
	public CommonVO.SharedCardVO selectSharedCardInfo(String cardId) throws Exception {
		Map<String, Object> param = new HashMap<>();
		param.put("cardId", cardId);
		return (CommonVO.SharedCardVO) selectOne("common.selectSharedCardInfo", param);
	}

	/**
	 * 공유 카드 원본 CARD_ID 조회
	 * @param shareId TB_KNOW_CARD_SHARE.SHARE_ID
	 * @return 원본 CARD_ID
	 * @throws Exception
	 */
	public String selectCardIdByShareId(String shareId) throws Exception {
		Map<String, Object> param = new HashMap<>();
		param.put("shareId", shareId);
		return (String) selectOne("common.selectCardIdByShareId", param);
	}

	/**
	 * 지식 카드 맨 앞 등록을 위한 정렬 순서 증가
	 * @param userId 사용자 ID
	 * @param categoryId 카테고리 ID
	 * @return 처리된 행 수
	 * @throws Exception
	 */
	public int updateKnowledgeSortOrdForPrepend(String userId, String categoryId) throws Exception {
		Map<String, Object> param = new HashMap<>();
		param.put("userId", userId);
		param.put("categoryId", categoryId);
		return update("common.updateKnowledgeSortOrdForPrepend", param);
	}

	/**
	 * 공유 받은 지식 카드 복사 등록
	 * @param paramMap cardId, userId, categoryId, srcCardId, sortOrd 포함
	 * @return 처리된 행 수
	 * @throws Exception
	 */
	public int insertReceiveKnowledgeCard(Map<String, Object> paramMap) throws Exception {
		return insert("common.insertReceiveKnowledgeCard", paramMap);
	}

	/**
	 * 공유 카드 저장 정보 업데이트 (SAVE_YN, SAVE_CARD_ID, SAVE_CATEGORY_ID)
	 * @param paramMap shareId, saveCardId, saveCategoryId 포함
	 * @return 처리된 행 수
	 * @throws Exception
	 */
	public int updateKnowledgeShareSave(Map<String, Object> paramMap) throws Exception {
		return update("common.updateKnowledgeShareSave", paramMap);
	}

	/**
	 * 알림 등록 (TB_NOTIFY)
	 * @param notifyVO notifyId, userId(수신자), sendUserId(발신자), notifyTyCd, title, content, refId 필수
	 * @return 처리된 행 수
	 * @throws Exception
	 */
	public int insertNotify(CommonVO.NotifyVO notifyVO) throws Exception {
		return insert("common.insertNotify", notifyVO);
	}

	/**
	 * 알림 읽음 처리
	 * @param notifyId 알림 ID
	 * @return 처리된 행 수
	 * @throws Exception
	 */
	public int updateNotifyRead(String notifyId) throws Exception {
		Map<String, Object> param = new HashMap<>();
		param.put("notifyId", notifyId);
		return update("common.updateNotifyRead", param);
	}

	/**
	 * 알림 삭제 (USE_YN='N')
	 * @param notifyId 알림 ID
	 * @return 처리된 행 수
	 * @throws Exception
	 */
	public int deleteNotify(String notifyId) throws Exception {
		Map<String, Object> param = new HashMap<>();
		param.put("notifyId", notifyId);
		return update("common.deleteNotify", param);
	}

	/**
	 * 알림 전체 읽음 처리 (해당 사용자의 READ_YN='N' 전체)
	 * @param userId 사용자 ID
	 * @return 처리된 행 수
	 * @throws Exception
	 */
	public int updateNotifyAllRead(String userId) throws Exception {
		Map<String, Object> param = new HashMap<>();
		param.put("userId", userId);
		return update("common.updateNotifyAllRead", param);
	}
}
