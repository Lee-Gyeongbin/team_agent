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
    private String profileImgNm;
    private String profileImgPath;

    /** 로그인 이력 조회 조건·결과 행 */
    @Getter
    @Setter
    public static class LoginHistoryVO extends CommonVO {
        private static final long serialVersionUID = 1L;

        private String fromDt;
        private String toDt;
        private String ipAddr;
        private String userAgent;
        private String result;
        private String failRson;
        private String createDt;
    }

    /** 비밀번호 변경 요청 */
    @Getter
    @Setter
    public static class PasswordChangeVO extends CommonVO {
        private static final long serialVersionUID = 1L;

        private String userId;
        private String oldPassword;
        private String newPassword;
        private String newPasswordConfirm;
        private String passwd;
    }
}
