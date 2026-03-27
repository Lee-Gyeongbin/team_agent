package kr.teamagent.dashboard.service;

import lombok.Getter;
import lombok.Setter;
@Getter
@Setter
public class DashBoardVO {

    /** 통계 요약 정보 */
    @Getter
    @Setter
    public static class StatSummary {
        private String feedbackCount;
        private String todayQueryCount;
        private Double satisfactionRate;
        private Double dissatisfactionRate;
    }

    /** 질의 비율 (LLM / RAG / TEXT_TO_SQL) */
    @Getter
    @Setter
    public static class QueryRatio {
        private Double llm;
        private Double rag;
        private Double textToSql;
    }

    /** 대시보드 공지 요약 항목 */
    @Getter
    @Setter
    public static class NoticeItem {
        private String noticeId;
        private String title;
        private String featuredYn;
        private String crtrId;
        private String createDt;
        private String modifyDt;
    }

    /** 토큰 사용량 (월별 + 한도) */
    @Getter
    @Setter
    public static class TokenUsage {
        private String ym;
        private Long tokenUsage;
        private Long monOrgLmt;
        private Double usageRate;
    }

    /** 사용자(방문) 추이 (일별) */
    @Getter
    @Setter
    public static class VisitorTrend {
        private String statDate;
        private Long successCnt;
    }

}

