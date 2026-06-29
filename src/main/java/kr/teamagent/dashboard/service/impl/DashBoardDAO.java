package kr.teamagent.dashboard.service.impl;

import java.util.HashMap;
import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.dashboard.service.DashBoardVO;

@Repository
public class DashBoardDAO extends EgovComAbstractDAO {

    /**
     * 대시보드 상단 통계 카드 조회
     */
    public DashBoardVO.StatSummary selectStatSummary() throws Exception {
        return selectOne("dashboard.selectStatSummary");
    }

    /**
     * 대시보드 질의 비율 조회
     */
    public DashBoardVO.QueryRatio selectQueryRatio(String ym) throws Exception {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("ym", ym);
        return selectOne("dashboard.selectQueryRatio", paramMap);
    }

    /**
     * 대시보드 공지 요약 목록 조회
     */
    public List<DashBoardVO.NoticeItem> selectDashboardNoticeList() throws Exception {
        return selectList("dashboard.selectDashboardNoticeList");
    }

    /**
     * 대시보드 토큰 사용량 목록 조회
     */
    public List<DashBoardVO.TokenUsage> selectTokenUsageList(String ym) throws Exception {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("ym", ym);
        return selectList("dashboard.selectTokenUsageList", paramMap);
    }

    /**
     * 대시보드 사용자(방문) 추이 조회
     */
    public List<DashBoardVO.VisitorTrend> selectVisitorTrendList() throws Exception {
        return selectList("dashboard.selectVisitorTrendList");
    }

    /**
     * 대시보드 키워드 추이 조회
     */
    public List<DashBoardVO.KeywordTrend> selectKeywordTrend(int dayCnt) throws Exception {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("dayCnt", dayCnt);
        return selectList("dashboard.selectKeywordTrend", paramMap);
    }

    /**
     * 배치용 어제 질문·답변 목록 조회
     */
    public List<DashBoardVO.KeywordChatLog> selectYesterdayKeywordChatLogList() throws Exception {
        return selectList("dashboard.selectYesterdayKeywordChatLogList");
    }

    /**
     * 배치용 어제 키워드 통계 삭제
     */
    public int deleteYesterdayKeywordDailyStat() throws Exception {
        return delete("dashboard.deleteYesterdayKeywordDailyStat");
    }

    /**
     * 배치용 어제 키워드 일별 통계 등록
     */
    public int insertYesterdayKeywordDailyStat(DashBoardVO.KeywordDailyStat saveVO) throws Exception {
        return insert("dashboard.insertYesterdayKeywordDailyStat", saveVO);
    }

    /**
     * 배치용 기준 키워드 조회 (N일 전 통계 목록)
     */
    public List<DashBoardVO.KeywordDailyStat> selectKeywordDailyStatListByDaysAgo(int daysAgo) throws Exception {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("daysAgo", daysAgo);
        return selectList("dashboard.selectKeywordDailyStatListByDaysAgo", paramMap);
    }
}
