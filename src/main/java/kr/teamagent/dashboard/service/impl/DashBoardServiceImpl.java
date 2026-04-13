package kr.teamagent.dashboard.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.dashboard.service.DashBoardVO;

@Service
public class DashBoardServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    DashBoardDAO dashBoardDAO;

    /**
     * 대시보드 상단 통계 카드 조회
     */
    public DashBoardVO.StatSummary selectStatSummary() throws Exception {
        return dashBoardDAO.selectStatSummary();
    }

    /**
     * 대시보드 질의 비율 조회
     */
    public DashBoardVO.QueryRatio selectQueryRatio(String ym) throws Exception {
        return dashBoardDAO.selectQueryRatio(ym);
    }

    /**
     * 대시보드 공지 요약 목록 조회
     */
    public List<DashBoardVO.NoticeItem> selectDashboardNoticeList() throws Exception {
        return dashBoardDAO.selectDashboardNoticeList();
    }

    /**
     * 대시보드 토큰 사용량 조회
     */
    public List<DashBoardVO.TokenUsage> selectTokenUsage(String ym) throws Exception {
        return dashBoardDAO.selectTokenUsageList(ym);
    }

    /**
     * 대시보드 사용자(방문) 추이 조회
     */
    public List<DashBoardVO.VisitorTrend> selectVisitorTrend() throws Exception {
        return dashBoardDAO.selectVisitorTrendList();
    }

}
