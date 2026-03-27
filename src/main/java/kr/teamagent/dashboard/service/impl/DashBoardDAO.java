package kr.teamagent.dashboard.service.impl;

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

}
