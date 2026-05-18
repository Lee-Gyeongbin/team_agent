package kr.teamagent.datadashboard.service.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.datadashboard.service.DataDashboardVO;
import kr.teamagent.datamart.service.DatamartVO;
import kr.teamagent.datamart.service.impl.DatamartServiceImpl;

@Service
public class DataDashboardServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(DataDashboardServiceImpl.class);

    @Autowired
    private DataDashboardDAO dataDashboardDAO;

    @Autowired
    private DatamartServiceImpl datamartService;

    @Autowired
    private KeyGenerate keyGenerate;

    // ===== SQL 목록 =====

    /**
     * 현재 사용자의 TextToSQL 쿼리 목록 조회
     * TB_CHAT_LOG (SVC_TY='S', TTSQ NOT NULL, CREATE_USER_ID=userId)
     */
    public List<DataDashboardVO> selectDashboardSqlList(DataDashboardVO searchVO) throws Exception {
        return dataDashboardDAO.selectDashboardSqlList(searchVO);
    }

    // ===== 위젯 =====

    /**
     * 사용자 위젯 목록 조회
     */
    public List<DataDashboardVO> selectDashboardWidgetList(DataDashboardVO searchVO) throws Exception {
        return dataDashboardDAO.selectDashboardWidgetList(searchVO);
    }

    /**
     * 위젯 저장 (신규 생성 또는 수정)
     */
    @Transactional(rollbackFor = Exception.class)
    public DataDashboardVO saveDashboardWidget(DataDashboardVO widgetVO) throws Exception {
        if (widgetVO.getWidgetId() == null || widgetVO.getWidgetId().trim().isEmpty()) {
            widgetVO.setWidgetId(keyGenerate.generateTableKey("WG", "TB_USER_DASHBOARD_WIDGET", "WIDGET_ID"));
            widgetVO.setSortOrd(selectMaxWidgetSortOrd(widgetVO) + 1);
        }
        dataDashboardDAO.saveDashboardWidget(widgetVO);
        return selectSavedWidget(widgetVO);
    }

    /**
     * 위젯 삭제
     */
    public void deleteDashboardWidget(DataDashboardVO searchVO) throws Exception {
        dataDashboardDAO.deleteDashboardWidget(searchVO);
    }

    /**
     * 위젯 순서 일괄 변경
     */
    public void updateDashboardWidgetOrder(DataDashboardVO searchVO) throws Exception {
        if (searchVO.getOrderList() != null && !searchVO.getOrderList().isEmpty()) {
            dataDashboardDAO.updateDashboardWidgetOrder(searchVO);
        }
    }

    // ===== SQL 실행 =====

    /**
     * 위젯의 SQL을 데이터마트에 직접 실행하여 결과 반환
     * @param searchVO widgetId 또는 logId, sqlParams(JSON) 포함
     * @return columns(컬럼명 목록) + rows(데이터 행 목록)
     */
    public Map<String, Object> executeDashboardSql(DataDashboardVO searchVO) throws Exception {
        // 1. 채팅 로그 + 데이터마트 연결 정보 조회
        DataDashboardVO info = dataDashboardDAO.selectSqlDatamartInfo(searchVO);
        if (info == null || info.getSqlContent() == null) {
            throw new Exception("SQL 정보를 찾을 수 없습니다.");
        }

        // 2. 데이터마트 연결 정보 조회
        DatamartVO dmSearch = new DatamartVO();
        dmSearch.setDatamartId(info.getDatamartId());
        DatamartVO dm = datamartService.selectDatamart(dmSearch);
        if (dm == null) {
            throw new Exception("데이터마트 연결 정보를 찾을 수 없습니다. (datamartId: " + info.getDatamartId() + ")");
        }

        // 3. 파라미터 파싱 (JSON string → Map)
        Map<String, String> paramMap = parseJsonParams(searchVO.getSqlParams());

        // 4. SQL에서 ':param_name' 패턴을 '?'로 치환하고 순서대로 값 추출
        String rawSql = info.getSqlContent().trim();
        List<String> paramOrder = new ArrayList<>();
        String execSql = replaceNamedParams(rawSql, paramMap, paramOrder);

        logger.info("[DataDashboard] execSql: {}", execSql);

        // 5. JDBC 연결 후 실행
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = datamartService.openJdbcConnection(dm);

            ps = conn.prepareStatement(execSql);
            for (int i = 0; i < paramOrder.size(); i++) {
                ps.setString(i + 1, paramMap.getOrDefault(paramOrder.get(i), ""));
            }

            rs = ps.executeQuery();
            return buildQueryResult(rs);

        } finally {
            if (rs != null) try { rs.close(); } catch (Exception ignored) {}
            if (ps != null) try { ps.close(); } catch (Exception ignored) {}
            if (conn != null) try { conn.close(); } catch (Exception ignored) {}
        }
    }

    // ===== private helpers =====

    private DataDashboardVO selectSavedWidget(DataDashboardVO widgetVO) throws Exception {
        List<DataDashboardVO> list = dataDashboardDAO.selectDashboardWidgetList(widgetVO);
        return list.stream()
                .filter(w -> widgetVO.getWidgetId().equals(w.getWidgetId()))
                .findFirst()
                .orElse(widgetVO);
    }

    private int selectMaxWidgetSortOrd(DataDashboardVO widgetVO) {
        try {
            List<DataDashboardVO> list = dataDashboardDAO.selectDashboardWidgetList(widgetVO);
            return list.stream()
                    .mapToInt(w -> w.getSortOrd() != null ? w.getSortOrd() : 0)
                    .max()
                    .orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * JSON 문자열 → Map<String,String> 파싱 (단순 구현, 중첩 없는 flat 객체 가정)
     * 예: {"start_date":"2026-05-01","end_date":"2026-05-31"}
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseJsonParams(String jsonParams) {
        Map<String, String> result = new HashMap<>();
        if (jsonParams == null || jsonParams.trim().isEmpty()) return result;
        try {
            // Jackson ObjectMapper가 없을 경우 수동 파싱
            // JSON: {"key":"val","key2":"val2"}
            String content = jsonParams.trim();
            if (content.startsWith("{") && content.endsWith("}")) {
                content = content.substring(1, content.length() - 1);
                // 쌍따옴표 키-값 파싱 (간단한 flat JSON)
                Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
                Matcher m = p.matcher(content);
                while (m.find()) {
                    result.put(m.group(1), m.group(2));
                }
            }
        } catch (Exception e) {
            logger.warn("[DataDashboard] JSON 파라미터 파싱 오류: {}", e.getMessage());
        }
        return result;
    }

    /**
     * SQL의 ':param_name' 패턴을 '?'로 치환하고 파라미터 순서 목록 반환
     */
    private String replaceNamedParams(String sql, Map<String, String> paramMap, List<String> paramOrder) {
        Pattern p = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher m = p.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            paramOrder.add(key);
            m.appendReplacement(sb, "?");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * ResultSet → {columns: [...], rows: [...]} 변환
     */
    private Map<String, Object> buildQueryResult(ResultSet rs) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(columns.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        return result;
    }

}
