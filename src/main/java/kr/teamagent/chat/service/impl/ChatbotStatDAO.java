package kr.teamagent.chat.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.chat.service.ChatbotVO;

@Repository
public class ChatbotStatDAO extends EgovComAbstractDAO {
    
    public List<ChatbotVO> selectStatList(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.stat.selectStatList", searchVO);
    }
    public List<ChatbotVO> selectStatDetailList(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.stat.selectStatDetailList", searchVO);
    }
}
