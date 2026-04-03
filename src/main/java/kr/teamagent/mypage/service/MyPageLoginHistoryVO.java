package kr.teamagent.mypage.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MyPageLoginHistoryVO extends CommonVO {
    private static final long serialVersionUID = 1L;

    private String ipAddr;
    private String userAgent;
    private String result;
    private String failRson;
    private String createDt;
}