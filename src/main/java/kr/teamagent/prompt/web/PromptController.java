package kr.teamagent.prompt.web;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.prompt.service.PromptVO;
import kr.teamagent.prompt.service.impl.PromptServiceImpl;

@Controller
@RequestMapping("/prompt")
public class PromptController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(PromptController.class);

    @Autowired
    private PromptServiceImpl promptService;

    /**
     * 시스템 프롬프트 목록 조회
     * @return { dataList: PromptVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/system/list.do")
    @ResponseBody
    public ModelAndView systemList() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", promptService.selectSystemPromptList());
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 시스템 프롬프트 등록/수정
     * @param searchVO PromptVO (promptId 기준 upsert, 없으면 자동 생성)
     * @return { data: PromptVO }
     * @throws Exception
     */
    @RequestMapping(value = "/system/save.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView systemSave(@RequestBody PromptVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", promptService.saveSystemPrompt(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 시스템 프롬프트 삭제 (promptId 기준 물리 삭제)
     * @param searchVO promptId 필수
     * @return { data: PromptVO } 삭제된 항목 정보
     * @throws Exception
     */
    @RequestMapping(value = "/system/delete.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView systemDelete(@RequestBody PromptVO searchVO) throws Exception {
        if (searchVO == null || searchVO.getPromptId() == null || searchVO.getPromptId().trim().isEmpty()) {
            return makeFailJsonData("promptId is required");
        }
        promptService.deleteSystemPrompt(searchVO);
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", searchVO);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 금지어/필터링 데이터 조회
     * @return { data: { inputBanWords: BanWordVO[], outputBanWords: BanWordVO[] } }
     * @throws Exception
     */
    @RequestMapping(value = "/filter/data.do")
    @ResponseBody
    public ModelAndView filterData() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", promptService.selectFilterData());
        return new ModelAndView("jsonView", resultMap);
    }

}
