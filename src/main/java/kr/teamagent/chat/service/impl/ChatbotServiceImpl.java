package kr.teamagent.chat.service.impl;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import kr.teamagent.chat.service.ChatbotService;
import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.chat.socket.ChatbotWebSocketHandler;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 챗봇 서비스 구현체
 * 각 서비스 타입별 AI API 호출 및 스트리밍 처리
 */
@Service
public class ChatbotServiceImpl implements ChatbotService {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatbotServiceImpl.class);

    @Autowired
    ChatbotDAO chatbotDAO;

    @Override
    public void streamAiResponseWebSocket(WebSocketSession session, String query, String threadId, String userId, String svcTy, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {

        String apiUrl = this.getApiUrl(svcTy);
        
        if (CommonUtil.isEmpty(apiUrl)) {
            callback.onError("API URL이 설정되지 않았습니다.");
            return;
        }

        callAiApiStreamingWebSocket(session, apiUrl, query, threadId, userId, svcTy, callback);
    }

    /**
     * API URL 지정
     * @param svcTy
     * @return
     */
    private String getApiUrl(String svcTy){
        String apiUrl = "";
        switch (svcTy) {
            case "C":
                apiUrl = PropertyUtil.getProperty("Globals.chatbot.gpt.apiUrl");
                break;
            case "M":
                apiUrl = PropertyUtil.getProperty("Globals.chatbot.manual.apiUrl");
                break;
            case "S":
                apiUrl = PropertyUtil.getProperty("Globals.chatbot.statQ.apiUrl");
                break;
            default:
                throw new IllegalArgumentException("알 수 없는 서비스 타입: " + svcTy);
        }
        return apiUrl;
    }

    @Override
    public ChatbotVO createChatRoom(ChatbotVO chatbotVO) throws Exception {
        // TODO: TB_CHAT_ROOM 테이블 생성 후 insertChatRoom 호출로 교체
        chatbotVO.setRoomId(1L);
        return chatbotVO;
    }

    /**
     * 답변 만족도 수정
     * @param chatbotVO
     * @return
     * @throws Exception
     */
    @Override
    public Map<String, Object> saveSatisYn(ChatbotVO chatbotVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            int result = chatbotDAO.saveSatisYn(chatbotVO);
            if (result > 0) {
                resultMap.put("successYn", true);
                resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return resultMap;
    }

    /**
     * WebSocket 방식으로 실제 AI API를 호출하고 스트리밍 응답을 처리
     */
    private void callAiApiStreamingWebSocket(WebSocketSession session, String apiUrl, String query, String threadId, String userId, String svcTy, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {
        // 요청 파라미터 구성 (JSON body)
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("user_id", userId != null ? userId : "");
        params.put("threadId", threadId != null ? threadId : "string");
        ChatbotVO chatbotVO = new ChatbotVO();
        chatbotVO.setUserId(userId);
        // 통계질의일 경우 지역권한코드도 같이 넘겨주기
        if(CommonUtil.isNotEmpty(svcTy)){
            if(svcTy.equals("S")){
                // 지역 권한 조회
                List<ChatbotVO> regnCdVoList = chatbotDAO.selectRegnCdList(chatbotVO);

                List<String> regnCdList = new ArrayList<>();
                if (regnCdVoList != null) {
                    for (ChatbotVO vo : regnCdVoList) {
                        regnCdList.add(vo.getStAreaCd());
                    }
                }
                // 지역 권한
                params.put("regn_auth_lst", regnCdList);

            }else if(svcTy.equals("M")){
                // 관리자 권한 조회
                ChatbotVO authFlag = chatbotDAO.selectAuthFlag(chatbotVO);
                // 관리자 권한
                params.put("auth_flag", authFlag.getAuthFlag());
            }
        }

        // 헤더 설정
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "text/event-stream");  // SSE 스트리밍을 위해 변경
        
        try {
            // OkHttpClient를 사용한 스트리밍 호출
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            
            // JSON body 생성
            com.google.gson.Gson gson = new com.google.gson.Gson();
            String jsonBody = gson.toJson(params);
            RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));
            
            // Request Builder
            Request.Builder requestBuilder = new Request.Builder()
                    .url(apiUrl)
                    .post(body);
            
            // Headers 추가
            headers.forEach(requestBuilder::addHeader);
            
            Request request = requestBuilder.build();
            
            logger.info("AI API 호출 시작 (WebSocket): {} - query: {}, threadId: {}", apiUrl, query, threadId);

            /**
             * OkHttp를 이용한 AI API 비동기 스트리밍 호출
             *
             * - enqueue()를 사용하여 HTTP 요청을 비동기로 실행한다.
             * - 네트워크 I/O 및 스트리밍 응답 처리는 OkHttp 내부 스레드 풀에서 수행된다.
             * - 호출한 스레드(ExecutorService 스레드)는 응답을 기다리지 않고 즉시 반환된다.
             *
             * AI 서버로부터 전달되는 스트리밍 응답(SSE/chunked)은
             * Callback(onResponse/onFailure)을 통해 수신되며,
             * 수신된 데이터는 ChatbotStreamingCallback을 통해
             * WebSocketHandler로 전달되어 클라이언트에 실시간 전송된다.
             *
             * 이 구조를 통해 WebSocket 스레드 및 서버 내부 작업 스레드가
             * 네트워크 지연이나 장시간 스트리밍으로 인해 블로킹되지 않도록 한다.
             */
            client.newCall(request).enqueue(new okhttp3.Callback() {
                /**
                 * HTTP 요청 자체가 실패한 경우 호출됨
                 * - 네트워크 오류
                 * - 타임아웃
                 * - 서버 연결 실패 등
                 * 이 시점에서는 AI 서버로부터 정상적인 HTTP 응답을 받지 못한 상태이다.
                 */
                @Override
                public void onFailure(okhttp3.Call call, IOException e) {
                    logger.error("AI API 호출 실패: {}", e.getMessage(), e);
                    callback.onError("API 호출 실패: " + e.getMessage());
                }

                /**
                 * HTTP 응답을 수신한 경우 호출됨
                 * (HTTP 상태 코드가 성공/실패인 경우 모두 포함)
                 * - 응답이 성공적이지 않은 경우(4xx, 5xx)는 오류로 처리
                 * - 응답이 성공적인 경우, ResponseBody를 통해
                 *   AI 서버의 스트리밍(SSE/chunked) 응답을 읽기 시작한다.
                 * 이 메서드는 OkHttp 내부 네트워크 스레드에서 실행된다.
                 */
                @Override
                public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        logger.error("AI API 응답 오류: {}", response.code());
                        callback.onError("API 응답 오류: " + response.code());
                        return;
                    }
                    
                    try (okhttp3.ResponseBody responseBody = response.body()) {
                        if (responseBody == null) {
                            callback.onError("응답 본문이 없습니다.");
                            return;
                        }

                        /**
                         * 스트리밍 응답 처리
                         * AI 서버로부터 전달되는 스트리밍 데이터를 순차적으로 읽으면서
                         * - 새로운 chunk 수신 시 callback.onChunk()
                         * - 스트리밍 종료 시 callback.onComplete()
                         를 호출한다.
                         */
                        processStreamingResponseWebSocket(responseBody, query, svcTy, userId, threadId, callback);
                    } catch (Exception e) {
                        logger.error("스트리밍 응답 처리 중 오류: {}", e.getMessage(), e);
                        callback.onError("스트리밍 처리 오류: " + e.getMessage());
                    }
                }
            });
            
        } catch (Exception e) {
            logger.error("AI API 호출 중 오류 발생: {}", e.getMessage(), e);
            callback.onError("API 호출 오류: " + e.getMessage());
        }
    }
    
    /**
     * WebSocket 방식으로 스트리밍 응답을 처리하여 클라이언트로 전달
     * SSE 형식: event: answer_delta, data: {"text": "..."}
     * 실시간 스트리밍을 위해 작은 버퍼 크기 사용
     */
    private void processStreamingResponseWebSocket(okhttp3.ResponseBody responseBody, String query, String svcTy, String userId, String threadId, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws IOException {

        // 작은 버퍼 크기로 실시간 스트리밍 보장
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(responseBody.byteStream(), "UTF-8"), 1);
        
        String line;
        StringBuilder accumulatedContent = new StringBuilder();
        String responseThreadId = threadId;
        String currentEvent = null;
        boolean isCompleteCalled = false; // complete 메시지 중복 전송 방지 플래그
        
        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // 빈 줄은 무시
                if (line.isEmpty()) {
                    continue;
                }
                
                // event 라인 처리
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim();
                    continue;
                }
                
                // data 라인 처리
                if (line.startsWith("data: ")) {
                    String jsonStr = line.substring(6).trim(); // "data: " 제거
                    
                    try {
                        JSONParser jsonParser = new JSONParser();
                        // jsonParser 로 개행문자까지 처리함.
                        JSONObject data = (JSONObject) jsonParser.parse(jsonStr);
                        
                        // answer_delta 이벤트 처리
                        if ("answer_delta".equals(currentEvent)) {
                            String text = (String) data.get("text");
                            if (text != null && !text.isEmpty()) {
                                accumulatedContent.append(text);
                                // 콜백을 통해 청크 즉시 전송 (한 글자씩 실시간 전송)
                                callback.onChunk(text, accumulatedContent.toString());
                            }
                        }
                        // done 이벤트 처리
                        else if ("done".equals(currentEvent)) {
                            String answer = (String) data.get("answer");
                            if (answer != null && !answer.isEmpty()) {
                                accumulatedContent = new StringBuilder(answer);
                            }

                            String logId = "";
                            Object logChatIdObj = data.get("log_chat_id");
                            if(logChatIdObj != null){
                                logId = String.valueOf(logChatIdObj);
                            }

                            // 차트를 위한 통계 값
                            String tableData = "";
                            Object tableDataObj = data.get("table_data");
                            if(tableDataObj != null){
                                tableData = String.valueOf(tableDataObj);
                            }

                            String filePath = "";
                            String page = "";
                            List<Integer> viewPage = new ArrayList<>();

                            if(CommonUtil.isNotEmpty((String) data.get("file_path"))){
                                filePath = (String) data.get("file_path");
                            }
                            if(CommonUtil.isNotEmpty((String) data.get("page"))){
                                page = (String) data.get("page");
                            }
                            // view_page: AI API에서 관련 페이지 배열로 전달 (예: [3, 7, 15])
                            Object viewPageObj = data.get("view_page");
                            if (viewPageObj instanceof JSONArray) {
                                JSONArray arr = (JSONArray) viewPageObj;
                                for (Object o : arr) {
                                    if (o instanceof Number) {
                                        viewPage.add(((Number) o).intValue());
                                    } else if (o instanceof String) {
                                        try {
                                            viewPage.add(Integer.parseInt((String) o));
                                        } catch (NumberFormatException ignored) {}
                                    }
                                }
                            }

                            // 정상 응답인 경우에만 사용량 증가
                            String errorCode = (String) data.get("errorCode");
                            if(errorCode.equals("None")){
                                this.doInsertAiLog(query, answer, svcTy, filePath, page, userId);
                            }

                            // 최종 완료 콜백 (생성된 logId 전달)
                            callback.onComplete(accumulatedContent.toString(), filePath, page, viewPage, responseThreadId != null ? responseThreadId : "", logId, tableData);
                            isCompleteCalled = true; // done 이벤트에서 onComplete 호출 시 플래그를 true로 설정
                            break;
                        }
                    } catch (Exception e) {
                        logger.warn("JSON 파싱 오류 (무시하고 계속): {} - line: {}", e.getMessage(), line);
                    }
                }
            }
            
            // 스트림이 정상적으로 종료되지 않은 경우
            // 루프 종료 후 isCompleteCalled가 false일 때만 onComplete 호출
            if (!isCompleteCalled && accumulatedContent.length() > 0) {
                String finalThreadId = responseThreadId != null && !responseThreadId.isEmpty() 
                        ? responseThreadId 
                        : "thread-" + System.currentTimeMillis();
                
                callback.onComplete(accumulatedContent.toString(), "", "", new ArrayList<>(), finalThreadId, null, null);
            }
            
        } finally {
            reader.close();
            responseBody.close();
        }
    }

    /**
     * 로그 등록
     * @param query     질문
     * @param answer    답변
     * @param svcTy     ai 타입
     * @param filePath  파일경로
     * @param page      파일 페이지
     * @param userId    사용자 ID
     * @return 생성된 logId
     * @throws Exception
     */
    private void doInsertAiLog(String query, String answer, String svcTy, String filePath, String page, String userId) throws Exception {

        // 일일 사용량 insert/update
        ChatbotVO usageVO = new ChatbotVO();
        String usageDate = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE);
        usageVO.setUsageDate(usageDate);
        usageVO.setSvcTy(svcTy);
        usageVO.setUserId(userId);
        chatbotDAO.insertAiDailyUsage(usageVO);

    }

}

