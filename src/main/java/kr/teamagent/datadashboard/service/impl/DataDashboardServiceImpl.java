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
import kr.teamagent.common.util.SessionUtil;
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
    public void saveDashboardWidget(DataDashboardVO widgetVO) throws Exception {
        widgetVO.setUserId(SessionUtil.getUserId());
        if (widgetVO.getWidgetId() == null || widgetVO.getWidgetId().trim().isEmpty()) {
            widgetVO.setWidgetId(keyGenerate.generateTableKey("WG", "TB_USER_DASHBOARD_WIDGET", "WIDGET_ID"));
            widgetVO.setSortOrd(selectMaxWidgetSortOrd(widgetVO) + 1);
        }
        dataDashboardDAO.saveDashboardWidget(widgetVO);
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

    // ===== 코드 매핑 =====

    /**
     * 데이터마트 컬럼 코드 매핑 조회
     * TB_DM_COL_CODE에서 datamartId 기준, USE_YN='Y'인 항목 반환
     */
    public List<DataDashboardVO> selectDashboardColCodeMap(DataDashboardVO searchVO) throws Exception {
        return dataDashboardDAO.selectDashboardColCodeMap(searchVO);
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

        // 4. WHERE 조건 직접 치환 (TTSQ는 하드코딩된 값을 가진 SQL이므로 키 기반 치환 사용)
        String rawSql = info.getSqlContent().trim();
        String execSql = replaceWhereConditions(rawSql, paramMap);

        logger.info("[DataDashboard] execSql: {}", execSql);

        // 5. JDBC 연결 후 실행
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = datamartService.openJdbcConnection(dm);

            ps = conn.prepareStatement(execSql);

            rs = ps.executeQuery();
            return buildQueryResult(rs);

        } finally {
            if (rs != null) try { rs.close(); } catch (Exception ignored) {}
            if (ps != null) try { ps.close(); } catch (Exception ignored) {}
            if (conn != null) try { conn.close(); } catch (Exception ignored) {}
        }
    }

    // ===== private helpers =====

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
     * JSON 문자열 → Map<String,String> 파싱 (flat 객체, 문자열·배열 값 지원)
     * - 문자열: {"year":"2026"}           → {year: "2026"}
     * - 배열:   {"regn_cd":["02","03"]}   → {regn_cd: "02,03"} (콤마 구분 문자열로 저장)
     */
    private Map<String, String> parseJsonParams(String jsonParams) {
        Map<String, String> result = new HashMap<>();
        if (jsonParams == null || jsonParams.trim().isEmpty()) return result;
        try {
            String content = jsonParams.trim();
            if (!content.startsWith("{") || !content.endsWith("}")) return result;
            content = content.substring(1, content.length() - 1);

            // 배열 값: "key":["val1","val2",...] → key=val1,val2
            Pattern arrayP = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[([^\\]]*)\\]");
            Matcher arrayM = arrayP.matcher(content);
            while (arrayM.find()) {
                String key = arrayM.group(1);
                String arrContent = arrayM.group(2);
                Pattern valP = Pattern.compile("\"([^\"]*)\"");
                Matcher valM = valP.matcher(arrContent);
                List<String> vals = new ArrayList<>();
                while (valM.find()) {
                    vals.add(valM.group(1));
                }
                result.put(key, String.join(",", vals));
            }

            // 문자열 값: "key":"val" (배열로 이미 파싱된 키는 덮어쓰지 않음)
            Pattern strP = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
            Matcher strM = strP.matcher(content);
            while (strM.find()) {
                result.putIfAbsent(strM.group(1), strM.group(2));
            }
        } catch (Exception e) {
            logger.warn("[DataDashboard] JSON 파라미터 파싱 오류: {}", e.getMessage());
        }
        return result;
    }

    /**
     * WHERE 조건에서 paramMap 키(컬럼명)에 해당하는 조건을 직접 치환
     * - 값이 있으면: IN('old') → IN('new'), = 'old' → = 'new'
     * - 값이 비어있으면: AND [alias.]COLUMN IN(...) 또는 AND [alias.]COLUMN = ... 조건 전체 제거
     *
     * 주의: PreparedStatement를 거치지 않으므로 내부 관리 도구 전용으로만 사용할 것.
     *       SQL 인젝션 최소 방어로 단따옴표 이스케이프만 적용.
     */
    private String replaceWhereConditions(String sql, Map<String, String> paramMap) {
        for (Map.Entry<String, String> entry : paramMap.entrySet()) {
            String colName = entry.getKey().toUpperCase();
            String value   = entry.getValue();

            if (value == null || value.isEmpty()) {
                // 빈값: AND [alias.]COL IN (...) 또는 AND [alias.]COL = '...' 조건 전체 제거
                Pattern p = Pattern.compile(
                    "\\s+AND\\s+(?:\\w+\\.)?"+colName+"\\s+(?:IN\\s*\\([^)]*\\)|=\\s*'[^']*'|=\\s*[0-9]+)",
                    Pattern.CASE_INSENSITIVE
                );
                sql = p.matcher(sql).replaceAll("");
            } else {
                // IN 조건 치환: [alias.]COL IN ('old1','old2',...) → IN ('new1','new2',...)
                Pattern inP = Pattern.compile(
                    "((?:\\w+\\.)?"+colName+"\\s+IN\\s*\\()([^)]*)(\\))",
                    Pattern.CASE_INSENSITIVE
                );
                Matcher inM = inP.matcher(sql);
                if (inM.find()) {
                    String inLiteral = buildInLiteral(value);
                    sql = inM.replaceAll("$1" + Matcher.quoteReplacement(inLiteral) + "$3");
                } else {
                    // = 조건 치환: [alias.]COL = 'old' 또는 = 123 → = 'new'
                    String safe = value.replace("'", "''");
                    Pattern eqP = Pattern.compile(
                        "((?:\\w+\\.)?"+colName+"\\s*=\\s*)('[^']*'|[0-9]+)",
                        Pattern.CASE_INSENSITIVE
                    );
                    sql = eqP.matcher(sql).replaceAll("$1'" + Matcher.quoteReplacement(safe) + "'");
                }
            }
        }
        return sql;
    }

    /**
     * 콤마 구분 값 문자열을 IN 리터럴로 변환
     * "02,03"  → "'02','03'"
     * "ST00031" → "'ST00031'"
     */
    private String buildInLiteral(String value) {
        String[] parts = value.split(",");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(",");
            sb.append("'").append(part.trim().replace("'", "''")).append("'");
        }
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
