package kr.teamagent.menumanage.service.impl;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.menumanage.service.MenuManageVO;

@Service
public class MenuManageServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private MenuManageDAO menuManageDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    /**
     * 메뉴 등록/수정
     * @param saveVO
     * @return
     * @throws Exception
     */
    public int saveMenu(MenuManageVO.SaveMenuVO saveVO) throws Exception {
        boolean isNew = saveVO.getMenuId() == null || saveVO.getMenuId().trim().isEmpty();

        if (isNew) {
            saveVO.setMenuId(keyGenerate.generateTableKey("ME", "tb_menu", "MENU_ID"));
            resolveMenuPath(saveVO);
            resolveMenuSortOrd(saveVO);
        } else {
            saveVO.setMenuId(saveVO.getMenuId().trim());
        }

        return menuManageDAO.saveMenu(saveVO);
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
