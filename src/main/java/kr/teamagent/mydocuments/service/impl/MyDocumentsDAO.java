package kr.teamagent.mydocuments.service.impl;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.mydocuments.service.MyDocumentsVO;

@Repository
public class MyDocumentsDAO extends EgovComAbstractDAO {

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

}
