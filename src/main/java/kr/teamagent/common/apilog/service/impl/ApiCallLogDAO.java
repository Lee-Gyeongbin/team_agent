package kr.teamagent.common.apilog.service.impl;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.common.apilog.service.ApiCallLogVO;

@Repository
public class ApiCallLogDAO extends EgovComAbstractDAO {

    /**
     * API 호출 로그 등록 (TB_API_CALL_LOG)
     */
    public int insertApiCallLog(ApiCallLogVO vo) throws Exception {
        return insert("ai.apiCallLog.insertApiCallLog", vo);
    }
}
