package kr.teamagent.chat.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.chat.socket.ChatbotWebSocketHandler;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 챗봇 서비스 구현체
 * 각 서비스 타입별 AI API 호출 및 스트리밍 처리
 */
@Service
public class ChatbotServiceImpl extends EgovAbstractServiceImpl{
    
    private static final Logger logger = LoggerFactory.getLogger(ChatbotServiceImpl.class);

    @Autowired
    ChatbotDAO chatbotDAO;

    @Autowired
    ChatbotStatDAO chatbotStatDAO;

    @Autowired
    KeyGenerate keyGenerate;

    /**
     * 모델 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectModelList(ChatbotVO searchVO) throws Exception {
        return chatbotDAO.selectModelList(searchVO);
    }

    /**
     * RAG 데이터 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectRagDsList(ChatbotVO searchVO) throws Exception {
        return chatbotDAO.selectRagDsList(searchVO);
    }
    
    /**
     * 데이터마트 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectDmList(ChatbotVO searchVO) throws Exception {
        return chatbotDAO.selectDmList(searchVO);
    }
    /**
     * CHAT 대화방 참조 문서 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectChatDocList(ChatbotVO searchVO) throws Exception {
        return chatbotDAO.selectChatDocList(searchVO);
    }
    /**
     * 통계 목록 조회(TODO 추후 시연 완료 후 삭제)
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectStatList(ChatbotVO searchVO) throws Exception {
        return chatbotStatDAO.selectStatList(searchVO);
    }
    /**
     * 통계 상세 목록 조회(TODO 추후 시연 완료 후 삭제)
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectStatDetailList(ChatbotVO searchVO) throws Exception {
        return chatbotStatDAO.selectStatDetailList(searchVO);
    }
    /**
     * CHAT 대화방 tableData 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectTableDataList(ChatbotVO searchVO) throws Exception {
        return chatbotDAO.selectTableDataList(searchVO);
    }
    public void streamAiResponseWebSocket(WebSocketSession session, String query, String threadId, String userId, String svcTy, String refId, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {

        String apiUrl = this.getApiUrl(svcTy);
        logger.info("AI API URL resolved - svcTy: {}, apiUrl: {}", svcTy, apiUrl);
        
        if (CommonUtil.isEmpty(apiUrl)) {
            callback.onError("API URL이 설정되지 않았습니다.");
            return;
        }

        callAiApiStreamingWebSocket(session, apiUrl, query, threadId, userId, svcTy, refId, callback);
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
            case "llmTest":
                // TODO 추후 AI 개발 완료 후 삭제
                apiUrl = PropertyUtil.getProperty("Globals.chatbot.gpt.apiUrl");
                break;
            default:
                throw new IllegalArgumentException("알 수 없는 서비스 타입: " + svcTy);
        }
        logger.info("getApiUrl called - svcTy: {}, resolved apiUrl: {}", svcTy, apiUrl);
        return apiUrl;
    }

    /**
     * CHAT 대화방 등록
     * @param chatbotVO
     * @return
     * @throws Exception
     */
    public ChatbotVO createChatRoom(ChatbotVO chatbotVO) throws Exception {
        chatbotVO.setRoomTitle(generateSummaryTitle(chatbotVO.getContent(),null ));
        int result = chatbotDAO.insertChatRoom(chatbotVO);
        return result > 0 ? chatbotVO : null;
    }
    /**
     * CHAT 대화방 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectChatRoomList(ChatbotVO searchVO) throws Exception {
        return chatbotDAO.selectChatRoomList(searchVO);
    }

    /**
     * CHAT 대화방 로그 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectChatLogList(ChatbotVO searchVO) throws Exception {
        return chatbotDAO.selectChatLogList(searchVO);
    }

    /**
     * 답변 만족도 수정
     * @param chatbotVO
     * @return
     * @throws Exception
     */
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
    private void callAiApiStreamingWebSocket(WebSocketSession session, String apiUrl, String query, String threadId, String userId, String svcTy, String refId, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {
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
                // List<ChatbotVO> regnCdVoList = chatbotDAO.selectRegnCdList(chatbotVO);

                // List<String> regnCdList = new ArrayList<>();
                // if (regnCdVoList != null) {
                //     for (ChatbotVO vo : regnCdVoList) {
                //         regnCdList.add(vo.getStAreaCd());
                //     }
                // }
                // 지역 권한
                params.put("regn_auth_lst", Arrays.asList("02", "03", "04", "05", "06", "07", "08", "09"));

            }else if(svcTy.equals("M")){
                // 관리자 권한 조회
                // ChatbotVO authFlag = chatbotDAO.selectAuthFlag(chatbotVO);
                // 관리자 권한
                params.put("auth_flag", "Y");
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
            logger.info("AI API request ready - url: {}, svcTy: {}, userId: {}, refId: {}, threadId: {}, body: {}",
                    apiUrl, svcTy, userId, refId, threadId, jsonBody);
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
                        processStreamingResponseWebSocket(responseBody, query, svcTy, refId, userId, threadId, callback);
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
    private void processStreamingResponseWebSocket(okhttp3.ResponseBody responseBody, String query, String svcTy, String refId, String userId, String threadId, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws IOException {

        // 작은 버퍼 크기로 실시간 스트리밍 보장
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(responseBody.byteStream(), "UTF-8"), 1);
        
        String line;
        StringBuilder accumulatedContent = new StringBuilder();
        String responseThreadId = threadId;
        String currentEvent = null;
        boolean isCompleteCalled = false; // complete 메시지 중복 전송 방지 플래그
        // done 이벤트에서 세팅, finally/콜백에서 사용
        String responseFilePath = "";
        String mainPageNo = "";
        List<Integer> relatedPageNos = new ArrayList<>();
        String docId = "";
        String errorCode = "None"; // 기본값
        int inputTokens = 0;
        int outputTokens = 0;
        String savedLogId = "";
        String tableData = "";
        String sql = "";
        boolean hasStreamError = false;

        try {
            while ((line = reader.readLine()) != null) {
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
                        else if ("done".equals(currentEvent) || "complete".equals(currentEvent)) {
                            String answer = (String) data.get("answer");
                            logger.info("done 이벤트 처리 - answer: {}", answer);
                            if (answer != null && !answer.isEmpty()) {
                                // answer_delta 없이 done에 최종 answer만 오는 경우, chunk를 1회 전송해 UI 일관성 유지
                                if (accumulatedContent.length() == 0) {
                                    callback.onChunk(answer, answer);
                                }
                                accumulatedContent = new StringBuilder(answer);
                                logger.info("done 이벤트 처리 - answer: {}, accumulatedContent: {}", answer, accumulatedContent.toString());
                            }
                            inputTokens = parseTokenCount(data.get("input_tokens"));
                            outputTokens = parseTokenCount(data.get("output_tokens"));
                            // 차트를 위한 통계 값
                            Object tableDataObj = data.get("table_data");

                            if (tableDataObj != null) {
                                com.google.gson.Gson gson = new com.google.gson.Gson();
                                tableData = gson.toJson(tableDataObj);
                            }

                            Object sqlObj = data.get("sql");
                            if(sqlObj != null){
                                sql = String.valueOf(sqlObj);
                            }

                            String filePathFromApi = CommonUtil.isNotEmpty((String) data.get("file_path")) ? (String) data.get("file_path") : "";
                            // 추후 AI 개발 시 DOC_ID 받아서 매핑 필요함. (배열일 수도 있음. 배열이면 배열 처리)
                            if (CommonUtil.isNotEmpty(filePathFromApi)) {
                                if (filePathFromApi.contains("제조정보")) {
                                    responseFilePath = "/CT000001/제조정보.pdf";
                                    docId = "DC000002";
                                } else {
                                    responseFilePath = "/CT000001/금형 및 Burr_수치넘침 검출 방안.pdf";
                                    docId = "DC000001";
                                }
                            }
                            if (CommonUtil.isNotEmpty((String) data.get("page"))) {
                                mainPageNo = (String) data.get("page");
                            }
                            // view_page: AI API에서 관련 페이지 배열로 전달 (예: [3, 7, 15])
                            relatedPageNos.clear();
                            Object viewPageObj = data.get("view_page");
                            if (viewPageObj instanceof JSONArray) {
                                JSONArray arr = (JSONArray) viewPageObj;
                                for (Object o : arr) {
                                    if (o instanceof Number) {
                                        relatedPageNos.add(((Number) o).intValue());
                                    } else if (o instanceof String) {
                                        try {
                                            relatedPageNos.add(Integer.parseInt((String) o));
                                        } catch (NumberFormatException ignored) {}
                                    }
                                }
                            }
                            break;
                        }
                    } catch (Exception e) {
                        logger.warn("JSON 파싱 오류 (무시하고 계속): {} - line: {}", e.getMessage(), line);
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("스트림 읽기 중 오류 발생 (클라이언트 연결 끊김 등): {}", e.getMessage());
            // 에러가 나더라도 finally 블록으로 넘어가서 저장하게 됩니다!
            hasStreamError = true; // 추가
        } finally {
            // 정상 종료든 비정상 종료든 DB 저장은 여기서 무조건 실행!
            if (accumulatedContent.length() > 0 && "None".equals(errorCode) && !svcTy.equals("llmTest")) {
                try {
                    savedLogId = this.doInsertAiLog(responseThreadId, query, accumulatedContent.toString(), inputTokens, outputTokens, svcTy, refId, docId, responseFilePath, mainPageNo, relatedPageNos, userId, tableData, sql);
                    
                    // 챗봇 대화방 마지막 채팅 일시 업데이트
                    this.updateChatRoomLastChatDt(responseThreadId);
                } catch (Exception e) {
                    logger.warn("챗봇 로그 저장 실패: {}", e.getMessage());
                }
            }

            // 콜백 완료 처리 (웹소켓 클라이언트에게 끝났다고 알려줌)
            // - 스트리밍 도중 예외가 발생했으면(끊김/전송 문제 등) complete를 보내지 않음
            if (!hasStreamError && !isCompleteCalled && accumulatedContent.length() > 0) {
                String fallbackThreadId = responseThreadId != null ? responseThreadId : "thread-" + System.currentTimeMillis();
                callback.onComplete(
                        accumulatedContent.toString(),
                        responseFilePath,
                        mainPageNo,
                        relatedPageNos,
                        fallbackThreadId,
                        CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null,
                        tableData
                );
                isCompleteCalled = true;
            }

            reader.close();
            responseBody.close();
        }
    }

    /**
     * 토큰 수(Number/String)를 안전하게 int로 변환
     */
    private int parseTokenCount(Object tokenObj) {
        if (tokenObj == null) {
            return 0;
        }
        if (tokenObj instanceof Number) {
            return ((Number) tokenObj).intValue();
        }
        try {
            String tokenText = String.valueOf(tokenObj);
            return CommonUtil.isNotEmpty(tokenText) ? Integer.parseInt(tokenText) : 0;
        } catch (NumberFormatException e) {
            return 0;
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
    private String doInsertAiLog(String responseThreadId, String query, String answer, int inputTokens, int outputTokens, String svcTy, String refId, String docId, String filePath, String page, List<Integer> viewPage, String userId, String tableData, String sql) throws Exception {

        // 챗봇 로그 insert
        ChatbotVO chatbotVO = new ChatbotVO();
        chatbotVO.setRoomId(Long.parseLong(responseThreadId));
        chatbotVO.setSvcTy(svcTy);
        chatbotVO.setRefId(refId);
        chatbotVO.setQContent(query);
        chatbotVO.setInTokens(inputTokens);
        chatbotVO.setOutTokens(outputTokens);
        chatbotVO.setRContent(answer);
        chatbotVO.setUserId(userId);
        chatbotVO.setTableData(tableData != null && !tableData.trim().isEmpty() ? tableData : null);
        chatbotVO.setSql(sql);
        chatbotDAO.insertChatLog(chatbotVO);
        
        if(svcTy.equals("M")){
            // 채팅 답변별 참조 문서 및 페이지 상세(TB_CHAT_REF) insert
            ChatbotVO chatbotRefVO = new ChatbotVO();
            chatbotRefVO.setLogId(chatbotVO.getLogId());
            chatbotRefVO.setDocId(docId);
            chatbotRefVO.setMainPageNo(page);
            chatbotRefVO.setRelatedPages(viewPage.toString());
            chatbotDAO.insertChatRef(chatbotRefVO);
        }
        return chatbotVO.getLogId() != null ? String.valueOf(chatbotVO.getLogId()) : "";
    }

    /**
     * 챗봇 대화방 마지막 채팅 일시 업데이트
     * @param responseThreadId
     * @throws Exception
     */
    private void updateChatRoomLastChatDt(String responseThreadId) throws Exception {
        ChatbotVO chatbotVO = new ChatbotVO();
        chatbotVO.setRoomId(Long.parseLong(responseThreadId));
        chatbotDAO.updateChatRoomLastChatDt(chatbotVO);
    }

    /**
     * 채팅방 고정
     * @param chatbotVO roomId 필수
     * @return
     * @throws Exception
     */
    public Map<String, Object> pinChatRoom(ChatbotVO chatbotVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            int result = chatbotDAO.pinChatRoom(chatbotVO);
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
     * 지식 카드 등록
     * @param chatbotVO logId, categoryId, userId 필수
     * @return
     * @throws Exception
     */
    public Map<String, Object> saveKnowledge(ChatbotVO chatbotVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        ChatbotVO chatLog = chatbotDAO.selectChatLogByLogId(chatbotVO);

        chatbotVO.setCardId(keyGenerate.generateTableKey("KD", "TB_KNOW_CARD", "CARD_ID"));

        Integer maxSortOrd = chatbotDAO.selectMaxSortOrd(chatbotVO);
        chatbotVO.setSortOrd(maxSortOrd != null ? maxSortOrd + 1 : 1);

        chatbotVO.setSvcTy(chatLog.getSvcTy());
        chatbotVO.setTitle(generateSummaryTitle(chatLog.getQContent(), chatLog.getRContent()));
        chatbotVO.setTags(generateSummaryTags(chatLog.getQContent(), chatLog.getRContent()));
        chatbotVO.setPinYn("N");
        chatbotVO.setArchiveYn("N");
        chatbotVO.setUseYn("Y");

        if ("S".equals(chatLog.getSvcTy())) {
            chatbotVO.setSqlCode(chatLog.getTtsq());
        }

        chatbotDAO.insertKnowledgeCard(chatbotVO);

        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        return resultMap;
    }

    private String truncateTitle(String text, int maxLength) {
        if (CommonUtil.isEmpty(text)) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * AI 서버를 통해 질문/답변을 요약한 제목을 생성한다.
     * 실패 시 qContent를 50자로 잘라 반환(fallback).
     */
    private String generateSummaryTitle(String qContent, String rContent) {
        if (CommonUtil.isEmpty(qContent)) {
            return truncateTitle(rContent, 50);
        }

        String prompt = "다음 대화의 핵심 내용을 20자 이내의 한 줄 제목으로 요약해줘. 제목만 출력해. "
                + "질문: " + truncateTitle(qContent, 200);
        if (CommonUtil.isNotEmpty(rContent)) {
            prompt += " 답변: " + truncateTitle(rContent, 500);
        }

        String result = callAiSummary(prompt, "title");
        if (CommonUtil.isNotEmpty(result)) {
            return truncateTitle(result, 50);
        }
        return truncateTitle(qContent, 50);
    }

    /**
     * AI 서버를 통해 질문/답변에서 태그를 추출한다.
     * 쉼표(,)로 구분된 최대 5개 태그를 반환한다.
     * 실패 시 빈 문자열 반환(fallback).
     */
    private String generateSummaryTags(String qContent, String rContent) {
        if (CommonUtil.isEmpty(qContent) && CommonUtil.isEmpty(rContent)) {
            return "";
        }

        String prompt = "다음 대화에서 핵심 키워드를 최대 5개 추출해줘. 쉼표(,)로 구분해서 키워드만 출력해. 부연 설명 없이 키워드만. "
                + "질문: " + truncateTitle(qContent, 200)
                + " 답변: " + truncateTitle(rContent, 500);

        String result = callAiSummary(prompt, "tags");
        if (CommonUtil.isNotEmpty(result)) {
            return truncateTitle(result.trim(), 200);
        }
        return "";
    }

    /**
     * GPT endpoint에 동기 호출하여 AI 응답 텍스트를 반환한다.
     * doInsertAiLog를 호출하지 않으므로 로그 테이블에 쌓이지 않는다.
     * @param prompt 요청 프롬프트
     * @param purpose 로깅용 호출 목적 (title, tags 등)
     * @return AI 응답 텍스트, 실패 시 null
     */
    private String callAiSummary(String prompt, String purpose) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.gpt.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("{} 생성 실패 - GPT API URL 미설정", purpose);
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("query", prompt);
        params.put("user_id", "");
        params.put("threadId", "string");

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            com.google.gson.Gson gson = new com.google.gson.Gson();
            String jsonBody = gson.toJson(params);
            RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .build();

            logger.info("AI {} 생성 호출 시작 - url: {}", purpose, apiUrl);

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("AI {} 응답 오류: {}", purpose, response.code());
                    return null;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(responseBody.byteStream(), "UTF-8"), 1);

                    String line;
                    StringBuilder accumulated = new StringBuilder();
                    String currentEvent = null;

                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event: ")) {
                            currentEvent = line.substring(7).trim();
                            continue;
                        }
                        if (line.startsWith("data: ")) {
                            String jsonStr = line.substring(6).trim();
                            try {
                                JSONParser jsonParser = new JSONParser();
                                JSONObject data = (JSONObject) jsonParser.parse(jsonStr);

                                if ("answer_delta".equals(currentEvent)) {
                                    String text = (String) data.get("text");
                                    if (text != null && !text.isEmpty()) {
                                        accumulated.append(text);
                                    }
                                } else if ("done".equals(currentEvent) || "complete".equals(currentEvent)) {
                                    String answer = (String) data.get("answer");
                                    if (answer != null && !answer.isEmpty()) {
                                        accumulated = new StringBuilder(answer);
                                    }
                                    break;
                                }
                            } catch (Exception e) {
                                logger.warn("AI {} SSE 파싱 오류 (무시): {}", purpose, e.getMessage());
                            }
                        }
                    }

                    if (accumulated.length() > 0) {
                        String result = accumulated.toString().trim();
                        logger.info("AI {} 생성 완료: {}", purpose, result);
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("AI {} 생성 중 오류 발생: {}", purpose, e.getMessage());
        }

        return null;
    }

    /**
     * 지식 카테고리 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO.KnowledgeItem> selectKnowledgeList(ChatbotVO searchVO) throws Exception {
        searchVO.setUserId(SessionUtil.getUserId());
        return chatbotDAO.selectKnowledgeList(searchVO);
    }

}

