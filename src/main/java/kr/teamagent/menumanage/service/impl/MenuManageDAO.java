package kr.teamagent.menumanage.service.impl;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.menumanage.service.MenuManageVO;

@Repository
public class MenuManageDAO extends EgovComAbstractDAO {

    public int saveMenu(MenuManageVO.SaveMenuVO saveVO) throws Exception {
        return insert("menuManage.saveMenu", saveVO);
    }

    public MenuManageVO selectMenuById(String menuId) throws Exception {
        return (MenuManageVO) selectOne("menuManage.selectMenuById", menuId);
    }

    public int selectMaxSortOrdByParnMenuId(String parnMenuId) throws Exception {
        Integer result = (Integer) selectOne("menuManage.selectMaxSortOrdByParnMenuId", parnMenuId);
        return result != null ? result : 0;
    }

    public int updateMenuOrder(MenuManageVO.UpdateMenuOrderVO searchVO) throws Exception {
        return update("menuManage.updateMenuOrder", searchVO);
    }

}
