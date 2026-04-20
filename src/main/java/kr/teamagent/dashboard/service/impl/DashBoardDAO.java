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
     * 대시보드 카테고리 목록 조회
     */
    public List<DashBoardVO.CategoryTrend> selectCategoryTrend(int dayCnt) throws Exception {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("dayCnt", dayCnt);
        return selectList("dashboard.selectCategoryTrend", paramMap);
    }

    /**
     * 대시보드 카테고리 목록 등록
     */
    public int insertCategoryTrend(DashBoardVO.CategoryTrend saveVO) throws Exception {
        return insert("dashboard.insertCategoryTrend", saveVO);
    }

    /**
     * 배치용 어제 질문 목록 조회
     */
    public List<String> selectYesterdayQContentList() throws Exception {
        return selectList("dashboard.selectYesterdayQContentList");
    }

    /**
     * 배치용 어제 카테고리 통계 삭제
     */
    public int deleteYesterdayCategoryTrend() throws Exception {
        return delete("dashboard.deleteYesterdayCategoryTrend");
    }
}
