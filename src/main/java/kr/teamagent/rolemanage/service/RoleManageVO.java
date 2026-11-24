package kr.teamagent.rolemanage.service;

import java.util.List;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RoleManageVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    private String roleId;
    private String roleName;
    private String description;
    private String useYn;
    private String defaultYn;
    private String createDt;
    private String modifyDt;
    private String createId;
    private String modifyId;

    /** 권한 그룹 저장 요청 (그룹 정보 + 메뉴별 권한) */
    @Getter
    @Setter
    public static class SaveRoleVO extends RoleManageVO {
        private static final long serialVersionUID = 1L;

        private List<RoleMenuVO> menuList;
    }

    /** 권한 그룹의 메뉴별 권한 [TB_ROLE_MENU + TB_MENU] */
    @Getter
    @Setter
    public static class RoleMenuVO extends CommonVO {
        private static final long serialVersionUID = 1L;

        private String roleId;
        private String menuId;
        private String menuName;
        private String parnMenuId;
        private Integer menuLevel;
        private Integer sortOrd;
        private String isLeaf;
        private String readYn;
        private String funcYn;
        private String createId;
        private String modifyId;
    }

    /** 권한 그룹 사용자 [TB_USER_ROLE + TB_USER] */
    @Getter
    @Setter
    public static class RoleUserVO extends CommonVO {
        private static final long serialVersionUID = 1L;

        private String roleId;
        /** 사용자를 옮겨 오는 원본 권한 그룹 ID (그룹 삭제 시 사용) */
        private String fromRoleId;
        private String roleName;
        private String email;
        private String orgId;
        private String orgNm;
        private String nameKeyword;
        private String createId;
        private String modifyId;
    }

    /** 권한 그룹 사용자 저장 요청 */
    @Getter
    @Setter
    public static class SaveRoleUserVO extends RoleManageVO {
        private static final long serialVersionUID = 1L;

        private List<String> userIdList;
    }
}
