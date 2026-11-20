package kr.teamagent.rolemanage.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.rolemanage.service.RoleManageVO;

@Repository
public class RoleManageDAO extends EgovComAbstractDAO {

    /* 권한 그룹 목록 조회 — roleManage.selectRoleList */
    public List<RoleManageVO> selectRoleList() throws Exception {
        return selectList("roleManage.selectRoleList");
    }

    /* 권한 그룹 단건 조회 — roleManage.selectRole */
    public RoleManageVO selectRole(String roleId) throws Exception {
        return (RoleManageVO) selectOne("roleManage.selectRole", roleId);
    }

    /* 권한 그룹 건수 조회 — roleManage.selectRoleCount */
    public int selectRoleCount() throws Exception {
        return (Integer) selectOne("roleManage.selectRoleCount");
    }

    /* 권한명 중복 건수 조회 (본인 제외) — roleManage.selectRoleNameCount */
    public int selectRoleNameCount(RoleManageVO searchVO) throws Exception {
        return (Integer) selectOne("roleManage.selectRoleNameCount", searchVO);
    }

    /* 이동 대상 기본 권한 그룹 ID 조회 — roleManage.selectFallbackRoleId */
    public String selectFallbackRoleId(String roleId) throws Exception {
        return (String) selectOne("roleManage.selectFallbackRoleId", roleId);
    }

    /* 권한 그룹 등록 — roleManage.insertRole */
    public int insertRole(RoleManageVO roleVO) throws Exception {
        return insert("roleManage.insertRole", roleVO);
    }

    /* 권한 그룹 수정 — roleManage.updateRole */
    public int updateRole(RoleManageVO roleVO) throws Exception {
        return update("roleManage.updateRole", roleVO);
    }

    /* 다른 권한 그룹의 기본 적용 해제 — roleManage.resetDefaultRole */
    public int resetDefaultRole(RoleManageVO roleVO) throws Exception {
        return update("roleManage.resetDefaultRole", roleVO);
    }

    /* 권한 그룹 삭제 — roleManage.deleteRole */
    public int deleteRole(String roleId) throws Exception {
        return delete("roleManage.deleteRole", roleId);
    }

    /* 그룹별 메뉴 권한 목록 조회 (전체 메뉴 + 부여 현황) — roleManage.selectRoleMenuList */
    public List<RoleManageVO.RoleMenuVO> selectRoleMenuList(String roleId) throws Exception {
        return selectList("roleManage.selectRoleMenuList", roleId);
    }

    /* 그룹별 메뉴 권한 삭제 — roleManage.deleteRoleMenuByRoleId */
    public int deleteRoleMenuByRoleId(String roleId) throws Exception {
        return delete("roleManage.deleteRoleMenuByRoleId", roleId);
    }

    /* 그룹별 메뉴 권한 등록 (사용 중인 최하위 메뉴만) — roleManage.insertRoleMenu */
    public int insertRoleMenu(RoleManageVO.RoleMenuVO roleMenuVO) throws Exception {
        return insert("roleManage.insertRoleMenu", roleMenuVO);
    }

    /* 권한 그룹 사용자 목록 조회 — roleManage.selectRoleUserList */
    public List<RoleManageVO.RoleUserVO> selectRoleUserList(String roleId) throws Exception {
        return selectList("roleManage.selectRoleUserList", roleId);
    }

    /* 조직(하위 조직 포함) 사용자 목록 조회 — roleManage.selectOrgUserList */
    public List<RoleManageVO.RoleUserVO> selectOrgUserList(RoleManageVO.RoleUserVO searchVO) throws Exception {
        return selectList("roleManage.selectOrgUserList", searchVO);
    }

    /* 사용자별 권한 지정 목록 조회 (엑셀 다운로드·업로드 검증) — roleManage.selectUserRoleList */
    public List<RoleManageVO.RoleUserVO> selectUserRoleList() throws Exception {
        return selectList("roleManage.selectUserRoleList");
    }

    /* 사용자의 권한 지정 전체 삭제 — roleManage.deleteUserRoleByUserId */
    public int deleteUserRoleByUserId(String userId) throws Exception {
        return delete("roleManage.deleteUserRoleByUserId", userId);
    }

    /* 사용자의 특정 권한 그룹 지정 삭제 — roleManage.deleteUserRole */
    public int deleteUserRole(RoleManageVO.RoleUserVO roleUserVO) throws Exception {
        return delete("roleManage.deleteUserRole", roleUserVO);
    }

    /* 권한 그룹 소속 사용자 지정 전체 삭제 — roleManage.deleteUserRoleByRoleId */
    public int deleteUserRoleByRoleId(String roleId) throws Exception {
        return delete("roleManage.deleteUserRoleByRoleId", roleId);
    }

    /* 사용자 권한 지정 등록 (존재하는 사용자만) — roleManage.insertUserRole */
    public int insertUserRole(RoleManageVO.RoleUserVO roleUserVO) throws Exception {
        return insert("roleManage.insertUserRole", roleUserVO);
    }

    /* 신규 사용자에게 기본 적용 권한 그룹 지정 — roleManage.insertDefaultUserRole */
    public int insertDefaultUserRole(RoleManageVO.RoleUserVO roleUserVO) throws Exception {
        return insert("roleManage.insertDefaultUserRole", roleUserVO);
    }

    /* 한 권한 그룹의 사용자를 다른 그룹으로 복사 지정 — roleManage.insertUserRoleFromRole */
    public int insertUserRoleFromRole(RoleManageVO.RoleUserVO roleUserVO) throws Exception {
        return insert("roleManage.insertUserRoleFromRole", roleUserVO);
    }
}
