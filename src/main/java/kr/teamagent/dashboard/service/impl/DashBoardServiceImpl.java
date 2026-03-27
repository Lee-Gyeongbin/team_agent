package kr.teamagent.dashboard.service.impl;

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

}
