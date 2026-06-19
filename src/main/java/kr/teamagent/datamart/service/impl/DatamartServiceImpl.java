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
    private static final String[] META_COLUMN_EXCEL_HEADERS = {
            META_HDR_TBL_ID, META_HDR_COL_ID, META_HDR_COL_PHY_NM, META_HDR_COL_KOR_NM, META_HDR_COL_DESC,
            META_HDR_DATA_TYPE, META_HDR_DATA_LEN, META_HDR_PK_YN, META_HDR_FK_YN, META_HDR_NULLABLE_YN,
            META_HDR_HAS_CODE_YN, META_HDR_AI_HINT, META_HDR_SORT_ORD
    };
    private static final int[] META_COLUMN_AUTO_GEN_HEADER_COLS = { 12 };
    private static final int META_COLUMN_PK_YN_COL_IDX = 7;
    private static final int META_COLUMN_FK_YN_COL_IDX = 8;
    private static final int META_COLUMN_NULLABLE_YN_COL_IDX = 9;
    private static final int META_COLUMN_HAS_CODE_YN_COL_IDX = 10;
    private static final String META_COLUMN_EXCEL_GUIDE_TEXT =
            "※ * 표시된 항목은 필수 입력값입니다.\n"
                    + "※ 업로드는 미리보기용이며, 저장 시 해당 데이터마트의 컬럼 메타가 전체 교체됩니다.\n"
                    + "  테이블ID·물리컬럼명·데이터타입(필수), 컬럼ID, PK/FK/NULL허용/코드값여부(Y/N)를 입력하세요. 컬럼ID 미입력 시 물리컬럼명이 사용됩니다.";

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
     * 메타 관리 > 테이블 저장 (TB_DM_TBL 해당 DATAMART_ID 전체 삭제 후 INSERT)
     * @param payload datamartId, tableList
     * @return result, msg
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
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

        datamartDAO.deleteDmTblByDatamartId(dm);

        List<DatamartVO.MetaTableItemVO> toSave = new ArrayList<>();
        List<DatamartVO.MetaTableItemVO> tableList = payload.getTableList();
        if (tableList != null) {
            for (DatamartVO.MetaTableItemVO table : tableList) {
                if (table != null) {
                    toSave.add(table);
                }
            }
        }

        if (!toSave.isEmpty()) {
            DatamartVO.MetaTableSavePayloadVO insertPayload = new DatamartVO.MetaTableSavePayloadVO();
            insertPayload.setDatamartId(payload.getDatamartId());
            insertPayload.setTableList(toSave);
            datamartDAO.insertMetaTableBatch(insertPayload);
        }

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
        }
    }

    private void applyMetaColumnExcelSheetOptions(XSSFSheet sheet) {
        ExcelUtil.applyDataSheetLayout(sheet, META_COLUMN_EXCEL_HEADERS.length);
        ExcelUtil.addUseYnListValidations(sheet, META_COLUMN_PK_YN_COL_IDX, META_COLUMN_FK_YN_COL_IDX,
                META_COLUMN_NULLABLE_YN_COL_IDX, META_COLUMN_HAS_CODE_YN_COL_IDX);
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
            Map<String, Integer> colIdx = ExcelUtil.detectUploadHeader(sheet, formatter, "컬럼 메타",
                    META_HDR_TBL_ID, META_HDR_TBL_ID, META_HDR_COL_PHY_NM, META_HDR_DATA_TYPE);

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

            for (int i = ExcelUtil.DATA_START_ROW; i <= sheet.getLastRowNum(); i++) {
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

                if (isSkippableMetaColumnExcelRow(tblId, colPhyNm, colId, colKorNm, colDesc, dataType, dataLen,
                        pkYn, fkYn, nullableYn, hasCodeYn, aiHint, sortOrdStr)) {
                    continue;
                }

                if (hasMetaColumnRequiredError(tblId, colPhyNm, dataType)) {
                    requiredFailCount++;
                    continue;
                }

                if (colId.isEmpty()) {
                    colId = colPhyNm;
                }

                pkYn = pkYn.isEmpty() ? "N" : pkYn;
                fkYn = fkYn.isEmpty() ? "N" : fkYn;
                nullableYn = nullableYn.isEmpty() ? "Y" : nullableYn;
                hasCodeYn = hasCodeYn.isEmpty() ? "N" : hasCodeYn;

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
            String hasCodeYn, String aiHint, String sortOrdStr) {
        if (ExcelUtil.isGuideMarkerRow(tblId)) {
            return true;
        }
        return tblId.isEmpty() && colPhyNm.isEmpty() && colId.isEmpty() && colKorNm.isEmpty() && colDesc.isEmpty()
                && dataType.isEmpty() && dataLen.isEmpty() && pkYn.isEmpty() && fkYn.isEmpty() && nullableYn.isEmpty()
                && hasCodeYn.isEmpty() && aiHint.isEmpty() && sortOrdStr.isEmpty();
    }

    private static boolean hasMetaColumnRequiredError(String tblId, String colPhyNm, String dataType) {
        return tblId.isEmpty() || colPhyNm.isEmpty() || dataType.isEmpty();
    }

    private static String buildMetaColumnUploadFailReturnMsg(int requiredFailCount) {
        return "업로드 실패 : 필수값 누락 " + requiredFailCount + "건이 있습니다.";
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
     * 메타 관리 > 코드그룹 매핑 저장 (TB_DM_COL_CODE 해당 DATAMART_ID 전체 삭제 후 INSERT)
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

        datamartDAO.deleteDmColCodeByDatamartId(dm);

        List<DatamartVO.MetaCodeColumnMappingVO> toSave = filterCodeMappingSaveList(payload.getCodeColumnMappingList());
        if (!toSave.isEmpty()) {
            DatamartVO.MetaCodeMappingSavePayloadVO insertPayload = new DatamartVO.MetaCodeMappingSavePayloadVO();
            insertPayload.setDatamartId(payload.getDatamartId());
            insertPayload.setCodeColumnMappingList(toSave);
            datamartDAO.insertDmColCodeBatch(insertPayload);
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
     * 데이터마트 동의어 저장 (TB_DM_SYNONYM 해당 DATAMART_ID 전체 삭제 후 INSERT)
     * @param payload datamartId, synonymList
     * @return { result, msg }
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public HashMap<String, Object> saveMetaSynonymList(DatamartVO.MetaSynonymSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        if (payload == null || CommonUtil.isEmpty(payload.getDatamartId())) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "datamartId is required");
            return resultMap;
        }

        String datamartId = payload.getDatamartId().trim();
        DatamartVO dm = new DatamartVO();
        dm.setDatamartId(datamartId);
        if (datamartDAO.selectDatamart(dm) == null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "데이터마트 정보를 찾을 수 없습니다.");
            return resultMap;
        }

        datamartDAO.deleteDmSynonymByDatamartId(dm);

        List<DatamartVO.MetaSynonymRowVO> validRows = new ArrayList<>();
        List<DatamartVO.MetaSynonymRowVO> synonymList = payload.getSynonymList();
        if (synonymList != null) {
            for (DatamartVO.MetaSynonymRowVO row : synonymList) {
                if (row == null || CommonUtil.isEmpty(row.getSynonymWord())) {
                    continue;
                }
                row.setDatamartId(datamartId);
                validRows.add(row);
            }
        }

        if (!validRows.isEmpty()) {
            assignSynonymIdsForSave(validRows);
            applyMetaSynonymSortOrd(validRows);
            for (DatamartVO.MetaSynonymRowVO row : validRows) {
                applyMetaSynonymRowDefaults(row);
                datamartDAO.insertMetaSynonym(row);
            }
        }

        resultMap.put("result", "SUCCESS");
        resultMap.put("msg", "데이터마트 동의어 저장 성공");
        return resultMap;
    }

    /**
     * 저장 payload 기준으로 SYNONYM_ID를 채운다.
     * - synonymId가 있으면 유지
     * - ID가 없는 일반 동의어(REPRESENT_YN!=Y)는 직전 그룹 ID 상속
     * - ID가 없는 대표(REPRESENT_YN=Y)는 신규 그룹 ID 발급
     * - 신규 ID는 payload·DB 최대 ID + 1부터 로컬 시퀀스로 발급 (delete 후 DB MAX만 보면 빈 번호가 재사용됨)
     */
    private void assignSynonymIdsForSave(List<DatamartVO.MetaSynonymRowVO> rows) throws Exception {
        String currentSynonymId = null;
        int maxSeq = 0;
        for (DatamartVO.MetaSynonymRowVO row : rows) {
            if (row != null && CommonUtil.isNotEmpty(row.getSynonymId())) {
                try {
                    maxSeq = Math.max(maxSeq, Integer.parseInt(row.getSynonymId().trim().substring(2)));
                } catch (NumberFormatException ignored) {
                    // skip invalid id
                }
            }
        }
        maxSeq = Math.max(maxSeq,
                Integer.parseInt(keyGenerate.generateTableKey("DS", "TB_DM_SYNONYM", "SYNONYM_ID").substring(2)) - 1);
        int[] batchNextSeq = new int[] { maxSeq + 1 };

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

            currentSynonymId = allocateSynonymIdInBatch(batchNextSeq);
            row.setSynonymId(currentSynonymId);
        }
    }

    /**
     * 동일 저장 요청에서 신규 SYNONYM_ID를 중복 없이 발급한다.
     */
    private String allocateSynonymIdInBatch(int[] batchNextSeq) {
        String key = "DS" + String.format("%06d", batchNextSeq[0]);
        batchNextSeq[0]++;
        return key;
    }

    /**
     * SORT_ORD 순서 지정 (저장 시에만 사용, payload 순서 기준)
     * - 대표(REPRESENT_YN=Y): 대표값끼리 순번 1..N
     * - 일반 동의어: 동일 REPRESENT_YN·SYNONYM_ID·DATAMART_ID 그룹별 순번 1..N
     */
    private void applyMetaSynonymSortOrd(List<DatamartVO.MetaSynonymRowVO> rows) {
        int representOrd = 1;
        Map<String, Integer> groupOrdMap = new HashMap<>();
        for (DatamartVO.MetaSynonymRowVO row : rows) {
            if (row == null) {
                continue;
            }

            boolean isRepresent = "Y".equalsIgnoreCase(CommonUtil.nullToBlank(row.getRepresentYn()));
            if (isRepresent) {
                row.setSortOrd(representOrd++);
                continue;
            }

            String groupKey = buildSynonymSortGroupKey(row);
            int groupOrd = groupOrdMap.getOrDefault(groupKey, 1);
            row.setSortOrd(groupOrd);
            groupOrdMap.put(groupKey, groupOrd + 1);
        }
    }

    private String buildSynonymSortGroupKey(DatamartVO.MetaSynonymRowVO row) {
        return CommonUtil.nullToBlank(row.getRepresentYn()) + "|"
                + CommonUtil.nullToBlank(row.getSynonymId()) + "|"
                + CommonUtil.nullToBlank(row.getDatamartId());
    }

    private void applyMetaSynonymRowDefaults(DatamartVO.MetaSynonymRowVO datamartVO) {
        if (CommonUtil.isEmpty(datamartVO.getRepresentYn())) {
            datamartVO.setRepresentYn("N");
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
     * 데이터마트 퓨샷 저장 (TB_DM_FEWSHOT 해당 DATAMART_ID 전체 삭제 후 INSERT)
     * @param payload datamartId, fewshotList
     * @return { result, msg }
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public HashMap<String, Object> saveMetaFewshotList(DatamartVO.MetaFewshotSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        if (payload == null || CommonUtil.isEmpty(payload.getDatamartId())) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "datamartId is required");
            return resultMap;
        }

        String datamartId = payload.getDatamartId().trim();
        DatamartVO dm = new DatamartVO();
        dm.setDatamartId(datamartId);
        if (datamartDAO.selectDatamart(dm) == null) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "데이터마트 정보를 찾을 수 없습니다.");
            return resultMap;
        }

        int dbMaxSortOrd = datamartDAO.selectMaxFewshotSortOrdByDatamartId(dm);
        datamartDAO.deleteDmFewshotByDatamartId(dm);

        List<DatamartVO.MetaFewshotRowVO> validRows = filterFewshotSaveList(payload.getFewshotList(), datamartId);

        if (!validRows.isEmpty()) {
            assignFewshotIdsForSave(validRows, dbMaxSortOrd);
            DatamartVO.MetaFewshotSavePayloadVO insertPayload = new DatamartVO.MetaFewshotSavePayloadVO();
            insertPayload.setDatamartId(datamartId);
            insertPayload.setFewshotList(validRows);
            datamartDAO.insertMetaFewshotBatch(insertPayload);
        }

        resultMap.put("result", "SUCCESS");
        resultMap.put("msg", "데이터마트 퓨샷 저장 성공");
        return resultMap;
    }

    private List<DatamartVO.MetaFewshotRowVO> filterFewshotSaveList(
            List<DatamartVO.MetaFewshotRowVO> fewshotList, String datamartId) {
        List<DatamartVO.MetaFewshotRowVO> validRows = new ArrayList<>();
        if (fewshotList == null) {
            return validRows;
        }
        for (DatamartVO.MetaFewshotRowVO row : fewshotList) {
            if (row == null || CommonUtil.isEmpty(row.getUserQuestion())) {
                continue;
            }
            row.setDatamartId(datamartId);
            validRows.add(row);
        }
        return validRows;
    }

    /**
     * 저장 payload 기준으로 FEWSHOT_ID·SORT_ORD를 채운다.
     * - ID 없는 행: payload·DB 최대 ID + 1부터 순차 발급 (빈 번호 재사용 방지)
     * - SORT_ORD: 있으면 유지, 없으면 DB·payload 최대값 + 1부터 순차 부여
     */
    private void assignFewshotIdsForSave(List<DatamartVO.MetaFewshotRowVO> rows, int dbMaxSortOrd) throws Exception {
        int maxSeq = 0;
        for (DatamartVO.MetaFewshotRowVO row : rows) {
            if (row != null && CommonUtil.isNotEmpty(row.getFewshotId())) {
                try {
                    maxSeq = Math.max(maxSeq, Integer.parseInt(row.getFewshotId().trim().substring(2)));
                } catch (NumberFormatException ignored) {
                    // skip invalid id
                }
            }
        }
        maxSeq = Math.max(maxSeq,
                Integer.parseInt(keyGenerate.generateTableKey("FW", "TB_DM_FEWSHOT", "FEWSHOT_ID").substring(2)) - 1);
        int[] batchNextSeq = new int[] { maxSeq + 1 };
        int maxSortOrd = dbMaxSortOrd;
        for (DatamartVO.MetaFewshotRowVO row : rows) {
            if (row != null && row.getSortOrd() != null) {
                maxSortOrd = Math.max(maxSortOrd, row.getSortOrd());
            }
        }
        int nextSortOrd = maxSortOrd + 1;
        for (DatamartVO.MetaFewshotRowVO row : rows) {
            if (row == null) {
                continue;
            }

            if (CommonUtil.isEmpty(row.getFewshotId())) {
                row.setFewshotId(allocateFewshotIdInBatch(batchNextSeq));
            }

            if (row.getSortOrd() == null) {
                row.setSortOrd(nextSortOrd++);
            }
        }
    }

    /**
     * 동일 저장 요청에서 신규 FEWSHOT_ID를 중복 없이 발급한다.
     */
    private String allocateFewshotIdInBatch(int[] batchNextSeq) {
        String key = "FW" + String.format("%06d", batchNextSeq[0]);
        batchNextSeq[0]++;
        return key;
    }
}
