package kr.teamagent.agent.service;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AgentVO {

    /** TB_AGT */
    private String agentId;
    private String agentNm;
    private String agentTypeCd;
    private String agentTypeCdNm;
    private String description;
    private Integer sortOrd;
    private String useYn;
    private String lastMdfDt;
    private String createDt;
    private String modifyDt;
    private String simThresh;
    private String maxSrchRslt;
    private String modelId;
    private String maxQrySec;
    private String sqlValidYn;
    private String readonlyYn;
    private String userCfrmYn;

    /** 데이터셋/데이터마트 연결 건수 */
    private Integer connCount;

    private String dynamicQuery;

}
