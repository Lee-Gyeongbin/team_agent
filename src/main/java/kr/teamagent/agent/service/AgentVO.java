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

    @Getter
    @Setter
    public static class ModelVO {
        private String modelId;
        private String modelName;
    }

    /** TB_AGT_DS */
    @Getter
    @Setter
    public static class DsVO {
        private String agentId;
        private String datasetId;
        private String dsNm;
        private String connYn;
        private Integer sortOrd;
        private String description;
        private Integer docCount;
        private Integer chunkSize;
        private String modifyDt;
    }

    /** TB_AGT_DM */
    @Getter
    @Setter
    public static class DmVO {
        private String agentId;
        private String datamartId;
        private String dmNm;
        private String description;
        private String connYn;
        private String dbType;
        private Integer tblCnt;
        private String lastVerifyDt;
    }

}
