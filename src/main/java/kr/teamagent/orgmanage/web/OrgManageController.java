package kr.teamagent.orgmanage.web;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.orgmanage.service.OrgManageVO;
import kr.teamagent.orgmanage.service.impl.OrgManageServiceImpl;

@Controller
@RequestMapping(value = { "/orgmanage" })
public class OrgManageController extends BaseController {

    @Autowired
    private OrgManageServiceImpl orgManageService;

    /**
     * 조직 목록 조회
     * @param searchVO
     * @return jsonView (list)
     * @throws Exception
     */
    @RequestMapping(value = "/selectOrgList.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectOrgList(OrgManageVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", orgManageService.selectOrgList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }
}
