package kr.teamagent.menumanage.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.menumanage.service.MenuManageVO;
import kr.teamagent.menumanage.service.impl.MenuManageServiceImpl;

@Controller
@RequestMapping(value = { "/menumanage" })
public class MenuManageController extends BaseController {

    @Autowired
    private MenuManageServiceImpl menuManageService;

    @RequestMapping(value = "/updateMenuOrder.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateMenuOrder(@RequestBody MenuManageVO.UpdateMenuOrderVO searchVO) throws Exception {
        if (searchVO == null || searchVO.getItems() == null || searchVO.getItems().isEmpty()) {
            return makeFailJsonData("정렬 대상 메뉴가 없습니다.");
        }
        menuManageService.updateMenuOrder(searchVO);
        return makeSuccessJsonData();
    }

}
