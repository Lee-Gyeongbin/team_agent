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

import org.codehaus.jackson.annotate.JsonIgnore;
import org.springframework.security.access.ConfigAttribute;

import kr.teamagent.common.CommonVO;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@EqualsAndHashCode(of = {"compId", "userId"}, callSuper = false)
public class UserVO extends CommonVO {
    private static final long serialVersionUID = 6985078694123877792L;

    private String compId;
    private String userId;
    private String userNm;
    private String deptId;
    private String deptNm;
    private String deptFullNm;
    private String userDeptNm;
    private String scDeptId;
    private String scDeptNm;
    private String jikgubId;
    private String jikgubCd;
    private String jikgubNm;
    private String posId;
    private String posCd;
    private String posNm;
    private String passwd;
    private String email;
    private String birthDt;
    private String joinDt;
    private String tel;
    private String phone;
    private String ipAddress;
    private String ipChk;
    private String etc;
    private String pwChangeDt;
    private String userStatusCd;
    private String userStatusNm;

    private String isIncludeDept;//부서포함해서가져오기여부
    private String isDept;//부서여부
    private String isSearchDept;//부서 검색조건

    private String profileImgBase64;
    private String profileImg;//첨부파일되는파일이름
    private String demoYn;//데모고객사여부
    private String authGbn;
    private String evalCycleCd;
    private String onbChatTarget;

    private String menuGbn;
    @JsonIgnore private String compSaiUseYn;//회사 AI Sai 사용여부
    @JsonIgnore private String aiSaiUseYn;//AI Sai 사용여부
    @JsonIgnore private String mainPgmId;
    @JsonIgnore private String mainPgmNm;
    @JsonIgnore private String initPgmId;
    @JsonIgnore private String initPgmUrl;
    @JsonIgnore private String authMethod = "password";//로그인 인증방법(password/token)
    @JsonIgnore private String pwInitYn;	// 패스워드 초기화 여부
    @JsonIgnore private String alertPwChangeYn;	// 패스워드 변경 알림 여부
    @JsonIgnore private String pwChangeCycle;	// 패스워드 변경 주기

    private String loginFlag;
    private String ip;

    @JsonIgnore private String findJikgubId;
    @JsonIgnore private String findPosId;

    private List<String> adminGubunList;
    private String connectionId;
    private String retireViewYn;
    private String accessToken;
    private String webhookUrl;
    private String pwResetGbn;
    private String beingYn;
    private String limit;

    public String getUniqueUserId() {
        return this.compId + "-" + this.userId;
    }

    public interface PasswordResetValid{}

    private LinkedHashMap<String, Collection<ConfigAttribute>> requestMap;
}
