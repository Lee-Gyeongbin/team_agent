package kr.teamagent.prompt.web;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
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

}
