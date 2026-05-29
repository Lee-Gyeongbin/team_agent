package kr.teamagent.chat.service.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.lang.reflect.Type;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.WebSocketSession;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.chat.service.ChatbotVO.RssArticleRow;
import kr.teamagent.chat.socket.ChatbotWebSocketHandler;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.common.util.NewsRssUtil;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.RestApiManager;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.prompt.service.impl.PromptServiceImpl;
import okhttp3.HttpUrl;
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
    private static final String LUNCH_MENU_AGENT_ID = "AG000009";
    private static final String MEME_AGENT_ID = "AG000011";

    /** summary_query 동기 호출 시 프롬프트·응답이 커 지연이 길어질 수 있는 경우의 OkHttp 읽기 타임아웃(초). */
    private static final int SUMMARY_QUERY_READ_TIMEOUT_LONG_SEC = 180;
    private static final String NEWS_CURATION_AGENT_ID = "AG000012";
    private static final Gson NEWS_CURATE_PROMPT_GSON = new Gson();

    @Autowired
    ChatbotDAO chatbotDAO;

    @Autowired
    ChatbotStatDAO chatbotStatDAO;

    @Autowired
    KeyGenerate keyGenerate;

    @Autowired
    FileServiceImpl fileService;

    @Autowired
    PromptServiceImpl promptService;

    @Autowired
    RestApiManager restApiManager;

    /**
     * 채팅 에이전트 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectAgentListForChat(ChatbotVO searchVO) throws Exception {
        List<ChatbotVO> agentList = chatbotDAO.selectAgentListForChat(searchVO);
        if (agentList == null || agentList.isEmpty()) {
            return agentList;
        }

        List<String> agentIdList = agentList.stream()
                .map(ChatbotVO::getAgentId)
                .filter(id -> !CommonUtil.isEmpty(id))
                .collect(Collectors.toList());
        if (agentIdList.isEmpty()) {
            return agentList;
        }

        ChatbotVO subCfgParam = new ChatbotVO();
        subCfgParam.setAgentIdList(agentIdList);
        List<ChatbotVO.AgtSubCfgVO> subCfgList = chatbotDAO.selectAgentSubCfgListByAgentIds(subCfgParam);

        Map<String, ChatbotVO.AgtSubCfgVO> subCfgByAgentId = new HashMap<>();
        if (subCfgList != null) {
            for (ChatbotVO.AgtSubCfgVO subCfg : subCfgList) {
                if (subCfg == null || CommonUtil.isEmpty(subCfg.getAgentId())) {
                    continue;
                }
                parseAgentSubAdditionalConfig(subCfg);
                subCfgByAgentId.put(subCfg.getAgentId(), subCfg);
            }
        }

        for (ChatbotVO agent : agentList) {
            agent.setSubCfg(subCfgByAgentId.get(agent.getAgentId()));
        }
        return agentList;
    }

    /**
     * Agent 서브 설정 파싱
     * @param subCfg
     */
    private void parseAgentSubAdditionalConfig(ChatbotVO.AgtSubCfgVO subCfg) {
        if (subCfg == null) {
            return;
        }
        String json = subCfg.getAdditionalConfig();
        if (CommonUtil.isEmpty(json)) {
            subCfg.setAdditionalConfigMap(null);
            return;
        }
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        subCfg.setAdditionalConfigMap(NEWS_CURATE_PROMPT_GSON.fromJson(json, type));
    }
    
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
    public void streamAiResponseWebSocket(WebSocketSession session, String query, String threadId, String userId, String svcTy, String modelId, String refId, String agentId, List<Long> attachmentFileIds, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {

        if (NEWS_CURATION_AGENT_ID.equals(agentId)) {
            deliverNewsRecommendationViaWebSocket(query, threadId, userId, svcTy, modelId, refId, agentId, attachmentFileIds, callback);
            return;
        }

        String apiUrl = this.resolveStreamingApiUrl(svcTy, agentId, attachmentFileIds);
        logger.info("AI API URL resolved - svcTy: {}, apiUrl: {}", svcTy, apiUrl);

        if (CommonUtil.isEmpty(apiUrl)) {
            callback.onError("API URL이 설정되지 않았습니다.");
            return;
        }

        callAiApiStreamingWebSocket(session, apiUrl, query, threadId, userId, svcTy, modelId, refId, agentId, attachmentFileIds, callback);
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
     * 스트리밍 호출용 URL. 점심·밈 에이전트는 전용 URL.
     */
    private String resolveStreamingApiUrl(String svcTy, String agentId, List<Long> attachmentFileIds) {
        if (LUNCH_MENU_AGENT_ID.equals(agentId)) {
            String lunchApiUrl = PropertyUtil.getProperty("Globals.chatbot.lunch.apiUrl");
            if (CommonUtil.isNotEmpty(lunchApiUrl)) {
                logger.info("resolveStreamingApiUrl: lunch agent -> lunch_query URL");
                return lunchApiUrl;
            }
        }

        if ("C".equals(svcTy) && MEME_AGENT_ID.equals(agentId)) {
            String searchOnlyApiUrl = PropertyUtil.getProperty("Globals.chatbot.apiIpSearchOnly");
            if (CommonUtil.isNotEmpty(searchOnlyApiUrl)) {
                logger.info("resolveStreamingApiUrl: meme agent -> query_search_only URL");
                return searchOnlyApiUrl;
            }
        }

        if ("C".equals(svcTy) && hasNonNullAttachmentId(attachmentFileIds)) {
            // 첨부파일이 있는 일반채팅이라면
            String fileUrl = PropertyUtil.getProperty("Globals.chatbot.gpt.apiFileUrl");
            if (CommonUtil.isNotEmpty(fileUrl)) {
                logger.info("resolveStreamingApiUrl: svcTy=C with attachments → file_query URL");
                return fileUrl;
            }
        }
        // 에이전트 ID가 있으면 에이전트 API URL 조회
        if(CommonUtil.isNotEmpty(agentId)){
            ChatbotVO searchVO = new ChatbotVO();
            searchVO.setAgentId(agentId);
            try {
                ChatbotVO agentVO = chatbotDAO.selectApiUrlEndpoint(searchVO);
                if(agentVO != null){
                    // 에이전트 API URL 세팅
                    String apiUrl = PropertyUtil.getProperty("Globals.chatbot.apiIp") + agentVO.getApiPort() + agentVO.getApiEndpoint();
                    return apiUrl;
                } else {
                    return getApiUrl(svcTy);
                }
            } catch (Exception e) {
                logger.error("API URL 조회 중 오류 발생: {}", e.getMessage(), e);
                return getApiUrl(svcTy);
            }
        }
        return getApiUrl(svcTy);
    }

    private static boolean hasNonNullAttachmentId(List<Long> attachmentFileIds) {
        if (attachmentFileIds == null || attachmentFileIds.isEmpty()) {
            return false;
        }
        for (Long id : attachmentFileIds) {
            if (id != null) {
                return true;
            }
        }
        return false;
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
    private void callAiApiStreamingWebSocket(WebSocketSession session, String apiUrl, String query, String threadId, String userId, String svcTy, String modelId, String refId, String agentId, List<Long> attachmentFileIds, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {
        // 요청 파라미터 구성 (JSON body)
        Map<String, Object> params = new HashMap<>();
        String requestQuery = buildRequestQueryByAgent(query, agentId);
        params.put("query", requestQuery);
        params.put("user_id", userId != null ? userId : "");
        params.put("threadId", threadId != null ? threadId : "string");
        // M(관리): 프론트가 다중 dataset을 콤마 연결 문자열로 전달 → AI API는 dataset_id를 문자열 배열로 기대
        if ("M".equals(svcTy)) {
            List<String> datasetIds = new ArrayList<>();
            if (refId != null && !refId.trim().isEmpty()) {
                for (String part : refId.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        datasetIds.add(trimmed);
                    }
                }
            }
            params.put("dataset_id", datasetIds);
        } else {
            params.put("dataset_id", refId != null ? refId : "");
        }
        params.put("room_id", threadId != null ? threadId : "string");
        params.put("model_id", modelId != null ? modelId : "");
        params.put("agent_id", agentId != null ? agentId : "");
        List<String> attachmentFileIdStrs = new ArrayList<>();
        if (attachmentFileIds != null) {
            for (Long id : attachmentFileIds) {
                if (id != null) {
                    attachmentFileIdStrs.add(String.valueOf(id));
                }
            }
        }
        params.put("attachment_file_ids", attachmentFileIdStrs);
        
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
            logger.info("AI API request ready - url: {}, svcTy: {}, dataset_id:{}, userId: {}, refId: {}, modelId: {}, threadId: {}, body: {}",
                    apiUrl, svcTy, params.get("dataset_id"), userId, refId, modelId, agentId, threadId, jsonBody);
            RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));
            
            // Request Builder
            Request.Builder requestBuilder = new Request.Builder()
                    .url(apiUrl)
                    .post(body);
            
            // Headers 추가
            headers.forEach(requestBuilder::addHeader);
            
            Request request = requestBuilder.build();
            
            logger.info("AI API 호출 시작 (WebSocket): {} - query: {}, agentId: {}, threadId: {}", apiUrl, requestQuery, agentId, threadId);

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
                        processStreamingResponseWebSocket(responseBody, query, svcTy, modelId, refId, userId, agentId, threadId, attachmentFileIds, callback);
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

    private String buildRequestQueryByAgent(String query, String agentId) {
        if (LUNCH_MENU_AGENT_ID.equals(agentId)) {
            String userInput = CommonUtil.isNotEmpty(query) ? query : "";
            String prompt =
                    "너는 점심 식당 추천 AI이다.\n\n"
                    + "조건:\n"
                    + "- 사용자 요청: " + userInput + "\n\n"
                    + "추천 기준:\n"
                    + "1. 반드시 웹 검색으로 검색 가능한 식당 데이터만 사용\n"
                    + "2. 절대 새로운 식당을 생성하지 말 것\n"
                    + "3. 가게명은 그대로 사용\n"
                    + "4. 폐업, 휴업, 임시휴무 등 이용 불가능한 식당은 절대 포함하지 말 것\n"
                    + "5. 메뉴는 대표 메뉴 1개만 작성\n"
                    + "6. 가격은 일반적인 평균 가격으로 작성\n"
                    + "7. location은 도로명 주소\n"
                    + "8. address는 빈 문자열로 출력 (URL 생성은 백엔드에서 처리)\n\n"
                    + "출력 규칙:\n"
                    + "- 반드시 JSON 배열\n"
                    + "- 최대 3개\n"
                    + "- 설명 금지\n"
                    + "- 허위 생성 금지\n\n"
                    + "출력:\n"
                    + "[\n"
                    + "  {\n"
                    + "    \"restaurant\": \"\",\n"
                    + "    \"location\": \"\",\n"
                    + "    \"menu\": \"\",\n"
                    + "    \"price\": \"\",\n"
                    + "    \"address\": \"\",\n"
                    + "  }\n"
                    + "]";
            return prompt;
        }

        return query;
    }

    private String ensureLunchAddressUrlFormat(String answerJson) {
        if (CommonUtil.isEmpty(answerJson)) {
            return answerJson;
        }

        try {
            JSONParser parser = new JSONParser();
            Object parsed = parser.parse(answerJson);
            if (!(parsed instanceof JSONArray)) {
                return answerJson;
            }

            JSONArray rows = (JSONArray) parsed;
            JSONArray normalizedRows = new JSONArray();
            for (Object rowObj : rows) {
                if (!(rowObj instanceof JSONObject)) {
                    continue;
                }
                JSONObject row = (JSONObject) rowObj;
                JSONObject normalizedRow = new JSONObject();
                String restaurant = getString(row.get("restaurant")).trim();
                String location = getString(row.get("location")).trim();
                String menu = getString(row.get("menu")).trim();
                String price = getString(row.get("price")).trim();

                normalizedRow.put("restaurant", restaurant);
                normalizedRow.put("location", location);
                normalizedRow.put("menu", menu);
                normalizedRow.put("price", price);
                normalizedRow.put("imageUrl", "[음식이미지]");

                String kakaoPlaceUrl = resolveKakaoPlaceUrlByKeyword(restaurant, location);
                normalizedRow.put("address", CommonUtil.isNotEmpty(kakaoPlaceUrl) ? kakaoPlaceUrl : "");
                normalizedRows.add(normalizedRow);
            }
            return normalizedRows.toJSONString();
        } catch (Exception e) {
            logger.warn("점심 추천 address URL 후처리 실패: {}", e.getMessage());
            return answerJson;
        }
    }

    /**
     * 점심 추천 항목의 메뉴명으로 이미지 API를 호출하고,
     * 프론트에서 바로 쓰는 data URL(data:image/...;base64,...)만 반환한다.
     * ({@code /ai/chatbot/getLunchMenuImageData.do}의 항목별 생성에 사용한다.)
     */
    public String getLunchMenuImageData(String menu) {
        String prompt = "음식 사진 생성. 설명 없이 음식만 사실적으로 표현. 음식명: " + menu;
        String imageResult = callAiImageApi(prompt);
        if (CommonUtil.isEmpty(imageResult)) {
            return "";
        }

        String normalized = imageResult.trim().replace("\\/", "/");
        if (normalized.startsWith("data:image/")) {
            return normalized;
        }
        return "data:image/png;base64," + normalized;
    }

    /**
     * 점심 카드용 — 메뉴명 목록에 대해 음식 이미지(data URL)를 생성한다.
     * 스트리밍 JSON의 {@code imageUrl}(예: 플레이스홀더)과 별개로, 실제 이미지가 필요할 때 {@code /ai/chatbot/getLunchMenuImageData.do}에서 사용한다.
     * 순서는 입력 {@code menus}와 동일하며, 최대 3개만 처리한다.
     *
     * @return 각 원소는 {@code menu}(String), {@code imageUrl}(String) 키를 가진 맵 목록
     */
    public List<Map<String, Object>> getLunchFoodImagesForMenus(List<String> menus) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (menus == null || menus.isEmpty()) {
            return rows;
        }
        int limit = Math.min(menus.size(), 3);
        for (int i = 0; i < limit; i++) {
            String menu = menus.get(i) != null ? menus.get(i).trim() : "";
            Map<String, Object> row = new HashMap<>();
            row.put("menu", menu);
            if (CommonUtil.isEmpty(menu)) {
                row.put("imageUrl", "");
            } else {
                String lunchImageData = getLunchMenuImageData(menu);
                row.put("imageUrl", CommonUtil.isNotEmpty(lunchImageData) ? lunchImageData : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private String resolveKakaoPlaceUrlByKeyword(String restaurant, String location) {
        String kakaoApiUrl = PropertyUtil.getProperty("Globals.kakao.local.keyword.apiUrl");
        String kakaoRestApiKey = PropertyUtil.getProperty("Globals.kakao.restApiKey");
        if (CommonUtil.isEmpty(kakaoApiUrl) || CommonUtil.isEmpty(kakaoRestApiKey)) {
            return "";
        }

        List<String> candidateKeywords = new ArrayList<>();
        String fullKeyword = (restaurant + " " + location).trim();
        if (CommonUtil.isNotEmpty(fullKeyword)) {
            candidateKeywords.add(fullKeyword);
        }
        if (CommonUtil.isNotEmpty(restaurant) && !candidateKeywords.contains(restaurant)) {
            candidateKeywords.add(restaurant);
        }
        if (candidateKeywords.isEmpty()) {
            return "";
        }

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            for (String keyword : candidateKeywords) {
                HttpUrl requestUrl = HttpUrl.parse(kakaoApiUrl).newBuilder()
                        .addQueryParameter("query", keyword)
                        .addQueryParameter("size", "1")
                        .build();
                Request request = new Request.Builder()
                        .url(requestUrl)
                        .get()
                        .addHeader("Authorization", "KakaoAK " + kakaoRestApiKey)
                        .addHeader("Accept", "application/json")
                        .build();

                try (okhttp3.Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        logger.warn("카카오 장소 검색 응답 오류: {} / keyword={}", response.code(), keyword);
                        continue;
                    }

                    String body = response.body().string();
                    if (CommonUtil.isEmpty(body)) {
                        continue;
                    }

                    JSONParser parser = new JSONParser();
                    JSONObject root = (JSONObject) parser.parse(body);
                    Object docsObj = root.get("documents");
                    if (!(docsObj instanceof JSONArray)) {
                        continue;
                    }

                    JSONArray documents = (JSONArray) docsObj;
                    if (documents.isEmpty()) {
                        continue;
                    }

                    Object firstObj = documents.get(0);
                    if (!(firstObj instanceof JSONObject)) {
                        continue;
                    }

                    JSONObject first = (JSONObject) firstObj;
                    String placeId = getString(first.get("id")).trim();
                    if (CommonUtil.isEmpty(placeId)) {
                        continue;
                    }
                    return "https://place.map.kakao.com/" + placeId;
                }
            }
            return "";
        } catch (Exception e) {
            logger.warn("카카오 장소 URL 생성 실패 - restaurant: {}, location: {}, error: {}", restaurant, location, e.getMessage());
            return "";
        }
    }

    /**
     * WebSocket 방식으로 스트리밍 응답을 처리하여 클라이언트로 전달
     * 실시간 스트리밍을 위해 작은 버퍼 크기 사용
     */
    private void processStreamingResponseWebSocket(okhttp3.ResponseBody responseBody, String query, String svcTy, String modelId, String refId, String userId, String agentId, String threadId, List<Long> attachmentFileIds, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws IOException {

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream(), "UTF-8"), 1);
        boolean isLunchAgent = LUNCH_MENU_AGENT_ID.equals(agentId);

        String line;
        String currentEvent = null;
        StringBuilder accumulatedContent = new StringBuilder();
        boolean imageDataUrlPrefixAppended = false;
        String responseThreadId = threadId;
        boolean isCompleteCalled = false;
        boolean hasStreamError = false;

        int inputTokens = 0;
        int outputTokens = 0;
        String mainDocFileId = "";
        String mainPage = "";
        String savedLogId = "";
        String tableData = "";
        String chartOption = "";
        String sql = "";
        String ttsqParam = "";
        List<ChatRefItem> chatRefItems = new ArrayList<>();
        /** answer_source 스트림에서 누적 — done.data.items 가 있으면 그쪽이 최종 우선 */
        String webGroundingJson = "";

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
                            if (!isLunchAgent) {
                                callback.onChunk(text, accumulatedContent.toString(), null);
                            }
                        }
                        continue;
                    }

                    if ("answer_linked".equals(currentEvent)) {
                        String linkedText = getString(data.get("text"));
                        if (CommonUtil.isNotEmpty(linkedText)) {
                            accumulatedContent = new StringBuilder(linkedText);
                            if (!isLunchAgent) {
                                callback.onChunk(linkedText, accumulatedContent.toString(), "answer_linked");
                            }
                        }
                        continue;
                    }

                    if ("answer_source".equals(currentEvent)) {
                        if (isLunchAgent) {
                            continue;
                        }
                        Object itemsObj = data.get("items");
                        if (itemsObj instanceof JSONArray) {
                            JSONArray items = (JSONArray) itemsObj;
                            JSONObject fullPayload = new JSONObject();
                            fullPayload.put("items", items);
                            webGroundingJson = fullPayload.toJSONString();
                            JSONArray accumulatedItems = new JSONArray();
                            for (Object item : items) {
                                accumulatedItems.add(item);
                                JSONObject accPayload = new JSONObject();
                                accPayload.put("items", accumulatedItems);
                                String itemJson = item instanceof JSONObject
                                        ? ((JSONObject) item).toJSONString()
                                        : String.valueOf(item);
                                callback.onChunk(itemJson, accPayload.toJSONString(), "answer_source");
                            }
                        }
                        continue;
                    }

                    if ("answer_image".equals(currentEvent)) {
                        // 일반 채팅(svcTy=C)에서만 이미지 청크 전달
                        if (!"C".equals(svcTy)) {
                            continue;
                        }
                        String rawImage = getString(data.get("image"));
                        int contentLenBefore = accumulatedContent.length();
                        imageDataUrlPrefixAppended = appendImageChunkToAccumulatedContent(
                                accumulatedContent, imageDataUrlPrefixAppended, rawImage);
                        if (accumulatedContent.length() > contentLenBefore) {
                            callback.onChunk(
                                    accumulatedContent.substring(contentLenBefore),
                                    accumulatedContent.toString(),
                                    "answer_image");
                        }
                        continue;
                    }

                    if ("error".equals(currentEvent)) {
                        String errorCode = getString(data.get("errorCode"));
                        String errorContent = getString(data.get("errorContent"));
                        JSONObject errorPayload = new JSONObject();
                        errorPayload.put("errorCode", errorCode);
                        errorPayload.put("errorContent", errorContent);
                        callback.onError(errorPayload.toJSONString());
                        hasStreamError = true;
                        break;
                    }

                    if ("done".equals(currentEvent) || "complete".equals(currentEvent)) {
                            String answer = getAnswerText(data);
                            if (CommonUtil.isNotEmpty(answer)) {
                                if (isLunchAgent) {
                                    answer = ensureLunchAddressUrlFormat(answer);
                                    callback.onChunk(answer, answer, null);
                                    accumulatedContent = new StringBuilder(answer);
                                } else if (accumulatedContent.length() == 0) {
                                    callback.onChunk(answer, answer, null);
                                    accumulatedContent = new StringBuilder(answer);
                                }
                            }

                            mainDocFileId = getString(data.get("docFileId"));
                            mainPage = getString(data.get("page"));
                            inputTokens = parseTokenCount(data.get("input_token"));
                            outputTokens = parseTokenCount(data.get("output_token"));
                            tableData = toJsonIfExists(data.get("table_data"));
                            chartOption = toJsonIfExists(data.get("chart_option"));
                            sql = getString(data.get("sql"));
                            ttsqParam = toJsonIfExists(data.get("ttsq_param"));

                            chatRefItems = extractChatRefItems(data);

                            Object doneItems = data.get("items");
                            if (doneItems instanceof JSONArray && ((JSONArray) doneItems).size() > 0) {
                                JSONObject fullPayload = new JSONObject();
                                fullPayload.put("items", (JSONArray) doneItems);
                                webGroundingJson = fullPayload.toJSONString();
                            }

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
                String finalAnswerContent = accumulatedContent.toString();
                if (CommonUtil.isNotEmpty(finalAnswerContent) && !"llmTest".equals(svcTy)) {
                    try {
                        savedLogId = this.doInsertAiLog(
                                responseThreadId,
                                agentId,
                                query,
                                finalAnswerContent,
                                inputTokens,
                                outputTokens,
                                svcTy,
                                modelId,
                                refId,
                                userId,
                                tableData,
                                sql,
                                ttsqParam,
                                mainDocFileId,
                                mainPage,
                                chatRefItems,
                                webGroundingJson,
                                chartOption
                            );

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

                if (!hasStreamError && !isCompleteCalled && CommonUtil.isNotEmpty(finalAnswerContent)) {
                    ChatRefItem firstRef = !chatRefItems.isEmpty() ? chatRefItems.get(0) : null;

                    List<Integer> relatedPageNos = firstRef != null ? new ArrayList<>(firstRef.relatedPageNos) : new ArrayList<>();
                    String fallbackThreadId = responseThreadId != null
                            ? responseThreadId
                            : "thread-" + System.currentTimeMillis();

                    callback.onComplete(
                            finalAnswerContent,
                            mainDocFileId,
                            mainPage,
                            relatedPageNos,
                            fallbackThreadId,
                            CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null,
                            tableData,
                            chartOption);
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
     * answer_image 청크를 스트리밍 도착 순서대로 accumulatedContent에 삽입.
     * 이후 answer_delta 텍스트는 이미지 뒤에 이어 붙여 R_CONTENT·화면 순서를 맞춘다.
     *
     * @return data URL 접두사를 이미 붙였으면 true
     */
    private boolean appendImageChunkToAccumulatedContent(
            StringBuilder accumulatedContent, boolean imageDataUrlPrefixAppended, String rawImage) {
        if (accumulatedContent == null || CommonUtil.isEmpty(rawImage)) {
            return imageDataUrlPrefixAppended;
        }
        String trimmed = rawImage.trim().replace("\\/", "/");
        String chunk = stripDataUrlBase64Prefix(trimmed);
        if (CommonUtil.isEmpty(chunk)) {
            return imageDataUrlPrefixAppended;
        }
        if (!imageDataUrlPrefixAppended) {
            if (accumulatedContent.length() > 0) {
                accumulatedContent.append("\n\n");
            }
            accumulatedContent.append("data:image/png;base64,");
            imageDataUrlPrefixAppended = true;
        }
        accumulatedContent.append(chunk);
        return imageDataUrlPrefixAppended;
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
     * webGroundingJson: answer_source 스트림 또는 done.data.items — JSON {@code {"items":[{url,title},...]}}.
     */
    private String doInsertAiLog(
            String responseThreadId,
            String agentId,
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
            String ttsqParam,
            String mainDocFileId,
            String mainPage,
            List<ChatRefItem> chatRefItems,
            String webGroundingJson,
            String chartOption) throws Exception {

        ChatbotVO chatbotVO = new ChatbotVO();
        chatbotVO.setRoomId(Long.parseLong(responseThreadId));
        chatbotVO.setAgentId(agentId);
        chatbotVO.setSvcTy(svcTy);
        chatbotVO.setRefId(refId);
        chatbotVO.setQContent(query);
        chatbotVO.setModelId(modelId);
        chatbotVO.setInTokens(inputTokens);
        chatbotVO.setOutTokens(outputTokens);
        chatbotVO.setRContent(answer);
        chatbotVO.setUserId(userId);
        chatbotVO.setTableData(CommonUtil.isNotEmpty(tableData) ? tableData : null);
        chatbotVO.setChartOption(CommonUtil.isNotEmpty(chartOption) ? chartOption : null);
        chatbotVO.setSql(CommonUtil.isNotEmpty(sql) ? sql : null);
        chatbotVO.setTtsqParam(CommonUtil.isNotEmpty(ttsqParam) ? ttsqParam : null);
        chatbotVO.setWebGroundingJson(CommonUtil.isNotEmpty(webGroundingJson) ? webGroundingJson : null);
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
        chatbotVO.setTitle(CommonUtil.isEmpty(chatLog.getQContent()) ? chatLog.getRoomTitle() : generateSummaryTitle(chatLog.getQContent(), chatLog.getRContent()));
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
     * @param purpose 로깅용 호출 목적 (title, tags, reAskReport 등). reAskReport는 응답 지연 대비 읽기 타임아웃이 더 김.
     * @return AI 응답 텍스트, 실패 시 null
     */
    public String callAiSummary(String prompt, String purpose) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.summary.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("{} 생성 실패 - GPT API URL 미설정", purpose);
            return null;
        }

        int readTimeoutSec = ("reAskReport".equals(purpose) || "insightReport".equals(purpose) || "createDoc".equals(purpose) || "news_curate".equals(purpose))
                ? SUMMARY_QUERY_READ_TIMEOUT_LONG_SEC
                : 60;

        Map<String, Object> params = new HashMap<>();
        params.put("query", prompt);
        params.put("room_id", "");

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(readTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
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
    private String generateSummaryThumbImg(String qContent, String rContent) throws Exception {
        if (CommonUtil.isEmpty(qContent) && CommonUtil.isEmpty(rContent)) {
            return null;
        }

        String promptContent = promptService.getPrompt("PI000018", "Y"); // 썸네일 이미지생성 프롬프트트

        if (CommonUtil.isNotEmpty(qContent)) {
            promptContent += "\n질문: " + truncateTitle(qContent, 200) + ' ';
        }
        if (CommonUtil.isNotEmpty(rContent)) {
            promptContent += "\n답변: " + truncateTitle(rContent, 500);
        }

        return callAiImageApi(promptContent);
    }

    /**
     * Globals.chatbot.image.apiUrl 동기 호출. 응답 JSON의 image 필드(base64)를 반환한다.
     * data:image/...;base64, 접두사가 있으면 제거한 순수 base64만 저장한다.
     */
    private String callAiImageApi(String query) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.image.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("이미지 생성 실패 - image API URL 미설정");
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
     * 로그인 사용자 뉴스 관심 카테고리 조회 (TB_USER_INTEREST_NEWS_CATEGORY)
     */
    public Map<String, Object> selectUserNewsInterestCategory(ChatbotVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        String userId = SessionUtil.getUserId();
        if (CommonUtil.isEmpty(userId)) {
            resultMap.put("codeIds", Collections.emptyList());
            return resultMap;
        }
        ChatbotVO param = new ChatbotVO();
        param.setUserId(userId);
        ChatbotVO saved = chatbotDAO.selectUserNewsInterestCategory(param);
        List<String> codeIds = parseNewsCategoryCdJson(saved != null ? saved.getNewsCategoryCd() : null);
        resultMap.put("codeIds", codeIds);
        if (saved != null) {
            resultMap.put("modifyDt", saved.getModifyDt());
        }
        return resultMap;
    }

    /**
     * 로그인 사용자 뉴스 관심 카테고리 저장 (사용자당 1행, NEWS_CATEGORY_CD JSON)
     */
    public Map<String, Object> saveUserNewsInterestCategories(ChatbotVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        String userId = SessionUtil.getUserId();
        if (CommonUtil.isEmpty(userId)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "로그인이 필요합니다.");
            return resultMap;
        }
        List<String> codeIds = searchVO != null && searchVO.getNewsCategoryCodeIdList() != null
                ? searchVO.getNewsCategoryCodeIdList()
                : Collections.emptyList();
        ChatbotVO param = new ChatbotVO();
        param.setUserId(userId);
        param.setNewsCategoryCd(NEWS_CURATE_PROMPT_GSON.toJson(codeIds));

        ChatbotVO existing = chatbotDAO.selectUserNewsInterestCategory(param);
        if (existing != null && CommonUtil.isNotEmpty(existing.getNewscgId())) {
            param.setNewscgId(existing.getNewscgId());
        } else {
            param.setNewscgId(keyGenerate.generateTableKey("NI", "TB_USER_INTEREST_NEWS_CATEGORY", "NEWSCG_ID"));
        }
        chatbotDAO.upsertUserNewsInterestCategories(param);
        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "");
        resultMap.put("codeIds", codeIds);
        return resultMap;
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
     * 대화방 첨부파일 업로드 여부 사전 확인
     */
    public Map<String, Object> checkRoomAttachment(ChatbotVO chatbotVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        if (chatbotVO == null || chatbotVO.getRoomId() == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "roomId가 필요합니다.");
            return resultMap;
        }

        String hasAttachment = chatbotDAO.selectHasAttachmentByRoomId(chatbotVO);
        resultMap.put("successYn", true);
        resultMap.put("hasAttachment", "Y".equals(hasAttachment));
        return resultMap;
    }
    
    /**
     * 채팅 첨부 업로드용 presigned URL 발급
     * - 요청 filePath를 스토리지 키로 사용
     */
    public Map<String, Object> saveChatFileUploadUrl(ChatbotVO chatbotVO) {
        FileVO req = new FileVO();
        req.setFileName(chatbotVO.getFileName());
        req.setFileType(chatbotVO.getFileType());
        if (chatbotVO.getFileSize() != null) {
            req.setFileSize(String.valueOf(chatbotVO.getFileSize()));
        }
        if (CommonUtil.isNotEmpty(chatbotVO.getFilePath())) {
            req.setKey(chatbotVO.getFilePath());
        }
        if (CommonUtil.isNotEmpty(chatbotVO.getStoreFileName())) {
            req.setStoreFileName(chatbotVO.getStoreFileName());
        }
        return fileService.createUploadPresignedUrl(req);
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
            resultMap.put("fileShareYn", validRoom.getIncludeAttachment());
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
     * 공유 링크(유효 토큰)의 원본 대화 로그를 로그인 사용자의 대화방으로 복사한다.
     * TB_CHAT_REF(M 타입 참조 행)는 새 LOG_ID에 맞게 함께 복사한다.
     * TB_CHAT_FILE은 동일 STORAGE 경로를 가리키는 행만 복사하며 CREATE_USER_ID는 원본 업로더를 유지한다.
     *
     * @param searchVO roomId: 복사 대상(신규) 대화방, shareToken: 공유 토큰
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> copySharedChatLogsToRoom(ChatbotVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        String userId = SessionUtil.getUserId();

        if (searchVO == null || searchVO.getRoomId() == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "roomId가 필요합니다.");
            return resultMap;
        }

        String shareToken = searchVO.getShareToken();
        if (CommonUtil.isEmpty(shareToken)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "shareToken이 필요합니다.");
            return resultMap;
        }

        ChatbotVO tokenParam = new ChatbotVO();
        tokenParam.setShareToken(shareToken);
        ChatbotVO validRoom = chatbotDAO.selectShareTokenValidRoomId(tokenParam);
        if (validRoom == null || validRoom.getRoomId() == null) {
            int exists = chatbotDAO.countShareTokenByToken(tokenParam);
            resultMap.put("successYn", false);
            if (exists > 0) {
                resultMap.put("returnMsg", "만료된 공유 URL입니다.");
            } else {
                resultMap.put("returnMsg", "유효하지 않은 공유 링크입니다.");
            }
            return resultMap;
        }

        Long sourceRoomId = validRoom.getRoomId();
        Long destRoomId = searchVO.getRoomId();
        if (sourceRoomId.longValue() == destRoomId.longValue()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "원본과 동일한 대화방입니다.");
            return resultMap;
        }

        ChatbotVO roomOwnerParam = new ChatbotVO();
        roomOwnerParam.setRoomId(destRoomId);
        roomOwnerParam.setUserId(userId);
        if (chatbotDAO.countChatRoomOwnedByUser(roomOwnerParam) <= 0) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "대화방이 없거나 복사 권한이 없습니다.");
            return resultMap;
        }

        ChatbotVO srcRoomParam = new ChatbotVO();
        srcRoomParam.setRoomId(sourceRoomId);

        List<ChatbotVO> sourceLogs = chatbotDAO.selectChatLogsForShareCopy(srcRoomParam);
        List<ChatbotVO> refRows = chatbotDAO.selectChatRefsForShareCopyRoom(srcRoomParam);
        List<ChatbotVO> fileRows = chatbotDAO.selectChatFilesForShareCopyRoom(srcRoomParam);
        Map<Long, List<ChatbotVO>> refsBySourceLogId = new HashMap<>();
        for (ChatbotVO r : refRows) {
            Long oldLogId = r.getLogId();
            if (oldLogId == null) {
                continue;
            }
            refsBySourceLogId.computeIfAbsent(oldLogId, k -> new ArrayList<>()).add(r);
        }
        Map<Long, List<ChatbotVO>> filesBySourceLogId = new HashMap<>();
        for (ChatbotVO f : fileRows) {
            Long oldLogId = f.getLogId();
            if (oldLogId == null) {
                continue;
            }
            filesBySourceLogId.computeIfAbsent(oldLogId, k -> new ArrayList<>()).add(f);
        }

        int copied = 0;
        for (ChatbotVO src : sourceLogs) {
            Long oldLogId = src.getLogId();
            ChatbotVO ins = new ChatbotVO();
            ins.setRoomId(destRoomId);
            ins.setUserId(userId);
            ins.setAgentId(src.getAgentId());
            ins.setSvcTy(src.getSvcTy());
            ins.setRefId(CommonUtil.isNotEmpty(src.getRefId()) ? src.getRefId() : null);
            ins.setModelId(CommonUtil.isNotEmpty(src.getModelId()) ? src.getModelId() : null);
            ins.setQContent(src.getQContent());
            ins.setRContent(src.getRContent());
            ins.setInTokens(src.getInTokens());
            ins.setOutTokens(src.getOutTokens());
            ins.setSatisYn(CommonUtil.isNotEmpty(src.getSatisYn()) ? src.getSatisYn() : null);
            ins.setSql(CommonUtil.isNotEmpty(src.getTtsq()) ? src.getTtsq() : null);
            ins.setTableData(CommonUtil.isNotEmpty(src.getTableData()) ? src.getTableData() : null);
            ins.setChartOption(CommonUtil.isNotEmpty(src.getChartOption()) ? src.getChartOption() : null);
            ins.setWebGroundingJson(CommonUtil.isNotEmpty(src.getWebGroundingJson()) ? src.getWebGroundingJson() : null);
            ins.setMainDocFileId(CommonUtil.isNotEmpty(src.getMainDocFileId()) ? src.getMainDocFileId() : null);
            ins.setMainPage(CommonUtil.isNotEmpty(src.getMainPage()) ? src.getMainPage() : null);
            ins.setReaskCnt(src.getReaskCnt());

            chatbotDAO.insertChatLog(ins);
            copied++;

            if ("M".equals(src.getSvcTy()) && oldLogId != null && ins.getLogId() != null) {
                List<ChatbotVO> refs = refsBySourceLogId.get(oldLogId);
                if (refs != null) {
                    for (ChatbotVO refSrc : refs) {
                        if (!CommonUtil.isNotEmpty(refSrc.getDocFileId())) {
                            continue;
                        }
                        ChatbotVO refIns = new ChatbotVO();
                        refIns.setLogId(ins.getLogId());
                        refIns.setDocFileId(refSrc.getDocFileId());
                        refIns.setMainPageNo(refSrc.getMainPageNo());
                        refIns.setRelatedPages(refSrc.getRelatedPages());
                        chatbotDAO.insertChatRef(refIns);
                    }
                }
            }

            if (oldLogId != null && ins.getLogId() != null) {
                List<ChatbotVO> attachList = filesBySourceLogId.get(oldLogId);
                if (attachList != null) {
                    for (ChatbotVO fSrc : attachList) {
                        if (!CommonUtil.isNotEmpty(fSrc.getFilePath())) {
                            continue;
                        }
                        ChatbotVO fIns = new ChatbotVO();
                        fIns.setRoomId(destRoomId);
                        fIns.setLogId(ins.getLogId());
                        fIns.setFileName(fSrc.getFileName());
                        fIns.setStoreFileName(fSrc.getStoreFileName());
                        fIns.setFilePath(fSrc.getFilePath());
                        fIns.setFileSize(fSrc.getFileSize());
                        fIns.setFileType(fSrc.getFileType());
                        fIns.setFileDelDt(fSrc.getFileDelDt());
                        String uploaderUserId = "Y".equals(searchVO.getFileShareYn())
                                ? userId
                                : fSrc.getChatFileUploaderUserId();
                        fIns.setChatFileUploaderUserId(uploaderUserId);
                        chatbotDAO.insertChatFileShareCopy(fIns);
                    }
                }
            }
        }

        if (copied > 0) {
            ChatbotVO lastChat = new ChatbotVO();
            lastChat.setRoomId(destRoomId);
            chatbotDAO.updateChatRoomLastChatDt(lastChat);
        }

        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        resultMap.put("copiedCnt", copied);
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
     * 채팅 첨부 미리보기 (사용자 검증 없음 — 공유 페이지 전용)
     */
    public Map<String, Object> viewChatFileShare(ChatbotVO searchVO) throws Exception {
        ChatbotVO row = chatbotDAO.selectChatFileById(searchVO);
        if (row == null || row.getFilePath() == null || row.getFilePath().trim().isEmpty()) {
            Map<String, Object> notFound = new HashMap<>();
            notFound.put("viewType", "DOWNLOAD");
            notFound.put("reason", "FILE_NOT_FOUND");
            notFound.put("fileName", "");
            notFound.put("downloadUrl", "");
            return notFound;
        }
        FileVO fileVo = new FileVO();
        fileVo.setFilePath(row.getFilePath());
        fileVo.setFileName(row.getFileName());
        fileVo.setFileType(row.getFileType());
        return fileService.createViewPresignedUrlForStorageObject(fileVo);
    }

    /**
     * 채팅 첨부 미리보기 (본인 대화방 + TB_CHAT_FILE.CREATE_USER_ID가 현재 사용자와 같거나 레거시 NULL인 경우만)
     */
    public Map<String, Object> viewChatFile(ChatbotVO searchVO) throws Exception {
        searchVO.setUserId(SessionUtil.getUserId());
        ChatbotVO row = chatbotDAO.selectChatFileOwnedByUser(searchVO);
        if (row == null || row.getFilePath() == null || row.getFilePath().trim().isEmpty()) {
            Map<String, Object> notFound = new HashMap<>();
            notFound.put("viewType", "DOWNLOAD");
            notFound.put("reason", "FILE_NOT_FOUND");
            notFound.put("fileName", "");
            notFound.put("downloadUrl", "");
            return notFound;
        }
        FileVO fileVo = new FileVO();
        fileVo.setFilePath(row.getFilePath());
        fileVo.setFileName(row.getFileName());
        fileVo.setFileType(row.getFileType());
        return fileService.createViewPresignedUrlForStorageObject(fileVo);
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

    /**
     * 외부에서 prompt를 받아 AI 요약 API를 호출하고 방사형 차트용 JSON 문자열을 반환한다.
     */
    public String getPsychologyChartData(String prompt) {
        return callAiSummary(prompt, "방사형 차트 데이터");
    }

    /**
     * 뉴스 큐레이션 에이전트 전용.
     * 관심 카테고리마다 RSS 수집 → AI 호출 → 서버에서 JSON 배열 병합.
     */
    private void deliverNewsRecommendationViaWebSocket(String query, String threadId, String userId, String svcTy,
            String modelId, String refId, String agentId, List<Long> attachmentFileIds,
            ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {
        String rawQuery = query != null ? query : "";
        List<String> interestCategories = selectNewsInterestCategoryCodeIds(userId);
        if (interestCategories.isEmpty()) {
            callback.onError("뉴스 관심 카테고리가 설정되지 않았습니다.");
            return;
        }
        List<String> categoryJsonList = new ArrayList<>();
        for (String codeId : interestCategories) {
            List<RssArticleRow> categoryRows = NewsRssUtil.collectCandidatesForCodeId(restApiManager, logger, codeId);
            if (categoryRows.isEmpty()) {
                continue;
            }
            String categoryLabel = categoryRows.get(0).getRssCategory();
            String curatorPrompt = appendNewsCandidateArticlesToCuratorQuery(rawQuery, categoryRows, codeId, categoryLabel);
            String categoryAiJson = runNewsCuratorAi(curatorPrompt, categoryRows);
            if (CommonUtil.isEmpty(categoryAiJson)) {
                continue;
            }
            categoryJsonList.add(categoryAiJson.trim());
        }
        String curatorAiJson = categoryJsonList.isEmpty() ? "" : mergeNewsCuratorCategoryResponses(categoryJsonList);
        if (CommonUtil.isEmpty(curatorAiJson)) {
            String msg = "AI가 선정한 뉴스를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.";
            callback.onError(msg);
            return;
        }
        callback.onChunk(curatorAiJson, curatorAiJson, null);
        String qContentForDb = buildNewsCurationQContentForDb(rawQuery, interestCategories);
        String savedLogId = "";
        if (!"llmTest".equals(svcTy) && CommonUtil.isNotEmpty(threadId)) {
            try {
                savedLogId = doInsertAiLog(
                        threadId,
                        agentId,
                        qContentForDb,
                        curatorAiJson,
                        0,
                        0,
                        svcTy,
                        modelId,
                        refId,
                        userId,
                        "",
                        "",
                        "",
                        "",
                        "",
                        new ArrayList<>(),
                        "",
                        "");
                updateChatRoomLastChatDt(threadId);
                if (CommonUtil.isNotEmpty(savedLogId) && attachmentFileIds != null && !attachmentFileIds.isEmpty()) {
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
        callback.onComplete(curatorAiJson, "", "", new ArrayList<>(), CommonUtil.nullToBlank(threadId),
                CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null, null, null);
    }

    /** AI 큐레이션 JSON 배열 응답. */
    private String runNewsCuratorAi(String curatorPrompt, List<RssArticleRow> rssCandidateRows) {
        String curatorAiJson = callAiSummary(curatorPrompt, "news_curate");
        if (CommonUtil.isEmpty(curatorAiJson)) {
            return "";
        }
        return curatorAiJson.trim();
    }

    private static String appendNewsCandidateArticlesToCuratorQuery(String frontendTemplate, List<RssArticleRow> candidates,
            String codeId, String categoryLabel) {
        String block = buildNewsCandidateArticlesMetadataBlock(candidates, codeId, categoryLabel);
        int roleIdx = frontendTemplate.indexOf("[역할]");
        if (roleIdx >= 0) {
            return frontendTemplate.substring(0, roleIdx).trim() + "\n" + block + "\n" + frontendTemplate.substring(roleIdx);
        }
        return frontendTemplate.trim() + "\n\n" + block;
    }

    /** TB_CHAT_LOG.Q_CONTENT — 프롬프트 템플릿 + 관심 카테고리 CODE_ID 목록 */
    private static String buildNewsCurationQContentForDb(String rawQuery, List<String> categoryCodeIds) {
        String base = rawQuery != null ? rawQuery.trim() : "";
        String categoryLine = "- 선정 대상 카테고리: " + NEWS_CURATE_PROMPT_GSON.toJson(categoryCodeIds);
        if (CommonUtil.isEmpty(base)) {
            return categoryLine;
        }
        return base + "\n\n" + categoryLine;
    }

    private static String buildNewsCandidateArticlesMetadataBlock(List<RssArticleRow> candidates, String codeId,
            String categoryLabel) {
        List<Map<String, Object>> curatorInputArticles = new ArrayList<>(candidates.size());
        for (RssArticleRow candidateRow : candidates) {
            curatorInputArticles.add(NewsRssUtil.curatorPromptArticleMap(candidateRow));
        }
        String articlesJson = NEWS_CURATE_PROMPT_GSON.toJson(curatorInputArticles);
        String normalizedCodeId = codeId != null ? codeId.trim() : "";
        String normalizedCategoryLabel = categoryLabel != null ? categoryLabel.trim() : "";
        if (normalizedCategoryLabel.isEmpty()) {
            normalizedCategoryLabel = CommonUtil.isNotEmpty(normalizedCodeId) ? normalizedCodeId : "미분류";
        }
        StringBuilder block = new StringBuilder();
        block.append("- 선정 대상 카테고리: ").append(normalizedCategoryLabel)
                .append(" (CODE_ID: ").append(normalizedCodeId).append(")\n");
        block.append("- 후보 기사 목록(JSON 배열): ").append(articlesJson);
        return block.toString();
    }

    /**
     * 카테고리별 AI 응답 JSON 문자열을 파싱 없이 문자열 그대로 이어 붙여 하나의 JSON 배열로 만든다.
     * 각 응답이 이미 배열 형태([ ... ])라고 가정하며, 그렇지 않으면 원문 그대로 하나의 원소로 포함한다.
     */
    private static String mergeNewsCuratorCategoryResponses(List<String> categoryJsonList) {
        StringBuilder sb = new StringBuilder("[");
        boolean firstItem = true;
        for (String json : categoryJsonList) {
            String trimmed = json.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                String inner = trimmed.substring(1, trimmed.length() - 1).trim();
                if (inner.isEmpty()) {
                    continue;
                }
                if (!firstItem) {
                    sb.append(",");
                }
                sb.append(inner);
            } else {
                if (!firstItem) {
                    sb.append(",");
                }
                sb.append(trimmed);
            }
            firstItem = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /** TB_USER_INTEREST_NEWS_CATEGORY.NEWS_CATEGORY_CD → RSS용 codeId 목록 */
    private List<String> selectNewsInterestCategoryCodeIds(String userId) throws Exception {
        if (CommonUtil.isEmpty(userId)) {
            return Collections.emptyList();
        }
        ChatbotVO param = new ChatbotVO();
        param.setUserId(userId);
        ChatbotVO saved = chatbotDAO.selectUserNewsInterestCategory(param);
        return parseNewsCategoryCdJson(saved != null ? saved.getNewsCategoryCd() : null);
    }

    private List<String> parseNewsCategoryCdJson(String newsCategoryCdJson) {
        if (CommonUtil.isEmpty(newsCategoryCdJson)) {
            return Collections.emptyList();
        }
        String trimmed = newsCategoryCdJson.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return Collections.emptyList();
        }
        try {
            Type listType = new TypeToken<List<String>>() {
            }.getType();
            List<String> parsed = NEWS_CURATE_PROMPT_GSON.fromJson(trimmed, listType);
            if (parsed == null || parsed.isEmpty()) {
                return Collections.emptyList();
            }
            return parsed.stream().filter(s -> s != null && !s.trim().isEmpty()).map(String::trim).collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

}
