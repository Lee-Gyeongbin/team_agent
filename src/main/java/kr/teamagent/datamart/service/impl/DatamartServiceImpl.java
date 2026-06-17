package kr.teamagent.datamart.service.impl;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.ExcelUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.datamart.service.DatamartVO;

@Service
public class DatamartServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(DatamartServiceImpl.class);

    private static final int DEFAULT_MYSQL_PORT = 3306;

    private static final String META_HDR_TBL_ID = "테이블ID *";
    private static final String META_HDR_COL_ID = "컬럼ID";
    private static final String META_HDR_COL_PHY_NM = "물리컬럼명 *";
    private static final String META_HDR_COL_KOR_NM = "한글컬럼명";
    private static final String META_HDR_COL_DESC = "컬럼설명";
    private static final String META_HDR_DATA_TYPE = "데이터타입 *";
    private static final String META_HDR_DATA_LEN = "데이터길이";
    private static final String META_HDR_PK_YN = "PK여부";
    private static final String META_HDR_FK_YN = "FK여부";
    private static final String META_HDR_NULLABLE_YN = "NULL허용";
    private static final String META_HDR_HAS_CODE_YN = "코드값여부";
    private static final String META_HDR_AI_HINT = "AI힌트";
    private static final String META_HDR_SORT_ORD = "정렬순서";
    private static final String META_HDR_USE_YN = "사용여부";
    private static final String[] META_COLUMN_EXCEL_HEADERS = {
            META_HDR_TBL_ID, META_HDR_COL_ID, META_HDR_COL_PHY_NM, META_HDR_COL_KOR_NM, META_HDR_COL_DESC,
            META_HDR_DATA_TYPE, META_HDR_DATA_LEN, META_HDR_PK_YN, META_HDR_FK_YN, META_HDR_NULLABLE_YN,
            META_HDR_HAS_CODE_YN, META_HDR_AI_HINT, META_HDR_SORT_ORD, META_HDR_USE_YN
    };
    private static final int[] META_COLUMN_AUTO_GEN_HEADER_COLS = { 12 };
    private static final int META_COLUMN_COL_KOR_NM_COL_IDX = 3;
    private static final int META_COLUMN_COL_DESC_COL_IDX = 4;
    private static final int META_COLUMN_AI_HINT_COL_IDX = 11;
    private static final int META_COLUMN_PK_YN_COL_IDX = 7;
    private static final int META_COLUMN_FK_YN_COL_IDX = 8;
    private static final int META_COLUMN_NULLABLE_YN_COL_IDX = 9;
    private static final int META_COLUMN_HAS_CODE_YN_COL_IDX = 10;
    private static final int META_COLUMN_USE_YN_COL_IDX = 13;
    private static final String META_COLUMN_EXCEL_GUIDE_TEXT =
            "※ * 표시된 항목은 필수 입력값입니다.\n"
                    + "※ 업로드는 미리보기용이며, 저장 시 해당 데이터마트의 컬럼 메타가 전체 교체됩니다.\n"
                    + "  테이블ID·물리컬럼명·데이터타입(필수), 컬럼ID, PK/FK/NULL허용/코드값여부/사용여부(Y/N)를 입력하세요. 컬럼ID 미입력 시 물리컬럼명이 사용됩니다.";

    @Autowired
    DatamartDAO datamartDAO;

    @Autowired
    KeyGenerate keyGenerate;

    /**
     * 데이터마트 목록 조회
     * @return
     * @throws Exception
     */
    public List<DatamartVO> selectDatamartList() throws Exception {
        return datamartDAO.selectDatamartList();
    }

    /**
     * 데이터마트 요약 정보 조회
     * @return
     * @throws Exception
     */
    public DatamartVO.SummaryVO selectDatamartSummary() throws Exception {
        return datamartDAO.selectDatamartSummary();
    }

    /**
     * 데이터마트 단건 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public DatamartVO selectDatamart(DatamartVO searchVO) throws Exception {
        return datamartDAO.selectDatamart(searchVO);
    }

    /**
     * 데이터마트 등록/수정
     * @param datamartVO
     * @return 저장된 DatamartVO
     * @throws Exception
     */
    public DatamartVO saveDatamart(DatamartVO datamartVO) throws Exception {
        if (datamartVO.getDatamartId() == null || datamartVO.getDatamartId().trim().isEmpty()) {
            datamartVO.setDatamartId(keyGenerate.generateTableKey("DM", "TB_DM", "DATAMART_ID"));
        }
        if (CommonUtil.isEmpty(datamartVO.getConnOpt())) {
            datamartVO.setConnOpt(null);
        }
        datamartDAO.saveDatamart(datamartVO);
        return datamartDAO.selectDatamart(datamartVO);
    }

    /**
     * 데이터마트 삭제
     * @param datamartVO datamartId 필수
     * @throws Exception
     */
    public void deleteDatamart(DatamartVO datamartVO) throws Exception {
        datamartDAO.deleteDatamart(datamartVO);
    }

    /**
     * JDBC Connection 생성 (MySQL 전용)
     * @param dm 접속 정보가 담긴 DatamartVO
     * @param extraParams JDBC URL에 추가할 파라미터 (예: "useInformationSchema=true")
     * @return Connection
     */
    /** 외부에서 직접 JDBC Connection이 필요한 경우 사용 (예: DataDashboard SQL 실행) */
    public Connection openJdbcConnection(DatamartVO dm) throws ClassNotFoundException, SQLException {
        return buildJdbcConnection(dm);
    }

    private Connection buildJdbcConnection(DatamartVO dm, String... extraParams) throws ClassNotFoundException, SQLException {
        int port = dm.getPort() != null ? dm.getPort() : DEFAULT_MYSQL_PORT;
        String jdbcUrl = "jdbc:mysql://" + dm.getHost() + ":" + port + "/" + dm.getSchNm();

        StringBuilder opts = new StringBuilder();
        if (!CommonUtil.isEmpty(dm.getConnOpt())) {
            opts.append(dm.getConnOpt());
        }
        for (String ep : extraParams) {
            if (opts.length() > 0) opts.append("&");
            opts.append(ep);
        }
        if (opts.length() > 0) {
            jdbcUrl += "?" + opts;
        }

        Class.forName("com.mysql.cj.jdbc.Driver");
        DriverManager.setLoginTimeout(5);
        logger.info("####### JDBC 연결 - host: {}, port: {}, schNm: {}, username: {}", dm.getHost(), port, dm.getSchNm(), dm.getUsername());
        return DriverManager.getConnection(jdbcUrl, dm.getUsername(), dm.getPwdEnc());
    }

    /**
     * 데이터마트 DB 연결 테스트
     * @param searchVO (datamartId 필수)
     * @return result(SUCCESS/FAIL), msg
     * @throws Exception
     */
    public HashMap<String, Object> testConnection(DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        DatamartVO dm = new DatamartVO();
        if(!CommonUtil.isEmpty(searchVO.getTestType()) && searchVO.getTestType().equals("saved")) {
            dm = datamartDAO.selectDatamart(searchVO);
        }else{
            dm = searchVO;
        }
        
        if (dm == null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "데이터마트 정보를 찾을 수 없습니다.");
            return resultMap;
        }

        if (!"mysql".equalsIgnoreCase(dm.getDbType())) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "현재 MySQL만 연결 테스트를 지원합니다. (dbType: " + dm.getDbType() + ")");
            return resultMap;
        }

        Connection conn = null;
        try {
            conn = buildJdbcConnection(dm);

            int tblCnt = 0;
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet rs = metaData.getTables(dm.getSchNm(), null, "%", new String[]{"TABLE"});
            while (rs.next()) {
                tblCnt++;
            }
            rs.close();

            resultMap.put("result", "SUCCESS");
            resultMap.put("msg", "연결 성공! 데이터베이스에 정상적으로 연결되었습니다.");
            resultMap.put("tblCnt", tblCnt);
        } catch (SQLTimeoutException e) {
            logger.error("DB 연결 타임아웃 - host: {}, error: {}", dm.getHost(), e.getMessage());
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "연결 실패: Timeout");
        } catch (SQLException e) {
            logger.error("DB 연결 실패 - host: {}, error: {}", dm.getHost(), e.getMessage());
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "연결 실패: " + e.getMessage());
        } catch (ClassNotFoundException e) {
            logger.error("JDBC 드라이버 로드 실패: {}", e.getMessage());
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "JDBC 드라이버를 찾을 수 없습니다.");
        } finally {
            datamartDAO.updateLastVerifyDt(dm);
            if (conn != null) {
                try { conn.close(); } catch (SQLException e) { logger.error(e.getMessage()); }
            }
        }

        return resultMap;
    }

    /**
     * 메타 테이블 목록 조회 (외부 DB 접속하여 스키마 정보 조회)
     * @param searchVO datamartId 필수
     * @return { result, msg, dataList: [ { id, physicalNm, logicalNm, colCnt, useYn, tableDescKo, columns: [...] } ] }
     *         컬럼: TB_DM_COL에 해당 DATAMART_ID·TBL_ID 행이 하나라도 있으면 저장 데이터만으로 구성, 없으면 JDBC 스키마로 구성
     * @throws Exception
     */
    public HashMap<String, Object> selectMetaTableList(DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();

        DatamartVO dm = datamartDAO.selectDatamart(searchVO);
        if (dm == null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "데이터마트 정보를 찾을 수 없습니다.");
            return resultMap;
        }

        if (!"mysql".equalsIgnoreCase(dm.getDbType())) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "현재 MySQL만 지원합니다. (dbType: " + dm.getDbType() + ")");
            return resultMap;
        }

        Connection conn = null;
        try {
            conn = buildJdbcConnection(dm, "useInformationSchema=true");
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = dm.getSchNm();

            ResultSet tableRs = metaData.getTables(catalog, null, "%", new String[]{"TABLE"});
            List<HashMap<String, Object>> dataList = new ArrayList<>();

            while (tableRs.next()) {
                String tableName = tableRs.getString("TABLE_NAME");
                String tableRemarks = tableRs.getString("REMARKS");
                String tableType = tableRs.getString("TABLE_TYPE").trim();

                Map<String, Object> colParamMap = new HashMap<>();
                colParamMap.put("datamartId", dm.getDatamartId());
                colParamMap.put("tblId", tableName);
                List<DatamartVO.MetaColumnRowVO> savedColList = datamartDAO.selectDmColListByDatamartAndTbl(colParamMap);

                List<DatamartVO.MetaColumnRowVO> columns = new ArrayList<>();
                if (savedColList != null && !savedColList.isEmpty()) {
                    for (DatamartVO.MetaColumnRowVO row : savedColList) {
                        if (row != null) {
                            columns.add(row);
                        }
                    }
                } else {
                    Set<String> pkColumns = new HashSet<>();
                    ResultSet pkRs = metaData.getPrimaryKeys(catalog, null, tableName);
                    while (pkRs.next()) {
                        pkColumns.add(pkRs.getString("COLUMN_NAME"));
                    }
                    pkRs.close();

                    Set<String> fkColumns = new HashSet<>();
                    ResultSet fkRs = metaData.getImportedKeys(catalog, null, tableName);
                    while (fkRs.next()) {
                        fkColumns.add(fkRs.getString("FKCOLUMN_NAME"));
                    }
                    fkRs.close();

                    ResultSet colRs = metaData.getColumns(catalog, null, tableName, "%");
                    while (colRs.next()) {
                        String colPhyNm = colRs.getString("COLUMN_NAME");
                        String remarks = colRs.getString("REMARKS");
                        if (remarks == null) {
                            remarks = "";
                        }
                        int columnSize = colRs.getInt("COLUMN_SIZE");
                        Integer dataLenInt = colRs.wasNull() ? null : Integer.valueOf(columnSize);

                        DatamartVO.MetaColumnRowVO col = new DatamartVO.MetaColumnRowVO();
                        col.setColId(colPhyNm);
                        col.setColPhyNm(colPhyNm);
                        col.setColKorNm(remarks);
                        col.setColDesc(remarks);
                        col.setDataType(colRs.getString("TYPE_NAME"));
                        col.setDataLen(dataLenInt != null ? String.valueOf(dataLenInt) : null);
                        col.setPkYn(pkColumns.contains(colPhyNm) ? "Y" : "N");
                        col.setFkYn(fkColumns.contains(colPhyNm) ? "Y" : "N");
                        col.setNullableYn("YES".equalsIgnoreCase(colRs.getString("IS_NULLABLE")) ? "Y" : "N");
                        col.setHasCodeYn("N");
                        col.setAiHint("");
                        col.setSortOrd(Integer.valueOf(colRs.getInt("ORDINAL_POSITION")));
                        col.setUseYn("Y");
                        columns.add(col);
                    }
                    colRs.close();
                }

                HashMap<String, Object> table = new HashMap<>();
                table.put("id", tableName);
                table.put("physicalNm", tableName);
                table.put("logicalNm", tableRemarks != null ? tableRemarks : "");
                table.put("colCnt", columns.size());
                Map<String, Object> paramMap = new HashMap<>();
                paramMap.put("datamartId", dm.getDatamartId());
                paramMap.put("tblId", tableName);
                String useYn = datamartDAO.selectDmTblUseYn(paramMap);
                table.put("useYn", useYn);
                table.put("tableDescKo", tableRemarks != null ? tableRemarks : "");
                table.put("columns", columns);
                dataList.add(table);
            }
            tableRs.close();

            resultMap.put("result", "SUCCESS");
            resultMap.put("msg", "메타 테이블 목록 조회 성공");
            resultMap.put("dataList", dataList);
        } catch (SQLTimeoutException e) {
            logger.error("메타 조회 타임아웃 - host: {}, error: {}", dm.getHost(), e.getMessage());
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "연결 실패: Timeout");
        } catch (SQLException e) {
            logger.error("메타 조회 실패 - host: {}, error: {}", dm.getHost(), e.getMessage());
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "조회 실패: " + e.getMessage());
        } catch (ClassNotFoundException e) {
            logger.error("JDBC 드라이버 로드 실패: {}", e.getMessage());
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "JDBC 드라이버를 찾을 수 없습니다.");
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException e) { logger.error(e.getMessage()); }
            }
        }

        return resultMap;
    }

    /**
     * 메타 관리 > 테이블 저장
     * @param payload datamartId, tableList
     * @return result, msg
     * @throws Exception
     */
    public HashMap<String, Object> saveMetaTableList(DatamartVO.MetaTableSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();

        if (payload == null || CommonUtil.isEmpty(payload.getDatamartId())) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "datamartId is required");
            return resultMap;
        }

        DatamartVO dm = new DatamartVO();
        dm.setDatamartId(payload.getDatamartId());
        if (datamartDAO.selectDatamart(dm) == null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "데이터마트 정보를 찾을 수 없습니다.");
            return resultMap;
        }

        List<DatamartVO.MetaTableItemVO> tableList = payload.getTableList();
        if (tableList == null || tableList.isEmpty()) {
            resultMap.put("result", "SUCCESS");
            resultMap.put("msg", "저장할 테이블이 없습니다.");
            return resultMap;
        }

        datamartDAO.saveMetaTable(payload);

        resultMap.put("result", "SUCCESS");
        resultMap.put("msg", "메타 테이블 저장 성공");
        return resultMap;
    }

    /**
     * 메타 관리 > 컬럼 저장 (해당 DATAMART_ID의 TB_DM_COL 전부 삭제 후, 페이로드의 columns만큼 INSERT)
     * @param payload datamartId, tableList(각 테이블의 columns)
     * @return result, msg
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public HashMap<String, Object> saveMetaColumnList(DatamartVO.MetaColumnSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();

        if (payload == null || CommonUtil.isEmpty(payload.getDatamartId())) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "datamartId is required");
            return resultMap;
        }

        DatamartVO dm = new DatamartVO();
        dm.setDatamartId(payload.getDatamartId());
        if (datamartDAO.selectDatamart(dm) == null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "데이터마트 정보를 찾을 수 없습니다.");
            return resultMap;
        }

        datamartDAO.deleteDmColByDatamartId(dm);

        List<DatamartVO.MetaColumnSaveTableItemVO> tableList = payload.getTableList();
        List<DatamartVO.MetaColumnSaveTableItemVO> tablesWithCols = new ArrayList<>();
        if (tableList != null) {
            for (DatamartVO.MetaColumnSaveTableItemVO table : tableList) {
                if (table != null && table.getColumns() != null && !table.getColumns().isEmpty()) {
                    tablesWithCols.add(table);
                }
            }
        }

        if (!tablesWithCols.isEmpty()) {
            DatamartVO.MetaColumnSavePayloadVO insertPayload = new DatamartVO.MetaColumnSavePayloadVO();
            insertPayload.setDatamartId(payload.getDatamartId());
            insertPayload.setTableList(tablesWithCols);
            datamartDAO.insertDmColBatch(insertPayload);
        }

        resultMap.put("result", "SUCCESS");
        resultMap.put("msg", "메타 컬럼 저장 성공");
        return resultMap;
    }

    /**
     * 메타 관리 > 컬럼 메타 엑셀 다운로드 (datamartId 단위)
     * @param response
     * @param datamartId 데이터마트 ID
     * @throws Exception
     */
    public void downloadMetaColumnExcel(HttpServletResponse response, String datamartId) throws Exception {
        if (CommonUtil.isEmpty(datamartId)) {
            throw new IllegalArgumentException("datamartId is required");
        }
        DatamartVO searchVO = new DatamartVO();
        searchVO.setDatamartId(datamartId.trim());
        if (datamartDAO.selectDatamart(searchVO) == null) {
            throw new IllegalArgumentException("데이터마트 정보를 찾을 수 없습니다.");
        }
        List<DatamartVO.MetaColumnExcelRowVO> list = datamartDAO.selectDmColListForExcel(searchVO);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = ExcelUtil.createSheetWithHeader(workbook, "컬럼메타", META_COLUMN_EXCEL_HEADERS,
                    META_COLUMN_AUTO_GEN_HEADER_COLS, META_COLUMN_EXCEL_GUIDE_TEXT);
            writeMetaColumnExcelRows(workbook, sheet, list);
            applyMetaColumnExcelSheetOptions(sheet);
            ExcelUtil.writeXlsxResponse(response, workbook);
        }
    }

    private void writeMetaColumnExcelRows(XSSFWorkbook workbook, XSSFSheet sheet, List<DatamartVO.MetaColumnExcelRowVO> list) {
        XSSFCellStyle rowStyle = ExcelUtil.createDataStyle(workbook);
        int rowNum = ExcelUtil.DATA_START_ROW;
        if (list == null) {
            return;
        }
        String prevTblId = null;
        for (DatamartVO.MetaColumnExcelRowVO vo : list) {
            if (vo == null) {
                continue;
            }
            String tblId = CommonUtil.nullToBlank(vo.getTblId());
            if (prevTblId != null && !prevTblId.equals(tblId)) {
                rowNum++;
            }
            prevTblId = tblId;

            Row row = sheet.createRow(rowNum++);
            ExcelUtil.applyDataCell(row, 0, tblId, rowStyle);
            ExcelUtil.applyDataCell(row, 1, CommonUtil.nullToBlank(vo.getColId()), rowStyle);
            ExcelUtil.applyDataCell(row, 2, CommonUtil.nullToBlank(vo.getColPhyNm()), rowStyle);
            ExcelUtil.applyDataCell(row, 3, CommonUtil.nullToBlank(vo.getColKorNm()), rowStyle);
            ExcelUtil.applyDataCell(row, 4, CommonUtil.nullToBlank(vo.getColDesc()), rowStyle);
            ExcelUtil.applyDataCell(row, 5, CommonUtil.nullToBlank(vo.getDataType()), rowStyle);
            ExcelUtil.applyDataCell(row, 6, CommonUtil.nullToBlank(vo.getDataLen()), rowStyle);
            ExcelUtil.applyDataCell(row, 7, CommonUtil.nullToBlank(vo.getPkYn()), rowStyle);
            ExcelUtil.applyDataCell(row, 8, CommonUtil.nullToBlank(vo.getFkYn()), rowStyle);
            ExcelUtil.applyDataCell(row, 9, CommonUtil.nullToBlank(vo.getNullableYn()), rowStyle);
            ExcelUtil.applyDataCell(row, 10, CommonUtil.nullToBlank(vo.getHasCodeYn()), rowStyle);
            ExcelUtil.applyDataCell(row, 11, CommonUtil.nullToBlank(vo.getAiHint()), rowStyle);
            ExcelUtil.applyDataCell(row, 12, vo.getSortOrd() != null ? String.valueOf(vo.getSortOrd()) : "", rowStyle);
            ExcelUtil.applyDataCell(row, 13, CommonUtil.nullToBlank(vo.getUseYn()), rowStyle);
        }
    }

    private void applyMetaColumnExcelSheetOptions(XSSFSheet sheet) {
        ExcelUtil.adjustColumnWidths(sheet, META_COLUMN_EXCEL_HEADERS.length);
        int korNmWidth = sheet.getColumnWidth(META_COLUMN_COL_KOR_NM_COL_IDX);
        sheet.setColumnWidth(META_COLUMN_COL_DESC_COL_IDX, korNmWidth);
        sheet.setColumnWidth(META_COLUMN_AI_HINT_COL_IDX, korNmWidth);
        sheet.createFreezePane(0, ExcelUtil.DATA_START_ROW);
        ExcelUtil.addUseYnListValidations(sheet, META_COLUMN_PK_YN_COL_IDX, META_COLUMN_FK_YN_COL_IDX,
                META_COLUMN_NULLABLE_YN_COL_IDX, META_COLUMN_HAS_CODE_YN_COL_IDX, META_COLUMN_USE_YN_COL_IDX);
    }

    /**
     * 메타 관리 > 컬럼 메타 엑셀 업로드 (파싱·검증 후 JSON 미리보기 반환, DB 저장 없음)
     * @param datamartId 데이터마트 ID
     * @param file 업로드 파일
     * @return datamartId, tableList(성공 시), returnMsg(실패 시)
     * @throws Exception
     */
    public Map<String, Object> uploadMetaColumnExcel(String datamartId, MultipartFile file) throws Exception {
        if (CommonUtil.isEmpty(datamartId)) {
            throw new IllegalArgumentException("datamartId is required");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("uploadFile is required");
        }

        DatamartVO dm = new DatamartVO();
        dm.setDatamartId(datamartId.trim());
        if (datamartDAO.selectDatamart(dm) == null) {
            throw new IllegalArgumentException("데이터마트 정보를 찾을 수 없습니다.");
        }

        int requiredFailCount = 0;
        Map<String, DatamartVO.MetaColumnSaveTableItemVO> tableMap = new LinkedHashMap<>();
        Map<String, Integer> tblColSeqMap = new HashMap<>();
        DataFormatter formatter = new DataFormatter();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = ExcelUtil.findHeaderRow(sheet, formatter, META_HDR_TBL_ID);
            if (headerRow == null) {
                throw new IllegalArgumentException("올바른 컬럼 메타 엑셀 파일이 아닙니다. (헤더 행 없음)");
            }

            Map<String, Integer> colIdx = ExcelUtil.parseHeaderColumns(headerRow, formatter);
            ExcelUtil.validateRequiredHeaderColumns(colIdx, "컬럼 메타", META_HDR_TBL_ID, META_HDR_COL_PHY_NM,
                    META_HDR_DATA_TYPE);

            Integer tblIdCol = colIdx.get(META_HDR_TBL_ID);
            Integer colIdCol = colIdx.get(META_HDR_COL_ID);
            Integer colPhyNmCol = colIdx.get(META_HDR_COL_PHY_NM);
            Integer colKorNmCol = colIdx.get(META_HDR_COL_KOR_NM);
            Integer colDescCol = colIdx.get(META_HDR_COL_DESC);
            Integer dataTypeCol = colIdx.get(META_HDR_DATA_TYPE);
            Integer dataLenCol = colIdx.get(META_HDR_DATA_LEN);
            Integer pkYnCol = colIdx.get(META_HDR_PK_YN);
            Integer fkYnCol = colIdx.get(META_HDR_FK_YN);
            Integer nullableYnCol = colIdx.get(META_HDR_NULLABLE_YN);
            Integer hasCodeYnCol = colIdx.get(META_HDR_HAS_CODE_YN);
            Integer aiHintCol = colIdx.get(META_HDR_AI_HINT);
            Integer sortOrdCol = colIdx.get(META_HDR_SORT_ORD);
            Integer useYnCol = colIdx.get(META_HDR_USE_YN);

            for (int i = headerRow.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                String tblId = ExcelUtil.getCellString(row, tblIdCol, formatter);
                String colPhyNm = ExcelUtil.getCellString(row, colPhyNmCol, formatter);
                String colId = ExcelUtil.getCellString(row, colIdCol, formatter);
                String colKorNm = ExcelUtil.getCellString(row, colKorNmCol, formatter);
                String colDesc = ExcelUtil.getCellString(row, colDescCol, formatter);
                String dataType = ExcelUtil.getCellString(row, dataTypeCol, formatter);
                String dataLen = ExcelUtil.getCellString(row, dataLenCol, formatter);
                String pkYn = ExcelUtil.getCellString(row, pkYnCol, formatter);
                String fkYn = ExcelUtil.getCellString(row, fkYnCol, formatter);
                String nullableYn = ExcelUtil.getCellString(row, nullableYnCol, formatter);
                String hasCodeYn = ExcelUtil.getCellString(row, hasCodeYnCol, formatter);
                String aiHint = ExcelUtil.getCellString(row, aiHintCol, formatter);
                String sortOrdStr = ExcelUtil.getCellString(row, sortOrdCol, formatter);
                String useYn = ExcelUtil.getCellString(row, useYnCol, formatter);

                if (isSkippableMetaColumnExcelRow(tblId, colPhyNm, colId, colKorNm, colDesc, dataType, dataLen,
                        pkYn, fkYn, nullableYn, hasCodeYn, aiHint, sortOrdStr, useYn)) {
                    continue;
                }

                if (hasMetaColumnRequiredError(tblId, colPhyNm, dataType)) {
                    requiredFailCount++;
                    continue;
                }

                if (colId.isEmpty()) {
                    colId = colPhyNm;
                }

                pkYn = defaultYn(pkYn, "N");
                fkYn = defaultYn(fkYn, "N");
                nullableYn = defaultYn(nullableYn, "Y");
                hasCodeYn = defaultYn(hasCodeYn, "N");
                useYn = defaultYn(useYn, "Y");

                int tblColSeq = tblColSeqMap.getOrDefault(tblId, 0) + 1;
                tblColSeqMap.put(tblId, tblColSeq);
                Integer sortOrd = parseSortOrd(sortOrdStr, tblColSeq);

                DatamartVO.MetaColumnRowVO col = new DatamartVO.MetaColumnRowVO();
                col.setColId(colId);
                col.setColPhyNm(colPhyNm);
                col.setColKorNm(colKorNm);
                col.setColDesc(colDesc);
                col.setDataType(dataType);
                col.setDataLen(dataLen);
                col.setPkYn(pkYn);
                col.setFkYn(fkYn);
                col.setNullableYn(nullableYn);
                col.setHasCodeYn(hasCodeYn);
                col.setAiHint(aiHint);
                col.setSortOrd(sortOrd);
                col.setUseYn(useYn);

                DatamartVO.MetaColumnSaveTableItemVO tableItem = tableMap.get(tblId);
                if (tableItem == null) {
                    tableItem = new DatamartVO.MetaColumnSaveTableItemVO();
                    tableItem.setId(tblId);
                    tableItem.setColumns(new ArrayList<>());
                    tableMap.put(tblId, tableItem);
                }
                tableItem.getColumns().add(col);
            }
        }

        if (requiredFailCount > 0) {
            String returnMsg = buildMetaColumnUploadFailReturnMsg(requiredFailCount);
            return buildMetaColumnUploadPreviewResult(datamartId.trim(), null, returnMsg);
        }

        return buildMetaColumnUploadPreviewResult(datamartId.trim(), new ArrayList<>(tableMap.values()), null);
    }

    private static Map<String, Object> buildMetaColumnUploadPreviewResult(String datamartId,
            List<DatamartVO.MetaColumnSaveTableItemVO> tableList, String returnMsg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datamartId", datamartId);
        if (returnMsg != null) {
            result.put("returnMsg", returnMsg);
        }
        if (tableList != null) {
            result.put("tableList", tableList);
        }
        return result;
    }

    private static boolean isSkippableMetaColumnExcelRow(String tblId, String colPhyNm, String colId, String colKorNm,
            String colDesc, String dataType, String dataLen, String pkYn, String fkYn, String nullableYn,
            String hasCodeYn, String aiHint, String sortOrdStr, String useYn) {
        if (ExcelUtil.isGuideMarkerRow(tblId)) {
            return true;
        }
        return tblId.isEmpty() && colPhyNm.isEmpty() && colId.isEmpty() && colKorNm.isEmpty() && colDesc.isEmpty()
                && dataType.isEmpty() && dataLen.isEmpty() && pkYn.isEmpty() && fkYn.isEmpty() && nullableYn.isEmpty()
                && hasCodeYn.isEmpty() && aiHint.isEmpty() && sortOrdStr.isEmpty() && useYn.isEmpty();
    }

    private static boolean hasMetaColumnRequiredError(String tblId, String colPhyNm, String dataType) {
        return tblId.isEmpty() || colPhyNm.isEmpty() || dataType.isEmpty();
    }

    private static String buildMetaColumnUploadFailReturnMsg(int requiredFailCount) {
        return "업로드 실패 : 필수값 누락 " + requiredFailCount + "건이 있습니다.";
    }

    private static String defaultYn(String value, String defaultValue) {
        return value.isEmpty() ? defaultValue : value;
    }

    private static Integer parseSortOrd(String sortOrdStr, int fallback) {
        if (sortOrdStr.isEmpty()) {
            return Integer.valueOf(fallback);
        }
        try {
            return Integer.valueOf(sortOrdStr);
        } catch (NumberFormatException e) {
            return Integer.valueOf(fallback);
        }
    }

    /**
     * 메타 관리 > 테이블 관계 저장 (TB_DM_REL 해당 DATAMART_ID 전체 삭제 후 INSERT)
     * @param payload datamartId, relationshipList
     * @return result, msg
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public HashMap<String, Object> saveMetaRelationshipList(DatamartVO.MetaRelationshipSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();

        if (payload == null || CommonUtil.isEmpty(payload.getDatamartId())) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "datamartId is required");
            return resultMap;
        }

        DatamartVO dm = new DatamartVO();
        dm.setDatamartId(payload.getDatamartId());
        if (datamartDAO.selectDatamart(dm) == null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "데이터마트 정보를 찾을 수 없습니다.");
            return resultMap;
        }

        datamartDAO.deleteDmRelByDatamartId(dm);

        List<DatamartVO.MetaRelationshipRowVO> rows = payload.getRelationshipList();
        List<DatamartVO.MetaRelationshipRowVO> toSave = new ArrayList<>();
        if (rows != null) {
            for (DatamartVO.MetaRelationshipRowVO row : rows) {
                if (row != null) {
                    toSave.add(row);
                }
            }
        }

        if (!toSave.isEmpty()) {
            assignRelIdsForInsertBatch(toSave);
            DatamartVO.MetaRelationshipSavePayloadVO daoPayload = new DatamartVO.MetaRelationshipSavePayloadVO();
            daoPayload.setDatamartId(payload.getDatamartId());
            daoPayload.setRelationshipList(toSave);
            datamartDAO.saveMetaRelationship(daoPayload);
        }

        resultMap.put("result", "SUCCESS");
        resultMap.put("msg", "메타 관계 저장 성공");
        return resultMap;
    }

    /**
     * 메타 관리 > 코드그룹 매핑 저장 (TB_DM_COL_CODE UK merge upsert, useYn N = soft delete)
     */
    @Transactional(rollbackFor = Exception.class)
    public HashMap<String, Object> saveMetaCodeMappingList(DatamartVO.MetaCodeMappingSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();

        if (payload == null || CommonUtil.isEmpty(payload.getDatamartId())) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "datamartId is required");
            return resultMap;
        }

        DatamartVO dm = new DatamartVO();
        dm.setDatamartId(payload.getDatamartId());
        if (datamartDAO.selectDatamart(dm) == null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "데이터마트 정보를 찾을 수 없습니다.");
            return resultMap;
        }

        List<DatamartVO.MetaCodeColumnMappingVO> toSave = filterCodeMappingSaveList(payload.getCodeColumnMappingList());
        if (!toSave.isEmpty()) {
            payload.setCodeColumnMappingList(toSave);
            datamartDAO.upsertDmColCodeBatch(payload);
        }

        List<DatamartVO.MetaCodeColumnMappingVO> dataList = datamartDAO.selectMetaCodeMappingRows(dm);
        if (dataList == null) {
            dataList = new ArrayList<>();
        }

        resultMap.put("result", "SUCCESS");
        resultMap.put("msg", "메타 코드그룹 매핑 저장 성공");
        resultMap.put("dataList", dataList);
        return resultMap;
    }

    private List<DatamartVO.MetaCodeColumnMappingVO> filterCodeMappingSaveList(
            List<DatamartVO.MetaCodeColumnMappingVO> mappings) {
        List<DatamartVO.MetaCodeColumnMappingVO> toSave = new ArrayList<>();
        if (mappings == null) {
            return toSave;
        }
        int sortOrd = 1;
        for (DatamartVO.MetaCodeColumnMappingVO mapping : mappings) {
            if (mapping == null || CommonUtil.isEmpty(mapping.getTblId()) || CommonUtil.isEmpty(mapping.getColId())
                    || CommonUtil.isEmpty(mapping.getCodeGrpId())) {
                continue;
            }
            String useYn = CommonUtil.nullToBlank(mapping.getUseYn()).trim();
            mapping.setUseYn(CommonUtil.isEmpty(useYn) ? "Y" : useYn.toUpperCase());
            if (mapping.getSortOrd() == null) {
                mapping.setSortOrd(sortOrd++);
            }
            toSave.add(mapping);
        }
        return toSave;
    }

    /**
     * 메타 관리 > 코드 매핑 메타데이터 목록 조회 (TB_DM_COL_CODE + TB_CODE_GRP)
     */
    public HashMap<String, Object> selectMetaCodeMappingList(DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();

        if (searchVO == null || CommonUtil.isEmpty(searchVO.getDatamartId())) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "datamartId is required");
            return resultMap;
        }

        DatamartVO dm = new DatamartVO();
        dm.setDatamartId(searchVO.getDatamartId());
        if (datamartDAO.selectDatamart(dm) == null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "데이터마트 정보를 찾을 수 없습니다.");
            return resultMap;
        }

        List<DatamartVO.MetaCodeColumnMappingVO> dataList = datamartDAO.selectMetaCodeMappingRows(searchVO);
        if (dataList == null) {
            dataList = new ArrayList<>();
        }

        resultMap.put("result", "SUCCESS");
        resultMap.put("msg", "메타 코드 매핑 목록 조회 성공");
        resultMap.put("dataList", dataList);
        return resultMap;
    }

    /**
     * 메타 관리 > 관계 메타데이터 목록 조회 (TB_DM_REL, DATAMART_ID 기준)
     * @param searchVO datamartId 필수
     * @return { result, msg, dataList: MetaRelationshipRowVO[] }
     * @throws Exception
     */
    public HashMap<String, Object> selectMetaRelationshipList(DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();

        if (searchVO == null || CommonUtil.isEmpty(searchVO.getDatamartId())) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "datamartId is required");
            return resultMap;
        }

        DatamartVO dm = new DatamartVO();
        dm.setDatamartId(searchVO.getDatamartId());
        if (datamartDAO.selectDatamart(dm) == null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "데이터마트 정보를 찾을 수 없습니다.");
            return resultMap;
        }

        List<DatamartVO.MetaRelationshipRowVO> dataList = datamartDAO.selectMetaRelationshipList(searchVO);
        if (dataList == null) {
            dataList = new ArrayList<>();
        }

        resultMap.put("result", "SUCCESS");
        resultMap.put("msg", "메타 관계 목록 조회 성공");
        resultMap.put("dataList", dataList);
        return resultMap;
    }

    /**
     * 동일 트랜잭션에서 {@link KeyGenerate#generateTableKey}를 행마다 호출하면 MAX 조회가 INSERT 반영 전에
     * 반복되어 동일 REL_ID가 나올 수 있음. 첫 행만 DB 기준 시드를 받고 이후는 로컬 시퀀스로 채움.
     */
    private void assignRelIdsForInsertBatch(List<DatamartVO.MetaRelationshipRowVO> rows) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String seedKey = keyGenerate.generateTableKey("DR", "tb_dm_rel", "REL_ID");
        rows.get(0).setRelId(seedKey);
        int nextSeq = Integer.parseInt(seedKey.substring(2));
        for (int i = 1; i < rows.size(); i++) {
            nextSeq++;
            rows.get(i).setRelId("DR" + String.format("%06d", nextSeq));
        }
    }

    /**
     * 데이터마트 동의어 목록 조회
     * @param searchVO datamartId 필수
     * @return { datamartId, synonymList: MetaSynonymRowVO[] }
     * @throws Exception
     */
    public HashMap<String, Object> selectMetaSynonymList(DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        List<DatamartVO.MetaSynonymRowVO> synonymList = datamartDAO.selectMetaSynonymList(searchVO);
        resultMap.put("datamartId", searchVO != null ? searchVO.getDatamartId() : "");
        resultMap.put("synonymList", synonymList != null ? synonymList : new ArrayList<DatamartVO.MetaSynonymRowVO>());
        return resultMap;
    }

    /**
     * 데이터마트 동의어 저장(목록)
     * @param payload datamartId, synonymList
     * @return { result, msg }
     * @throws Exception
     */
    @Transactional
    public HashMap<String, Object> saveMetaSynonymList(DatamartVO.MetaSynonymSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        List<DatamartVO.MetaSynonymRowVO> synonymList = payload != null ? payload.getSynonymList() : null;
        if (synonymList == null || synonymList.isEmpty()) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "동의어 목록이 비어 있습니다.");
            return resultMap;
        }

        String payloadDatamartId = payload.getDatamartId();
        List<DatamartVO.MetaSynonymRowVO> validRows = new ArrayList<>();
        for (DatamartVO.MetaSynonymRowVO row : synonymList) {
            if (row == null || CommonUtil.isEmpty(row.getSynonymWord())) {
                continue;
            }
            if (CommonUtil.isEmpty(row.getDatamartId())) {
                row.setDatamartId(payloadDatamartId);
            }
            validRows.add(row);
        }
        if (validRows.isEmpty()) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "동의어 목록이 비어 있습니다.");
            return resultMap;
        }

        String synonymDuplicateMsg = validateMetaSynonymUniqueness(payloadDatamartId, validRows);
        if (synonymDuplicateMsg != null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", synonymDuplicateMsg);
            return resultMap;
        }

        assignSynonymIdsForSave(validRows);
        applyMetaSynonymSortOrd(validRows);
        for (DatamartVO.MetaSynonymRowVO row : validRows) {
            applyMetaSynonymRowDefaults(row);
            datamartDAO.updateMetaSynonym(row);
        }

        resultMap.put("result", "SUCCESS");
        resultMap.put("msg", "데이터마트 동의어 저장 성공");
        return resultMap;
    }

    /**
     * 저장 payload 기준으로 SYNONYM_ID를 채운다.
     * - synonymId가 있으면 유지
     * - ID가 없는 일반 동의어(REPRESENT_YN!=Y)는 직전 그룹 ID 상속
     * - ID가 없는 대표(REPRESENT_YN=Y)는 동일 단어의 기존 대표 행 ID 재사용, 없으면 신규 그룹 ID
     * - 신규 ID는 한 요청 내에서 로컬 시퀀스로 발급 (generateTableKey 반복 호출 시 동일 ID 중복 방지)
     */
    private void assignSynonymIdsForSave(List<DatamartVO.MetaSynonymRowVO> rows) throws Exception {
        String currentSynonymId = null;
        int[] batchNextSeq = new int[] { -1 };

        for (DatamartVO.MetaSynonymRowVO row : rows) {
            if (row == null) {
                continue;
            }

            if (CommonUtil.isNotEmpty(row.getSynonymId())) {
                currentSynonymId = row.getSynonymId().trim();
                continue;
            }

            boolean isRepresent = "Y".equalsIgnoreCase(CommonUtil.nullToBlank(row.getRepresentYn()));
            if (!isRepresent && CommonUtil.isNotEmpty(currentSynonymId)) {
                row.setSynonymId(currentSynonymId);
                continue;
            }

            DatamartVO.MetaSynonymRowVO existsRow = datamartDAO.selectMetaSynonymByWord(row);
            boolean reuseExistingGroupId = existsRow != null
                    && CommonUtil.isNotEmpty(existsRow.getSynonymId())
                    && (!isRepresent || "Y".equalsIgnoreCase(CommonUtil.nullToBlank(existsRow.getRepresentYn())));

            if (reuseExistingGroupId) {
                currentSynonymId = existsRow.getSynonymId().trim();
            } else {
                currentSynonymId = allocateSynonymIdInBatch(batchNextSeq);
            }
            row.setSynonymId(currentSynonymId);
        }
    }

    /**
     * 동일 저장 요청에서 신규 SYNONYM_ID를 중복 없이 발급한다.
     */
    private String allocateSynonymIdInBatch(int[] batchNextSeq) throws Exception {
        if (batchNextSeq[0] < 0) {
            String seedKey = keyGenerate.generateTableKey("DS", "TB_DM_SYNONYM", "SYNONYM_ID");
            batchNextSeq[0] = Integer.parseInt(seedKey.substring(2)) + 1;
            return seedKey;
        }
        String key = "DS" + String.format("%06d", batchNextSeq[0]);
        batchNextSeq[0]++;
        return key;
    }

    /**
     * SORT_ORD 순서 지정 (저장 시에만 사용)
     * - 대표(REPRESENT_YN=Y): 전역 순번 1..N
     * - 일반 동의어(REPRESENT_YN!=Y): SYNONYM_ID 그룹별 순번 1..N
     */
    private void applyMetaSynonymSortOrd(List<DatamartVO.MetaSynonymRowVO> rows) {
        List<DatamartVO.MetaSynonymRowVO> sortedList = new ArrayList<>();
        for (DatamartVO.MetaSynonymRowVO row : rows) {
            if (row != null) {
                sortedList.add(row);
            }
        }

        sortedList.sort((a, b) -> {
            String aRepresentYn = CommonUtil.nullToBlank(a.getRepresentYn());
            String bRepresentYn = CommonUtil.nullToBlank(b.getRepresentYn());
            boolean aIsRepresent = "Y".equalsIgnoreCase(aRepresentYn);
            boolean bIsRepresent = "Y".equalsIgnoreCase(bRepresentYn);

            // 1) 대표값 먼저
            if (aIsRepresent != bIsRepresent) {
                return aIsRepresent ? -1 : 1;
            }

            int aSortOrd = a.getSortOrd() != null ? a.getSortOrd() : Integer.MAX_VALUE;
            int bSortOrd = b.getSortOrd() != null ? b.getSortOrd() : Integer.MAX_VALUE;

            // 2) 대표값끼리는 기존 sortOrd 기준
            if (aIsRepresent && bIsRepresent) {
                return Integer.compare(aSortOrd, bSortOrd);
            }

            // 3) 같은 동의어 ID끼리 묶기
            String aSynonymId = CommonUtil.nullToBlank(a.getSynonymId());
            String bSynonymId = CommonUtil.nullToBlank(b.getSynonymId());
            int idCompare = aSynonymId.compareTo(bSynonymId);
            if (idCompare != 0) {
                return idCompare;
            }

            // 4) 같은 동의어 ID 내 일반 동의어 정렬
            return Integer.compare(aSortOrd, bSortOrd);
        });

        int representOrd = 1;
        Map<String, Integer> normalOrdMap = new HashMap<>();
        for (DatamartVO.MetaSynonymRowVO row : sortedList) {
            boolean isRepresent = "Y".equalsIgnoreCase(CommonUtil.nullToBlank(row.getRepresentYn()));
            if (isRepresent) {
                row.setSortOrd(representOrd++);
                continue;
            }

            String key = CommonUtil.nullToBlank(row.getSynonymId());
            int normalOrd = normalOrdMap.getOrDefault(key, 1);
            row.setSortOrd(normalOrd);
            normalOrdMap.put(key, normalOrd + 1);
        }

        rows.clear();
        rows.addAll(sortedList);
    }

    private void applyMetaSynonymRowDefaults(DatamartVO.MetaSynonymRowVO datamartVO) {
        if (CommonUtil.isEmpty(datamartVO.getRepresentYn())) {
            datamartVO.setRepresentYn("N");
        }
        if (CommonUtil.isEmpty(datamartVO.getUseYn())) {
            datamartVO.setUseYn("Y");
        }
    }

    /**
     * 데이터마트 퓨샷 목록 조회
     * @param searchVO datamartId 필수
     * @return { datamartId, fewshotList: MetaFewshotRowVO[] }
     * @throws Exception
     */
    public HashMap<String, Object> selectMetaFewshotList(DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        List<DatamartVO.MetaFewshotRowVO> fewshotList = datamartDAO.selectMetaFewshotList(searchVO);
        resultMap.put("datamartId", searchVO != null ? searchVO.getDatamartId() : "");
        resultMap.put("fewshotList", fewshotList != null ? fewshotList : new ArrayList<DatamartVO.MetaFewshotRowVO>());
        return resultMap;
    }

    /**
     * 데이터마트 퓨샷 저장(목록)
     * @param payload datamartId, fewshotList (useYn: Y=저장, N=삭제(프론트 전달))
     * @return { result, msg }
     * @throws Exception
     */
    @Transactional
    public HashMap<String, Object> saveMetaFewshotList(DatamartVO.MetaFewshotSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        if (payload == null || CommonUtil.isEmpty(payload.getDatamartId())) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "datamartId is required");
            return resultMap;
        }

        List<DatamartVO.MetaFewshotRowVO> fewshotList = payload.getFewshotList();
        if (fewshotList == null || fewshotList.isEmpty()) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "퓨샷 목록이 비어 있습니다.");
            return resultMap;
        }

        String payloadDatamartId = payload.getDatamartId();
        List<DatamartVO.MetaFewshotRowVO> validRows = new ArrayList<>();
        for (DatamartVO.MetaFewshotRowVO row : fewshotList) {
            if (row == null) {
                continue;
            }
            if (CommonUtil.isEmpty(row.getDatamartId())) {
                row.setDatamartId(payloadDatamartId);
            }
            normalizeMetaFewshotUseYn(row);

            if (isMetaFewshotDeleteRow(row)) {
                if (CommonUtil.isEmpty(row.getFewshotId())) {
                    continue;
                }
                validRows.add(row);
                continue;
            }
            if (CommonUtil.isEmpty(row.getUserQuestion())) {
                continue;
            }
            validRows.add(row);
        }
        if (validRows.isEmpty()) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "퓨샷 목록이 비어 있습니다.");
            return resultMap;
        }

        String fewshotDuplicateMsg = validateMetaFewshotUniqueness(payloadDatamartId, validRows);
        if (fewshotDuplicateMsg != null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", fewshotDuplicateMsg);
            return resultMap;
        }

        assignFewshotIdsForSave(payloadDatamartId, validRows);
        for (DatamartVO.MetaFewshotRowVO row : validRows) {
            datamartDAO.saveMetaFewshot(row);
        }

        resultMap.put("result", "SUCCESS");
        resultMap.put("msg", "데이터마트 퓨샷 저장 성공");
        return resultMap;
    }

    private boolean isMetaFewshotDeleteRow(DatamartVO.MetaFewshotRowVO row) {
        return row != null && "N".equalsIgnoreCase(CommonUtil.nullToBlank(row.getUseYn()));
    }

    private void normalizeMetaFewshotUseYn(DatamartVO.MetaFewshotRowVO row) {
        if (row == null) {
            return;
        }
        String useYn = CommonUtil.nullToBlank(row.getUseYn()).trim();
        if (CommonUtil.isEmpty(useYn)) {
            row.setUseYn("Y");
            return;
        }
        row.setUseYn(useYn.toUpperCase());
    }

    /**
     * 동의어 (DATAMART_ID, SYNONYM_WORD) UNIQUE 검증
     * @return 중복 시 FAIL 메시지, 없으면 null
     */
    private String validateMetaSynonymUniqueness(String datamartId, List<DatamartVO.MetaSynonymRowVO> rows)
            throws Exception {
        Set<String> seenWords = new HashSet<>();
        for (DatamartVO.MetaSynonymRowVO row : rows) {
            if (row == null || CommonUtil.isEmpty(row.getSynonymWord())) {
                continue;
            }
            String synonymWord = row.getSynonymWord().trim();
            if (!seenWords.add(synonymWord)) {
                return "동일한 동의어가 중복되었습니다: " + synonymWord;
            }
        }

        for (DatamartVO.MetaSynonymRowVO row : rows) {
            if (row == null || CommonUtil.isEmpty(row.getSynonymWord())) {
                continue;
            }
            if (CommonUtil.isEmpty(row.getDatamartId())) {
                row.setDatamartId(datamartId);
            }
            DatamartVO.MetaSynonymRowVO existsRow = datamartDAO.selectMetaSynonymByWord(row);
            if (existsRow == null || CommonUtil.isEmpty(existsRow.getSynonymWord())) {
                continue;
            }
            String existingWord = existsRow.getSynonymWord().trim();
            String rowWord = row.getSynonymWord().trim();
            if (!existingWord.equals(rowWord)) {
                continue;
            }
            String existingSynonymId = CommonUtil.nullToBlank(existsRow.getSynonymId()).trim();
            String rowSynonymId = CommonUtil.nullToBlank(row.getSynonymId()).trim();
            if (CommonUtil.isNotEmpty(rowSynonymId) && !existingSynonymId.equals(rowSynonymId)) {
                return "이미 등록된 동의어입니다: " + rowWord;
            }
        }
        return null;
    }

    /**
     * 퓨샷 (DATAMART_ID, USER_QUESTION) UNIQUE 검증
     * @return 중복 시 FAIL 메시지, 없으면 null
     */
    private String validateMetaFewshotUniqueness(String datamartId, List<DatamartVO.MetaFewshotRowVO> rows)
            throws Exception {
        Set<String> seenQuestions = new HashSet<>();
        Set<String> seenFewshotIds = new HashSet<>();
        for (DatamartVO.MetaFewshotRowVO row : rows) {
            if (row == null) {
                continue;
            }
            if (isMetaFewshotDeleteRow(row)) {
                if (CommonUtil.isNotEmpty(row.getFewshotId())) {
                    String fewshotId = row.getFewshotId().trim();
                    if (!seenFewshotIds.add(fewshotId)) {
                        return "동일한 퓨샷 ID가 중복되었습니다: " + fewshotId;
                    }
                }
                continue;
            }
            String userQuestion = CommonUtil.nullToBlank(row.getUserQuestion()).trim();
            if (CommonUtil.isEmpty(userQuestion)) {
                continue;
            }
            if (!seenQuestions.add(userQuestion)) {
                return "동일한 사용자 질문이 중복되었습니다: " + userQuestion;
            }
            if (CommonUtil.isNotEmpty(row.getFewshotId())) {
                String fewshotId = row.getFewshotId().trim();
                if (!seenFewshotIds.add(fewshotId)) {
                    return "동일한 퓨샷 ID가 중복되었습니다: " + fewshotId;
                }
            }
        }

        for (DatamartVO.MetaFewshotRowVO row : rows) {
            if (row == null || isMetaFewshotDeleteRow(row)) {
                continue;
            }
            String userQuestion = CommonUtil.nullToBlank(row.getUserQuestion()).trim();
            if (CommonUtil.isEmpty(userQuestion)) {
                continue;
            }
            if (CommonUtil.isEmpty(row.getDatamartId())) {
                row.setDatamartId(datamartId);
            }
            DatamartVO.MetaFewshotRowVO existsRow = datamartDAO.selectMetaFewshotByQuestion(row);
            if (existsRow == null || CommonUtil.isEmpty(existsRow.getUserQuestion())) {
                continue;
            }
            String existingQuestion = existsRow.getUserQuestion().trim();
            if (!existingQuestion.equals(userQuestion)) {
                continue;
            }
            String existingFewshotId = CommonUtil.nullToBlank(existsRow.getFewshotId()).trim();
            String rowFewshotId = CommonUtil.nullToBlank(row.getFewshotId()).trim();
            if (CommonUtil.isEmpty(rowFewshotId) || !existingFewshotId.equals(rowFewshotId)) {
                return "이미 등록된 사용자 질문입니다: " + userQuestion;
            }
        }
        return null;
    }

    /**
     * 저장 payload 기준으로 신규 행에 FEWSHOT_ID·SORT_ORD를 채운다.
     * - ID: allocateSynonymIdInBatch / allocateFewshotIdInBatch와 동일(로컬 시퀀스)
     * - SORT_ORD: AgentServiceImpl.saveAgent 신규 등록과 동일(MAX+1, datamartId 단위)
     */
    private void assignFewshotIdsForSave(String datamartId, List<DatamartVO.MetaFewshotRowVO> rows) throws Exception {
        Set<String> reservedIds = new HashSet<>();
        for (DatamartVO.MetaFewshotRowVO row : rows) {
            if (row != null && CommonUtil.isNotEmpty(row.getFewshotId())) {
                reservedIds.add(row.getFewshotId().trim());
            }
        }

        int[] batchNextSeq = new int[] { -1 };
        int[] batchNextSortOrd = new int[] { -1 };
        for (DatamartVO.MetaFewshotRowVO row : rows) {
            if (row == null || CommonUtil.isNotEmpty(row.getFewshotId())) {
                continue;
            }
            if (isMetaFewshotDeleteRow(row)) {
                continue;
            }

            String newId;
            do {
                newId = allocateFewshotIdInBatch(batchNextSeq);
            } while (reservedIds.contains(newId));
            reservedIds.add(newId);
            row.setFewshotId(newId);

            if (batchNextSortOrd[0] < 0) {
                DatamartVO searchVO = new DatamartVO();
                searchVO.setDatamartId(datamartId);
                batchNextSortOrd[0] = datamartDAO.selectMetaFewshotMaxSortOrd(searchVO) + 1;
            }
            row.setSortOrd(batchNextSortOrd[0]++);
        }
    }

    /**
     * 동일 저장 요청에서 신규 FEWSHOT_ID를 중복 없이 발급한다.
     */
    private String allocateFewshotIdInBatch(int[] batchNextSeq) throws Exception {
        if (batchNextSeq[0] < 0) {
            String seedKey = keyGenerate.generateTableKey("FW", "TB_DM_FEWSHOT", "FEWSHOT_ID");
            batchNextSeq[0] = Integer.parseInt(seedKey.substring(2)) + 1;
            return seedKey;
        }
        String key = "FW" + String.format("%06d", batchNextSeq[0]);
        batchNextSeq[0]++;
        return key;
    }
}
