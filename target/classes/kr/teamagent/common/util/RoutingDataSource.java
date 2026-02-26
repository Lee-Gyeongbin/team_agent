package kr.teamagent.common.util;

import kr.teamagent.common.secure.service.SecureService;
import org.apache.commons.dbcp.BasicDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author sidnancy
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    Logger log = LoggerFactory.getLogger(RoutingDataSource.class);

    private ConcurrentHashMap<Object, Object> dataSources = new ConcurrentHashMap<>();

    @Override
    protected Object determineCurrentLookupKey() {
        return ContextHolder.getDataSourceType();
    }

    @Override
    public synchronized void setTargetDataSources(Map<Object, Object> targetDataSources) {

        final String driverClassName = PropertyUtil.getProperty("Globals.mysql.DriverClassName");
        final String url = PropertyUtil.getProperty("Globals.mysql.Url");
        final String userName = PropertyUtil.getProperty("Globals.mysql.UserName");
        final String password = PropertyUtil.getProperty("Globals.mysql.Password");
        final String masterDb = PropertyUtil.getProperty("Globals.Master.db");

        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rsltSet = null;

        /**
         * 데이테베이스 접속정보
         */
        ArrayList<HashMap<String, String>> connList = new ArrayList<>() ;
        try {
            Class.forName(driverClassName);
            con = DriverManager.getConnection(url, userName, password);
            StringBuffer connQuery  = new StringBuffer();
            connQuery.append("SELECT									\n");
            connQuery.append("	A.DB_ID,                            	\n");
            connQuery.append("	A.CONNECTION_ID,                    	\n");
            connQuery.append("	A.COMP_ID,                          	\n");
            connQuery.append("	A.DB_ID,                            	\n");
            connQuery.append("	A.DB_DRIVER,                        	\n");
            connQuery.append("	A.DB_URL,                           	\n");
            connQuery.append("	A.DB_USER_ID,                       	\n");
            connQuery.append("	A.DB_USER_PASSWD                     	\n");
            connQuery.append("FROM " +masterDb + ".COM_COMP_DBINFO A          			\n");
            connQuery.append("   INNER JOIN COM_COMPINFO B    			\n");
            connQuery.append("   ON A.COMP_ID = B.COMP_ID           	\n");
//			connQuery.append("   AND B.USE_YN = 'Y'                 	\n");  // 2025-09-23 테이블 컬럼 변경
            //connQuery.append("   WHERE A.DB_ID IN ('public_super','public_template','public_ispark','public_theborn','public_brandx','public_hlb','public_bseng','public_korcham','public_topco')                  	\n");
            pstmt = con.prepareStatement(connQuery.toString());
            rsltSet = pstmt.executeQuery();
            while(rsltSet.next())
            {
                String connectionId = SecureService.decryptStr(rsltSet.getString("CONNECTION_ID"));
                String compId       = SecureService.decryptStr(rsltSet.getString("COMP_ID"));
                String dbId         = SecureService.decryptStr(rsltSet.getString("DB_ID"));
                String dbDriver     = SecureService.decryptStr(rsltSet.getString("DB_DRIVER"));
                String dbUrl        = SecureService.decryptStr(rsltSet.getString("DB_URL"));

                //TEST
                if(url.contains("db-1jbfb.pub-cdb.ntruss.com"))
                {
                    dbUrl = dbUrl.replaceAll("db-1jbfb.cdb.ntruss.com", "db-1jbfb.pub-cdb.ntruss.com");
                }

                String dbUserId     = SecureService.decryptStr(rsltSet.getString("DB_USER_ID"));
                String dbUserPasswd = SecureService.decryptStr(rsltSet.getString("DB_USER_PASSWD"));
                boolean isConn = isConn(dbDriver, dbUrl, dbUserId, dbUserPasswd);

                log.info("=======================================================");
                log.info("connectionId  : {}",  connectionId);
                log.info("compId        : {}",  compId      );
                log.info("dbId          : {}",  dbId        );
                log.info("dbDriver      : {}",  dbDriver    );
                log.info("dbUrl         : {}",  dbUrl       );
                log.info("dbUserId      : {}",  dbUserId    );
                log.info("dbUserPasswd  : {}",  dbUserPasswd);
                log.info("isConn  : {}",  isConn);
                log.info("=======================================================");

                if(!isConn)
                {
                    //throw new SQLException("DB CONNECTION ERROR.");
                    log.error("DB CONNECTION ERROR.");
                }

                HashMap<String, String> connInfo = new HashMap<>();
                connInfo.put("CONNECTION_ID", connectionId);
                connInfo.put("COMP_ID"		, compId      );
                connInfo.put("DB_ID"		, dbId        );
                connInfo.put("DB_DRIVER"	, dbDriver    );
                connInfo.put("DB_URL"		, dbUrl       );
                connInfo.put("DB_USER_ID"	, dbUserId    );
                connInfo.put("DB_USER_PASSWD",dbUserPasswd);
                connList.add(connInfo);
            }

        } catch (SQLException e1) {
            log.error(e1.getMessage());

        } catch (ClassNotFoundException e) {
            log.error(e.getMessage());
        } finally {
            if(rsltSet!=null) {
                try {
                    rsltSet.close();
                } catch (SQLException e) {
                    log.error(e.getMessage());
                }
            }
            if(pstmt!=null) {
                try {
                    pstmt.close();
                } catch (SQLException e) {
                    log.error(e.getMessage());
                }
            }
            if(con!=null) {
                try {
                    con.close();
                } catch (SQLException e) {
                    log.error(e.getMessage());
                }
            }
        }
        System.out.println("################target datainfo read start###############");

        if(CommonUtil.isNotEmpty(connList))
        {
            for(HashMap<String, String> connInfo : connList) {
                BasicDataSource ds = new BasicDataSource();

                String compId = connInfo.get("COMP_ID").trim();
                String dbDriver = connInfo.get("DB_DRIVER").trim();
                String dbUrl = connInfo.get("DB_URL").trim();
                String dbUserId = connInfo.get("DB_USER_ID").trim();
                String dbUserPasswd = connInfo.get("DB_USER_PASSWD").trim();
                String connectionId = connInfo.get("CONNECTION_ID").trim();

                ds.setDriverClassName(dbDriver);
                ds.setUrl(dbUrl);
                ds.setUsername(dbUserId);
                ds.setPassword(dbUserPasswd);
                ds.setInitialSize(5);
                ds.setMaxActive(10);
                ds.setMaxIdle(5);
                ds.setMinIdle(2);
                ds.setValidationQuery("SELECT 1");
                ds.setTestOnBorrow(true);

                //System.out.println("ds -> " +ds.getJdbcUrl());
                //커넥션ID
                dataSources.put(DataSourceType.valueOf(connectionId), ds);
            }
        }







        System.out.println("################target datainfo read end###############");
        super.setTargetDataSources(dataSources);
    }

    public void resetTargetDataSources() {
        setTargetDataSources(new HashMap<Object, Object>());
        super.afterPropertiesSet();
    }


    /**
     * 커넥션 조회 및 갱신
     * @param connId
     * @return
     * -1 : 정보없음, 0 : 기존에있음, 1 : 새로 생성
     */
    public int checkDataSource(String connId){
        int result = -1;//

        //기존에 존재하는 커넥션인지 체크
        if (dataSources.containsKey(DataSourceType.valueOf(CommonUtil.nullToBlank(connId).trim()))){
            log.info("checkDatasource : {} is existing ds", connId);
            result =  0;
        }else{
            //존재 하지 않는 커넥션 일 경우 다시 로드
            List<String> connIds = Arrays.stream(DataSourceType.values()).map(Enum::name).collect(Collectors.toList());
            if (connIds.contains(connId)) {
                resetTargetDataSources();
                setTargetDataSources(dataSources);
                log.info("checkDatasource : {} is new ds. added!", connId);
                result = 1;
            }
        }

        return result;
    }



    /**
     * DB접속가능테스트
     * @param dbDriver,dbUrl,dbUserId,DbUserPasswd
     * @return
     */
    public boolean isConn(String dbDriver, String  dbUrl, String  dbUserId, String DbUserPasswd){
        boolean isOk = true;
        Connection conn = null;
        try{
            Class.forName(dbDriver);
            conn = DriverManager.getConnection(dbUrl, dbUserId, DbUserPasswd);
        }catch(SQLException sqe){
            isOk = false;
            sqe.getCause();
        }catch(Exception e){
            isOk = false;
            e.getCause();
        }finally{
            try{
                if(conn == null){
                    isOk = false;
                    //log.debug("!!!!!!!loop : "+dbUrl+":"+dbUserId+":"+DbUserPasswd+" --> isconn : " + isOk);
                }else{
                    //log.debug("!!!!!!!loop : "+dbUrl+":"+dbUserId+":"+DbUserPasswd+" --> isconn : " + isOk);
                    conn.close();
                }
            }catch(SQLException e){
                log.error(e.getMessage());
            }
        }

        return isOk;

    }
}
