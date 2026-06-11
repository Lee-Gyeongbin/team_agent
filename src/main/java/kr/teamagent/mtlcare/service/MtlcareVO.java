package kr.teamagent.mtlcare.service;

import lombok.Getter;
import lombok.Setter;

/**
 * 멘탈케어 진단 결과 → 매니저 면담 요청 VO
 */
@Getter
@Setter
public class MtlcareVO {

    /** 진단 결과 (TB_MTLCARE_RESULT) */
    @Getter
    @Setter
    public static class ResultVO {
        private String resultId;
        private String userId;
        private String scoreJson;
        private String riskLevel;
        private String riskColor;
        private String riskBgColor;
        private String riskSummary;
        private String coreAreasSummary;
        private String createDt;
    }

    /** 멘탈케어 리포트 템플릿 정보 (TB_TMPL, TMPL_TYPE=MTLCARE_REPORT) */
    @Getter
    @Setter
    public static class TmplVO {
        private String tmplId;
        private String tmplType;
        private String llmPrompt;
        private String tmplHtml;
    }

    /** 면담 리포트 (TB_MTLCARE_REPORT) */
    @Getter
    @Setter
    public static class ReportVO {
        private String reportId;
        private String resultId;
        private String reqUserId;
        private String reqUserNm;
        private String mgrUserId;
        private String reqComment;
        private String tmplId;
        private String reportHtml;
        private String status;
        private String createDt;
        private String confirmDt;

        // 리포트 상세 조회 시 함께 반환되는 진단 결과 정보 (조인 결과)
        private String scoreJson;
        private String riskLevel;
        private String riskColor;
        private String riskBgColor;
        private String riskSummary;
        private String coreAreasSummary;
    }
}
