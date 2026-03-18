package kr.teamagent.chat.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.common.dao.CmbComAbstractDAO;
// TODO 추후 시연 완료 후 삭제
@Repository
public class ChatbotStatDAO extends CmbComAbstractDAO {
    
    public List<ChatbotVO> selectStatList(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.stat.selectStatList", searchVO);
    }
    public List<ChatbotVO> selectStatDetailList(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.stat.selectStatDetailList", searchVO);
    }
}
