package kr.teamagent.prompt.service;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PromptVO {

    /** TB_PROMPT */
    private String promptId;
    private String promptTypeCd;
    private String content;
    private Double temperature;
    private Double topP;
    private String applyLlmYn;
    private String applyRagYn;
    private String applySqlYn;
    private String useYn;
    private String createDt;
    private String modifyDt;

    /** TB_CODE 조인 (PR000001) */
    private String promptName;

}
