package kr.teamagent.mypage.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MyPageVO extends CommonVO {
    private static final long serialVersionUID = 1L;

    private String userId;
    private String userNm;
    private String email;
    private String phone;
    private String orgId;
    private String useYn;
    private String lastLoginDt;
    private String pwdChgDt;
    private String loginFailCnt;
    private String acctStatusCd;
    private String acctStatusDesc;
}
