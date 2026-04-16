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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.datamart.service.DatamartVO;

@Service
public class DatamartServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(DatamartServiceImpl.class);

    private static final int DEFAULT_MYSQL_PORT = 3306;
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
     * @return { result, msg, dataList: [ { id, physicalNm, logicalNm, colCnt, useYn, tableDescKo, usageTy, columns: [...] } ] }
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
                String tableType = tableRs.getString("TABLE_TYPE");

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

                List<HashMap<String, Object>> columns = new ArrayList<>();
                ResultSet colRs = metaData.getColumns(catalog, null, tableName, "%");
                while (colRs.next()) {
                    HashMap<String, Object> col = new HashMap<>();
                    String colName = colRs.getString("COLUMN_NAME");
                    col.put("id", colName);
                    col.put("colName", colName);
                    col.put("dataType", colRs.getString("TYPE_NAME"));
                    col.put("isPk", pkColumns.contains(colName));
                    col.put("isFk", fkColumns.contains(colName));
                    col.put("descKo", colRs.getString("REMARKS") != null ? colRs.getString("REMARKS") : "");
                    col.put("nullable", "YES".equals(colRs.getString("IS_NULLABLE")) ? "Y" : "N");
                    columns.add(col);
                }
                colRs.close();

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
                table.put("usageTy", tableType);
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

}
