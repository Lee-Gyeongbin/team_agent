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
import java.util.concurrent.ConcurrentHashMap;
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
import kr.teamagent.chat.service.impl.agent.NewsCurationAgentService;
import kr.teamagent.chat.service.impl.agent.ProposalAgentService;
import kr.teamagent.chat.service.impl.agent.ResearcherAgentService;
import kr.teamagent.chat.service.impl.agent.RiskDiagnosisAgentService;
import kr.teamagent.chat.service.impl.agent.SurveyAgentService;
import kr.teamagent.chat.service.impl.agent.TranslationAgentService;
import kr.teamagent.common.apilog.service.impl.ApiCallLogServiceImpl;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.common.util.NewsRssUtil;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.RestApiManager;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.TranslationDocUtil;
import kr.teamagent.prompt.service.impl.PromptServiceImpl;
import kr.teamagent.datamart.service.DatamartVO;
import kr.teamagent.datamart.service.impl.DatamartDAO;
import kr.teamagent.tmpl.service.impl.TmplHtmlRenderService;
import kr.teamagent.tmpl.service.impl.TmplServiceImpl;
import kr.teamagent.tmpl.service.TmplVO;
import kr.teamagent.library.service.LibraryVO;
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

    /** summary_query 동기 호출 시 프롬프트·응답이 커 지연이 길어질 수 있는 경우의 OkHttp 읽기 타임아웃(초). */
    private static final int SUMMARY_QUERY_READ_TIMEOUT_LONG_SEC = 180;
    private static final Gson NEWS_CURATE_PROMPT_GSON = new Gson();

    /** WebSocket 세션별 진행 중인 AI API 스트리밍 호출 — 정지 버튼 클릭 시 cancel() 처리용 */
    private static final ConcurrentHashMap<String, okhttp3.Call> activeStreamCalls = new ConcurrentHashMap<>();

    /** 다음 추천 질문 생성을 위한 스레드 풀 — 메인 응답 전송과 분리된 별도 비동기 LLM 호출 처리 */
    private static final java.util.concurrent.ExecutorService recommendQuestionExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "recommend-question-worker");
                thread.setDaemon(true);
                return thread;
            });

    private java.util.concurrent.ExecutorService getRecommendQuestionExecutor() {
        return recommendQuestionExecutor;
    }

    /**
     * 세션의 진행 중인 AI API 스트리밍 호출을 취소
     */
    public void cancelStream(String sessionId) {
        okhttp3.Call call = activeStreamCalls.get(sessionId);
        if (call != null) {
            call.cancel();
            logger.info("스트리밍 응답 중단 요청 처리: sessionId={}", sessionId);
        }
    }

    @Autowired
    ApiCallLogServiceImpl apiCallLogService;

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
    DatamartDAO datamartDAO;

    @Autowired
    RestApiManager restApiManager;

    @Autowired
    TmplServiceImpl tmplService;

    @Autowired
    TmplHtmlRenderService tmplHtmlRenderService;

    @Autowired
    ChatbotAgentSupport agentSupport;

    @Autowired
    NewsCurationAgentService newsCurationAgentService;

    @Autowired
    TranslationAgentService translationAgentService;

    @Autowired
    ResearcherAgentService researcherAgentService;

    @Autowired
    RiskDiagnosisAgentService riskDiagnosisAgentService;

    @Autowired
    ProposalAgentService proposalAgentService;

    @Autowired
    SurveyAgentService surveyAgentService;

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
                agentSupport.parseAgentSubAdditionalConfig(subCfg);
                subCfgByAgentId.put(subCfg.getAgentId(), subCfg);
            }
        }

        for (ChatbotVO agent : agentList) {
            agent.setSubCfg(subCfgByAgentId.get(agent.getAgentId()));
        }
        return agentList;
    }

    /**
     * Agent 서브 설정 파싱 — ChatbotAgentSupport로 위임
     * @param subCfg
     */
    private void parseAgentSubAdditionalConfig(ChatbotVO.AgtSubCfgVO subCfg) {
        agentSupport.parseAgentSubAdditionalConfig(subCfg);
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

    /**
     * AI API 스트리밍 응답 처리(WebSocket으로 들어온 채팅 요청을 에이전트 종류에 따라 분기하는 라우터)
     * @param session
     * @param query
     * @param threadId
     * @param userId
     * @param svcTy
     * @param modelId
     * @param refId
     * @param agentId
     * @param attachmentFileIds
     * @param callback
     * @throws Exception
     */
    public void streamAiResponseWebSocket(WebSocketSession session, String query, String threadId, String userId, String svcTy, String modelId, String refId, String agentId, List<Long> attachmentFileIds, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {

        ChatbotVO.AgtSubCfgVO agentSubCfg = agentSupport.getAgentSubCfg(agentId);
        if (agentSupport.isActiveSubCfg(agentSubCfg, ChatbotAgentSupport.CURATION_SUB_TY)) {
            newsCurationAgentService.deliverNewsRecommendationViaWebSocket(query, threadId, userId, svcTy, modelId, refId, agentId,
                    attachmentFileIds, agentSubCfg.getAdditionalConfigMap(), callback);
            return;
        }

        if (agentSupport.isActiveSubCfg(agentSubCfg, ChatbotAgentSupport.TRANSLATE_SUB_TY)) {
            // TB_CHAT_LOG.SVC_TY는 번역 에이전트(SVC_TY='W') 기준으로 저장한다.
            svcTy = "W";
            if (hasNonNullAttachmentId(attachmentFileIds)) {
                translationAgentService.deliverTranslationFileViaWebSocket(query, threadId, userId, svcTy, modelId, refId, agentId,
                        attachmentFileIds, callback);
                return;
            }
        }

        // RESEARCHER 에이전트: 웹검색 + RAG 통합 리서치 리포트
        if (agentSupport.isResearcherAgent(agentId)) {
            researcherAgentService.deliverResearchReportViaWebSocket(query, threadId, userId, svcTy, modelId, refId, agentId, attachmentFileIds, callback);
            return;
        }

        // RISK 에이전트: RFP(PDF) 업로드 → 섹션 분리 → 섹션별 병렬 LLM 진단 → 리포트
        if (agentSupport.isRiskDiagnosisAgent(agentId)) {
            // TB_CHAT_LOG.SVC_TY는 리스크진단 에이전트(SVC_TY='D') 기준으로 저장한다.
            svcTy = "D";
            riskDiagnosisAgentService.deliverRiskReportViaWebSocket(query, threadId, userId, svcTy, modelId, refId, agentId, attachmentFileIds, callback);
            return;
        }

        // PROPOSAL 에이전트: 요구사항(RFP) 업로드 → 자사RAG + 경쟁사RAG/웹서치 → 제안 슬라이드 JSON 생성
        if (agentSupport.isProposalAgent(agentId)) {
            svcTy = "D";
            proposalAgentService.deliverProposalDraftViaWebSocket(query, threadId, userId, svcTy, modelId, refId, agentId, attachmentFileIds, callback);
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
            case "W":
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
     * 스트리밍 호출용 URL 결정.
     * selectApiUrlEndpoint DB 조회는 1회만 수행하며, 에이전트 타입별 우선순위로 URL을 반환한다.
     */
    private String resolveStreamingApiUrl(String svcTy, String agentId, List<Long> attachmentFileIds) {
        ChatbotVO.AgtSubCfgVO subCfg = agentSupport.getAgentSubCfg(agentId);

        // 1. AUTO_RECOMMEND 검색전용 → query_search_only URL
        if ("C".equals(svcTy) && agentSupport.isAutoRecommendSearchOnlyAgent(subCfg)) {
            String searchOnlyApiUrl = PropertyUtil.getProperty("Globals.chatbot.apiIpSearchOnly");
            if (CommonUtil.isNotEmpty(searchOnlyApiUrl)) {
                logger.info("resolveStreamingApiUrl: auto-recommend agent(searchOnly) -> query_search_only URL");
                return searchOnlyApiUrl;
            }
        }

        // 2. TRANSLATE → 기본 chat URL (agentVO 조회 우회)
        if (agentSupport.isActiveSubCfg(subCfg, ChatbotAgentSupport.TRANSLATE_SUB_TY)) {
            logger.info("resolveStreamingApiUrl: translate agent -> default chat URL");
            return getApiUrl(svcTy);
        }

        // 3. 첨부파일 있는 일반채팅 → file_query URL
        if ("C".equals(svcTy) && hasNonNullAttachmentId(attachmentFileIds)) {
            String fileUrl = PropertyUtil.getProperty("Globals.chatbot.gpt.apiFileUrl");
            if (CommonUtil.isNotEmpty(fileUrl)) {
                logger.info("resolveStreamingApiUrl: svcTy=C with attachments → file_query URL");
                return fileUrl;
            }
        }

        // 4. DB agentVO URL 조회 — RECOMMEND 포함 모든 에이전트 공통 (1회 호출)
        //    TB_AGT.API_URL_CD 기반: 런치픽(006 → /lunch_query), 위켄더(003 → /query) 등
        if (CommonUtil.isNotEmpty(agentId)) {
            try {
                ChatbotVO searchVO = new ChatbotVO();
                searchVO.setAgentId(agentId);
                ChatbotVO agentVO = chatbotDAO.selectApiUrlEndpoint(searchVO);
                if (agentVO != null && CommonUtil.isNotEmpty(agentVO.getApiEndpoint())) {
                    String apiUrl = PropertyUtil.getProperty("Globals.chatbot.apiIp")
                            + agentVO.getApiPort() + agentVO.getApiEndpoint();
                    logger.info("resolveStreamingApiUrl: agent({}) DB URL -> {}", agentId, apiUrl);
                    return apiUrl;
                }
            } catch (Exception e) {
                logger.error("API URL 조회 중 오류 발생: {}", e.getMessage(), e);
                return getApiUrl(svcTy);
            }
        }

        // 5. RECOMMEND fallback → recommend_query URL 또는 기본 chat URL
        if (agentSupport.isPromptReadyRecommendAgent(subCfg)) {
            String recommendApiUrl = PropertyUtil.getProperty("Globals.chatbot.recommend.apiUrl");
            if (CommonUtil.isNotEmpty(recommendApiUrl)) {
                logger.info("resolveStreamingApiUrl: recommend-style agent -> recommend_query URL");
                return recommendApiUrl;
            }
            logger.info("resolveStreamingApiUrl: recommend-style agent -> default chat URL");
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
        chatbotVO.setRoomTitle(generateSummaryTitle(chatbotVO.getContent(), null, null));
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
     * Agent 필터 목록 조회 (USE_YN 무관 전체)
     * @return
     * @throws Exception
     */
    public List<ChatbotVO> selectAgtFilterList() throws Exception {
        return chatbotDAO.selectAgtFilterList();
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
            String sessionId = session.getId();
            final long callStartTime = System.currentTimeMillis();
            okhttp3.Call call = client.newCall(request);
            activeStreamCalls.put(sessionId, call);
            call.enqueue(new okhttp3.Callback() {
                /**
                 * HTTP 요청 자체가 실패한 경우 호출됨
                 * - 네트워크 오류
                 * - 타임아웃
                 * - 서버 연결 실패 등
                 * 이 시점에서는 AI 서버로부터 정상적인 HTTP 응답을 받지 못한 상태이다.
                 */
                @Override
                public void onFailure(okhttp3.Call call, IOException e) {
                    activeStreamCalls.remove(sessionId, call);
                    int respMs = (int) Math.min(System.currentTimeMillis() - callStartTime, Integer.MAX_VALUE);
                    if (call.isCanceled()) {
                        logger.info("AI API 호출이 사용자 요청으로 중단되었습니다: threadId={}", threadId);
                        apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "CHAT", jsonBody, 0, 0, respMs, "N", "사용자 중단", userId);
                        callback.onComplete("사용자 요청에 의해 응답 생성이 중단되었습니다.", "", "", new ArrayList<>(), threadId, null, "", "", "");
                        return;
                    }
                    logger.error("AI API 호출 실패: {}", e.getMessage(), e);
                    apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "CHAT", jsonBody, 0, 0, respMs, "N", e.getMessage(), userId);
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
                        int respMs = (int) Math.min(System.currentTimeMillis() - callStartTime, Integer.MAX_VALUE);
                        logger.error("AI API 응답 오류: {}", response.code());
                        apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "CHAT", jsonBody, 0, 0, respMs, "N", "HTTP " + response.code(), userId);
                        callback.onError("API 응답 오류: " + response.code());
                        return;
                    }

                    try (okhttp3.ResponseBody responseBody = response.body()) {
                        if (responseBody == null) {
                            int respMs = (int) Math.min(System.currentTimeMillis() - callStartTime, Integer.MAX_VALUE);
                            apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "CHAT", jsonBody, 0, 0, respMs, "N", "응답 본문 없음", userId);
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
                        processStreamingResponseWebSocket(responseBody, call, sessionId, query, svcTy, modelId, refId, userId, agentId, threadId, attachmentFileIds, jsonBody, callStartTime, apiUrl, callback);
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
        // RECOMMEND·AUTO_RECOMMEND: Frontend에서 완성형 프롬프트 전달 — 래핑 없이 그대로 반환
        if (agentSupport.isPromptReadyRecommendAgent(agentId)) {
            return query;
        }

        return query;
    }

    private String ensureLunchAddressUrlFormat(String answerJson, String agentId) {
        if (CommonUtil.isEmpty(answerJson)) {
            return answerJson;
        }

        // ADDITIONAL_CONFIG.features.imageEnrichment 모드
        //  - "kakaoImage" : 장소·행사명 키워드로 카카오 이미지 검색 → 실제 사진 썸네일 주입
        //  - 그 외(aiGenerate 등) : 기존 동작 유지 — 프론트가 placeholder 보고 AI 이미지 생성
        String imageMode = getRecommendImageEnrichmentMode(agentId);
        boolean useKakaoImage = "kakaoImage".equals(imageMode);

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

                String kakaoPlaceUrl = resolveKakaoPlaceUrlByKeyword(restaurant, location);

                if (useKakaoImage) {
                    // 카카오맵 place 페이지의 대표사진(og:image) 사용 — 없으면 빈 값(이미지 미표시)
                    String ogImage = CommonUtil.isNotEmpty(kakaoPlaceUrl) ? resolveKakaoPlaceOgImage(kakaoPlaceUrl) : "";
                    normalizedRow.put("imageUrl", ogImage);
                } else {
                    normalizedRow.put("imageUrl", "[음식이미지]");
                }

                normalizedRow.put("address", CommonUtil.isNotEmpty(kakaoPlaceUrl) ? kakaoPlaceUrl : "");
                normalizedRows.add(normalizedRow);
            }
            return normalizedRows.toJSONString();
        } catch (Exception e) {
            logger.warn("추천 결과 address/image URL 후처리 실패: {}", e.getMessage());
            return answerJson;
        }
    }

    /**
     * RECOMMEND 에이전트의 ADDITIONAL_CONFIG.features.imageEnrichment 값을 반환한다.
     * (예: "aiGenerate", "kakaoImage") — 미설정 시 빈 문자열.
     */
    private String getRecommendImageEnrichmentMode(String agentId) {
        ChatbotVO.AgtSubCfgVO subCfg = agentSupport.getAgentSubCfg(agentId);
        if (subCfg == null || subCfg.getAdditionalConfigMap() == null) {
            return "";
        }
        Object featuresObj = subCfg.getAdditionalConfigMap().get("features");
        if (!(featuresObj instanceof Map)) {
            return "";
        }
        Object mode = ((Map<?, ?>) featuresObj).get("imageEnrichment");
        return mode != null ? String.valueOf(mode) : "";
    }

    /**
     * 점심 추천 항목의 메뉴명으로 이미지 API를 호출하고,
     * 프론트에서 바로 쓰는 data URL(data:image/...;base64,...)만 반환한다.
     * ({@code /ai/chatbot/getLunchMenuImageData.do}의 항목별 생성에 사용한다.)
     */
    public String getLunchMenuImageData(String menu) {
        String prompt = "음식 사진 생성. 설명 없이 음식만 사실적으로 표현. 음식명: " + menu;
        String imageResult = callAiImageApi(prompt, null);
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

    // 카카오맵 place 페이지 og:image 추출용 (속성·content 순서 양방향 대응)
    private static final java.util.regex.Pattern OG_IMAGE_PATTERN = java.util.regex.Pattern.compile(
            "property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern OG_IMAGE_PATTERN_ALT = java.util.regex.Pattern.compile(
            "content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:image[\"']", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 카카오맵 place 페이지(place_url)의 OpenGraph 대표 이미지(og:image)를 추출한다.
     * (imageEnrichment == "kakaoImage" — 장소 대표사진)
     * 대표 사진이 없어 지도 캡처(staticmap) 등이 노출되면 빈 문자열을 반환한다.
     */
    private String resolveKakaoPlaceOgImage(String placeUrl) {
        if (CommonUtil.isEmpty(placeUrl)) {
            return "";
        }

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url(placeUrl)
                    .get()
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .addHeader("Accept", "text/html")
                    .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("카카오 place 페이지 응답 오류: {} / url={}", response.code(), placeUrl);
                    return "";
                }

                String html = response.body().string();
                if (CommonUtil.isEmpty(html)) {
                    return "";
                }

                java.util.regex.Matcher matcher = OG_IMAGE_PATTERN.matcher(html);
                String img = matcher.find() ? matcher.group(1).trim() : "";
                if (CommonUtil.isEmpty(img)) {
                    java.util.regex.Matcher altMatcher = OG_IMAGE_PATTERN_ALT.matcher(html);
                    img = altMatcher.find() ? altMatcher.group(1).trim() : "";
                }

                // 대표 사진 없음 → 지도 캡처(staticmap)만 노출되는 경우 제외
                if (CommonUtil.isEmpty(img) || img.contains("staticmap")) {
                    return "";
                }

                // 프로토콜 상대 URL(//...) / http 보정 → https
                if (img.startsWith("//")) {
                    return "https:" + img;
                }
                if (img.startsWith("http://")) {
                    return "https://" + img.substring("http://".length());
                }
                return img;
            }
        } catch (Exception e) {
            logger.warn("카카오 place og:image 추출 실패 - url: {}, error: {}", placeUrl, e.getMessage());
            return "";
        }
    }

    /**
     * WebSocket 방식으로 스트리밍 응답을 처리하여 클라이언트로 전달
     * 실시간 스트리밍을 위해 작은 버퍼 크기 사용
     */
    private void processStreamingResponseWebSocket(okhttp3.ResponseBody responseBody, okhttp3.Call call, String sessionId, String query, String svcTy, String modelId, String refId, String userId, String agentId, String threadId, List<Long> attachmentFileIds, String reqParamJson, long callStartTime, String apiUrl, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws IOException {

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream(), "UTF-8"), 1);
        ChatbotVO.AgtSubCfgVO subCfg = agentSupport.getAgentSubCfg(agentId);
        boolean isKakaoAddressEnrichmentAgent = agentSupport.isKakaoAddressEnrichmentAgent(subCfg);
        if (!isKakaoAddressEnrichmentAgent && agentSupport.isPromptReadyRecommendAgent(subCfg)) {
            logger.info("processStreamingResponse: recommend-style agent (agentId={}) — 일반 스트리밍 처리", agentId);
        }

        String line;
        String currentEvent = null;
        StringBuilder accumulatedContent = new StringBuilder();
        boolean imageDataUrlPrefixAppended = false;
        String responseThreadId = threadId;
        boolean isCompleteCalled = false;
        boolean hasStreamError = false;
        boolean isCancelled = false;

        int inputTokens = 0;
        int outputTokens = 0;
        String mainDocFileId = "";
        String mainPage = "";
        String savedLogId = "";
        String tableData = "";
        String chartOption = "";
        String sql = "";
        String ttsqParam = "";
        String ttsqPeriodParam = "";
        String retrieverQuery = "";
        String chunk = "";
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

                    
                    if ("status".equals(currentEvent)) {
                        String code = (String) data.get("code");
                        String message = (String) data.get("message");
                        JSONObject status = new JSONObject();
                        status.put("statusCode", code);
                        status.put("statusMessage", message);
                        callback.onStatus(code, message);
                        continue;
                    }

                    // AI 답변 청크
                    if ("answer_delta".equals(currentEvent)) {
                        String text = (String) data.get("text");
                        if (text != null && text.length() > 0) {
                            accumulatedContent.append(text);
                            if (!isKakaoAddressEnrichmentAgent) {
                                callback.onChunk(text, accumulatedContent.toString(), null);
                            }
                        }
                        continue;
                    }

                    // 출처 답변 청크
                    if ("answer_source".equals(currentEvent)) {
                        if (isKakaoAddressEnrichmentAgent) {
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

                    // 이미지 청크(base64 이미지 데이터)
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
                                if (isKakaoAddressEnrichmentAgent) {
                                    answer = ensureLunchAddressUrlFormat(answer, agentId);
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
                            Object ttsqPeriodParamObj = data.get("ttsq_period_param");
                            if (ttsqPeriodParamObj == null) {
                                ttsqPeriodParamObj = data.get("ttsq_period_param ");
                            }
                            ttsqPeriodParam = toJsonIfExists(ttsqPeriodParamObj);

                            Object retrieverQueryObj = data.get("retriever_query");
                            retrieverQuery = (retrieverQueryObj instanceof JSONArray || retrieverQueryObj instanceof JSONObject)
                                    ? toJsonIfExists(retrieverQueryObj)
                                    : getString(retrieverQueryObj);
                            Object chunkObj = data.get("chunk");
                            chunk = (chunkObj instanceof JSONArray || chunkObj instanceof JSONObject)
                                    ? toJsonIfExists(chunkObj)
                                    : getString(chunkObj);

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
            if (call.isCanceled()) {
                isCancelled = true;
                logger.info("스트리밍 응답이 사용자 요청으로 중단되었습니다: threadId={}", threadId);
            } else {
                logger.error("스트림 읽기 중 오류 발생 (클라이언트 연결 끊김 등): {}", e.getMessage());
                hasStreamError = true;
            }
        } finally {
            activeStreamCalls.remove(sessionId, call);
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
                                ttsqPeriodParam,
                                mainDocFileId,
                                mainPage,
                                chatRefItems,
                                webGroundingJson,
                                chartOption,
                                retrieverQuery,
                                chunk
                            );

                        this.updateChatRoomLastChatDt(responseThreadId);

                        // 첨부파일 LOG_ID 연결
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

                        // API 호출 로그 저장 (성공)
                        Long refLogIdLong = CommonUtil.isNotEmpty(savedLogId) ? Long.parseLong(savedLogId) : null;
                        int respMs = (int) Math.min(System.currentTimeMillis() - callStartTime, Integer.MAX_VALUE);
                        apiCallLogService.insertSilently(agentId, refLogIdLong, apiUrl, modelId, "CHAT", reqParamJson,
                                inputTokens, outputTokens, respMs, "Y", null, userId);

                    } catch (Exception e) {
                        logger.warn("챗봇 로그 저장 실패: {}", e.getMessage());
                    }
                } else if (hasStreamError) {
                    // 스트리밍 오류 — 채팅 로그 미저장이므로 REF_LOG_ID 없이 기록
                    int respMs = (int) Math.min(System.currentTimeMillis() - callStartTime, Integer.MAX_VALUE);
                    apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "CHAT", reqParamJson, inputTokens, outputTokens, respMs, "N", "스트리밍 오류", userId);
                }

                if (!isCompleteCalled && (isCancelled || (!hasStreamError && CommonUtil.isNotEmpty(finalAnswerContent)))) {
                    ChatRefItem firstRef = !chatRefItems.isEmpty() ? chatRefItems.get(0) : null;

                    List<Integer> relatedPageNos = firstRef != null ? new ArrayList<>(firstRef.relatedPageNos) : new ArrayList<>();
                    String fallbackThreadId = responseThreadId != null
                            ? responseThreadId
                            : "thread-" + System.currentTimeMillis();

                    String completeContent = (isCancelled && CommonUtil.isEmpty(finalAnswerContent))
                            ? "사용자 요청에 의해 응답 생성이 중단되었습니다."
                            : finalAnswerContent;

                    callback.onComplete(
                            completeContent,
                            mainDocFileId,
                            mainPage,
                            relatedPageNos,
                            fallbackThreadId,
                            CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null,
                            tableData,
                            chartOption,
                            sql);
                    isCompleteCalled = true;
                }

                // 다음 추천 질문 생성: 메인 응답 전송 후 별도 비동기 메시지로 전달
                // 일반질의(svcTy=C)는 agentId가 없는 순수 일반질의에서만 추천 질문 노출
                // (agentId가 있으면 일반질의를 활용한 에이전트 질의이므로 제외)
                boolean shouldSuggestNextQuestions = !isCancelled
                        && !hasStreamError
                        && ("C".equals(svcTy) || "S".equals(svcTy) || "M".equals(svcTy))
                        && (!"C".equals(svcTy) || CommonUtil.isEmpty(agentId))
                        && !agentSupport.isPromptReadyRecommendAgent(subCfg)
                        && CommonUtil.isNotEmpty(savedLogId)
                        && CommonUtil.isNotEmpty(finalAnswerContent);

                if (shouldSuggestNextQuestions) {
                    final String logIdForRecommend = savedLogId;
                    final Long refLogIdForRecommend = CommonUtil.isNotEmpty(savedLogId) ? Long.parseLong(savedLogId) : null;
                    final String queryForRecommend = query;
                    final String answerForRecommend = finalAnswerContent;
                    getRecommendQuestionExecutor().execute(() -> {
                        try {
                            List<String> nextQuestions = generateNextRecommendedQuestions(queryForRecommend, answerForRecommend, refLogIdForRecommend);
                            if (!nextQuestions.isEmpty()) {
                                callback.onRecommendQuestions(logIdForRecommend, nextQuestions);
                            }
                        } catch (Exception e) {
                            logger.warn("다음 추천 질문 생성 실패: {}", e.getMessage());
                        }
                    });
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
     * 패키지-private: 에이전트 서비스에서 호출 가능.
     */
    public List<ChatRefItem> extractChatRefItems(JSONObject data) {
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
     * TB_CHAT_LOG 저장 및 svcTy == M(리서처) 또는 D(리스크진단) 이면 TB_CHAT_REF(chatRefItems) 반복 저장.
     * webGroundingJson: answer_source 스트림 또는 done.data.items — JSON {@code {"items":[{url,title},...]}}.
     * 패키지-private: 에이전트 서비스에서 호출 가능.
     */
    public String doInsertAiLog(
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
            String ttsqPeriodParam,
            String mainDocFileId,
            String mainPage,
            List<ChatRefItem> chatRefItems,
            String webGroundingJson,
            String chartOption,
            String retrieverQuery,
            String chunk) throws Exception {

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
        chatbotVO.setTtsqPeriodParam(CommonUtil.isNotEmpty(ttsqPeriodParam) ? ttsqPeriodParam : null);
        chatbotVO.setWebGroundingJson(CommonUtil.isNotEmpty(webGroundingJson) ? webGroundingJson : null);
        chatbotVO.setMainDocFileId(CommonUtil.isNotEmpty(mainDocFileId) ? mainDocFileId : null);
        chatbotVO.setMainPage(CommonUtil.isNotEmpty(mainPage) ? mainPage : null);
        chatbotVO.setRetrieverQuery(CommonUtil.isNotEmpty(retrieverQuery) ? retrieverQuery : null);
        chatbotVO.setChunk(CommonUtil.isNotEmpty(chunk) ? chunk : null);

        chatbotDAO.insertChatLog(chatbotVO);

        if (("M".equals(svcTy) || "D".equals(svcTy)) && chatRefItems != null && !chatRefItems.isEmpty()) {
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
     * TB_CHAT_LOG 저장 (reportHtml 포함 오버로드).
     * RESEARCHER 에이전트 등 리포트 HTML을 함께 저장해야 하는 경우 사용한다.
     * 패키지-private: 에이전트 서비스에서 호출 가능.
     */
    public String doInsertAiLog(
            String responseThreadId, String agentId, String query, String answer,
            int inputTokens, int outputTokens, String svcTy, String modelId, String refId,
            String userId, String tableData, String sql, String ttsqParam, String ttsqPeriodParam,
            String mainDocFileId, String mainPage, List<ChatRefItem> chatRefItems,
            String webGroundingJson, String chartOption,
            String reportHtml) throws Exception {

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
        chatbotVO.setTtsqPeriodParam(CommonUtil.isNotEmpty(ttsqPeriodParam) ? ttsqPeriodParam : null);
        chatbotVO.setWebGroundingJson(CommonUtil.isNotEmpty(webGroundingJson) ? webGroundingJson : null);
        chatbotVO.setMainDocFileId(CommonUtil.isNotEmpty(mainDocFileId) ? mainDocFileId : null);
        chatbotVO.setMainPage(CommonUtil.isNotEmpty(mainPage) ? mainPage : null);
        chatbotVO.setReportHtml(CommonUtil.isNotEmpty(reportHtml) ? reportHtml : null);

        chatbotDAO.insertChatLog(chatbotVO);

        if (("M".equals(svcTy) || "D".equals(svcTy)) && chatRefItems != null && !chatRefItems.isEmpty()) {
            for (ChatRefItem refItem : chatRefItems) {
                if (!CommonUtil.isNotEmpty(refItem.docFileId)) {
                    continue;
                }
                ChatbotVO chatbotRefVO = new ChatbotVO();
                chatbotRefVO.setLogId(chatbotVO.getLogId());
                chatbotRefVO.setDocFileId(refItem.docFileId);
                chatbotRefVO.setMainPageNo(refItem.mainPageNo);
                chatbotRefVO.setRelatedPages(refItem.relatedPageNos.isEmpty() ? "" : refItem.relatedPageNos.toString());
                chatbotDAO.insertChatRef(chatbotRefVO);
            }
        }

        return String.valueOf(chatbotVO.getLogId());
    }

    /**
     * 챗봇 대화방 마지막 채팅 일시 업데이트
     * 패키지-private: 에이전트 서비스에서 호출 가능.
     * @param responseThreadId
     * @throws Exception
     */
    public void updateChatRoomLastChatDt(String responseThreadId) throws Exception {
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
        Long knowledgeRefLogId = chatLog.getLogId();
        chatbotVO.setTitle(CommonUtil.isEmpty(chatLog.getQContent()) ? chatLog.getRoomTitle() : generateSummaryTitle(chatLog.getQContent(), chatLog.getRContent(), knowledgeRefLogId));
        chatbotVO.setTags(generateSummaryTags(chatLog.getQContent(), chatLog.getRContent(), knowledgeRefLogId));

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
    private String generateSummaryTitle(String qContent, String rContent, Long refLogId) {
        if (CommonUtil.isEmpty(qContent)) {
            return truncateTitle(rContent, 50);
        }

        String prompt = "다음 대화의 핵심 내용을 20자 이내의 한 줄 제목으로 요약해줘. 제목만 출력해. "
                + "질문: " + truncateTitle(qContent, 200);
        if (CommonUtil.isNotEmpty(rContent)) {
            prompt += " 답변: " + truncateTitle(rContent, 500);
        }

        String result = callAiSummary(prompt, "title", refLogId);
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
    private String generateSummaryTags(String qContent, String rContent, Long refLogId) {
        if (CommonUtil.isEmpty(qContent) && CommonUtil.isEmpty(rContent)) {
            return "";
        }

        String prompt = "다음 대화에서 핵심 키워드를 최대 5개 추출해줘. 쉼표(,)로 구분해서 키워드만 출력해. 부연 설명 없이 키워드만.각 키워드는 10글자 이내로 하고, 반드시 최대 5개까지만 추출해줘. 쉼표(,)뒤에는 공백이 없도록 출력해줘."
                + "질문: " + truncateTitle(qContent, 200)
                + " 답변: " + truncateTitle(rContent, 500);

        String result = callAiSummary(prompt, "tags", refLogId);
        if (CommonUtil.isNotEmpty(result)) {
            return truncateTitle(result.trim(), 200);
        }
        return "";
    }

    /**
     * AI 서버를 통해 이전 질문/답변 맥락을 기반으로 다음 추천 질문을 생성한다.
     * 줄바꿈으로 구분된 최대 3개의 질문을 반환한다. 실패 시 빈 리스트 반환.
     */
    private List<String> generateNextRecommendedQuestions(String qContent, String rContent, Long refLogId) {
        if (CommonUtil.isEmpty(qContent) && CommonUtil.isEmpty(rContent)) {
            return Collections.emptyList();
        }

        String prompt = "다음은 사용자와 AI의 대화 내용이다.\n"
                + "질문: " + truncateTitle(qContent, 500) + "\n"
                + "답변: " + truncateTitle(rContent, 1000) + "\n\n"
                + "위 대화 맥락을 참고하여 사용자가 다음에 이어서 물어볼 만한 질문을 2~3개 한국어로 제안해줘. "
                + "각 질문은 25자 이내로 간결하게 작성하고, 줄바꿈으로만 구분해서 질문 텍스트만 출력해. "
                + "번호, 글머리 기호, 따옴표 등 부가 텍스트는 포함하지 마.";

        String result = callAiSummary(prompt, "nextQuestions", refLogId);
        if (CommonUtil.isEmpty(result)) {
            return Collections.emptyList();
        }

        return Arrays.stream(result.split("\n"))
                .map(String::trim)
                .map(line -> line.replaceFirst("^\\d+[.\\)\\-]\\s*", ""))
                .filter(CommonUtil::isNotEmpty)
                .map(line -> truncateTitle(line, 50))
                .limit(3)
                .collect(Collectors.toList());
    }

    /**
     * GPT endpoint에 동기 호출하여 AI 응답 텍스트를 반환한다.
     * ChatbotAgentSupport.callAiSummary 로 위임한다.
     *
     * @param prompt   요청 프롬프트
     * @param purpose  로깅용 호출 목적 (title, tags, reAskReport 등)
     * @param refLogId 참조 로그 ID
     * @return AI 응답 텍스트, 실패 시 null
     */
    public String callAiSummary(String prompt, String purpose, Long refLogId) {
        return agentSupport.callAiSummary(prompt, purpose, refLogId);
    }

    /** 질의 진단 프롬프트(평가기준) 관리 ID — RDB(prompt)에 등록 시 우선 사용, 없으면 기본 루브릭 */
    private static final String DIAGNOSE_PROMPT_ID = "PI_DIAGNOSE";


    /** 기본 평가 루브릭 (제안서 §2·§3·§6) — prompt 테이블에 PI_DIAGNOSE가 없을 때 사용 */
    private static final String DEFAULT_DIAGNOSE_RUBRIC = String.join("\n",
            "너는 데이터분석 질의의 품질을 평가하는 심사자다.",
            "사용자의 자연어 질문이 Text-to-SQL로 정확한 SQL을 만들 수 있을 만큼 구체적인지 판단한다.",
            "다음 항목을 기준으로 0~100점을 산정한다. (문장 길이·단어 수가 아니라 의미와 구체성으로 평가)",
            "- 지표(무엇을 조회): 25점",
            "- 기간: 20점",
            "- 올해, 작년, 최근 N개월/일, 상반기, 하반기 등 상대·자연어 기간 표현이 있으면 기간 항목(20점)을 충족한 것으로 본다. 단 '최근'만 단독으로 쓰여 범위가 불명확할 때만 period 보완질문을 한다.",
            "- 대상/필터: 20점",
            "- 집계·분석 방식: 15점",
            "- 데이터 매핑 가능성(아래 제공된 지표/구분으로 답할 수 있는가): 15점",
            "- 출력 형태: 5점",
            "판정 규칙:",
            "- 제공된 데이터로 답할 수 없는 주제면 status=OUT_OF_SCOPE, 대체 가능한 통계를 alternatives에 제시.",
            "- 80점 미만이면 status=CLARIFICATION_REQUIRED 또는 TERM_AMBIGUOUS. 이때만 clarificationQuestions·questionPreview를 작성한다.",
            "보완질문(clarificationQuestions) 규칙 — 80점 미만일 때만 적용:",
            "- SQL 생성에 '반드시' 필요한 핵심 누락만 담는다. 있으면 좋은 수준의 선택적 필터·조건은 절대 넣지 않는다.",
            "- 위 '이 데이터마트에서 제공하는 용어'에 근거가 없는 항목은 보완질문으로 만들지 않는다.",
            "- 각 항목: item, question, placeholder, options. 최대 3개.",
            "- options는 데이터마트 용어만 사용하고 빈 배열 금지. 원 질문의 placeholder 자리에 대체될 후보 단어·표현이어야 한다.",
            "- placeholder는 원 질문에서 대체되어야 하는 단어만 적는다(예: 기간, 매출액).",
            "- questionPreview는 원문을 유지하고, 대체되어야 하는 단어만 placeholder 글자 그대로 남긴다.",
            "- clarificationQuestions[].placeholder와 questionPreview의 대체 대상 단어가 1:1 일치해야 한다.",
            "- 80점 이상이면 status=READY, sqlGenerationAllowed=true, rewrittenQuestion 작성, clarificationQuestions=[], questionPreview=null.",
            "참고: 동의어·의미상 매핑이 가능하다면 OUT_OF_SCOPE로 판정하지 않는다. 실행 가능한 질의는 과도하게 막지 말 것.");

    /**
     * 데이터분석(SVC_TY='S') 질의 품질 진단 — LLM이 평가기준(프롬프트)으로 점수·상태·보완을 산정한다.
     * 응답은 프론트 QuestionDiagnosis 계약(JSON)과 동일한 맵으로 반환한다.
     * @param searchVO question, datamartId
     */
    public HashMap<String, Object> diagnoseQuestion(ChatbotVO searchVO) throws Exception {
        String question = searchVO != null && searchVO.getQuestion() != null ? searchVO.getQuestion().trim() : "";
        String datamartId = searchVO != null && searchVO.getDatamartId() != null ? searchVO.getDatamartId().trim() : "";

        if (CommonUtil.isEmpty(question)) {
            return diagnosisFallback("질문이 비어 있습니다. 조회할 내용을 입력해주세요.");
        }

        String prompt = buildDiagnosePrompt(question, buildTermContextForDiagnose(datamartId));
        String answer = callAiSummary(prompt, "diagnoseQuestion", null);
        HashMap<String, Object> parsed = parseDiagnosisJson(answer);
        if (parsed != null) {
            syncQuestionPreviewPlaceholders(question, parsed);
            return parsed;
        }
        return diagnosisFallback("질의 검증에 실패했습니다. 잠시 후 다시 시도해주세요.");
    }

    /** 평가기준 프롬프트 + 용어 컨텍스트 + 질문 + JSON 출력 지시를 합쳐 최종 프롬프트 구성 */
    private String buildDiagnosePrompt(String question, String termContext) {
        String base = "";
        try {
            base = promptService.getPrompt(DIAGNOSE_PROMPT_ID, "Y");
        } catch (Exception ignore) {
            // prompt 미등록 시 기본 루브릭 사용
        }
        if (CommonUtil.isEmpty(base)) {
            base = DEFAULT_DIAGNOSE_RUBRIC;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(base).append("\n\n");
        if (CommonUtil.isNotEmpty(termContext)) {
            sb.append("[이 데이터마트에서 제공하는 용어]\n").append(termContext).append("\n");
        }
        sb.append("[사용자 질문]\n").append(question).append("\n\n");
        sb.append("반드시 아래 JSON 형식 하나만 출력해라. 코드블록·설명·주석 없이 JSON만.\n");
        sb.append("{\n");
        sb.append("  \"status\": \"READY|CLARIFICATION_REQUIRED|TERM_AMBIGUOUS|OUT_OF_SCOPE\",\n");
        sb.append("  \"readinessScore\": 0,\n");
        sb.append("  \"interpretedIntent\": \"질문 의도 요약\",\n");
        sb.append("  \"rewrittenQuestion\": \"READY일 때 정제된 질문, 아니면 null\",\n");
        sb.append("  \"questionPreview\": \"READY이면 null, 보완 필요 시 원문 기반 문장(대체되어야 하는 단어는 placeholder 글자만)\",\n");
        sb.append("  \"clarificationQuestions\": [{ \"item\": \"period|metric|dimension\", \"question\": \"반드시 필요한 핵심 보완 질문\", \"placeholder\": \"원 질문에서 대체되어야 하는 단어\", \"options\": [\"placeholder 자리에 대체될 데이터마트 용어 후보\"] }],\n");
        sb.append("  \"alternatives\": [\"대체 통계1\"],\n");
        sb.append("  \"sqlGenerationAllowed\": false\n");
        sb.append("}");
        return sb.toString();
    }

    /** 진단 대상 데이터마트의 용어사전(TB_DM_TERM_DICT)을 프롬프트 컨텍스트 문자열로 구성 */
    private String buildTermContextForDiagnose(String datamartId) {
        if (CommonUtil.isEmpty(datamartId)) {
            return "";
        }
        try {
            DatamartVO dmVO = new DatamartVO();
            dmVO.setDatamartId(datamartId);
            List<DatamartVO.MetaTermDictRowVO> terms = datamartDAO.selectMetaTermDictList(dmVO);
            if (terms == null || terms.isEmpty()) {
                return "";
            }
            StringBuilder metrics = new StringBuilder();
            StringBuilder dims = new StringBuilder();
            for (DatamartVO.MetaTermDictRowVO t : terms) {
                if (t == null || "N".equals(t.getUseYn()) || CommonUtil.isEmpty(t.getTermNm())) {
                    continue;
                }
                String label = t.getTermNm();
                if (CommonUtil.isNotEmpty(t.getSynonyms())) {
                    label += "[동의어:" + t.getSynonyms() + "]";
                }
                if (CommonUtil.isNotEmpty(t.getSampleValues())) {
                    label += "(" + t.getSampleValues() + ")";
                }
                StringBuilder target = "DIMENSION".equals(t.getTermType()) ? dims : metrics;
                if (target.length() > 0) {
                    target.append(", ");
                }
                target.append(label);
            }
            StringBuilder sb = new StringBuilder();
            if (metrics.length() > 0) {
                sb.append("- 조회 가능한 지표: ").append(metrics).append("\n");
            }
            if (dims.length() > 0) {
                sb.append("- 분석 가능한 구분: ").append(dims).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("용어사전 컨텍스트 구성 실패: {}", e.getMessage());
            return "";
        }
    }

    /** LLM JSON 응답 파싱 — 코드블록/잡텍스트를 제거하고 진단 맵으로 변환. 실패 시 null */
    private HashMap<String, Object> parseDiagnosisJson(String answer) {
        if (CommonUtil.isEmpty(answer)) {
            return null;
        }
        String json = answer.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        json = json.substring(start, end + 1);
        try {
            Gson gson = new Gson();
            Type type = new TypeToken<HashMap<String, Object>>() {}.getType();
            HashMap<String, Object> map = gson.fromJson(json, type);
            if (map == null || map.get("status") == null) {
                return null;
            }
            boolean ready = "READY".equals(String.valueOf(map.get("status")));
            if (!(map.get("sqlGenerationAllowed") instanceof Boolean)) {
                map.put("sqlGenerationAllowed", ready);
            }
            return map;
        } catch (Exception ex) {
            logger.warn("진단 JSON 파싱 실패: {}", ex.getMessage());
            return null;
        }
    }

    /** questionPreview에서 clarificationQuestions.placeholder 위치에 { }를 붙인다. */
    private void syncQuestionPreviewPlaceholders(String originalQuestion, HashMap<String, Object> map) {
        if ("READY".equals(String.valueOf(map.get("status"))) || !(map.get("clarificationQuestions") instanceof List)) {
            return;
        }
        List<String> placeholders = new ArrayList<>();
        for (Object o : (List<?>) map.get("clarificationQuestions")) {
            if (!(o instanceof Map)) {
                continue;
            }
            Object ph = ((Map<?, ?>) o).get("placeholder");
            if (ph == null || String.valueOf(ph).trim().isEmpty()) {
                continue;
            }
            placeholders.add(String.valueOf(ph).trim());
        }
        if (placeholders.isEmpty()) {
            return;
        }
        String preview = map.get("questionPreview") != null ? String.valueOf(map.get("questionPreview")).trim() : "";
        if (CommonUtil.isEmpty(preview)) {
            preview = originalQuestion;
        }
        placeholders.sort((a, b) -> b.length() - a.length());
        for (String label : placeholders) {
            if (!preview.contains("{" + label + "}")) {
                preview = preview.replace(label, "{" + label + "}");
            }
        }
        map.put("questionPreview", preview);
    }

    /** 진단 실패/예외 시 안전한 폴백 — 전송 차단(sqlGenerationAllowed=false) 유지 */
    private HashMap<String, Object> diagnosisFallback(String message) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("status", "CLARIFICATION_REQUIRED");
        map.put("readinessScore", 0);
        map.put("interpretedIntent", message);
        map.put("rewrittenQuestion", null);
        map.put("questionPreview", null);
        map.put("alternatives", new ArrayList<>());
        Map<String, Object> cq = new HashMap<>();
        cq.put("item", "retry");
        cq.put("question", message);
        cq.put("placeholder", "");
        cq.put("options", new ArrayList<>());
        List<Map<String, Object>> cqs = new ArrayList<>();
        cqs.add(cq);
        map.put("clarificationQuestions", cqs);
        map.put("sqlGenerationAllowed", false);
        return map;
    }

    /**
     * 웹 검색 엔드포인트(query_search_only)에 동기 호출하여 검색 결과 텍스트와 출처를 반환한다.
     * SSE 이벤트:
     * - event: answer_delta / data: {"text": "..."}
     * - event: answer_source / data: {"items":[{url,title},..]}
     * - event: done / data: {"answer": "...", ...}
     * @return [0]=answer text, [1]=출처 문자열(제목: URL 줄바꿈 목록). 실패 시 null.
     */
    /** 패키지-private: 에이전트 서비스에서 호출 가능. */
    public String[] callWebSearchSync(String query, String modelId) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.apiIpSearchOnly");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("웹 검색 실패 - query_search_only URL 미설정");
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("model_id", modelId != null ? modelId : "");
        params.put("room_id", "");

        com.google.gson.Gson wsGson = new com.google.gson.Gson();
        String wsReqJson = wsGson.toJson(params);
        long wsStartMs = System.currentTimeMillis();

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(SUMMARY_QUERY_READ_TIMEOUT_LONG_SEC, java.util.concurrent.TimeUnit.SECONDS)
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            String jsonBody = wsReqJson;
            RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build();

            logger.info("웹 검색 호출 시작 - url: {}", apiUrl);

            try (okhttp3.Response response = client.newCall(request).execute()) {
                int wsRespMs = (int) Math.min(System.currentTimeMillis() - wsStartMs, Integer.MAX_VALUE);
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("웹 검색 응답 오류: {}", response.code());
                    apiCallLogService.insertSilently(null, null, apiUrl, modelId, "webSearch", wsReqJson, 0, 0, wsRespMs, "N", "HTTP " + response.code(), null);
                    return null;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    // /query SSE 응답 파싱
                    // - event: answer_delta / data: {"text": "..."}            → 텍스트 누적
                    // - event: answer_source / data: {"items":[{url,title},..]} → 웹 그라운딩 출처
                    // - event: done / data: {"answer": "...", ...}             → 최종 답변
                    // (단일 JSON 응답 {"answer":...}도 처리 — data: 접두 없이 본문 전체가 JSON일 때)
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(responseBody.byteStream(), "UTF-8"));
                    StringBuilder answerBuilder = new StringBuilder();
                    StringBuilder sourceBuilder = new StringBuilder();
                    String doneAnswer = "";
                    String line;
                    JSONParser jsonParser = new JSONParser();
                    java.util.Set<String> seenUrls = new java.util.LinkedHashSet<>();

                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event: ")) {
                            continue;
                        }
                        String jsonStr;
                        if (line.startsWith("data: ")) {
                            jsonStr = line.substring(6).trim();
                        } else if (line.trim().startsWith("{")) {
                            // SSE가 아닌 단일 JSON 응답 라인
                            jsonStr = line.trim();
                        } else {
                            continue;
                        }
                        if (jsonStr.isEmpty()) {
                            continue;
                        }
                        try {
                            JSONObject data = (JSONObject) jsonParser.parse(jsonStr);
                            Object textObj = data.get("text");
                            if (textObj != null) {
                                answerBuilder.append(String.valueOf(textObj));
                            }
                            Object answerObj = data.get("answer");
                            if (answerObj != null) {
                                doneAnswer = String.valueOf(answerObj);
                            }
                            // answer_source: items 배열(url/title)
                            Object itemsObj = data.get("items");
                            if (itemsObj instanceof JSONArray) {
                                for (Object it : (JSONArray) itemsObj) {
                                    if (!(it instanceof JSONObject)) continue;
                                    JSONObject item = (JSONObject) it;
                                    String url = getString(item.get("url")).trim();
                                    String title = getString(item.get("title")).trim();
                                    if (!url.isEmpty() && seenUrls.add(url)) {
                                        sourceBuilder.append("- ");
                                        if (!title.isEmpty()) sourceBuilder.append(title).append(": ");
                                        sourceBuilder.append(url).append("\n");
                                    }
                                }
                            }
                            // answer_source: 문자열 형태(혹시 모를 호환)
                            Object sourceObj = data.get("answer_source");
                            if (sourceObj != null && CommonUtil.isNotEmpty(String.valueOf(sourceObj))
                                    && !"None".equalsIgnoreCase(String.valueOf(sourceObj).trim())) {
                                sourceBuilder.append(String.valueOf(sourceObj)).append("\n");
                            }
                        } catch (Exception ignore) {
                            // 개별 라인 파싱 실패는 무시하고 계속
                        }
                    }

                    // done.answer가 있으면 우선 사용, 없으면 델타 누적분 사용
                    String answer = CommonUtil.isNotEmpty(doneAnswer) ? doneAnswer : answerBuilder.toString();
                    String answerSource = sourceBuilder.toString();
                    apiCallLogService.insertSilently(null, null, apiUrl, modelId, "webSearch", wsReqJson, 0, 0, wsRespMs, "Y", null, null);
                    return new String[]{ answer, answerSource };
                }
            }
        } catch (Exception e) {
            int wsRespMs = (int) Math.min(System.currentTimeMillis() - wsStartMs, Integer.MAX_VALUE);
            logger.warn("웹 검색 중 오류 발생: {}", e.getMessage());
            apiCallLogService.insertSilently(null, null, apiUrl, modelId, "webSearch", wsReqJson, 0, 0, wsRespMs, "N", e.getMessage(), null);
        }
        return null;
    }

    /**
     * 템플릿 필드(TB_TMPL_FIELD)로부터 LLM JSON 응답 지시문을 동적으로 생성한다.
     * 어떤 템플릿이든 그 템플릿의 jsonKey 목록에 맞춰 LLM이 응답하도록 강제한다.
     * - layoutType=table → 객체 배열, multilineYn=Y → 문자열 배열, 그 외 → 문자열
     */
    /** 패키지-private: 에이전트 서비스에서 호출 가능. */
    public String buildTemplateJsonInstruction(List<LibraryVO.TmplFieldItem> tmplFieldList) {
        if (tmplFieldList == null || tmplFieldList.isEmpty()) {
            return "\n\n반드시 순수 JSON 형식으로만 응답하세요(코드블록/설명 없이).";
        }

        StringBuilder desc = new StringBuilder();
        StringBuilder skeleton = new StringBuilder("{\n");
        for (int i = 0; i < tmplFieldList.size(); i++) {
            LibraryVO.TmplFieldItem field = tmplFieldList.get(i);
            if (field == null || CommonUtil.isEmpty(field.getJsonKey())) {
                continue;
            }
            String key = field.getJsonKey();
            String nm = CommonUtil.nullToBlank(field.getFieldNm());
            boolean isTable = "table".equalsIgnoreCase(CommonUtil.nullToBlank(field.getLayoutType()));
            boolean multiline = "Y".equals(field.getMultilineYn());

            desc.append("- ").append(key);
            if (CommonUtil.isNotEmpty(nm)) {
                desc.append(" (").append(nm).append(")");
            }
            if (isTable) {
                desc.append(": 항목들을 객체 배열로 작성 (예: [{\"항목명\":\"값\", ...}, ...])");
                skeleton.append("  \"").append(key).append("\": [{ ... }]");
            } else if (multiline) {
                desc.append(": 여러 항목을 문자열 배열로 작성 (예: [\"...\", \"...\"])");
                skeleton.append("  \"").append(key).append("\": [\"...\"]");
            } else {
                desc.append(": 문자열로 작성");
                skeleton.append("  \"").append(key).append("\": \"...\"");
            }
            desc.append("\n");
            skeleton.append(i < tmplFieldList.size() - 1 ? ",\n" : "\n");
        }
        skeleton.append("}");

        return "\n\n## 응답 형식 (중요)\n"
                + "문서 종류와 관계없이, 아래에 명시된 JSON 키에만 정확히 맞춰 응답하세요.\n"
                + "키 이름을 변경/추가/삭제하지 말고, 위 다른 안내에 다른 키가 있더라도 무시하고 아래 키만 사용하세요.\n"
                + "내용이 부족한 항목은 합리적으로 채우되 빈 값으로 두지 마세요.\n\n"
                + desc.toString()
                + "\n반드시 아래 구조의 순수 JSON으로만 응답하세요(마크다운 코드블록·설명 문장 없이):\n"
                + skeleton.toString();
    }

    /**
     * PROPOSAL 에이전트 — 슬라이드 JSON을 PPTX로 변환.
     * ProposalAgentService 로 위임한다.
     */
    public byte[] exportProposalPptx(String slidesJson, String agentId) throws Exception {
        return proposalAgentService.exportProposalPptx(slidesJson, agentId);
    }

    /**
     * 리스크진단 전체 리포트를 한 번의 LLM(9000) 호출로 생성하기 위한 프롬프트를 구성한다.
     * 모든 섹션을 [[SEC:키]]…[[/SEC]] 구분자로 받고, 각 내용은 escape 없이 그대로 주입할 HTML 조각이다.
     * @param companyContext 1단계 RAG 검색으로 받아온 자사 역량 자료 요지(또는 폴백 문서 목록)
     */
    private String buildRiskReportPrompt(String basePrompt, List<LibraryVO.TmplFieldItem> fields,
            String rfpText, String companyContext, String userQuery) {
        StringBuilder secSpec = new StringBuilder();
        for (LibraryVO.TmplFieldItem field : fields) {
            if (field == null || CommonUtil.isEmpty(field.getJsonKey())) {
                continue;
            }
            String key = field.getJsonKey();
            if ("sources".equalsIgnoreCase(key)) {
                continue;
            }
            String fieldNm = CommonUtil.nullToBlank(field.getFieldNm());
            secSpec.append("- [[SEC:").append(key).append("]] ")
                    .append(CommonUtil.isNotEmpty(fieldNm) ? fieldNm : key)
                    .append(" — ").append(riskFieldFormatRule(field)).append("\n");
        }

        return basePrompt
                + "\n\n## 작성할 진단 항목 (아래 모든 항목을 빠짐없이 작성)\n" + secSpec
                + "\n## 작성 규칙 (반드시 준수)\n"
                + "1) 어떤 항목도 비우지 마세요. RFP에 근거가 없으면 '※ RFP 미명시 — 추정/권고: ...' 로 합리적 추정·권고를 채우세요.\n"
                + "2) **충실하고 구체적으로**: 일반론이 아니라 이 RFP 고유의 리스크·조건을 도출하고, RFP의 구체 수치·일정·"
                + "자격요건·계약조항(예: 추정금액, 보증금율, 평가배점, 마감일, 참가자격 등)을 직접 인용하세요. "
                + "각 항목은 근거·영향·대응을 빠짐없이 담아 실무에서 바로 활용 가능한 수준으로 작성하세요.\n"
                + "3) 리스크 → 대응책 → 자사 역량 적정성이 서로 일관되게 연결되도록 작성하세요.\n"
                + "4) '자사 역량 자료' 검색 결과를 적정성 진단·대응책의 근거로 적극 활용하세요(보유/미보유를 구체적으로 대비).\n"
                + "\n## 출력 형식 (매우 중요)\n"
                + "- 각 항목을 정확히 '[[SEC:키]]내용[[/SEC]]' 구분자로 감싸 위 목록 순서대로 출력하세요.\n"
                + "- 구분자 밖에는 어떤 텍스트(설명·JSON·코드블록 ```·인사말)도 출력하지 마세요.\n"
                + "- 각 항목의 '내용'은 HTML 조각입니다. 허용 태그: "
                + "<h3> <h4> <p> <ul> <ol> <li> <strong> <em> <br> <table> <thead> <tbody> <tr> <th> <td>\n"
                + "- 금지: <script> <style> <iframe>, on...= 이벤트 속성, 인라인 style, <html>/<body> 등 문서 루트 태그.\n"
                + "- 제목류는 태그 없는 한 줄 평문, 단락류는 <p>·<div>로 감싸지 말 것(템플릿이 감쌈), 목록은 <ul>, 비교는 <table>.\n"
                + "\n## 분석 대상 RFP 본문\n" + rfpText
                + "\n\n## 자사 역량 자료 (사내 문서 검색 결과 — 적정성 진단·대응책의 근거로 활용)\n" + companyContext
                + "\n\n## 사용자 추가 요청\n" + userQuery
                + "\n\n## 다시 강조\n각 항목을 [[SEC:키]]…[[/SEC]] 구분자로만 출력하세요. 구분자 밖 텍스트 금지.";
    }

    /** 템플릿 필드 1개의 출력 형식 지시문(단일 호출 프롬프트의 항목별 가이드). */
    private String riskFieldFormatRule(LibraryVO.TmplFieldItem field) {
        String jsonKey = field.getJsonKey();
        String fieldNm = CommonUtil.nullToBlank(field.getFieldNm());
        boolean isTable = "table".equalsIgnoreCase(CommonUtil.nullToBlank(field.getLayoutType()));
        boolean multiline = "Y".equals(field.getMultilineYn());
        boolean titleLike = "title".equalsIgnoreCase(jsonKey) || fieldNm.contains("제목");
        if (titleLike) {
            return "한 줄 제목 평문(HTML 태그 금지, 요약 문단 금지).";
        } else if (isTable) {
            return "<table><thead><tr><th>구분</th><th>내용</th><th>영향/대응</th></tr></thead><tbody>"
                    + "…5~6행, 각 셀은 RFP의 구체 수치·조항을 인용해 구체적으로…</tbody></table>";
        } else if (multiline) {
            return "<ul><li><strong>라벨</strong> — 2~3문장(근거: RFP의 구체 문구·수치·일정·자격요건 인용, 그에 따른 영향, "
                    + "그리고 실행 가능한 대응책을 모두 포함)</li> …5~7개, 충실하게…</ul> (비교가 효과적이면 <table> 사용).";
        } else {
            return "4~6문장의 충실한 단락 평문(RFP 핵심 근거를 담아 구체적으로. 블록 태그 금지, 강조 <strong>·줄바꿈 <br>만).";
        }
    }

    /**
     * 단일 호출 응답에서 [[SEC:키]]…[[/SEC]] 구분자를 파싱해 템플릿 필드별 HTML 조각 맵을 만든다.
     * 각 조각은 cleanSectionHtml로 코드펜스 제거 + 새니타이즈한다. sources 필드는 백엔드가 구성하므로 제외.
     */
    private Map<String, String> parseRiskSections(String response, List<LibraryVO.TmplFieldItem> fields) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        String resp = response != null ? response : "";
        for (LibraryVO.TmplFieldItem field : fields) {
            if (field == null || CommonUtil.isEmpty(field.getJsonKey())) {
                continue;
            }
            String key = field.getJsonKey();
            if ("sources".equalsIgnoreCase(key)) {
                continue;
            }
            String html = "";
            try {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                        "\\[\\[\\s*SEC\\s*:\\s*" + java.util.regex.Pattern.quote(key)
                                + "\\s*\\]\\](.*?)\\[\\[\\s*/\\s*SEC\\s*\\]\\]",
                        java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
                java.util.regex.Matcher m = p.matcher(resp);
                if (m.find()) {
                    html = cleanSectionHtml(m.group(1));
                }
            } catch (Exception e) {
                logger.warn("리스크진단 섹션 '{}' 파싱 실패: {}", key, e.getMessage());
            }
            map.put(key, html);
        }
        return map;
    }

    /**
     * 섹션 LLM 응답을 리포트에 주입할 안전한 HTML 조각으로 정리한다.
     * 코드펜스 제거 + 위험 태그/속성 제거(간이 새니타이즈).
     */
    private String cleanSectionHtml(String answer) {
        if (CommonUtil.isEmpty(answer)) {
            return "";
        }
        String s = answer.trim();
        // 코드펜스 제거 (```html / ``` )
        if (s.startsWith("```html")) {
            s = s.substring(7);
        } else if (s.startsWith("```")) {
            s = s.substring(3);
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        s = s.trim();
        // 간이 새니타이즈 — script/style/iframe, 이벤트 핸들러, javascript: 제거
        s = s.replaceAll("(?is)<\\s*script[^>]*>.*?<\\s*/\\s*script\\s*>", "");
        s = s.replaceAll("(?is)<\\s*style[^>]*>.*?<\\s*/\\s*style\\s*>", "");
        s = s.replaceAll("(?is)<\\s*iframe[^>]*>.*?<\\s*/\\s*iframe\\s*>", "");
        s = s.replaceAll("(?is)\\son\\w+\\s*=\\s*\"[^\"]*\"", "");
        s = s.replaceAll("(?is)\\son\\w+\\s*=\\s*'[^']*'", "");
        s = s.replaceAll("(?is)javascript:", "");
        return s.trim();
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

        return callAiImageApi(promptContent, null);
    }

    /**
     * Globals.chatbot.image.apiUrl 동기 호출. 응답 JSON의 image 필드(base64)를 반환한다.
     * data:image/...;base64, 접두사가 있으면 제거한 순수 base64만 저장한다.
     * 패키지-private: 에이전트 서비스에서 호출 가능.
     */
    public String callAiImageApi(String query, String agentId) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.image.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("이미지 생성 실패 - image API URL 미설정");
            return null;
        }
        if (CommonUtil.isEmpty(query)) {
            return null;
        }

        String modelId = "gpt";
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("room_id", "");
        params.put("model", modelId);
        params.put("aspect_ratio", "16:9");

        com.google.gson.Gson imgGson = new com.google.gson.Gson();
        String imgReqJson = imgGson.toJson(params);
        long imgStartMs = System.currentTimeMillis();

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            String jsonBody = imgReqJson;
            RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build();

            logger.info("AI 썸네일 이미지 호출 시작 - url: {}", apiUrl);

            try (okhttp3.Response response = client.newCall(request).execute()) {
                int imgRespMs = (int) Math.min(System.currentTimeMillis() - imgStartMs, Integer.MAX_VALUE);
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("AI 썸네일 이미지 응답 오류: {}", response.code());
                    apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "image", imgReqJson, 0, 0, imgRespMs, "N", "HTTP " + response.code(), null);
                    return null;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    String raw = responseBody.string();
                    if (CommonUtil.isEmpty(raw)) {
                        apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "image", imgReqJson, 0, 0, imgRespMs, "N", "빈 응답", null);
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
                                apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "image", imgReqJson, 0, 0, imgRespMs, "N", errorCode + ": " + errorContent, null);
                                return null;
                            }
                        }

                        Object imageObj = data.get("image");
                        if (imageObj == null) {
                            apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "image", imgReqJson, 0, 0, imgRespMs, "N", "이미지 필드 없음", null);
                            return null;
                        }
                        String image = String.valueOf(imageObj).trim();
                        if (CommonUtil.isEmpty(image)) {
                            apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "image", imgReqJson, 0, 0, imgRespMs, "N", "이미지 값 없음", null);
                            return null;
                        }
                        String normalized = stripDataUrlBase64Prefix(image);
                        apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "image", imgReqJson, 0, 0, imgRespMs, "Y", null, null);
                        return normalized;
                    } catch (Exception e) {
                        logger.warn("AI 썸네일 이미지 응답 파싱 오류: {}", e.getMessage());
                        apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "image", imgReqJson, 0, 0, imgRespMs, "N", "파싱 오류: " + e.getMessage(), null);
                    }
                }
            }
        } catch (Exception e) {
            int imgRespMs = (int) Math.min(System.currentTimeMillis() - imgStartMs, Integer.MAX_VALUE);
            logger.warn("AI 썸네일 이미지 호출 중 오류: {}", e.getMessage());
            apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "image", imgReqJson, 0, 0, imgRespMs, "N", e.getMessage(), null);
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
            ins.setTtsqParam(CommonUtil.isNotEmpty(src.getTtsqParam()) ? src.getTtsqParam() : null);
            ins.setTtsqPeriodParam(CommonUtil.isNotEmpty(src.getTtsqPeriodParam()) ? src.getTtsqPeriodParam() : null);
            ins.setTableData(CommonUtil.isNotEmpty(src.getTableData()) ? src.getTableData() : null);
            ins.setChartOption(CommonUtil.isNotEmpty(src.getChartOption()) ? src.getChartOption() : null);
            ins.setWebGroundingJson(CommonUtil.isNotEmpty(src.getWebGroundingJson()) ? src.getWebGroundingJson() : null);
            ins.setReportHtml(CommonUtil.isNotEmpty(src.getReportHtml()) ? src.getReportHtml() : null);
            ins.setRetrieverQuery(CommonUtil.isNotEmpty(src.getRetrieverQuery()) ? src.getRetrieverQuery() : null);
            ins.setChunk(CommonUtil.isNotEmpty(src.getChunk()) ? src.getChunk() : null);
            ins.setMainDocFileId(CommonUtil.isNotEmpty(src.getMainDocFileId()) ? src.getMainDocFileId() : null);
            ins.setMainPage(CommonUtil.isNotEmpty(src.getMainPage()) ? src.getMainPage() : null);
            ins.setReaskCnt(src.getReaskCnt());

            chatbotDAO.insertChatLog(ins);
            copied++;

            if (("M".equals(src.getSvcTy()) || "D".equals(src.getSvcTy())) && oldLogId != null && ins.getLogId() != null) {
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
     * 번역 결과 텍스트를 .docx/.txt 파일 바이트로 변환한다.
     * TranslationAgentService 로 위임한다.
     */
    public byte[] exportTranslationFile(String content, String fileType) throws Exception {
        return translationAgentService.exportTranslationFile(content, fileType);
    }

    /**
     * 즉시번역(드래그 선택 번역) — 동기 1회 호출, 채팅 로그를 남기지 않는다.
     * TranslationAgentService 로 위임한다.
     */
    public String instantTranslate(String content, String targetLang, String tone) {
        return translationAgentService.instantTranslate(content, targetLang, tone);
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
     * ws 전송 실패 등으로 LOG_ID가 연결되지 못한 파일을 삭제 대상으로 표시한다.
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
     * done 이벤트의 file_info 배열 항목 (TB_CHAT_REF 다건 INSERT용).
     * 패키지-private: 에이전트 서비스에서 참조 가능.
     */
    public static class ChatRefItem {
        /** TB_CHAT_REF.DOC_FILE_ID / API docFileId */
        public String docFileId;
        public String mainPageNo;
        public List<Integer> relatedPageNos = new ArrayList<>();
    }

    /**
     * 외부에서 prompt를 받아 AI 요약 API를 호출하고 방사형 차트용 JSON 문자열을 반환한다.
     * SurveyAgentService 로 위임한다.
     */
    public String getPsychologyChartData(String prompt) {
        return surveyAgentService.getPsychologyChartData(prompt);
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

    public void deleteBatchAuto() throws Exception {
        logger.info("=== 채팅 첨부파일 삭제 배치 시작 ===");

        try {
            ChatbotVO fileVo = new ChatbotVO();
            int deleteTerm = Integer.parseInt(PropertyUtil.getProperty("Globals.chatbot.file.deleteTerm"));
            fileVo.setDeleteFileTerm(deleteTerm);
            // 1. 삭제 대상 파일 조회
            List<ChatbotVO> targetList = chatbotDAO.selectChatFileDelete(fileVo);

            // 3. 각 통계별로 API 연동 실행 (10일 마감 대상 제외)
            for (ChatbotVO target : targetList) {

                try {
                    // ncp 삭제
                    Map<String, Object> ncpResult = fileService.deleteStorageObjectByKey(target.getFilePath());
                    if (ncpResult != null && Boolean.FALSE.equals(ncpResult.get("successYn"))) {
                        logger.warn("자동 배치 NCP 삭제 실패 - chatFileId: {}, filePath: {}, returnMsg: {}",
                                target.getChatFileId(), target.getFilePath(), ncpResult.get("returnMsg"));
                        continue;
                    }

                    // 물리 파일 삭제 모두 성공 시 db 삭제
                    int deleted = chatbotDAO.deleteChatFile(target);
                    if (deleted <= 0) {
                        logger.warn("자동 배치 DB 삭제 대상 없음 - chatFileId: {}, filePath: {}",
                                target.getChatFileId(), target.getFilePath());
                        continue;
                    }
                    logger.info("자동 배치 삭제 완료 - chatFileId: {}, filePath: {}",
                            target.getChatFileId(), target.getFilePath());
                } catch (Exception e) {
                    logger.error("자동 배치 실행 중 오류 - e: {}", e);
                    // 개별 실패는 로그만 남기고 계속 진행
                }
            }

            logger.info("=== 자동 배치 완료 (월말 마감) ===");
        } catch (Exception e) {
            logger.error("=== 자동 배치 오류 발생 (월말 마감) ===", e);
            throw e;
        }
    }
}
