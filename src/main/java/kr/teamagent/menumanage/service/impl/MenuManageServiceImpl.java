package kr.teamagent.menumanage.service.impl;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.menumanage.service.MenuManageVO;

@Service
public class MenuManageServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private MenuManageDAO menuManageDAO;

    public int updateMenuOrder(MenuManageVO.UpdateMenuOrderVO searchVO) throws Exception {
        if (searchVO == null || searchVO.getItems() == null || searchVO.getItems().isEmpty()) {
            return 0;
        }
        return menuManageDAO.updateMenuOrder(searchVO);
    }

}
