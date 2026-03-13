package kr.teamagent.llm.service;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LlmVO {

    /** TB_LLM_MDL */
    private String modelId;
    private String modelName;
    private String providerId;
    private String version;
    private BigDecimal inputCost;
    private BigDecimal outputCost;
    private String modelUseYn;
    private String modelDescription;
    private Integer sortOrder;
    private String modelCreateDt;
    private String modelModifyDt;

    /** TB_LLM_PROVIDER */
    private String providerName;
    private String baseUrl;
    private String authType;
    private String providerDescription;
    private String providerUseYn;
    private String providerCreateDt;
    private String providerModifyDt;

    /** TB_LLM_MDL_API */
    private String apiUrl;
    private String apiKey;
    private Integer tmoSec;
    private Integer retryCnt;
    private String custHeaders;

    /** TB_LLM_MDL_PARAM */
    private Double temperature;
    private Double topP;
    private Integer maxTokens;
    private Integer ctxtWin;
    private Double freqPenalty;
    private Double presPenalty;
    private String streamYn;
    private String fnCallYn;
    private String visionYn;

    /** TB_LLM_MDL_LMT */
    private Integer dayReqLmt;
    private Integer rpmLimit;
    private Integer tpmLimit;
    private BigDecimal dayCostLmt;

    /** TB_LLM_MDL_ACCESS */
    private String roleId;
    private String allowedYn;

}
