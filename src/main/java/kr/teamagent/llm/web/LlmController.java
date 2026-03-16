package kr.teamagent.llm.web;

import java.util.HashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.llm.service.LlmVO;
import kr.teamagent.llm.service.impl.LlmServiceImpl;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/llm")
public class LlmController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(LlmController.class);

    @Autowired
    private LlmServiceImpl llmService;

    /**
     * LLM 모델 목록 조회
     * @return { list: LlmVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/list.do")
    @ResponseBody
    public ModelAndView list() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", llmService.selectLlmList());
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * LLM 모델 등록/수정
     * modelId가 "auto" 또는 비어있으면 신규 등록, 그 외에는 수정
     * @param llmVO 모델 전체 정보 (TB_LLM_MDL, TB_LLM_MDL_API, TB_LLM_MDL_PARAM, TB_LLM_MDL_LMT, TB_LLM_MDL_ACCESS)
     * @return { data: LlmVO }
     * @throws Exception
     */
    @RequestMapping(value = "/save.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView save(@RequestBody LlmVO llmVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", llmService.saveLlm(llmVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * LLM 모델 SORT_ORDER 일괄 업데이트
     * @param orderList [{ modelId, sortOrder }, ...]
     * @return { data: null }
     * @throws Exception
     */
    @RequestMapping(value = "/order.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView order(@RequestBody List<LlmVO> orderList) throws Exception {
        llmService.updateModelOrder(orderList);
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", null);
        return makeSuccessJsonData(resultMap);
    }

    /**
     * LLM Provider 목록 조회 (옵션용)
     * @return { dataList: LlmProviderVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/provider/list.do")
    @ResponseBody
    public ModelAndView providerList() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", llmService.selectProviderList());
        return new ModelAndView("jsonView", resultMap);
    }

}
