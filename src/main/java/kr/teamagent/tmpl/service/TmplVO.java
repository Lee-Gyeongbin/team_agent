package kr.teamagent.tmpl.service;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 템플릿 도메인 VO (TB_TMPL)
 */
@Getter
@Setter
public class TmplVO {

    /** TB_TMPL */
    private String tmplId;
    private String tmplNm;
    private String tmplType;
    private String description;
    private String llmPromptSmry;
    private String llmPrompt;
    private String sysTmplYn;
    private String useYn;
    private String createDt;
    private String modifyDt;
    private List<TmplFieldVO> fields;

    /** TB_TMPL_FIELD */
    @Getter
    @Setter
    public static class TmplFieldVO {

        private String fieldId;
        private String tmplId;
        private String jsonKey;
        private String fieldNm;
        private String multilineYn;
        private Integer sortOrd;
        private String useYn;
        private String createDt;
        private String modifyDt;

    }

}
