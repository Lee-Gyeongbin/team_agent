package kr.teamagent.loginhistory.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginHistoryVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    private String dbId;
    private Long logId;
    private String userId;
    private String loginTp;
    private String accessTp;
    private String ipAddr;
    private String userAgent;
    private String result;
    private String failRson;
    private int failCnt;
    private String token;
    private String otpStatus;
    private String ipStatus;
    private String createDt;

}
