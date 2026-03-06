/*************************************************************************
* CLASS 명	: UserVO
* 작 업 자	: kimyh
* 작 업 일	: 2017. 11. 23.
* 기	능	: 사용자 정보 VO
* ---------------------------- 변 경 이 력 --------------------------------
* 번호	작 업 자		작	업	일			변 경 내 용				비고
* ----	---------	----------------	---------------------	-----------
*	1	kimyh		2017. 11. 23.		최 초 작 업
**************************************************************************/

package kr.teamagent.common.security.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.security.access.ConfigAttribute;

import kr.teamagent.common.CommonVO;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@EqualsAndHashCode(of = {"userId"}, callSuper = false)
public class UserVO extends CommonVO {
    private static final long serialVersionUID = 6985078694123877792L;

    private String userId;
    private String userName;
    private String email;
    private String password;
    private String phone;
    private String orgId;
    private String useYn;
    private String lastLoginAt;
    private String pwdChangedAt;
    private int loginFailCnt;
    private String twoFactorYn;
    private String createdAt;
    private String updatedAt;
    private String createdBy;
    private String updatedBy;
    private String orgName;

    private String profileImgBase64;
    private String profileImg;
    private String demoYn;
    private String authGbn;
    private String evalCycleCd;
    private String onbChatTarget;

    private String menuGbn;
    @JsonIgnore private String compSaiUseYn;
    @JsonIgnore private String aiSaiUseYn;
    @JsonIgnore private String mainPgmId;
    @JsonIgnore private String mainPgmNm;
    @JsonIgnore private String initPgmId;
    @JsonIgnore private String initPgmUrl;
    @JsonIgnore private String authMethod = "password";

    private String loginFlag;
    private String ip;

    private List<String> adminGubunList;
    private String connectionId;
    private String retireViewYn;
    private String accessToken;
    private String webhookUrl;
    private String pwResetGbn;
    private String limit;

    public String getUniqueUserId() {
        return getUserId();
    }

    public interface PasswordResetValid{}

    private LinkedHashMap<String, Collection<ConfigAttribute>> requestMap;
}
