package kr.teamagent.datamart.service.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;

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

        int port = dm.getPort() != null ? dm.getPort() : DEFAULT_MYSQL_PORT;
        String jdbcUrl = "jdbc:mysql://" + dm.getHost() + ":" + port + "/" + dm.getSchNm();
        if (!CommonUtil.isEmpty(dm.getConnOpt())) {
            jdbcUrl += "?" + dm.getConnOpt();
        }

        String password = dm.getPwdEnc();
        logger.info("연결 테스트 시작 - host: {}, port: {}, schNm: {}, username: {}", dm.getHost(), port, dm.getSchNm(), dm.getUsername());

        Connection conn = null;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            conn = DriverManager.getConnection(jdbcUrl, dm.getUsername(), password);
            resultMap.put("result", "SUCCESS");
            resultMap.put("msg", "연결 성공! 데이터베이스에 정상적으로 연결되었습니다.");
        } catch (SQLException e) {
            logger.error("DB 연결 실패 - url: {}, error: {}", jdbcUrl, e.getMessage());
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

}
