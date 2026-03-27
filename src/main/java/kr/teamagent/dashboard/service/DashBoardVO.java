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

}

