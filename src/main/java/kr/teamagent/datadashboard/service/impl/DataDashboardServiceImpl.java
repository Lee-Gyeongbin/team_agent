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
import java.util.stream.Collectors;
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
     * 위젯 저장 (신규 생성 또는 수정).
     * 신규 위젯 생성 시 레이아웃 초기 레코드도 함께 생성.
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveDashboardWidget(DataDashboardVO widgetVO) throws Exception {
        widgetVO.setUserId(SessionUtil.getUserId());
        boolean isNew = widgetVO.getWidgetId() == null || widgetVO.getWidgetId().trim().isEmpty();
        if (isNew) {
            widgetVO.setWidgetId(keyGenerate.generateTableKey("WG", "TB_USER_DASHBOARD_WIDGET", "WIDGET_ID"));
            widgetVO.setSortOrd(selectMaxWidgetSortOrd(widgetVO) + 1);
        }
        dataDashboardDAO.saveDashboardWidget(widgetVO);
        if (isNew) {
            initDashboardLayout(widgetVO);
        }
    }

    /**
     * 위젯 삭제 (레이아웃 레코드도 함께 삭제)
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDashboardWidget(DataDashboardVO searchVO) throws Exception {
        dataDashboardDAO.deleteDashboardLayout(searchVO);
        dataDashboardDAO.deleteDashboardWidget(searchVO);
    }

    // ===== 코드 매핑 =====

    /**
     * 데이터마트 컬럼명 한국어 매핑 조회 (TB_DM_COL, COL_ID 당 1건)
     */
    public List<DataDashboardVO> selectDashboardColNmMap(DataDashboardVO searchVO) throws Exception {
        return dataDashboardDAO.selectDashboardColNmMap(searchVO);
    }

    /**
     * 데이터마트 컬럼 코드 매핑 조회
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

        // 4. WHERE 조건 직접 치환
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

    // ===== 레이아웃 =====

    /**
     * 사용자 레이아웃 목록 조회
     */
    public List<DataDashboardVO> selectDashboardLayoutList(DataDashboardVO searchVO) throws Exception {
        return dataDashboardDAO.selectDashboardLayoutList(searchVO);
    }

    /**
     * 레이아웃 저장 (신규/수정) — 위젯 생성 시 초기 레코드 생성 및 단건 수정 시 사용
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveDashboardLayout(DataDashboardVO layoutVO) throws Exception {
        layoutVO.setUserId(SessionUtil.getUserId());
        if (layoutVO.getLayoutId() == null || layoutVO.getLayoutId().trim().isEmpty()) {
            layoutVO.setLayoutId(resolveLayoutId(layoutVO.getUserId(), layoutVO.getWidgetId()));
        }
        // 기본값 적용
        if (layoutVO.getX()        == null) layoutVO.setX(0);
        if (layoutVO.getY()        == null) layoutVO.setY(0);
        if (layoutVO.getW()        == null) layoutVO.setW(3);
        if (layoutVO.getH()        == null) layoutVO.setH(4);
        if (layoutVO.getMinW()     == null) layoutVO.setMinW(2);
        if (layoutVO.getMaxW()     == null) layoutVO.setMaxW(6);
        if (layoutVO.getMinH()     == null) layoutVO.setMinH(2);
        if (layoutVO.getMaxH()     == null) layoutVO.setMaxH(12);
        if (layoutVO.getIsVisible() == null) layoutVO.setIsVisible(true);
        if (layoutVO.getSortOrd()  == null) layoutVO.setSortOrd(1);
        dataDashboardDAO.saveDashboardLayout(layoutVO);
    }

    /**
     * 레이아웃 일괄 UPSERT (GridStack "레이아웃 저장" 버튼 클릭 시).
     * 신규 INSERT에 대비해 각 항목에 LI prefix 키를 미리 할당.
     * ON DUPLICATE KEY UPDATE 시 layoutId는 무시되고 나머지 컬럼만 갱신됨.
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveLayoutBatch(DataDashboardVO searchVO) throws Exception {
        if (searchVO.getLayoutBatchList() == null || searchVO.getLayoutBatchList().isEmpty()) return;
        searchVO.setUserId(SessionUtil.getUserId());
        assignLayoutIdsForBatch(searchVO);
        dataDashboardDAO.saveLayoutBatch(searchVO);
    }

    /**
     * 사용자의 전체 레이아웃 삭제 (레이아웃 초기화).
     * 삭제 후 프론트엔드가 페이지를 다시 로드하면 모든 위젯이 GridStack 기본 레이아웃으로 표시됨.
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetAllLayouts(DataDashboardVO searchVO) throws Exception {
        searchVO.setUserId(SessionUtil.getUserId());
        dataDashboardDAO.deleteAllLayouts(searchVO);
    }

    /**
     * 레이아웃 삭제
     */
    public void deleteDashboardLayout(DataDashboardVO searchVO) throws Exception {
        searchVO.setUserId(SessionUtil.getUserId());
        dataDashboardDAO.deleteDashboardLayout(searchVO);
    }

    // ===== private helpers =====

    /**
     * 기존 레이아웃 ID 재사용, 없을 때만 신규 키 생성.
     */
    private String resolveLayoutId(String userId, String widgetId) throws Exception {
        if (widgetId == null || widgetId.trim().isEmpty()) {
            return keyGenerate.generateTableKey("LI", "TB_USER_DASHBOARD_LAYOUT", "LAYOUT_ID");
        }
        DataDashboardVO searchVO = new DataDashboardVO();
        searchVO.setUserId(userId);
        searchVO.setWidgetId(widgetId);
        DataDashboardVO existing = dataDashboardDAO.selectDashboardLayoutByWidget(searchVO);
        if (existing != null && existing.getLayoutId() != null && !existing.getLayoutId().trim().isEmpty()) {
            return existing.getLayoutId();
        }
        return keyGenerate.generateTableKey("LI", "TB_USER_DASHBOARD_LAYOUT", "LAYOUT_ID");
    }

    /**
     * 일괄 UPSERT용 layoutId 할당.
     * 기존 위젯은 DB layoutId 재사용, 신규만 MAX 1회 조회 후 순번 증가.
     */
    private void assignLayoutIdsForBatch(DataDashboardVO searchVO) throws Exception {
        List<DataDashboardVO> existingLayouts = dataDashboardDAO.selectDashboardLayoutList(searchVO);
        Map<String, String> layoutIdByWidget = existingLayouts.stream()
                .filter(l -> l.getWidgetId() != null && l.getLayoutId() != null)
                .collect(Collectors.toMap(DataDashboardVO::getWidgetId, DataDashboardVO::getLayoutId, (a, b) -> a));

        int nextNum = -1;
        for (DataDashboardVO.LayoutBatchItemVO item : searchVO.getLayoutBatchList()) {
            String existingId = layoutIdByWidget.get(item.getWidgetId());
            if (existingId != null) {
                item.setLayoutId(existingId);
            } else {
                if (nextNum < 0) {
                    String firstKey = keyGenerate.generateTableKey("LI", "TB_USER_DASHBOARD_LAYOUT", "LAYOUT_ID");
                    nextNum = Integer.parseInt(firstKey.substring(2));
                }
                item.setLayoutId("LI" + String.format("%06d", nextNum++));
            }
        }
    }

    /**
     * 신규 위젯 생성 시 레이아웃 초기 레코드 생성.
     * GridStack 기본 레이아웃(w=3, h=4)으로 초기화.
     */
    private void initDashboardLayout(DataDashboardVO widgetVO) throws Exception {
        DataDashboardVO layoutVO = new DataDashboardVO();
        layoutVO.setUserId(widgetVO.getUserId());
        layoutVO.setWidgetId(widgetVO.getWidgetId());
        layoutVO.setLayoutId(keyGenerate.generateTableKey("LI", "TB_USER_DASHBOARD_LAYOUT", "LAYOUT_ID"));
        layoutVO.setSortOrd(widgetVO.getSortOrd());
        layoutVO.setX(0);
        layoutVO.setY(0);
        layoutVO.setW(3);
        layoutVO.setH(4);
        layoutVO.setMinW(2);
        layoutVO.setMaxW(6);
        layoutVO.setMinH(2);
        layoutVO.setMaxH(12);
        layoutVO.setIsVisible(true);
        dataDashboardDAO.saveDashboardLayout(layoutVO);
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
     * JSON 문자열 → Map<String,String> 파싱 (flat 객체, 문자열·배열 값 지원)
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
     * WHERE 조건에서 paramMap 키(컬럼명)에 해당하는 조건을 직접 치환.
     * 주의: PreparedStatement를 거치지 않으므로 내부 관리 도구 전용으로만 사용할 것.
     */
    private String replaceWhereConditions(String sql, Map<String, String> paramMap) {
        for (Map.Entry<String, String> entry : paramMap.entrySet()) {
            String colName = entry.getKey().toUpperCase();
            String value   = entry.getValue();

            if (value == null || value.isEmpty()) {
                Pattern p = Pattern.compile(
                    "\\s+AND\\s+(?:\\w+\\.)?"+colName+"\\s+(?:IN\\s*\\([^)]*\\)|=\\s*'[^']*'|=\\s*[0-9]+)",
                    Pattern.CASE_INSENSITIVE
                );
                sql = p.matcher(sql).replaceAll("");
            } else {
                Pattern inP = Pattern.compile(
                    "((?:\\w+\\.)?"+colName+"\\s+IN\\s*\\()([^)]*)(\\))",
                    Pattern.CASE_INSENSITIVE
                );
                Matcher inM = inP.matcher(sql);
                if (inM.find()) {
                    String inLiteral = buildInLiteral(value);
                    sql = inM.replaceAll("$1" + Matcher.quoteReplacement(inLiteral) + "$3");
                } else {
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

    private String buildInLiteral(String value) {
        String[] parts = value.split(",");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(",");
            sb.append("'").append(part.trim().replace("'", "''")).append("'");
        }
        return sb.toString();
    }

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
