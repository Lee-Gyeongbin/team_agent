package kr.teamagent.chat.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.chat.service.ChatbotVO;

@Repository
public class ChatbotDAO extends EgovComAbstractDAO {

    /**
     * 채팅 에이전트 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectAgentListForChat(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectAgentListForChat", searchVO);
    }

    /**
     * Agent ID 목록으로 서브 설정 일괄 조회 (TB_AGT_SUB_CFG)
     * @param searchVO agentIdList
     * @return
     * @throws Exception
     */
    public List<ChatbotVO.AgtSubCfgVO> selectAgentSubCfgListByAgentIds(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectAgentSubCfgListByAgentIds", searchVO);
    }

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
     * Agent 필터 목록 조회 (USE_YN 무관 전체)
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectAgtFilterList() throws Exception {
        return selectList("ai.chatbot.selectAgtFilterList");
    }

    /** 대화방이 해당 사용자에게 속하는지 확인 */
    public int countChatRoomOwnedByUser(ChatbotVO searchVO) throws Exception {
        Integer cnt = selectOne("ai.chatbot.countChatRoomOwnedByUser", searchVO);
        return cnt != null ? cnt.intValue() : 0;
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

    public ChatbotVO selectApiUrlEndpoint(ChatbotVO searchVO) throws Exception{
        return selectOne("ai.chatbot.selectApiUrlEndpoint", searchVO);
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

    public List<ChatbotVO.KnowledgeItem> selectKnowledgeList(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectKnowledgeList", searchVO);
    }

    public int pinChatRoom(ChatbotVO dataVO) throws Exception {
        return update("ai.chatbot.updatePinChatRoom", dataVO);
    }

    public int renameChatRoom(ChatbotVO dataVO) throws Exception {
        return update("ai.chatbot.updateChatRoomTitle", dataVO);
    }

    public int deleteChatRoom(ChatbotVO dataVO) throws Exception {
        return delete("ai.chatbot.deleteChatRoom", dataVO);
    }
    public int deleteChatLog(ChatbotVO dataVO) throws Exception {
        return delete("ai.chatbot.deleteChatLog", dataVO);
    }
    public int deleteChatRef(ChatbotVO dataVO) throws Exception {
        return delete("ai.chatbot.deleteChatRef", dataVO);
    }

    public ChatbotVO selectChatLogByLogId(ChatbotVO searchVO) throws Exception {
        return selectOne("ai.chatbot.selectChatLogByLogId", searchVO);
    }

    public Integer selectMaxSortOrd(ChatbotVO searchVO) throws Exception {
        return selectOne("ai.chatbot.selectMaxSortOrd", searchVO);
    }

    public int updateKnowledgeSortOrdForPrepend(ChatbotVO dataVO) throws Exception {
        return update("ai.chatbot.updateKnowledgeSortOrdForPrepend", dataVO);
    }

    public int insertKnowledgeCard(ChatbotVO dataVO) throws Exception {
        return insert("ai.chatbot.insertKnowledgeCard", dataVO);
    }

    /**
     * 공유 토큰 등록 (TB_SHARE_TOKEN)
     */
    public int insertShareToken(ChatbotVO dataVO) throws Exception {
        return insert("ai.chatbot.insertShareToken", dataVO);
    }

    /**
     * 대화방 첨부파일 존재 여부 조회 (Y/N)
     */
    public String selectHasAttachmentByRoomId(ChatbotVO searchVO) throws Exception {
        return selectOne("ai.chatbot.selectHasAttachmentByRoomId", searchVO);
    }

    /**
     * 만료되지 않은 공유 토큰에 매핑된 ROOM_ID
     */
    public ChatbotVO selectShareTokenValidRoomId(ChatbotVO searchVO) throws Exception {
        return selectOne("ai.chatbot.selectShareTokenValidRoomId", searchVO);
    }

    /**
     * 공유 토큰 행 존재 여부 (만료/무효 구분)
     */
    public int countShareTokenByToken(ChatbotVO searchVO) throws Exception {
        Integer cnt = selectOne("ai.chatbot.countShareTokenByToken", searchVO);
        return cnt != null ? cnt : 0;
    }

    /** 공유 원본 ROOM의 채팅 로그 (복사 INSERT용, 시간순) */
    public List<ChatbotVO> selectChatLogsForShareCopy(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectChatLogsForShareCopy", searchVO);
    }

    /** 공유 원본 ROOM에 연결된 TB_CHAT_REF 목록 */
    public List<ChatbotVO> selectChatRefsForShareCopyRoom(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectChatRefsForShareCopyRoom", searchVO);
    }

    /** 공유 원본 ROOM의 TB_CHAT_LOG에 연결된 TB_CHAT_FILE (복사 INSERT용, LOG_ID 오름차순) */
    public List<ChatbotVO> selectChatFilesForShareCopyRoom(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectChatFilesForShareCopyRoom", searchVO);
    }

    /** 공유 로그 복사 시 첨부 행 INSERT (실파일 재사용, CREATE_USER_ID = 원본 업로더) */
    public int insertChatFileShareCopy(ChatbotVO chatbotVO) throws Exception {
        return insert("ai.chatbot.insertChatFileShareCopy", chatbotVO);
    }

    /**
     * 채팅 파일 저장
     * @param chatbotVO
     * @return
     * @throws Exception
     */
    public int saveChatFile(ChatbotVO chatbotVO) throws Exception {
        return insert("ai.chatbot.insertChatFile", chatbotVO);
    }

    /**
     * 채팅 파일 orphan 처리
     * @param chatbotVO chatFileIdList 필수
     * @return 처리된 행 수
     * @throws Exception
     */
    public int markChatFileOrphan(ChatbotVO chatbotVO) throws Exception {
        return update("ai.chatbot.updateChatFileOrphan", chatbotVO);
    }

    /**
     * 첨부파일에 LOG_ID 연결 (로그 저장 성공 시 호출)
     * @param chatbotVO chatFileIdList + logId 필수
     * @return 처리된 행 수
     * @throws Exception
     */
    public int linkChatFilesToLog(ChatbotVO chatbotVO) throws Exception {
        return update("ai.chatbot.updateChatFileLogId", chatbotVO);
    }

    /**
     * 채팅 첨부 단건 조회 (대화방 소유 + 첨부 CREATE_USER 일치 또는 CREATE_USER 미기록 레거시)
     */
    public ChatbotVO selectChatFileOwnedByUser(ChatbotVO searchVO) throws Exception {
        return selectOne("ai.chatbot.selectChatFileOwnedByUser", searchVO);
    }

    /**
     * 채팅 첨부 단건 조회 (사용자 검증 없음 — 공유 페이지 전용)
     */
    public ChatbotVO selectChatFileById(ChatbotVO searchVO) throws Exception {
        return selectOne("ai.chatbot.selectChatFileById", searchVO);
    }

    /**
     * 사용자 뉴스 관심 카테고리 조회
     */
    public ChatbotVO selectUserNewsInterestCategory(ChatbotVO searchVO) throws Exception {
        return selectOne("ai.chatbot.selectUserNewsInterestCategory", searchVO);
    }

    /**
     * 사용자 뉴스 관심 카테고리 등록/수정
     */
    public int upsertUserNewsInterestCategories(ChatbotVO searchVO) throws Exception {
        return insert("ai.chatbot.upsertUserNewsInterestCategories", searchVO);
    }

    public List<ChatbotVO> selectChatFileDelete(ChatbotVO param) throws Exception {
        return selectList("ai.chatbot.selectChatFileDelete", param);
    }

    public int deleteChatFile(ChatbotVO param) throws Exception {
        return delete("ai.chatbot.deleteChatFile", param);
    }
    /**
     * 데이터셋에 속한 문서 파일명 목록 조회 (TB_DS_DOC → TB_DOC_FILE)
     */
    public List<ChatbotVO> selectDatasetDocFileNames(ChatbotVO searchVO) throws Exception {
        return selectList("ai.chatbot.selectDatasetDocFileNames", searchVO);
    }
}
