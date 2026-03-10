package kr.teamagent.loginhistory.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginHistoryVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    private String userId;
    private String ipAddr;
    private String userAgent;
    private String result;
    private String failRson;
    private String createDt;

}
