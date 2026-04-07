package kr.teamagent.chat.service.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    public void streamAiResponseWebSocket(WebSocketSession session, String query, String threadId, String userId, String svcTy, String modelId, String refId, List<Long> attachmentFileIds, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {

        String apiUrl = this.getApiUrl(svcTy);
        logger.info("AI API URL resolved - svcTy: {}, apiUrl: {}", svcTy, apiUrl);

        if (CommonUtil.isEmpty(apiUrl)) {
            callback.onError("API URL이 설정되지 않았습니다.");
            return;
        }

        callAiApiStreamingWebSocket(session, apiUrl, query, threadId, userId, svcTy, modelId, refId, attachmentFileIds, callback);
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
    private void callAiApiStreamingWebSocket(WebSocketSession session, String apiUrl, String query, String threadId, String userId, String svcTy, String modelId, String refId, List<Long> attachmentFileIds, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {
        // 요청 파라미터 구성 (JSON body)
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("user_id", userId != null ? userId : "");
        params.put("threadId", threadId != null ? threadId : "string");
        params.put("dataset_id", refId != null ? refId : "");
        params.put("room_id", threadId != null ? threadId : "string");
        params.put("model_id", modelId != null ? modelId : "");
        params.put("attachment_file_ids", attachmentFileIds);
        
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
            logger.info("AI API request ready - url: {}, svcTy: {}, userId: {}, refId: {}, modelId: {}, threadId: {}, body: {}",
                    apiUrl, svcTy, userId, refId, modelId, threadId, jsonBody);
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
                        processStreamingResponseWebSocket(responseBody, query, svcTy, modelId, refId, userId, threadId, attachmentFileIds, callback);
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
    private void processStreamingResponseWebSocket(okhttp3.ResponseBody responseBody, String query, String svcTy, String modelId, String refId, String userId, String threadId, List<Long> attachmentFileIds, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws IOException {

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream(), "UTF-8"), 1);

        String line;
        String currentEvent = null;
        StringBuilder accumulatedContent = new StringBuilder();
        String responseThreadId = threadId;
        boolean isCompleteCalled = false;
        boolean hasStreamError = false;

        int inputTokens = 0;
        int outputTokens = 0;
        String mainDocFileId = "";
        String mainPage = "";
        String savedLogId = "";
        String tableData = "";
        String sql = "";
        List<ChatRefItem> chatRefItems = new ArrayList<>();

        try {
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim();
                    continue;
                }
                if (!line.startsWith("data: ")) {
                    continue;
                }

                String jsonStr = line.substring(6).trim();

                try {
                    JSONParser jsonParser = new JSONParser();
                    JSONObject data = (JSONObject) jsonParser.parse(jsonStr);

                    if ("answer_delta".equals(currentEvent)) {
                        String text = (String) data.get("text");
                        if (text != null && text.length() > 0) {
                            accumulatedContent.append(text);
                            callback.onChunk(text, accumulatedContent.toString());
                        }
                        continue;
                    }

                    if ("done".equals(currentEvent) || "complete".equals(currentEvent)) {
                        String answer = getAnswerText(data);
                        if (CommonUtil.isNotEmpty(answer)) {
                            if (accumulatedContent.length() == 0) {
                                callback.onChunk(answer, answer);
                            }
                            accumulatedContent = new StringBuilder(answer);
                        }

                        mainDocFileId = getString(data.get("docFileId"));
                        mainPage = getString(data.get("page"));
                        inputTokens = parseTokenCount(data.get("input_token"));
                        outputTokens = parseTokenCount(data.get("output_token"));
                        tableData = toJsonIfExists(data.get("table_data"));
                        sql = getString(data.get("sql"));

                        chatRefItems = extractChatRefItems(data);

                        break;
                    }
                } catch (Exception e) {
                    logger.warn("JSON 파싱 오류 (무시하고 계속): {} - line: {}", e.getMessage(), line);
                }
            }
        } catch (Exception e) {
            logger.error("스트림 읽기 중 오류 발생 (클라이언트 연결 끊김 등): {}", e.getMessage());
            hasStreamError = true;
        } finally {
            try {
                if (accumulatedContent.length() > 0 && !"llmTest".equals(svcTy)) {
                    try {
                        savedLogId = this.doInsertAiLog(
                                responseThreadId,
                                query,
                                accumulatedContent.toString(),
                                inputTokens,
                                outputTokens,
                                svcTy,
                                modelId,
                                refId,
                                userId,
                                tableData,
                                sql,
                                mainDocFileId,
                                mainPage,
                                chatRefItems);

                        this.updateChatRoomLastChatDt(responseThreadId);

                        // 첨부파일 LOG_ID 연결 + EXPIRE_DT 해제
                        if (CommonUtil.isNotEmpty(savedLogId)
                                && attachmentFileIds != null
                                && !attachmentFileIds.isEmpty()) {
                            try {
                                ChatbotVO fileVO = new ChatbotVO();
                                fileVO.setChatFileIdList(attachmentFileIds);
                                fileVO.setLogId(Long.parseLong(savedLogId));
                                chatbotDAO.linkChatFilesToLog(fileVO);
                            } catch (Exception e) {
                                logger.warn("첨부파일 LOG_ID 연결 실패: {}", e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("챗봇 로그 저장 실패: {}", e.getMessage());
                    }
                }

                if (!hasStreamError && !isCompleteCalled && accumulatedContent.length() > 0) {
                    ChatRefItem firstRef = !chatRefItems.isEmpty() ? chatRefItems.get(0) : null;

                    List<Integer> relatedPageNos = firstRef != null ? new ArrayList<>(firstRef.relatedPageNos) : new ArrayList<>();
                    String fallbackThreadId = responseThreadId != null
                            ? responseThreadId
                            : "thread-" + System.currentTimeMillis();

                    callback.onComplete(
                            accumulatedContent.toString(),
                            mainDocFileId,
                            mainPage,
                            relatedPageNos,
                            fallbackThreadId,
                            CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null,
                            tableData);
                    isCompleteCalled = true;
                }
            } finally {
                reader.close();
                responseBody.close();
            }
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

    private String getAnswerText(JSONObject data) {
        String answer = getString(data.get("answer"));
        if (!CommonUtil.isNotEmpty(answer)) {
            answer = getString(data.get("답변"));
        }
        return answer;
    }

    private String getString(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    private String getPageString(Object obj) {
        if (obj == null) {
            return "";
        }
        if (obj instanceof Number) {
            return String.valueOf(((Number) obj).intValue());
        }
        return String.valueOf(obj);
    }

    private List<Integer> parsePageList(Object obj) {
        List<Integer> result = new ArrayList<>();

        if (!(obj instanceof JSONArray)) {
            return result;
        }

        JSONArray arr = (JSONArray) obj;
        for (Object o : arr) {
            if (o instanceof Number) {
                result.add(((Number) o).intValue());
            } else if (o instanceof String) {
                try {
                    result.add(Integer.parseInt((String) o));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return result;
    }

    private String toJsonIfExists(Object obj) {
        if (obj == null) {
            return "";
        }
        return new com.google.gson.Gson().toJson(obj);
    }

    private String nvl(String str) {
        return str == null ? "" : str;
    }

    /**
     * done 페이로드 최상위 page가 0이면 매뉴얼 미매칭 등으로 보고,
     * file_info에 파일이 있어도 참조(TB_CHAT_REF·onComplete docFileId)에 쓰지 않는다.
     */
    private boolean isRootPageZero(JSONObject data) {
        Object pageObj = data.get("page");
        if (pageObj == null) {
            return false;
        }
        if (pageObj instanceof Number) {
            return ((Number) pageObj).intValue() == 0;
        }
        try {
            return Integer.parseInt(String.valueOf(pageObj).trim()) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * done 페이로드에서 TB_CHAT_REF 저장용 참조 목록 추출.
     * file_info 우선, 없으면 docFileId + page + relatedPages(또는 view_page) 1건.
     */
    private List<ChatRefItem> extractChatRefItems(JSONObject data) {
        List<ChatRefItem> result = new ArrayList<>();

        if (isRootPageZero(data)) {
            return result;
        }

        Object fileInfoObj = data.get("file_info");
        if (fileInfoObj instanceof JSONArray) {
            JSONArray fileInfoArr = (JSONArray) fileInfoObj;

            for (Object fi : fileInfoArr) {
                if (!(fi instanceof JSONObject)) {
                    continue;
                }

                JSONObject fiObj = (JSONObject) fi;
                String docFileId = getString(fiObj.get("docFileId"));
                if (!CommonUtil.isNotEmpty(docFileId)) {
                    continue;
                }

                ChatRefItem item = new ChatRefItem();
                item.docFileId = docFileId;
                item.mainPageNo = getPageString(fiObj.get("mainPageNo"));
                item.relatedPageNos = parsePageList(fiObj.get("relatedPages"));

                result.add(item);
            }
        }

        if (!result.isEmpty()) {
            return result;
        }

        String rootDocFileId = getString(data.get("docFileId"));
        if (CommonUtil.isNotEmpty(rootDocFileId)) {
            ChatRefItem item = new ChatRefItem();
            item.docFileId = rootDocFileId;
            item.mainPageNo = getPageString(data.get("page"));
            item.relatedPageNos = parsePageList(data.get("relatedPages"));
            if (item.relatedPageNos.isEmpty()) {
                item.relatedPageNos = parsePageList(data.get("view_page"));
            }
            result.add(item);
        }

        return result;
    }

    /**
     * TB_CHAT_LOG 저장 및 svcTy == M 이면 TB_CHAT_REF(chatRefItems) 반복 저장.
     */
    private String doInsertAiLog(
            String responseThreadId,
            String query,
            String answer,
            int inputTokens,
            int outputTokens,
            String svcTy,
            String modelId,
            String refId,
            String userId,
            String tableData,
            String sql,
            String mainDocFileId,
            String mainPage,
            List<ChatRefItem> chatRefItems) throws Exception {

        ChatbotVO chatbotVO = new ChatbotVO();
        chatbotVO.setRoomId(Long.parseLong(responseThreadId));
        chatbotVO.setSvcTy(svcTy);
        chatbotVO.setRefId(refId);
        chatbotVO.setQContent(query);
        chatbotVO.setModelId(modelId);
        chatbotVO.setInTokens(inputTokens);
        chatbotVO.setOutTokens(outputTokens);
        chatbotVO.setRContent(answer);
        chatbotVO.setUserId(userId);
        chatbotVO.setTableData(CommonUtil.isNotEmpty(tableData) ? tableData : null);
        chatbotVO.setSql(CommonUtil.isNotEmpty(sql) ? sql : null);
        chatbotVO.setMainDocFileId(CommonUtil.isNotEmpty(mainDocFileId) ? mainDocFileId : null);
        chatbotVO.setMainPage(CommonUtil.isNotEmpty(mainPage) ? mainPage : null);

        chatbotDAO.insertChatLog(chatbotVO);

        if ("M".equals(svcTy) && chatRefItems != null && !chatRefItems.isEmpty()) {
            for (ChatRefItem refItem : chatRefItems) {
                if (!CommonUtil.isNotEmpty(refItem.docFileId)) {
                    continue;
                }

                ChatbotVO chatbotRefVO = new ChatbotVO();
                chatbotRefVO.setLogId(chatbotVO.getLogId());
                chatbotRefVO.setDocFileId(refItem.docFileId);
                chatbotRefVO.setMainPageNo(refItem.mainPageNo);

                String relatedStr = refItem.relatedPageNos != null
                        ? refItem.relatedPageNos.toString()
                        : "[]";

                if (relatedStr.length() > 500) {
                    relatedStr = relatedStr.substring(0, 500);
                }

                chatbotRefVO.setRelatedPages(relatedStr);
                chatbotDAO.insertChatRef(chatbotRefVO);
            }
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
     * 채팅방 제목 수정
     * @param chatbotVO roomId, roomTitle 필수
     * @return
     * @throws Exception
     */
    public Map<String, Object> renameChatRoom(ChatbotVO chatbotVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            int result = chatbotDAO.renameChatRoom(chatbotVO);
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
     * 채팅방 삭제
     * @param chatbotVO roomId 필수
     * @return
     * @throws Exception
     */
    public Map<String, Object> deleteChatRoom(ChatbotVO chatbotVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            int result = 0;
            result += chatbotDAO.deleteChatRef(chatbotVO);
            result += chatbotDAO.deleteChatLog(chatbotVO);
            result += chatbotDAO.deleteChatRoom(chatbotVO);
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

        chatbotDAO.updateKnowledgeSortOrdForPrepend(chatbotVO);
        chatbotVO.setSortOrd(1);

        chatbotVO.setSvcTy(chatLog.getSvcTy());
        chatbotVO.setTitle(CommonUtil.isEmpty(chatLog.getRoomTitle()) ? generateSummaryTitle(chatLog.getQContent(), chatLog.getRContent()) : chatLog.getRoomTitle());
        chatbotVO.setTags(generateSummaryTags(chatLog.getQContent(), chatLog.getRContent()));

        chatbotVO.setThumbImg(generateSummaryThumbImg(chatLog.getQContent(), chatLog.getRContent()));

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

        String prompt = "다음 대화에서 핵심 키워드를 최대 5개 추출해줘. 쉼표(,)로 구분해서 키워드만 출력해. 부연 설명 없이 키워드만.각 키워드는 10글자 이내로 하고, 반드시 최대 5개까지만 추출해줘. 쉼표(,)뒤에는 공백이 없도록 출력해줘."
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
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.summary.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("{} 생성 실패 - GPT API URL 미설정", purpose);
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("query", prompt);
        params.put("room_id", "");

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
                    .addHeader("Accept", "application/json")
                    .build();

            logger.info("AI {} 생성 호출 시작 - url: {}", purpose, apiUrl);

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("AI {} 응답 오류: {}", purpose, response.code());
                    return null;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    String raw = responseBody.string();
                    if (CommonUtil.isEmpty(raw)) {
                        return null;
                    }
                    String trimmed = raw.trim();
                    String jsonStr = trimmed;
                    if (trimmed.startsWith("data: ")) {
                        jsonStr = trimmed.substring(6).trim();
                        int nl = jsonStr.indexOf('\n');
                        if (nl >= 0) {
                            jsonStr = jsonStr.substring(0, nl).trim();
                        }
                    }
                    try {
                        JSONParser jsonParser = new JSONParser();
                        JSONObject data = (JSONObject) jsonParser.parse(jsonStr);

                        Object errCodeObj = data.get("errorCode");
                        if (errCodeObj != null) {
                            String errorCode = String.valueOf(errCodeObj).trim();
                            if (!errorCode.isEmpty() && !"None".equalsIgnoreCase(errorCode)) {
                                Object errContentObj = data.get("errorContent");
                                String errorContent = errContentObj != null ? String.valueOf(errContentObj) : "";
                                logger.warn("AI {} API 오류 응답: {} - {}", purpose, errorCode, errorContent);
                                return null;
                            }
                        }

                        String answer = (String) data.get("answer");
                        if (CommonUtil.isNotEmpty(answer)) {
                            String result = answer.trim();
                            logger.info("AI {} 생성 완료", purpose);
                            return result;
                        }
                    } catch (Exception e) {
                        logger.warn("AI {} 응답 파싱 오류: {}", purpose, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("AI {} 생성 중 오류 발생: {}", purpose, e.getMessage());
        }

        return null;
    }

    /**
     * AI 이미지 API를 호출해 지식 카드 썸네일용 base64 이미지 문자열을 반환한다.
     * 실패 시 null.
     */
    private String generateSummaryThumbImg(String qContent, String rContent) {
        if (CommonUtil.isEmpty(qContent) && CommonUtil.isEmpty(rContent)) {
            return null;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("다음 대화를 요약한 지식 카드에 어울리는 썸네일 이미지를 만들어줘. 텍스트는 포함하지말고 이미지만 생성해주고 썸네일 이미지답게 단순한 이미지로 생성해줘. 이미지 크기는 270px x 80px 정도로 맞춰줘.");
        if (CommonUtil.isNotEmpty(qContent)) {
            prompt.append("질문: ").append(truncateTitle(qContent, 200)).append(' ');
        }
        if (CommonUtil.isNotEmpty(rContent)) {
            prompt.append("답변: ").append(truncateTitle(rContent, 500));
        }

        return callAiImageApi(prompt.toString());
    }

    /**
     * Globals.chatbot.image.apiUrl 동기 호출. 응답 JSON의 image 필드(base64)를 반환한다.
     * data:image/...;base64, 접두사가 있으면 제거한 순수 base64만 저장한다.
     */
    private String callAiImageApi(String query) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.image.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("썸네일 이미지 생성 실패 - image API URL 미설정");
            return null;
        }
        if (CommonUtil.isEmpty(query)) {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("room_id", "");

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            com.google.gson.Gson gson = new com.google.gson.Gson();
            String jsonBody = gson.toJson(params);
            RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build();

            logger.info("AI 썸네일 이미지 호출 시작 - url: {}", apiUrl);

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("AI 썸네일 이미지 응답 오류: {}", response.code());
                    return null;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    String raw = responseBody.string();
                    if (CommonUtil.isEmpty(raw)) {
                        return null;
                    }
                    String trimmed = raw.trim();
                    String jsonStr = trimmed;
                    if (trimmed.startsWith("data: ")) {
                        jsonStr = trimmed.substring(6).trim();
                        int nl = jsonStr.indexOf('\n');
                        if (nl >= 0) {
                            jsonStr = jsonStr.substring(0, nl).trim();
                        }
                    }
                    try {
                        JSONParser jsonParser = new JSONParser();
                        JSONObject data = (JSONObject) jsonParser.parse(jsonStr);

                        Object errCodeObj = data.get("errorCode");
                        if (errCodeObj != null) {
                            String errorCode = String.valueOf(errCodeObj).trim();
                            if (!errorCode.isEmpty() && !"None".equalsIgnoreCase(errorCode)) {
                                Object errContentObj = data.get("errorContent");
                                String errorContent = errContentObj != null ? String.valueOf(errContentObj) : "";
                                logger.warn("AI 썸네일 이미지 API 오류: {} - {}", errorCode, errorContent);
                                return null;
                            }
                        }

                        Object imageObj = data.get("image");
                        if (imageObj == null) {
                            return null;
                        }
                        String image = String.valueOf(imageObj).trim();
                        if (CommonUtil.isEmpty(image)) {
                            return null;
                        }
                        String normalized = stripDataUrlBase64Prefix(image);
                        return normalized;
                    } catch (Exception e) {
                        logger.warn("AI 썸네일 이미지 응답 파싱 오류: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("AI 썸네일 이미지 호출 중 오류: {}", e.getMessage());
        }

        return null;
    }

    /** data:image/png;base64, 접두사 제거 후 순수 base64만 반환 */
    private static String stripDataUrlBase64Prefix(String image) {
        if (image == null) {
            return null;
        }
        String s = image.trim();
        int comma = s.indexOf("base64,");
        if (comma >= 0) {
            return s.substring(comma + "base64,".length()).trim();
        }
        return s;
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

    /**
     * 대화방 공유 토큰 발급
     * @param chatbotVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> createShareToken(ChatbotVO chatbotVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        if (chatbotVO == null || chatbotVO.getRoomId() == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "roomId가 필요합니다.");
            return resultMap;
        }

        String userId = SessionUtil.getUserId();
        chatbotVO.setUserId(userId);

        String shareToken = UUID.randomUUID().toString();
        chatbotVO.setShareToken(shareToken);

        int inserted = chatbotDAO.insertShareToken(chatbotVO);
        if (inserted > 0) {
            resultMap.put("successYn", true);
            resultMap.put("shareToken", shareToken);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        } else {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "토큰 저장에 실패하였습니다.");
        }
        return resultMap;
    }

    /**
     * 공유 토큰으로 채팅 로그 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> selectSharedChatLogList(ChatbotVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        String shareToken = searchVO != null ? searchVO.getShareToken() : null;

        if (CommonUtil.isEmpty(shareToken)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "shareToken이 필요합니다.");
            resultMap.put("list", new ArrayList<ChatbotVO>());
            return resultMap;
        }

        ChatbotVO tokenParam = new ChatbotVO();
        tokenParam.setShareToken(shareToken);

        ChatbotVO validRoom = chatbotDAO.selectShareTokenValidRoomId(tokenParam);
        if (validRoom != null && validRoom.getRoomId() != null) {
            ChatbotVO logParam = new ChatbotVO();
            logParam.setRoomId(validRoom.getRoomId());
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            resultMap.put("list", selectChatLogList(logParam));
            return resultMap;
        }

        int exists = chatbotDAO.countShareTokenByToken(tokenParam);
        resultMap.put("successYn", false);
        if (exists > 0) {
            resultMap.put("returnMsg", "만료된 공유 URL입니다.");
        } else {
            resultMap.put("returnMsg", "유효하지 않은 공유 링크입니다.");
        }
        resultMap.put("list", new ArrayList<ChatbotVO>());
        return resultMap;
    }

    /**
     * 채팅 파일 저장
     * @param chatbotVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> saveChatFile(ChatbotVO chatbotVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            chatbotVO.setUserId(SessionUtil.getUserId());
            /**
             * 업로드 직후 insert
                LOG_ID = NULL
                FILE_EXIST_YN = 'Y'
                EXPIRE_DT = NOW() + INTERVAL 1 DAY (또는 3시간/24시간 정책)

                CASE 1. ws 전송 성공 + onComplete에서 로그 저장 완료 시
                해당 파일들 LOG_ID = savedLogId로 업데이트
                EXPIRE_DT = NULL로 해제

                CASE 2. ws 전송 실패(또는 일정 시간 내 LOG_ID 미연결) 시
                그대로 LOG_ID IS NULL + EXPIRE_DT <= NOW() 대상이 배치 삭제 후보
                배치 작업
             */
            int result = chatbotDAO.saveChatFile(chatbotVO);
            if (result > 0) {
                resultMap.put("successYn", true);
                resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
                resultMap.put("chatFileId", chatbotVO.getChatFileId());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return resultMap;
    }

    /**
     * 채팅 파일 orphan 처리
     * ws 전송 실패 등으로 LOG_ID가 연결되지 못한 파일의 EXPIRE_DT를 현재 시각으로 갱신해
     * 배치 삭제 대상으로 표시한다.
     */
    public Map<String, Object> markChatFileOrphan(ChatbotVO chatbotVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        if (chatbotVO.getChatFileIdList() == null || chatbotVO.getChatFileIdList().isEmpty()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "처리할 파일 ID가 없습니다.");
            return resultMap;
        }

        int result = chatbotDAO.markChatFileOrphan(chatbotVO);
        if (result > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        } else {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
        }

        return resultMap;
    }

    /**
     * done 이벤트의 file_info 배열 항목 (TB_CHAT_REF 다건 INSERT용)
     */
    private static class ChatRefItem {
        /** TB_CHAT_REF.DOC_FILE_ID / API docFileId */
        String docFileId;
        String mainPageNo;
        List<Integer> relatedPageNos = new ArrayList<>();
    }

}

