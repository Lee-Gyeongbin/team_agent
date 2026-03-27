package kr.teamagent.chat.web;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/")
public class ChatbotController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ChatbotController.class);

    @Autowired
    private ChatbotServiceImpl chatbotService;

    

    /**
     * 모델 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value="/ai/chatbot/selectModelList.do")
    @ResponseBody
    public ModelAndView selectModelList(ChatbotVO searchVO)throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();

        resultMap.put("modelList", chatbotService.selectModelList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * RAG 데이터 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value="/ai/chatbot/selectRagDsList.do")
    @ResponseBody
    public ModelAndView selectRagDsList(ChatbotVO searchVO)throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("subOptionList", chatbotService.selectRagDsList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 데이터마트 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value="/ai/chatbot/selectDmList.do")
    @ResponseBody
    public ModelAndView selectDmList(ChatbotVO searchVO)throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("subOptionList", chatbotService.selectDmList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * CHAT 대화방 등록
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value="/ai/chatbot/createChatRoom.do")
    @ResponseBody
    public ModelAndView createChatRoom(@RequestBody ChatbotVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        searchVO.setUserId(SessionUtil.getUserId());
        resultMap.put("data", chatbotService.createChatRoom(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }
    
    @RequestMapping(value="/ai/chatbot/selectChatRoomList.do")
    @ResponseBody
    public ModelAndView selectChatRoomList(ChatbotVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", chatbotService.selectChatRoomList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * CHAT 대화방 로그 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/ai/chatbot/selectChatLogList.do")
    @ResponseBody
    public ModelAndView selectChatLogList(ChatbotVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", chatbotService.selectChatLogList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/chatbot/selectChatDocList.do")
    @ResponseBody
    public ModelAndView selectChatDocList(ChatbotVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", chatbotService.selectChatDocList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/chatbot/selectTableDataList.do")
    @ResponseBody
    public ModelAndView selectTableDataList(ChatbotVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        // TODO 추후 시연 완료 후 삭제
        resultMap.put("statList", chatbotService.selectStatList(searchVO));
        resultMap.put("statDetailList", chatbotService.selectStatDetailList(searchVO));
        resultMap.put("list", chatbotService.selectTableDataList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping("/ai/chatbot/saveSatisYn.do")
    public @ResponseBody Map<String, Object> saveSatisYn(@RequestBody ChatbotVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            // 유효성 검사
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }

            resultMap = chatbotService.saveSatisYn(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

    @RequestMapping("/ai/chatbot/saveKnowledge.do")
    public @ResponseBody Map<String, Object> saveKnowledge(@RequestBody ChatbotVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }

            dataVO.setUserId(SessionUtil.getUserId());
            resultMap = chatbotService.saveKnowledge(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

    @RequestMapping("/ai/chatbot/deleteChatRoom.do")
    public @ResponseBody Map<String, Object> deleteChatRoom(@RequestBody ChatbotVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }

            resultMap = chatbotService.deleteChatRoom(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

    @RequestMapping("/ai/chatbot/renameChatRoom.do")
    public @ResponseBody Map<String, Object> renameChatRoom(@RequestBody ChatbotVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }

            resultMap = chatbotService.renameChatRoom(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

    @RequestMapping("/ai/chatbot/pinChatRoom.do")
    public @ResponseBody Map<String, Object> pinChatRoom(@RequestBody ChatbotVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }

            dataVO.setUserId(SessionUtil.getUserId());
            resultMap = chatbotService.pinChatRoom(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

    /**
     * 지식 카테고리 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value="/ai/chatbot/selectKnowledgeList.do")
    @ResponseBody
    public ModelAndView selectKnowledgeList(ChatbotVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", chatbotService.selectKnowledgeList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }
    
    /**
         * 대화방 공유 토큰 발급 (TB_SHARE_TOKEN 저장, 만료 3일)
         */
    @RequestMapping(value = "/ai/chatbot/createShareToken.do")
    @ResponseBody
    public ModelAndView createShareToken(@RequestBody ChatbotVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>(chatbotService.createShareToken(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 대화방 공유 토큰으로 채팅 로그 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/ai/chatbot/selectSharedChatLogList.do")
    @ResponseBody
    public ModelAndView selectSharedChatLogList(ChatbotVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>(chatbotService.selectSharedChatLogList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }
}
