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
    private String tmplHtml;

    private List<TmplFieldVO> fields;
    

    /** 템플릿 저장 요청 폼 (TB_TMPL + TB_TMPL_FIELD) */
    @Getter
    @Setter
    public static class SaveFormVO extends TmplVO {
    }

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
