package kr.teamagent.mypage.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MyPagePasswordChangeVO extends CommonVO {
    private static final long serialVersionUID = 1L;

    private String userId;
    private String oldPassword;
    private String newPassword;
    private String newPasswordConfirm;
    private String passwd;
}
