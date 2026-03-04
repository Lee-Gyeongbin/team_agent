package kr.teamagent.chat.service;

import java.util.List;
import java.util.Map;

import org.springframework.web.socket.WebSocketSession;

import kr.teamagent.chat.socket.ChatbotWebSocketHandler;

/**
 * 챗봇 서비스 인터페이스
 * 각 서비스 타입별 AI API 호출 및 스트리밍 처리 (WebSocket 방식)
 */
public interface ChatbotService {
    
    /**
     * AI API를 호출하고 스트리밍 응답을 클라이언트로 전달 (WebSocket 방식)
     * 
     * @param session WebSocket Session
     * @param query 사용자의 질의
     * @param threadId 고유값 ID (초기 요청시 공백)
     * @param userId 사용자 식별 아이디
     * @param svcTy 서비스 타입 (C: chatGPT, M: 매뉴얼, S: 통계질의)
     * @param callback 스트리밍 콜백
     * @throws Exception
     */
    void streamAiResponseWebSocket(WebSocketSession session, String query, String threadId, String userId, String svcTy, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception;

    ChatbotVO selectAiDailyUsage(ChatbotVO chatbotVO) throws Exception;

    Map<String, Object> saveSatisYn(ChatbotVO chatbotVO) throws Exception;
}

