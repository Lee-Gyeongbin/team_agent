package kr.teamagent.mydocuments.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.mydocuments.service.MyDocumentsVO;

@Repository
public class MyDocumentsDAO extends EgovComAbstractDAO {

    /**
     * 내 문서 목록 조회 (로그인 사용자, DOC_HTML 등 대용량 컬럼 제외)
     */
    @SuppressWarnings("unchecked")
    public List<MyDocumentsVO> selectMyDocList(MyDocumentsVO searchVO) throws Exception {
        return (List<MyDocumentsVO>) list("myDocuments.selectMyDocList", searchVO);
    }

    /**
     * 내 문서 상세 조회 (docId, 로그인 사용자 기준)
     */
    public MyDocumentsVO selectMyDocDetail(MyDocumentsVO searchVO) throws Exception {
        return (MyDocumentsVO) selectOne("myDocuments.selectMyDocDetail", searchVO);
    }

    /**
     * 사용자 문서 정렬 순서 +1 (신규 문서를 맨 앞에 두기 위함)
     */
    public int incrementSortOrdByUserId(MyDocumentsVO searchVO) throws Exception {
        return update("myDocuments.incrementSortOrdByUserId", searchVO);
    }

    /**
     * 나의 문서 보고서 저장 (INSERT ... ON DUPLICATE KEY UPDATE)
     */
    public int saveReport(MyDocumentsVO searchVO) throws Exception {
        return (int) insert("myDocuments.saveReport", searchVO);
    }

    /**
     * 내 문서 신규 여부(NEW_YN) 변경
     */
    public int updateNewYn(MyDocumentsVO searchVO) throws Exception {
        return update("myDocuments.updateNewYn", searchVO);
    }

    /**
     * 내 문서명(DOC_NM) 변경
     */
    public int updateDocNm(MyDocumentsVO searchVO) throws Exception {
        return update("myDocuments.updateDocNm", searchVO);
    }

    /**
     * 내 문서 공유 정보 등록 (TB_MY_DOC_SHARE)
     * @param searchVO shareId, docId, fromUserId, toUserId, shareMsg 필수
     * @return
     * @throws Exception
     */
    public int insertDocShare(MyDocumentsVO.ShareDocPayload searchVO) throws Exception {
        return insert("myDocuments.insertDocShare", searchVO);
    }

    /**
     * 공유 문서 원본 DOC_ID 조회 (수신자·미저장 건만)
     * @param shareId TB_MY_DOC_SHARE.SHARE_ID
     * @param userId 수신 사용자 ID
     * @return 원본 DOC_ID
     * @throws Exception
     */
    public String selectDocIdByShareId(String shareId, String userId) throws Exception {
        Map<String, Object> param = new HashMap<>();
        param.put("shareId", shareId);
        param.put("userId", userId);
        return (String) selectOne("myDocuments.selectDocIdByShareId", param);
    }

    /**
     * 공유 문서 프리뷰용 원본 DOC_ID 조회 (SHARE_ID만)
     * @param shareId TB_MY_DOC_SHARE.SHARE_ID
     * @return 원본 DOC_ID
     * @throws Exception
     */
    public String selectDocIdByShareIdForInfo(String shareId) throws Exception {
        Map<String, Object> param = new HashMap<>();
        param.put("shareId", shareId);
        return (String) selectOne("myDocuments.selectDocIdByShareIdForInfo", param);
    }

    /**
     * 공유 문서 프리뷰 상세 조회 (원본 DOC_ID 기준, 소유자 USER_ID 조건 없음)
     * @param docId 원본 DOC_ID
     * @return 상세 VO
     * @throws Exception
     */
    public MyDocumentsVO selectSharedDocInfo(String docId) throws Exception {
        Map<String, Object> param = new HashMap<>();
        param.put("docId", docId);
        return (MyDocumentsVO) selectOne("myDocuments.selectSharedDocInfo", param);
    }

    /**
     * 공유 받은 내 문서 복사 등록
     * @param paramMap docId, userId, srcDocId, sortOrd 포함
     * @return 처리된 행 수
     * @throws Exception
     */
    public int insertReceiveMyDoc(Map<String, Object> paramMap) throws Exception {
        return insert("myDocuments.insertReceiveMyDoc", paramMap);
    }

    /**
     * 공유 문서 저장 정보 업데이트 (SAVE_YN, SAVE_DOC_ID)
     * @param paramMap shareId, saveDocId 포함
     * @return 처리된 행 수
     * @throws Exception
     */
    public int updateDocShareSave(Map<String, Object> paramMap) throws Exception {
        return update("myDocuments.updateDocShareSave", paramMap);
    }

}
