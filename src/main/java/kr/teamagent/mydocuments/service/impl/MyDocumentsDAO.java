package kr.teamagent.mydocuments.service.impl;

import java.util.List;

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

}
