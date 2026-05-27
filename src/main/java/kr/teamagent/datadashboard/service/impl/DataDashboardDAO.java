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
     * 위젯 너비(COL_SPAN)만 변경
     */
    public int updateDashboardWidgetColSpan(DataDashboardVO searchVO) throws Exception {
        return update("dataDashboard.updateDashboardWidgetColSpan", searchVO);
    }

    /**
     * 위젯 순서 일괄 변경
     */
    public int updateDashboardWidgetOrder(DataDashboardVO searchVO) throws Exception {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("orderList", searchVO.getOrderList());
        paramMap.put("userId", searchVO.getUserId());
        return update("dataDashboard.updateDashboardWidgetOrder", paramMap);
    }

    /**
     * SQL 실행에 필요한 채팅 로그 + 데이터마트 연결 정보 조회
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
     * 레이아웃 저장 (INSERT ... ON DUPLICATE KEY UPDATE)
     */
    public int saveDashboardLayout(DataDashboardVO layoutVO) throws Exception {
        return (int) insert("dataDashboard.saveDashboardLayout", layoutVO);
    }

    /**
     * 레이아웃 순서/위치 일괄 UPSERT (드래그 후)
     * 레코드 없으면 INSERT, 있으면 UPDATE
     */
    public int updateDashboardLayoutOrder(DataDashboardVO searchVO) throws Exception {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("layoutOrderList", searchVO.getLayoutOrderList());
        paramMap.put("userId", searchVO.getUserId());
        return (int) insert("dataDashboard.updateDashboardLayoutOrder", paramMap);
    }

    /**
     * 높이 초기화 (HEIGHT_PX = NULL)
     */
    public int resetDashboardLayoutHeight(DataDashboardVO searchVO) throws Exception {
        return update("dataDashboard.resetDashboardLayoutHeight", searchVO);
    }

    /**
     * 레이아웃 삭제
     */
    public int deleteDashboardLayout(DataDashboardVO searchVO) throws Exception {
        return delete("dataDashboard.deleteDashboardLayout", searchVO);
    }

}
