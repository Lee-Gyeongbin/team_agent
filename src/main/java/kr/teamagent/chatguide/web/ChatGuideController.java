package kr.teamagent.chatguide.web;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.chatguide.service.ChatGuideVO;
import kr.teamagent.chatguide.service.impl.ChatGuideServiceImpl;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/chatguide")
public class ChatGuideController extends BaseController<ChatGuideVO> {

    @Autowired
    private ChatGuideServiceImpl chatGuideService;

    @RequestMapping(value = "/greetingList.do")
    @ResponseBody
    public ModelAndView greetingList(ChatGuideVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", chatGuideService.selectChatGuideGreetingList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/noticeList.do")
    @ResponseBody
    public ModelAndView noticeList(ChatGuideVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", chatGuideService.selectChatGuideNoticeList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/errorList.do")
    @ResponseBody
    public ModelAndView errorList(ChatGuideVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", chatGuideService.selectChatGuideErrorList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/maintenanceList.do")
    @ResponseBody
    public ModelAndView maintenanceList(ChatGuideVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", chatGuideService.selectChatGuideMaintenanceList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

}
