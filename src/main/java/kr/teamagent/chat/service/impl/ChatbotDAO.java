package kr.teamagent.chat.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.chat.service.ChatbotVO;

@Repository
public class ChatbotDAO extends EgovComAbstractDAO {

    /**
     * 모델 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectModelList(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectModelList", searchVO);
    }

    public int insertChatRoom(ChatbotVO chatbotVO) throws Exception{
        return insert("ai.chatbot.insertChatRoom",chatbotVO);
    }

    public ChatbotVO selectAiDailyUsage(ChatbotVO chatbotVO) throws Exception{
        return selectOne("ai.chatbot.selectAiDailyUsage",chatbotVO);
    }

    /**
     * ai 로그 등록
     * @param dataVO
     * @return
     * @throws Exception
     */
    public int insertAiLog(ChatbotVO dataVO)throws Exception {
        return insert("ai.chatbot.insertAiLog", dataVO);
    }

    /**
     * AI 서비스 사용자별 일일 사용량 등록
     * @param dataVO
     * @return
     * @throws Exception
     */
    public int insertAiDailyUsage(ChatbotVO dataVO)throws Exception {
        return insert("ai.chatbot.insertAiDailyUsage", dataVO);
    }

    /**
     * 지역권한 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectRegnCdList(ChatbotVO searchVO)throws Exception {
        return selectList("ai.chatbot.selectRegnCdList", searchVO);
    }

    /**
     * 관리자 권한 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public ChatbotVO selectAuthFlag(ChatbotVO searchVO)throws Exception {
        return selectOne("ai.chatbot.selectAuthFlag", searchVO);
    }

    /**
     * 답변 만족도 수정
     * @param dataVO
     * @return
     * @throws Exception
     */
    public int saveSatisYn(ChatbotVO dataVO)throws Exception {
        return update("ai.chatbot.updateSatisYn", dataVO);
    }
}
