package kr.teamagent.common.apilog.service.impl;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.apilog.service.ApiCallLogVO;
import kr.teamagent.common.util.PropertyUtil;

@Service
public class ApiCallLogServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(ApiCallLogServiceImpl.class);

    /** API 호출 로그용 서버 호스트명 — 기동 시 한 번만 조회 */
    private static final String LOCAL_HOST_NM;
    static {
        String hn = "unknown";
        try {
            String resolved = java.net.InetAddress.getLocalHost().getHostName();
            if (resolved != null && !resolved.isEmpty()) hn = resolved;
        } catch (Exception ignore) {}
        LOCAL_HOST_NM = hn;
    }

    @Autowired
    private ApiCallLogDAO apiCallLogDAO;

    /**
     * TB_API_CALL_LOG 저장 — 실패 시 로그만 남기고 본 흐름에 영향 없음.
     */
    public void insertSilently(String agentId, Long refLogId, String apiUrl, String modelId,
            String callType, String reqParamJson, int inTokens, int outTokens, int respTimeMs,
            String successYn, String errorMsg, String createUserId) {
        try {
            ApiCallLogVO logVO = new ApiCallLogVO();
            logVO.setAgentId(agentId != null && !agentId.isEmpty() ? agentId : null);
            logVO.setApiUrl(apiUrl != null && !apiUrl.isEmpty() ? apiUrl : null);
            logVO.setCallType(callType);
            logVO.setRefLogId(refLogId);
            logVO.setModelId(modelId);
            String rawEnv = PropertyUtil.getProperty("Globals.env");
            logVO.setEnvCd(rawEnv != null ? rawEnv.toUpperCase() : "UNKNOWN");
            logVO.setHostNm(LOCAL_HOST_NM);
            logVO.setInTokens(inTokens);
            logVO.setOutTokens(outTokens);
            logVO.setRespTimeMs(respTimeMs);
            logVO.setSuccessYn(successYn);
            logVO.setErrorMsg(errorMsg != null && errorMsg.length() > 1000 ? errorMsg.substring(0, 1000) : errorMsg);
            logVO.setReqParam(reqParamJson);
            logVO.setCreateUserId(createUserId);
            apiCallLogDAO.insertApiCallLog(logVO);
        } catch (Exception e) {
            logger.warn("API 호출 로그 저장 실패: {}", e.getMessage());
        }
    }
}
