package kr.teamagent.menumanage.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import kr.teamagent.common.system.service.impl.MenuPermissionServiceImpl;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.menumanage.service.MenuManageVO;
import kr.teamagent.rolemanage.service.RoleManageVO;
import kr.teamagent.rolemanage.service.impl.RoleManageDAO;

@Service
public class MenuManageServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private MenuManageDAO menuManageDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    @Autowired
    private RoleManageDAO roleManageDAO;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MenuPermissionServiceImpl menuPermissionService;

    public List<RoleManageVO.RoleMenuVO> selectMenuRolePermissions(String menuId) throws Exception {
        requireRoleAssignmentPermission();
        return menuManageDAO.selectMenuRolePermissions(menuId);
    }

    private void requireRoleAssignmentPermission() throws Exception {
        if (!menuPermissionService.canFunc("/menu-manage") || !menuPermissionService.canRead("/role-manage")
                || !menuPermissionService.canFunc("/role-manage")) {
            throw new IllegalArgumentException("메뉴 편집과 권한그룹 편집 권한이 필요합니다.");
        }
    }

    /**
     * 메뉴 등록/수정
     * @param saveVO
     * @return
     * @throws Exception
     */
    public int saveMenu(MenuManageVO.SaveMenuVO saveVO) throws Exception {
        boolean isNew = saveVO.getMenuId() == null || saveVO.getMenuId().trim().isEmpty();
        boolean assignRoles = saveVO.getRolePermissions() != null
                && (!isNew || !saveVO.getRolePermissions().isEmpty());
        if (assignRoles) {
            if (!"Y".equals(saveVO.getIsLeaf()) || !"Y".equals(saveVO.getUseYn())) {
                throw new IllegalArgumentException("사용 중인 최하위 메뉴에만 권한을 지정할 수 있습니다.");
            }
            requireRoleAssignmentPermission();
            return transactionTemplate.execute(status -> {
                try {
                    int result = saveMenuData(saveVO, isNew);
                    if (!isNew) {
                        menuManageDAO.deleteActiveMenuRolePermissions(saveVO.getMenuId());
                    }
                    saveRolePermissions(saveVO);
                    return result;
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("메뉴와 권한그룹을 저장하지 못했습니다.", e);
                }
            });
        }
        return saveMenuData(saveVO, isNew);
    }

    private int saveMenuData(MenuManageVO.SaveMenuVO saveVO, boolean isNew) throws Exception {
        saveVO.setNewMenu(isNew);
        if (isNew) {
            saveVO.setMenuId(keyGenerate.generateTableKey("ME", "tb_menu", "MENU_ID"));
            resolveMenuPath(saveVO);
            resolveMenuSortOrd(saveVO);
        } else {
            saveVO.setMenuId(saveVO.getMenuId().trim());
        }

        return menuManageDAO.saveMenu(saveVO);
    }

    private void saveRolePermissions(MenuManageVO.SaveMenuVO saveVO) throws Exception {
        String userId = SessionUtil.getUserId();
        Set<String> assignedRoleIds = new HashSet<>();
        for (RoleManageVO.RoleMenuVO grant : saveVO.getRolePermissions()) {
            if (grant == null || grant.getRoleId() == null || !assignedRoleIds.add(grant.getRoleId())
                    || !"Y".equals(grant.getReadYn())
                    || !("Y".equals(grant.getFuncYn()) || "N".equals(grant.getFuncYn()))) {
                throw new IllegalArgumentException("권한그룹과 조회·편집 권한을 확인해 주세요.");
            }
            RoleManageVO role = roleManageDAO.selectRole(grant.getRoleId());
            if (role == null || !"Y".equals(role.getUseYn())) {
                throw new IllegalArgumentException("사용 가능한 권한그룹을 선택해 주세요.");
            }
            grant.setMenuId(saveVO.getMenuId());
            grant.setCreateId(userId);
            if (roleManageDAO.insertRoleMenu(grant) != 1) {
                throw new IllegalArgumentException("메뉴 권한을 저장하지 못했습니다.");
            }
        }
    }

    /**
     * 메뉴 경로 설정
     * @param saveVO
     * @throws Exception
     */
    private void resolveMenuPath(MenuManageVO.SaveMenuVO saveVO) throws Exception {
        String parnMenuId = saveVO.getParnMenuId();
        if (parnMenuId != null && !parnMenuId.trim().isEmpty()) {
            MenuManageVO parent = menuManageDAO.selectMenuById(parnMenuId.trim());
            if (parent != null) {
                String parentPath = (parent.getMenuPath() != null && !parent.getMenuPath().trim().isEmpty())
                        ? parent.getMenuPath().trim()
                        : parnMenuId.trim();
                saveVO.setMenuPath(parentPath + "/" + saveVO.getMenuId());
            } else {
                saveVO.setMenuPath(saveVO.getMenuId());
            }
        } else {
            saveVO.setMenuPath(saveVO.getMenuId());
        }
    }

    /**
     * 메뉴 정렬순서 설정
     * @param saveVO
     * @throws Exception
     */
    private void resolveMenuSortOrd(MenuManageVO.SaveMenuVO saveVO) throws Exception {
        int maxSortOrd = menuManageDAO.selectMaxSortOrdByParnMenuId(saveVO.getParnMenuId());
        saveVO.setSortOrd(maxSortOrd + 1);
    }

    /**
     * 메뉴 정렬
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int updateMenuOrder(MenuManageVO.UpdateMenuOrderVO searchVO) throws Exception {
        if (searchVO == null || searchVO.getItems() == null || searchVO.getItems().isEmpty()) {
            return 0;
        }
        return menuManageDAO.updateMenuOrder(searchVO);
    }

}
