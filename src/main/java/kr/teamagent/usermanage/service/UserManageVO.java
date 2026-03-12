package kr.teamagent.usermanage.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserManageVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    private String userId;
    private String userNm;
    private String email;
    private String passwd;
    private String phone;
    private String orgId;
    private String useYn;
    private String lastLoginDt;
    private String pwdChgDt;
    private Integer loginFailCnt;
    private String twoFaYn;
    private String accTp;
    private String acctStatusCd;
    private String lockDt;
    private String createDt;
    private String modifyDt;
    private String crtrId;
    private String mdfrId;
}