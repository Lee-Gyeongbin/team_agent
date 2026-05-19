package kr.teamagent.orgmanage.web;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.util.ExcelUtil;
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

    /**
     * 조직 엑셀 다운로드
     */
    @RequestMapping(value = "/downloadOrgExcel.do", method = RequestMethod.GET)
    public void downloadOrgExcel(HttpServletResponse response) throws Exception {
        orgManageService.downloadOrgExcel(response);
    }

    /**
     * 조직 엑셀 업로드
     * @return jsonView (successYn, data: successCount/failDetails 등)
     */
    @RequestMapping(value = "/uploadOrgExcel.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView uploadOrgExcel(@RequestParam("uploadFile") MultipartFile uploadFile) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        Map<String, Object> uploadResult = orgManageService.uploadOrgExcel(uploadFile);
        boolean hasUploadFail = ExcelUtil.hasUploadFailures(uploadResult);
        resultMap.put("successYn", !hasUploadFail);
        if (hasUploadFail) {
            Object returnMsg = uploadResult.get("returnMsg");
            resultMap.put("returnMsg", returnMsg != null ? returnMsg : "엑셀 업로드 검증에 실패했습니다.");
        }
        resultMap.put("data", uploadResult);
        return new ModelAndView("jsonView", resultMap);
    }
}

