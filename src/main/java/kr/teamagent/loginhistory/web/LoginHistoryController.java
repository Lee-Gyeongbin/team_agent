package kr.teamagent.loginhistory.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.loginhistory.service.LoginHistoryVO;
import kr.teamagent.loginhistory.service.impl.LoginHistoryServiceImpl;

@Controller
@RequestMapping("/api")
public class LoginHistoryController extends BaseController {

    @Autowired
    private LoginHistoryServiceImpl loginHistoryService;

    /**
     * 로그인 히스토리 목록 조회
     * @param searchVO userId, ipAddr, firstIndex, recordCountPerPage, dbId
     * @return jsonView (list)
     */
    @RequestMapping(value = "/login/loginHistory/selectLoginHistoryList.do")
    @ResponseBody
    public ModelAndView selectLoginHistoryList(@ModelAttribute("searchVO") LoginHistoryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new java.util.HashMap<>();
        resultMap.put("list", loginHistoryService.selectLoginHistoryList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }
}
