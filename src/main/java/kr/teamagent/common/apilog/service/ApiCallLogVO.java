package kr.teamagent.common.apilog.service;

import lombok.Getter;
import lombok.Setter;

/**
 * API 호출 로그 VO (TB_API_CALL_LOG)
 */
@Getter
@Setter
public class ApiCallLogVO {

    private Long   apiLogId;
    private String agentId;
    private String apiUrl;
    private String callType;
    private Long   refLogId;
    private String modelId;
    private String envCd;
    private String hostNm;
    private int    inTokens;
    private int    outTokens;
    private int    respTimeMs;
    private String successYn = "Y";
    private String errorMsg;
    private String reqParam;
    private String createUserId;

}
