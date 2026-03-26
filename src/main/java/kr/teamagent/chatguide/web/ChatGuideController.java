package kr.teamagent.chatguide.web;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.chatguide.service.ChatGuideVO;
import kr.teamagent.chatguide.service.impl.ChatGuideServiceImpl;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/chatguide")
public class ChatGuideController extends BaseController<Object> {

    @Autowired
    private ChatGuideServiceImpl chatGuideService;

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ModelAndView handleIllegalArgument(IllegalArgumentException ex) {
        return makeFailJsonData(ex.getMessage());
    }

    /**
     * 챗봇가이드 인사멘트 목록 조회
     * @param searchVO ChatGuideVO
     * @return ModelAndView
     * @throws Exception
     */
    @RequestMapping(value = "/greetingList.do")
    @ResponseBody
    public ModelAndView greetingList(ChatGuideVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", chatGuideService.selectChatGuideGreetingList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 챗봇가이드 인사멘트 저장
     * @param searchVO ChatGuideVO
     * @return { result, msg, data: ChatGuideVO }
     * @throws Exception
     */
    @RequestMapping(value = "/greetingSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView greetingSave(@RequestBody ChatGuideVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", chatGuideService.insertChatGuideGreetingList(searchVO));
        return makeSuccessJsonData(resultMap);
    }

    /**
     * 챗봇가이드 안내멘트 목록 조회
     * @param searchVO ChatGuideVO
     * @return ModelAndView
     * @throws Exception
     */
    @RequestMapping(value = "/noticeList.do")
    @ResponseBody
    public ModelAndView noticeList(ChatGuideVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", chatGuideService.selectChatGuideNoticeList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 챗봇가이드 안내멘트 저장(묶음)
     * @param requestVO NoticeSaveVO
     * @return { result, msg, data: NoticeSaveVO }
     * @throws Exception
     */
    @RequestMapping(value = "/noticeSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView noticeSave(@RequestBody ChatGuideVO.NoticeSaveVO requestVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", chatGuideService.saveNoticeGroups(requestVO));
        return makeSuccessJsonData(resultMap);
    }

    /**
     * 챗봇가이드 오류메시지 목록 조회
     * @param searchVO ChatGuideVO
     * @return ModelAndView
     * @throws Exception
     */
    @RequestMapping(value = "/errorMessageList.do")
    @ResponseBody
    public ModelAndView errorMessageList(ChatGuideVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", chatGuideService.selectChatGuideErrorMessageListGrouped(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 챗봇가이드 오류메시지 저장(묶음)
     * @param requestVO ErrorMessageSaveVO
     * @return { result, msg, data: ErrorMessageSaveVO }
     * @throws Exception
     */
    @RequestMapping(value = "/errorMessageSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView errorMessageSave(@RequestBody ChatGuideVO.ErrorMessageSaveVO requestVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", chatGuideService.saveErrorMessageGroups(requestVO));
        return makeSuccessJsonData(resultMap);
    }

    /**
     * 챗봇가이드 점검/장애 목록 조회
     * @param searchVO ChatGuideVO
     * @return ModelAndView
     * @throws Exception
     */
    @RequestMapping(value = "/maintenanceList.do")
    @ResponseBody
    public ModelAndView maintenanceList(ChatGuideVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", chatGuideService.selectChatGuideMaintenanceList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 챗봇가이드 점검/장애 묶음 저장
     * @param requestVO MaintenanceSaveVO
     * @return { result, msg, data: MaintenanceSaveVO }
     * @throws Exception
     */
    @RequestMapping(value = "/maintenanceSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView maintenanceSave(@RequestBody ChatGuideVO.MaintenanceSaveVO requestVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", chatGuideService.saveMaintenanceGroups(requestVO));
        return makeSuccessJsonData(resultMap);
    }

}
