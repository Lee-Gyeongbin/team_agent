package kr.teamagent.datadashboard.service.impl;

import java.util.HashMap;
import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.datadashboard.service.DataDashboardVO;

@Repository
public class DataDashboardDAO extends EgovComAbstractDAO {

    /**
     * 사용자의 TextToSQL 목록 조회
     * TB_CHAT_LOG에서 SVC_TY='S', TTSQ IS NOT NULL, CREATE_USER_ID=userId
     */
    public List<DataDashboardVO> selectDashboardSqlList(DataDashboardVO searchVO) throws Exception {
        return selectList("dataDashboard.selectDashboardSqlList", searchVO);
    }

    /**
     * 사용자 위젯 목록 조회 (TB_USER_DASHBOARD_WIDGET JOIN TB_CHAT_LOG)
     */
    public List<DataDashboardVO> selectDashboardWidgetList(DataDashboardVO searchVO) throws Exception {
        return selectList("dataDashboard.selectDashboardWidgetList", searchVO);
    }

    /**
     * 위젯 저장 (INSERT ... ON DUPLICATE KEY UPDATE)
     */
    public int saveDashboardWidget(DataDashboardVO widgetVO) throws Exception {
        return (int) insert("dataDashboard.saveDashboardWidget", widgetVO);
    }

    /**
     * 위젯 삭제
     */
    public int deleteDashboardWidget(DataDashboardVO searchVO) throws Exception {
        return delete("dataDashboard.deleteDashboardWidget", searchVO);
    }

    /**
     * SQL 실행에 필요한 채팅 로그 + 데이터마트 ID 조회
     */
    public DataDashboardVO selectSqlDatamartInfo(DataDashboardVO searchVO) throws Exception {
        return (DataDashboardVO) selectOne("dataDashboard.selectSqlDatamartInfo", searchVO);
    }

    /**
     * 데이터마트 컬럼 코드 매핑 조회 (TB_DM_COL_CODE, USE_YN='Y')
     */
    public List<DataDashboardVO> selectDashboardColCodeMap(DataDashboardVO searchVO) throws Exception {
        return selectList("dataDashboard.selectDashboardColCodeMap", searchVO);
    }

    // ===== 레이아웃 =====

    /**
     * 사용자 레이아웃 목록 조회
     */
    public List<DataDashboardVO> selectDashboardLayoutList(DataDashboardVO searchVO) throws Exception {
        return selectList("dataDashboard.selectDashboardLayoutList", searchVO);
    }

    /**
     * 위젯별 레이아웃 단건 조회 (USER_ID + WIDGET_ID)
     */
    public DataDashboardVO selectDashboardLayoutByWidget(DataDashboardVO searchVO) throws Exception {
        return (DataDashboardVO) selectOne("dataDashboard.selectDashboardLayoutByWidget", searchVO);
    }

    /**
     * 레이아웃 저장 (INSERT ... ON DUPLICATE KEY UPDATE) — 위젯 신규 생성 시 초기 레코드 생성
     */
    public int saveDashboardLayout(DataDashboardVO layoutVO) throws Exception {
        return (int) insert("dataDashboard.saveDashboardLayout", layoutVO);
    }

    /**
     * 레이아웃 일괄 UPSERT (GridStack 레이아웃 저장 버튼 클릭 시)
     * UNIQUE KEY (USER_ID, WIDGET_ID) 기준으로 없으면 INSERT, 있으면 UPDATE
     */
    public int saveLayoutBatch(DataDashboardVO searchVO) throws Exception {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("layoutBatchList", searchVO.getLayoutBatchList());
        paramMap.put("userId", searchVO.getUserId());
        return (int) insert("dataDashboard.saveLayoutBatch", paramMap);
    }

    /**
     * 사용자의 전체 레이아웃 삭제 (레이아웃 초기화)
     */
    public int deleteAllLayouts(DataDashboardVO searchVO) throws Exception {
        return delete("dataDashboard.deleteAllLayouts", searchVO);
    }

    /**
     * 레이아웃 삭제 (위젯 삭제 시 함께 호출)
     */
    public int deleteDashboardLayout(DataDashboardVO searchVO) throws Exception {
        return delete("dataDashboard.deleteDashboardLayout", searchVO);
    }

}
