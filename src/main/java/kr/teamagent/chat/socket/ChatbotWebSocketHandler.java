package kr.teamagent.chat.socket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.security.service.UserVO;

/**
 * 챗봇 WebSocket 핸들러
 * 양방향 실시간 통신을 위한 WebSocket 처리
 */
@Component
public class ChatbotWebSocketHandler extends TextWebSocketHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatbotWebSocketHandler.class);
    
    // 사용자 ID -> WebSocketSession 관리
    private static final ConcurrentHashMap<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();
    
    @Autowired(required = false)
    private ChatbotServiceImpl chatbotService;
    
    // 스트리밍 응답 처리를 위한 스레드 풀
    private ExecutorService executorService;

    /**
     * Spring은 연결 생명주기 이벤트에 따라 다음 메서드들을 자동 호출
     * afterConnectionEstablished : 연결 성공 시
     * handleTextMessage : 메시지 수신 시
     * afterConnectionClosed : 연결 종료 시
     */
    
    /**
     * 스레드 풀 초기화
     * 서버 시작 시 실행
     */
    @PostConstruct
    public void init() {
        // 스트리밍 응답 처리를 위한 스레드 풀 생성
        // 최대 20개의 스레드로 동시 처리 (필요에 따라 조정 가능)
        executorService = Executors.newFixedThreadPool(20);
        logger.info("챗봇 WebSocket 스레드 풀 초기화 완료 (최대 스레드 수: 20)");
    }
    
    /**
     * 스레드 풀 종료
     * 서버 종료 시 실행
     */
    @PreDestroy
    public void destroy() {
        if (executorService != null) {
            executorService.shutdown();
            logger.info("챗봇 WebSocket 스레드 풀 종료");
        }
    }
    
    /**
     * 스레드 풀 가져오기 (지연 초기화)
     * null이면 초기화 후 반환
     */
    private synchronized ExecutorService getExecutorService() {
        // 스레드 풀이 없다면 생성 후 반환 (정상 흐름에서는 안 탐)
        if (executorService == null) {
            executorService = Executors.newFixedThreadPool(20);
            logger.info("챗봇 WebSocket 스레드 풀 지연 초기화 완료 (최대 스레드 수: 20)");
        }
        // 서버 기동 시 @PostConstruct 에서 이미 만들어졌다면 그걸 그대로 반환
        return executorService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, Object> attributes = session.getAttributes();
        UserVO user = (UserVO) attributes.get("loginVO");
        
        if (user != null) {
            String userId = (String) session.getAttributes().get("userId");
            String uniqueUserId = user.getUniqueUserId();
            
            // 세션 저장
            sessionMap.put(uniqueUserId, session);
            
            logger.info("챗봇 WebSocket 연결 수립: userId={}, uniqueUserId={}, sessionId={}", 
                    userId, uniqueUserId, session.getId());
            
            // 연결 성공 메시지 전송
            sendMessage(session, createMessage("connected", "WebSocket 연결이 성공했습니다.", null));
        } else {
            logger.warn("사용자 정보가 없어 WebSocket 연결을 종료합니다.");
            session.close(CloseStatus.NOT_ACCEPTABLE);
        }
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Map<String, Object> attributes = session.getAttributes();
        UserVO user = (UserVO) attributes.get("loginVO");
        
        if (user != null) {
            String uniqueUserId = user.getUniqueUserId();
            sessionMap.remove(uniqueUserId);
            
            logger.info("챗봇 WebSocket 연결 종료: uniqueUserId={}, sessionId={}", 
                    uniqueUserId, session.getId());
        }
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            // 클라이언트로부터 받은 메시지 파싱
            JSONParser parser = new JSONParser();
            JSONObject messageObj = (JSONObject) parser.parse(message.getPayload());
            
            String messageType = (String) messageObj.get("type");
            
            if ("question".equals(messageType)) {
                // 질문 처리
                handleQuestion(session, messageObj);
            } else {
                logger.warn("알 수 없는 메시지 타입: {}", messageType);
            }
            
        } catch (Exception e) {
            logger.error("메시지 처리 중 오류 발생", e);
            sendMessage(session, createMessage("error", "메시지 처리 중 오류가 발생했습니다.", null));
        }
    }
    
    /**
     * 질문 처리 및 스트리밍 응답
     */
    private void handleQuestion(WebSocketSession session, JSONObject messageObj) {
        try {
            String query = toStr(messageObj.get("query"));
            String threadId = toStr(messageObj.get("threadId"));
            String svcTy = toStr(messageObj.get("svcTy"));
            String refId = toStr(messageObj.get("refId"));
            String modelId = toStr(messageObj.get("modelId"));
            String agentId = toStr(messageObj.get("agentId"));
            if (modelId == null || modelId.isEmpty()) {
                sendMessage(session, createMessage("error", "모델 ID가 지정되지 않았습니다.", null));
                return;
            }
            if (!"C".equals(svcTy) && (refId == null || refId.isEmpty())) {
                sendMessage(session, createMessage("error", "참조 문서 ID가 지정되지 않았습니다.", null));
                return;
            }
            
            if (query == null || query.isEmpty()) {
                sendMessage(session, createMessage("error", "질문 내용이 없습니다.", null));
                return;
            }
            
            if (svcTy == null || svcTy.isEmpty()) {
                sendMessage(session, createMessage("error", "서비스 타입이 지정되지 않았습니다.", null));
                return;
            }
            // 사용자 ID 가져오기
            String userId = (String) session.getAttributes().get("userId");

            // 첨부파일 chatFileId 목록 파싱
            List<Long> attachmentFileIds = new ArrayList<>();
            Object attachmentsObj = messageObj.get("attachments");
            if (attachmentsObj instanceof JSONArray) {
                for (Object item : (JSONArray) attachmentsObj) {
                    if (item instanceof JSONObject) {
                        Object fileIdObj = ((JSONObject) item).get("chatFileId");
                        if (fileIdObj instanceof Number) {
                            attachmentFileIds.add(((Number) fileIdObj).longValue());
                        } else if (fileIdObj instanceof String) {
                            try {
                                attachmentFileIds.add(Long.parseLong((String) fileIdObj));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            logger.info("질문 수신: query={}, threadId={}, svcTy={}, refId={}, userId={}, agentId={}, attachments={}",
                    query, threadId, svcTy, refId, userId, agentId, attachmentFileIds.size());

            // 질문 수신 확인 메시지 전송
            sendMessage(session, createMessage("question_received", "질문을 받았습니다.", null));

            /**
             * 스트리밍 응답 처리 (스레드 풀에서 실행)
             *
             * [스레드 풀에서 실행하는 이유]
             * 1. WebSocket 처리 스레드와 분리하기 위함
             *    - WebSocket 스레드는 소수의 워커 스레드로 관리되며,
             *      클라이언트와의 연결 유지(Ping/Pong, 메시지 수신 등)를 담당하므로
             *      장시간 블로킹되면 안 됨.
             *
             * 2. AI 스트리밍 작업 특성 때문
             *    - AI API 호출 및 응답 스트리밍은 수 초~수십 초 동안 지속되며,
             *      chunk 단위로 데이터를 계속 수신·처리해야 하는 장시간 작업임.
             *
             * 따라서,
             * 클라이언트 <-> 서버는 WebSocket으로 연결을 유지한 상태에서
             * 서버는 WebSocket 스레드를 차단하지 않도록
             * AI API 요청 및 응답 스트리밍을 별도의 Thread Pool에서 처리하고,
             * 해당 결과를 WebSocket을 통해 비동기적으로 클라이언트에 전송한다.
             */
            final List<Long> finalAttachmentFileIds = attachmentFileIds;
            getExecutorService().execute(() -> {
                try {
                    // 해당 작업을 execute가 스레드 풀에 던져서 실행하게 함.(스레드가 다 차 있을 경우엔 큐에 대기)
                    streamResponse(session, query, threadId, userId, svcTy, modelId, refId, agentId, finalAttachmentFileIds);
                } catch (Exception e) {
                    logger.error("스트리밍 응답 처리 중 오류", e);
                    sendMessage(session, createMessage("error", "응답 처리 중 오류가 발생했습니다.", null));
                }
            });
            
        } catch (Exception e) {
            logger.error("질문 처리 중 오류", e);
            sendMessage(session, createMessage("error", "질문 처리 중 오류가 발생했습니다.", null));
        }
    }
    
    /**
     * 스트리밍 응답 처리
     */
    private void streamResponse(WebSocketSession session, String query, String threadId, String userId, String svcTy, String modelId, String refId, String agentId, List<Long> attachmentFileIds) throws Exception {

        /**
         * 스트리밍 응답 처리를 위한 콜백 인터페이스
         *
         * AI 응답은 한 번에 완료되지 않고,
         * chunk(토큰/문자) 단위의 중간 결과가 지속적으로 전달되므로
         * 단일 반환값이 아닌 콜백 방식으로 응답을 처리한다.
         *
         * 각 콜백 메서드는 스트리밍 진행 상태에 따라 호출된다.
         *  - onChunk    : 새로운 응답 조각(chunk) 수신 시
         *  - onComplete : 스트리밍 종료 시 (최종 응답 완성)
         *  - onError    : 스트리밍 중 오류 발생 시
         *
         * 이 인터페이스는 AI 응답 수신 로직과
         * 응답 전달 방식(WebSocket, SSE 등)을 분리하기 위해 사용된다.
         * ChatbotService는 콜백 호출만 담당하고,
         * 실제 응답 전송 방식은 구현체(WebSocketHandler)에서 결정한다.
         */
        ChatbotStreamingCallback callback = new ChatbotStreamingCallback() {
            @Override
            public void onChunk(String content, String accumulated, String chunkEvent) {
                // chunk 메시지: content는 새로 추가된 글자만 전송 (GPT처럼 한 글자씩 실시간 표시)
                // chunkEvent가 있으면(예: answer_source) 구조화 청크 — 클라이언트가 본문과 분리 처리
                JSONObject message = new JSONObject();
                message.put("type", "chunk");
                message.put("content", content);
                if (chunkEvent != null && !chunkEvent.isEmpty()) {
                    message.put("chunkEvent", chunkEvent);
                    message.put("accumulated", accumulated);
                }
                sendMessage(session, message.toJSONString());
            }
            
            @Override
            public void onComplete(String content, String docFileId, String page, List<Integer> viewPage, String responseThreadId, String logId, String tableData, String chartOption) {
                // complete 메시지: 최종 내용과 threadId, logId 전송
                JSONObject message = new JSONObject();
                message.put("type", "complete");
                message.put("content", content);
                message.put("docFileId", docFileId);
                message.put("page", page);
                message.put("threadId", responseThreadId != null ? responseThreadId : "");
                if (viewPage != null && !viewPage.isEmpty()) {
                    JSONArray viewPageArr = new JSONArray();
                    viewPageArr.addAll(viewPage);
                    message.put("viewPage", viewPageArr);
                }
                if (logId != null) {
                    message.put("logId", logId);
                }
                if (tableData != null) {
                    message.put("tableData", tableData);
                }
                if (chartOption != null) {
                    message.put("chartOption", chartOption);
                }
                sendMessage(session, message.toJSONString());
            }
            
            @Override
            public void onError(String error) {
                sendMessage(session, createMessage("error", error, null));
            }
        };
        
        // ChatbotService를 통해 스트리밍 응답 받기
        chatbotService.streamAiResponseWebSocket(session, query, threadId, userId, svcTy, modelId, refId, agentId, attachmentFileIds, callback);
    }
    
    /**
     * WebSocket으로 메시지 전송 (세션 단위 동기화)
     * - 여러 스레드(Executor/OkHttp 콜백)에서 동시에 sendMessage 호출될 수 있어
     *   session 단위로 동기화해 전송 충돌/IOException을 줄입니다.
     */
    private void sendMessage(WebSocketSession session, String message) {
        if (session == null) return;

        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                }
            }
        } catch (IOException e) {
            logger.error("WebSocket 메시지 전송 실패", e);
        } catch (Exception e) {
            logger.error("WebSocket 메시지 전송 중 예외", e);
        }
    }
    
    /**
     * JSON 값(Long 등)을 String으로 안전 변환
     */
    private static String toStr(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    /**
     * 메시지 JSON 생성
     */
    private String createMessage(String type, String content, String threadId) {
        JSONObject message = new JSONObject();
        message.put("type", type);
        message.put("content", content != null ? content : "");
        if (threadId != null) {
            message.put("threadId", threadId);
        }
        return message.toJSONString();
    }
    
    /**
     * 스트리밍 콜백 인터페이스
     */
    public interface ChatbotStreamingCallback {
        /**
         * @param chunkEvent null 또는 빈 문자열이면 답변 텍스트 델타(answer_delta). "answer_source" 등이면 구조화 청크.
         */
        void onChunk(String content, String accumulated, String chunkEvent);
        void onComplete(String content, String docFileId, String page, List<Integer> viewPage, String threadId, String logId, String tableData, String chartOption);
        void onError(String error);
    }
}

