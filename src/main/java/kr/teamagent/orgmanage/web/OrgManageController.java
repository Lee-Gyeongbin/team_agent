package kr.teamagent.orgmanage.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * 사용자 목록 조회
     * @param orgId
     * @return jsonView (list)
     * @throws Exception
     */
    @RequestMapping(value = "/selectUserList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView selectUserList(@RequestBody OrgManageVO orgManageVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        String targetOrgId = orgManageVO.getOrgId();
        resultMap.put("list", orgManageService.selectUserList(targetOrgId));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 조직 등록
     * @param orgManageVO
     * @return jsonView (successYn, returnMsg, data: 영향받은 행 수)
     * @throws Exception
     */
    @RequestMapping(value = "/insertOrg.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView insertOrg(@RequestBody OrgManageVO orgManageVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", true);
        resultMap.put("data", orgManageService.insertOrg(orgManageVO));
        return new ModelAndView("jsonView", resultMap);
    }
    
    
    /**
     * 조직 수정
     * @param orgManageVO
     * @return jsonView (successYn, returnMsg, data: 영향받은 행 수)
     * @throws Exception
     */
    @RequestMapping(value = "/updateOrg.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateOrg(@RequestBody OrgManageVO orgManageVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", true);
        resultMap.put("data", orgManageService.updateOrg(orgManageVO));
        return new ModelAndView("jsonView", resultMap);
    }
    
    
    /**
     * 조직 정렬순서 수정
     * @param orgManageVO
     * @return jsonView (successYn, returnMsg, data: 영향받은 행 수)
     * @throws Exception
     */
    @RequestMapping(value = "/updateOrgSortOrder.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateOrgSortOrder(@RequestBody OrgManageVO orgManageVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", true);
        resultMap.put("data", orgManageService.updateOrgSortOrder(orgManageVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 조직 삭제
     * @param requestMap
     * @return jsonView (successYn, returnMsg, data: 영향받은 행 수)
     * @throws Exception
     */
    @RequestMapping(value = "/deleteOrg.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView deleteOrg(@RequestBody Map<String, String> requestMap) throws Exception {
        OrgManageVO orgManageVO = new OrgManageVO();
        orgManageVO.setOrgId(requestMap.get("orgId"));
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", true);
        resultMap.put("data", orgManageService.deleteOrg(orgManageVO));
        return new ModelAndView("jsonView", resultMap);
    }
}

