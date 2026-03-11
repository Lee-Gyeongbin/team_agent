package kr.teamagent.codes.web;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;
import kr.teamagent.codes.service.CodesVO;
import kr.teamagent.codes.service.impl.CodesServiceImpl;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/codes")
public class CodesController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(CodesController.class);

    @Autowired
    private CodesServiceImpl codesService;

    /**
     * 코드 그룹 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/groupList.do")
    @ResponseBody
    public ModelAndView groupList(CodesVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", codesService.selectGroupList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 코드 목록 조회
     * @param searchVO codeGrpId 필수
     * @return codesVO, dataList
     * @throws Exception
     */
    @RequestMapping(value = "/list.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView list(@RequestBody CodesVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("codesVO", searchVO);
        resultMap.put("dataList", codesService.selectCodeList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 코드 그룹 등록/수정
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/saveGroup.do")
    @ResponseBody
    public Map<String, Object> saveGroup(@RequestBody CodesVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            resultMap.put("data", codesService.saveGroup(searchVO));
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

    /**
     * 코드 등록/수정
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/saveCode.do")
    @ResponseBody
    public Map<String, Object> saveCode(@RequestBody CodesVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            resultMap.put("data", codesService.saveCode(searchVO));
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

    /**
     * 코드 정렬순서 일괄 수정
     * @param codesVO { codeGrpId, items: [{ codeId, sortOrder }] }
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/updateSortOrder.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> updateSortOrder(@RequestBody CodesVO codesVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            resultMap.put("data", codesService.updateSortOrder(codesVO));
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

}
