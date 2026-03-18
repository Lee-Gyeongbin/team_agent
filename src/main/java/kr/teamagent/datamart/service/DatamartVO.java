package kr.teamagent.datamart.service;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DatamartVO {

    /** TB_DM */
    private String datamartId;
    private String dmNm;
    private String description;
    private String dbType;
    private String dbVersion;
    private String host;
    private Integer port;
    private String dbNm;
    private String username;
    private String pwdEnc;
    private String schNm;
    private String connOpt;
    private String readonlyYn;
    private String ipWlistYn;
    private String sslYn;
    private Integer tblCnt;
    private String lastVerifyDt;
    private Integer sortOrd;
    private String useYn;
    private String createDt;
    private String modifyDt;

}
