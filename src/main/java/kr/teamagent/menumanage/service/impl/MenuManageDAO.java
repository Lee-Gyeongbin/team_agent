package kr.teamagent.menumanage.service.impl;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.menumanage.service.MenuManageVO;

@Repository
public class MenuManageDAO extends EgovComAbstractDAO {

    public int saveMenu(MenuManageVO.SaveMenuVO saveVO) throws Exception {
        return insert("menuManage.saveMenu", saveVO);
    }

    public int updateMenuOrder(MenuManageVO.UpdateMenuOrderVO searchVO) throws Exception {
        return update("menuManage.updateMenuOrder", searchVO);
    }

}
