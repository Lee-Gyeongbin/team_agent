package kr.teamagent.agent.service;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AgentVO {

    /** TB_AGT */
    private String agentId;
    private String agentNm;
    private String svcTy;
    private String svcTyNm;
    private String temperature;
    private String tempDefaultYn;
    private String topP;
    private String topPDefaultYn;
    private String apiUrlCd;
    private String iconId;
    private String colorId;
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

    /** TB_COLOR */
    @Getter
    @Setter
    public static class ColorVO {
        private String colorId;
        private String colorNm;
        private String colorKey;
        private String colorHex;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    /** TB_ICON */
    @Getter
    @Setter
    public static class IconVO {
        private String iconId;
        private String iconNm;
        private String iconClassNm;
        private String iconFileNm;
        private String iconFilePath;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    /** TB_AGT_DS */
    @Getter
    @Setter
    public static class DsVO {
        private String agentId;
        private String datasetId;
        private String dsNm;
        private String connYn;
        private String description;
        private Integer docCount;
        private Integer chunkSize;
        private String modifyDt;
    }

    /** 에이전트 저장 폼 */
    @Getter
    @Setter
    public static class SaveFormVO extends AgentVO {
        private List<DsVO> datasetList;
        private List<DmVO> datamartList;
    }

    /** 에이전트 순서 변경 항목 */
    @Getter
    @Setter
    public static class OrderItemVO {
        private String agentId;
        private Integer sortOrd;
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
