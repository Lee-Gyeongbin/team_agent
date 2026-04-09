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
    private String applyLlmYn;
    private String sysPtYn;
    private Integer priority;
    private String useYn;
    private String createDt;
    private String modifyDt;


    /** TB_CODE 조인 (PR000001) */
    private String promptName;

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

    /** 금지어/필터링 저장 요청용 */
    @Getter
    @Setter
    public static class FilterSaveVO {
        private List<BanWordVO> inputBanWords;
        private List<BanWordVO> outputBanWords;
        private List<PolicyVO> policies;
    }

    /** TB_TOKEN_LMT */
    @Getter
    @Setter
    public static class TokenLmtVO {
        private String limitId;
        private Integer maxInTokens;
        private Integer maxOutTokens;
        private Integer ctxtWin;
        private Integer dayUserLmt;
        private Integer monOrgLmt;
        private Integer rateLmtRpm;
        private Integer minRespLen;
        private Integer respTmo;
        private Integer retryCnt;
        private String streamYn;
        private String modifyDt;
    }

    /** TB_AGENT */
    @Getter
    @Setter
    public static class AgentVO {
        private String agentId;
        private String agentNm;
    }

    /** 프롬프트 적용 에이전트 목록 */
    @Getter
    @Setter
    public static class PromptAppAgtVO extends AgentVO {
        private String promptId;
        private String applyYn;
    }

    /** 시스템 프롬프트 저장 폼 (PromptVO + 적용 에이전트 목록) */
    @Getter
    @Setter
    public static class SaveFormVO extends PromptVO {
        private List<PromptAppAgtVO> promptAppAgtList;
    }

}
