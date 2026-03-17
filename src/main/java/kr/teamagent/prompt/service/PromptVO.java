package kr.teamagent.prompt.service;

import java.util.List;

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

    /** 금지어/필터링 저장용 */
    private List<BanWordVO> inputBanWords;
    private List<BanWordVO> outputBanWords;
    private List<PolicyVO> policies;

    /** TB_BAN_WORD */
    @Getter
    @Setter
    public static class BanWordVO {
        private String wordId;
        private String word;
        private String wordType;
        private String useYn;
        private String createDt;
    }

    /** TB_CONTENT_FLTR + TB_CODE (CF000001) */
    @Getter
    @Setter
    public static class PolicyVO {
        private String filterCd;
        private String filterName;
        private String filterDesc;
        private String applyYn;
        private Integer sortOrd;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

}
