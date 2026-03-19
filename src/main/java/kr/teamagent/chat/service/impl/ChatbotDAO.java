package kr.teamagent.chat.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.chat.service.ChatbotVO;

@Repository
public class ChatbotDAO extends EgovComAbstractDAO {

    /**
     * CHAT 대화방 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectChatRoomList(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectChatRoomList", searchVO);
    }
    /**
     * 모델 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectModelList(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectModelList", searchVO);
    }

    /**
     * RAG 데이터 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectRagDsList(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectRagDsList", searchVO);
    }

    /**
     * CHAT 대화방 tableData 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectTableDataList(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectTableDataList", searchVO);
    }
    /**
     * 데이터마트 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectDmList(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectDmList", searchVO);
    }
    public List<ChatbotVO> selectChatDocList(ChatbotVO searchVO) throws Exception{
        return selectList("ai.chatbot.selectChatDocList", searchVO);
    }
    public int insertChatRoom(ChatbotVO chatbotVO) throws Exception{
        return insert("ai.chatbot.insertChatRoom",chatbotVO);
    }

    public List<ChatbotVO> selectChatLogList(ChatbotVO searchVO) throws Exception{
        return selectList("ai.chatbot.selectChatLogList", searchVO);
    }
    public int insertChatLog(ChatbotVO chatbotVO) throws Exception{
        return insert("ai.chatbot.insertChatLog", chatbotVO);
    }
    public int updateChatRoomLastChatDt(ChatbotVO chatbotVO) throws Exception{
        return update("ai.chatbot.updateChatRoomLastChatDt", chatbotVO);
    }
    public int insertChatRef(ChatbotVO chatbotVO) throws Exception{
        return insert("ai.chatbot.insertChatRef", chatbotVO);
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
