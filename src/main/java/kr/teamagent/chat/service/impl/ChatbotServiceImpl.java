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
    private static final String MEME_AGENT_ID = "AG000011";
    private static final String RECOMMEND_SUB_TY = "RECOMMEND";
    private static final String CURATION_SUB_TY = "CURATION";
    private static final String TRANSLATE_SUB_TY = "TRANSLATE";
    private static final String RESEARCHER_SUB_TY = "RESEARCHER";
    private static final String RISK_SUB_TY = "RISK";
    /** 리스크진단 기본 리포트 템플릿 ID (subCfg.features.tmplId 미설정 시 폴백) */
    private static final String RISK_DEFAULT_TMPL_ID = "TM000008";
    /** 리스크진단 생성(9000) 프롬프트에 주입하는 RFP 추출 텍스트 최대 길이(문자).
     *  2단계 생성은 대용량 컨텍스트(임베딩 아님)라 RFP 전체를 충분히 담아 분석 충실도를 높인다. */
    private static final int RISK_RFP_TEXT_MAX_CHARS = 24000;
    /** 대용량 RFP 요약 시 청크 최대 개수(문서가 커져도 요약 호출 수를 이 값으로 제한). */
    private static final int RISK_SUMMARY_MAX_CHUNKS = 16;
    /** 대용량 RFP 요약 시 청크 최소 길이(문자). 너무 잘게 쪼개지지 않도록 하한. */
    private static final int RISK_SUMMARY_MIN_CHUNK_CHARS = 15000;
    /** 청크 요약을 합친 '압축 RFP'의 최대 길이(문자). 후반부(붙임·평가기준) 요약이 잘리지 않도록 넉넉히 둔다.
     *  (이미 압축된 텍스트라 2단계 9000 대용량 컨텍스트에 충분히 들어간다) */
    private static final int RISK_CONDENSED_MAX_CHARS = 80000;
    /** 청크 경계에 걸친 표·항목이 잘리지 않도록 인접 청크 간 겹치는 길이(문자). */
    private static final int RISK_SUMMARY_CHUNK_OVERLAP = 800;
    /** 리스크진단 단일 호출은 전체 리포트를 한 번에 생성하므로 읽기 타임아웃을 길게 둔다(초). */
    private static final int RISK_QUERY_READ_TIMEOUT_SEC = 300;
    /** 리서처 리포트 출처 섹션 — 템플릿 HTML escape 우회용 치환 토큰 */
    private static final String SOURCES_TOKEN = "@@RSRC_SOURCES@@";
    /** RAG 문서 출처 링크 — 백엔드 GET 리다이렉트 엔드포인트.
     *  native target=_blank로 새 탭이 열리면 presigned 파일 URL로 302 리다이렉트된다.
     *  (/api는 프론트 프록시 prefix — 브라우저가 프론트 도메인에서 요청 → 백엔드로 포워딩) */
    private static final String RAG_DOC_LINK_PREFIX = "/api/repository/viewDocRedirect.do?docFileId=";

    /** 번역 에이전트 공통 지시문 — 프론트엔드 translateAgentUtil.ts의 TRANSLATE_BASE_PROMPT와 동일하게 유지 */
    private static final String TRANSLATE_BASE_PROMPT = String.join("\n",
            "당신은 전문 비즈니스 번역가입니다.",
            "- 입력 텍스트를 목표 언어로 번역하세요.",
            "- 단순 직역이 아닌 비즈니스 문서/메일/채팅 맥락에 맞는 의미를 전달하세요.",
            "- 지정된 톤을 유지하거나 목표 언어 관습에 맞게 자연스럽게 조정하세요.",
            "- 숫자, 날짜, 고유명사, 회사명, 제품명, 약어는 원문 그대로 유지하세요.",
            "- 원문의 줄바꿈, 목록, 강조 등 서식을 그대로 유지하세요.",
            "- 번역 결과 외 다른 설명은 출력하지 마세요.");

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

    /** 대용량 RFP 청크 병렬 요약용 스레드 풀 (요약은 청크당 작은 입력이라 빠르게 동시 처리) */
    private static final java.util.concurrent.ExecutorService riskSummarizeExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread thread = new Thread(r, "risk-summarize-worker");
                thread.setDaemon(true);
                return thread;
            });

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

    @Autowired
    TmplServiceImpl tmplService;

    @Autowired
    TmplHtmlRenderService tmplHtmlRenderService;

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

        ChatbotVO.AgtSubCfgVO curationSubCfg = getAgentSubCfg(agentId);
        if (curationSubCfg != null && CURATION_SUB_TY.equals(curationSubCfg.getSubTy()) && "Y".equals(curationSubCfg.getUseYn())) {
            deliverNewsRecommendationViaWebSocket(query, threadId, userId, svcTy, modelId, refId, agentId,
                    attachmentFileIds, curationSubCfg.getAdditionalConfigMap(), callback);
            return;
        }

        ChatbotVO.AgtSubCfgVO translateSubCfg = getAgentSubCfg(agentId);
        if (translateSubCfg != null && TRANSLATE_SUB_TY.equals(translateSubCfg.getSubTy())
                && "Y".equals(translateSubCfg.getUseYn())) {
            // TB_CHAT_LOG.SVC_TY는 번역 에이전트(SVC_TY='W') 기준으로 저장한다.
            svcTy = "W";
            if (hasNonNullAttachmentId(attachmentFileIds)) {
                deliverTranslationFileViaWebSocket(query, threadId, userId, svcTy, modelId, refId, agentId,
                        attachmentFileIds, callback);
                return;
            }
        }

        // RESEARCHER 에이전트: 웹검색 + RAG 통합 리서치 리포트
        if (isResearcherAgent(agentId)) {
            deliverResearchReportViaWebSocket(query, threadId, userId, svcTy, modelId, refId, agentId, attachmentFileIds, callback);
            return;
        }

        // RISK 에이전트: RFP(PDF) 업로드 → 섹션 분리 → 섹션별 병렬 LLM 진단 → 리포트
        if (isRiskDiagnosisAgent(agentId)) {
            // TB_CHAT_LOG.SVC_TY는 리스크진단 에이전트(SVC_TY='D') 기준으로 저장한다.
            svcTy = "D";
            deliverRiskReportViaWebSocket(query, threadId, userId, svcTy, modelId, refId, agentId, attachmentFileIds, callback);
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
     * 스트리밍 호출용 URL. 점심·밈 에이전트는 전용 URL.
     */
    private String resolveStreamingApiUrl(String svcTy, String agentId, List<Long> attachmentFileIds) {
        // RECOMMEND 에이전트: 전용 URL이 설정된 경우 사용, 없으면 기본 chat API 사용
        if (isRecommendAgent(agentId)) {
            String recommendApiUrl = PropertyUtil.getProperty("Globals.chatbot.recommend.apiUrl");
            if (CommonUtil.isNotEmpty(recommendApiUrl)) {
                logger.info("resolveStreamingApiUrl: recommend agent -> recommend_query URL");
                return recommendApiUrl;
            }
            logger.info("resolveStreamingApiUrl: recommend agent -> default chat URL");
            return getApiUrl(svcTy);
        }

        // TRANSLATE 에이전트: AGENT_ID 기반 agentVO 조회(API_URL_CD 미설정 시 깨짐)를 우회하고 기본 chat API 사용
        if (isTranslateAgent(agentId)) {
            logger.info("resolveStreamingApiUrl: translate agent -> default chat URL");
            return getApiUrl(svcTy);
        }

        if (isKakaoAddressEnrichmentAgent(agentId)) {
            String lunchApiUrl = PropertyUtil.getProperty("Globals.chatbot.lunch.apiUrl");
            if (CommonUtil.isNotEmpty(lunchApiUrl)) {
                logger.info("resolveStreamingApiUrl: kakao address-enrichment agent -> lunch_query URL");
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
                    if (call.isCanceled()) {
                        logger.info("AI API 호출이 사용자 요청으로 중단되었습니다: threadId={}", threadId);
                        callback.onComplete("사용자 요청에 의해 응답 생성이 중단되었습니다.", "", "", new ArrayList<>(), threadId, null, "", "", "");
                        return;
                    }
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
                        processStreamingResponseWebSocket(responseBody, call, sessionId, query, svcTy, modelId, refId, userId, agentId, threadId, attachmentFileIds, callback);
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
     * 에이전트의 TB_AGT_SUB_CFG 조회 + ADDITIONAL_CONFIG 파싱 — SUB_TY 기반 분기 판별 공용 헬퍼
     */
    private ChatbotVO.AgtSubCfgVO getAgentSubCfg(String agentId) {
        if (CommonUtil.isEmpty(agentId)) return null;
        try {
            ChatbotVO searchVO = new ChatbotVO();
            searchVO.setAgentIdList(java.util.Collections.singletonList(agentId));
            List<ChatbotVO.AgtSubCfgVO> list = chatbotDAO.selectAgentSubCfgListByAgentIds(searchVO);
            if (list == null || list.isEmpty()) return null;
            ChatbotVO.AgtSubCfgVO subCfg = list.get(0);
            parseAgentSubAdditionalConfig(subCfg);
            return subCfg;
        } catch (Exception e) {
            logger.warn("getAgentSubCfg 조회 중 오류 (agentId={}): {}", agentId, e.getMessage());
            return null;
        }
    }

    /**
     * SUB_TY=RECOMMEND 에이전트 여부 판별.
     * RECOMMEND 에이전트는 Frontend에서 완성형 프롬프트를 전달하므로 백엔드 래핑 불필요.
     */
    private boolean isRecommendAgent(String agentId) {
        ChatbotVO.AgtSubCfgVO subCfg = getAgentSubCfg(agentId);
        return subCfg != null && RECOMMEND_SUB_TY.equals(subCfg.getSubTy()) && "Y".equals(subCfg.getUseYn());
    }

    /**
     * RECOMMEND 에이전트 중 ADDITIONAL_CONFIG.features.addressEnrichment == "kakao" 인지 판별.
     * 식당명+위치 기반 카카오 장소 URL 보강 및 전용 스트리밍 처리(answer_linked) 대상 여부에 사용.
     */
    private boolean isKakaoAddressEnrichmentAgent(String agentId) {
        ChatbotVO.AgtSubCfgVO subCfg = getAgentSubCfg(agentId);
        if (subCfg == null || !RECOMMEND_SUB_TY.equals(subCfg.getSubTy()) || !"Y".equals(subCfg.getUseYn())) {
            return false;
        }
        Object featuresObj = subCfg.getAdditionalConfigMap() != null ? subCfg.getAdditionalConfigMap().get("features") : null;
        if (!(featuresObj instanceof Map)) return false;
        return "kakao".equals(((Map<?, ?>) featuresObj).get("addressEnrichment"));
    }

    /**
     * SUB_TY=TRANSLATE 에이전트 여부 판별.
     * TRANSLATE 에이전트는 Frontend에서 완성형 프롬프트를 전달하므로 백엔드 래핑 불필요.
     */
    private boolean isTranslateAgent(String agentId) {
        ChatbotVO.AgtSubCfgVO subCfg = getAgentSubCfg(agentId);
        return subCfg != null && TRANSLATE_SUB_TY.equals(subCfg.getSubTy()) && "Y".equals(subCfg.getUseYn());
    }

    /**
     * SUB_TY=RESEARCHER 에이전트 여부 판별.
     */
    private boolean isResearcherAgent(String agentId) {
        ChatbotVO.AgtSubCfgVO subCfg = getAgentSubCfg(agentId);
        return subCfg != null && RESEARCHER_SUB_TY.equals(subCfg.getSubTy()) && "Y".equals(subCfg.getUseYn());
    }

    /**
     * SUB_TY=RISK 에이전트(프로젝트 리스크진단) 여부 판별.
     */
    private boolean isRiskDiagnosisAgent(String agentId) {
        ChatbotVO.AgtSubCfgVO subCfg = getAgentSubCfg(agentId);
        return subCfg != null && RISK_SUB_TY.equals(subCfg.getSubTy()) && "Y".equals(subCfg.getUseYn());
    }

    private String buildRequestQueryByAgent(String query, String agentId) {
        // RECOMMEND 에이전트: Frontend에서 완성형 프롬프트 전달 — 래핑 없이 그대로 반환
        if (isRecommendAgent(agentId)) {
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
        ChatbotVO.AgtSubCfgVO subCfg = getAgentSubCfg(agentId);
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
    private void processStreamingResponseWebSocket(okhttp3.ResponseBody responseBody, okhttp3.Call call, String sessionId, String query, String svcTy, String modelId, String refId, String userId, String agentId, String threadId, List<Long> attachmentFileIds, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws IOException {

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream(), "UTF-8"), 1);
        boolean isKakaoAddressEnrichmentAgent = isKakaoAddressEnrichmentAgent(agentId);
        if (!isKakaoAddressEnrichmentAgent && isRecommendAgent(agentId)) {
            logger.info("processStreamingResponse: RECOMMEND agent (agentId={}) — 일반 스트리밍 처리", agentId);
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
                    } catch (Exception e) {
                        logger.warn("챗봇 로그 저장 실패: {}", e.getMessage());
                    }
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
                        && !MEME_AGENT_ID.equals(agentId)
                        && !isRecommendAgent(agentId)
                        && CommonUtil.isNotEmpty(savedLogId)
                        && CommonUtil.isNotEmpty(finalAnswerContent);

                if (shouldSuggestNextQuestions) {
                    final String logIdForRecommend = savedLogId;
                    final String queryForRecommend = query;
                    final String answerForRecommend = finalAnswerContent;
                    getRecommendQuestionExecutor().execute(() -> {
                        try {
                            List<String> nextQuestions = generateNextRecommendedQuestions(queryForRecommend, answerForRecommend);
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
     * TB_CHAT_LOG 저장 및 svcTy == M(리서처) 또는 D(리스크진단) 이면 TB_CHAT_REF(chatRefItems) 반복 저장.
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
     */
    private String doInsertAiLog(
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
     * AI 서버를 통해 이전 질문/답변 맥락을 기반으로 다음 추천 질문을 생성한다.
     * 줄바꿈으로 구분된 최대 3개의 질문을 반환한다. 실패 시 빈 리스트 반환.
     */
    private List<String> generateNextRecommendedQuestions(String qContent, String rContent) {
        if (CommonUtil.isEmpty(qContent) && CommonUtil.isEmpty(rContent)) {
            return Collections.emptyList();
        }

        String prompt = "다음은 사용자와 AI의 대화 내용이다.\n"
                + "질문: " + truncateTitle(qContent, 500) + "\n"
                + "답변: " + truncateTitle(rContent, 1000) + "\n\n"
                + "위 대화 맥락을 참고하여 사용자가 다음에 이어서 물어볼 만한 질문을 2~3개 한국어로 제안해줘. "
                + "각 질문은 25자 이내로 간결하게 작성하고, 줄바꿈으로만 구분해서 질문 텍스트만 출력해. "
                + "번호, 글머리 기호, 따옴표 등 부가 텍스트는 포함하지 마.";

        String result = callAiSummary(prompt, "nextQuestions");
        if (CommonUtil.isEmpty(result)) {
            return Collections.emptyList();
        }

        return Arrays.stream(result.split("\n"))
                .map(String::trim)
                .map(line -> line.replaceFirst("^[\\d\\.\\-\\)\\s]+", ""))
                .filter(CommonUtil::isNotEmpty)
                .map(line -> truncateTitle(line, 50))
                .limit(3)
                .collect(Collectors.toList());
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

        int readTimeoutSec = ("reAskReport".equals(purpose) || "insightReport".equals(purpose) || "createDoc".equals(purpose) || "news_curate".equals(purpose) || "mtlcareReport".equals(purpose) || "translate_file".equals(purpose))
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
     * 웹 검색 엔드포인트(query_search_only)에 동기 호출하여 검색 결과 텍스트와 출처를 반환한다.
     * SSE 이벤트:
     * - event: answer_delta / data: {"text": "..."}
     * - event: answer_source / data: {"items":[{url,title},..]}
     * - event: done / data: {"answer": "...", ...}
     * @return [0]=answer text, [1]=출처 문자열(제목: URL 줄바꿈 목록). 실패 시 null.
     */
    private String[] callWebSearchSync(String query, String modelId) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.apiIpSearchOnly");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("웹 검색 실패 - query_search_only URL 미설정");
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("model_id", modelId != null ? modelId : "");
        params.put("room_id", "");

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(SUMMARY_QUERY_READ_TIMEOUT_LONG_SEC, java.util.concurrent.TimeUnit.SECONDS)
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

            logger.info("웹 검색 호출 시작 - url: {}", apiUrl);

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("웹 검색 응답 오류: {}", response.code());
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
                    return new String[]{ answer, answerSource };
                }
            }
        } catch (Exception e) {
            logger.warn("웹 검색 중 오류 발생: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 웹 검색 출처 문자열(answer_source)에서 URL을 추출하여 출처 items 배열로 변환한다.
     * 각 item은 {url, title} 형태. URL이 없으면 빈 배열 반환.
     */
    @SuppressWarnings("unchecked")
    private JSONArray extractWebSourceItems(String source) {
        JSONArray items = new JSONArray();
        if (CommonUtil.isEmpty(source)) {
            return items;
        }
        // http(s) URL 추출 (공백·따옴표·괄호·꺾쇠 등 경계 제외)
        java.util.regex.Pattern urlPattern = java.util.regex.Pattern.compile("https?://[^\\s\"'<>\\)\\]]+");
        java.util.regex.Matcher matcher = urlPattern.matcher(source);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            String url = matcher.group();
            // 끝의 문장부호 제거
            url = url.replaceAll("[.,;]+$", "");
            if (seen.add(url)) {
                JSONObject item = new JSONObject();
                item.put("url", url);
                items.add(item);
            }
        }
        return items;
    }

    /**
     * 템플릿 필드(TB_TMPL_FIELD)로부터 LLM JSON 응답 지시문을 동적으로 생성한다.
     * 어떤 템플릿이든 그 템플릿의 jsonKey 목록에 맞춰 LLM이 응답하도록 강제한다.
     * - layoutType=table → 객체 배열, multilineYn=Y → 문자열 배열, 그 외 → 문자열
     */
    private String buildTemplateJsonInstruction(List<LibraryVO.TmplFieldItem> tmplFieldList) {
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
     * 리서치 리포트 출처 섹션 HTML을 구성한다.
     * - 사내 문서(RAG): 파일 뷰 링크 (프론트가 클릭 가로채 파일 열기)
     * - 웹 출처: 실제 URL 링크 (새 탭)
     */
    private String buildResearcherSourcesHtml(List<ChatbotVO> ragDocs, String webSearchSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("<ul>");

        // 사내 문서 출처
        if (ragDocs != null) {
            for (ChatbotVO doc : ragDocs) {
                if (doc == null || CommonUtil.isEmpty(doc.getFileName())) {
                    continue;
                }
                String fileName = htmlEscape(doc.getFileName());
                String docFileId = CommonUtil.nullToBlank(doc.getDocFileId());
                String href = RAG_DOC_LINK_PREFIX + docFileId;
                sb.append("<li>사내 문서: <a href=\"").append(href).append("\">")
                        .append(fileName).append("</a></li>");
            }
        }

        // 웹 출처 (answer_source 문자열에서 URL 추출)
        JSONArray webItems = extractWebSourceItems(webSearchSource);
        for (Object itemObj : webItems) {
            if (!(itemObj instanceof JSONObject)) {
                continue;
            }
            String url = String.valueOf(((JSONObject) itemObj).get("url"));
            if (CommonUtil.isEmpty(url)) {
                continue;
            }
            String urlEsc = htmlEscape(url);
            sb.append("<li>웹페이지: <a href=\"").append(urlEsc)
                    .append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
                    .append(urlEsc).append("</a></li>");
        }

        sb.append("</ul>");
        return sb.toString();
    }

    /** HTML 태그를 제거해 평문으로 변환 (채팅 버블 요약용) */
    private String stripHtmlTags(String s) {
        if (CommonUtil.isEmpty(s)) {
            return "";
        }
        String text = s.replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    /** HTML 특수문자 escape */
    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * AI 응답 JSON의 한글/변형 키를 템플릿 필드의 영문 키로 매핑한다.
     * 매핑되지 않는 키는 그대로 유지한다.
     */
    @SuppressWarnings("unchecked")
    private JSONObject mapResearcherJsonKeys(JSONObject src) {
        // 한글 키 → 영문 템플릿 키 매핑 테이블
        Map<String, String> keyMap = new java.util.LinkedHashMap<>();
        keyMap.put("제목", "title");
        keyMap.put("주제", "title");
        keyMap.put("요약", "executive_summary");
        keyMap.put("핵심요약", "executive_summary");
        keyMap.put("핵심_요약", "executive_summary");
        keyMap.put("summary", "executive_summary");
        keyMap.put("시장개요", "market_overview");
        keyMap.put("시장_개요", "market_overview");
        keyMap.put("시장현황", "market_overview");
        keyMap.put("시장_현황", "market_overview");
        keyMap.put("주요발견사항", "key_findings");
        keyMap.put("주요_발견사항", "key_findings");
        keyMap.put("경쟁사분석", "competitor_analysis");
        keyMap.put("경쟁사_분석", "competitor_analysis");
        keyMap.put("경쟁사비교", "competitor_analysis");
        keyMap.put("경쟁사_비교", "competitor_analysis");
        keyMap.put("SWOT분석", "swot");
        keyMap.put("SWOT_분석", "swot");
        keyMap.put("결론", "conclusion");
        keyMap.put("결론및제언", "conclusion");
        keyMap.put("결론_및_제언", "conclusion");
        keyMap.put("참고출처", "sources");
        keyMap.put("참고_출처", "sources");
        keyMap.put("출처", "sources");

        JSONObject mapped = new JSONObject();
        for (Object keyObj : src.keySet()) {
            String key = String.valueOf(keyObj);
            // 숫자 접두사 제거: "1. AI 반도체 시장 현황" → "AI 반도체 시장 현황"
            String normalizedKey = key.replaceAll("^\\d+\\.\\s*", "").trim();
            // 공백/특수문자 제거 후 매핑 시도
            String compactKey = normalizedKey.replaceAll("[\\s_·.\\-]", "");

            String mappedKey = null;
            for (Map.Entry<String, String> entry : keyMap.entrySet()) {
                String candidate = entry.getKey().replaceAll("[\\s_·.\\-]", "");
                if (candidate.equalsIgnoreCase(compactKey)) {
                    mappedKey = entry.getValue();
                    break;
                }
            }

            // 매핑 성공 시 영문 키 사용, 실패 시 원본 키 유지
            String targetKey = mappedKey != null ? mappedKey : key;
            Object val = src.get(keyObj);

            // 중첩 객체도 재귀 매핑 (1단계만)
            if (val instanceof JSONObject) {
                mapped.put(targetKey, mapResearcherJsonKeys((JSONObject) val));
            } else {
                mapped.put(targetKey, val);
            }
        }
        return mapped;
    }

    /**
     * RESEARCHER 에이전트: 웹 검색 + 사내 문서 RAG를 통합하여 리서치 리포트를 생성한다.
     * 1) query_search_only(9000) 동기 호출 → 웹 검색 답변 + 웹 출처(URL)
     * 2) 템플릿 조회 + 웹 결과를 주입한 enriched query 구성
     * 3) ragQuery(9111/query) 동기 호출 → dataset_id 벡터 검색 + JSON 리포트 생성 + file_info(참조 문서)
     * 4) TmplHtmlRenderService → 리포트 HTML 렌더링 (출처: 사내 문서 링크 + 웹 URL)
     * 5) TB_CHAT_LOG/TB_CHAT_REF 저장 + 결과 스트리밍 전송
     */
    private void deliverResearchReportViaWebSocket(
            String query, String threadId, String userId, String svcTy, String modelId,
            String refId, String agentId, List<Long> attachmentFileIds,
            ChatbotWebSocketHandler.ChatbotStreamingCallback callback) {
        try {
            // ① 웹 검색
            callback.onStatus("searching_web", "웹 검색 중");
            String[] webSearchResult = callWebSearchSync(query, modelId);
            String webSearchAnswer = (webSearchResult != null) ? webSearchResult[0] : "";
            String webSearchSource = (webSearchResult != null) ? webSearchResult[1] : "";

            // ② 템플릿 조회
            callback.onStatus("loading_template", "리포트 템플릿 준비 중");
            ChatbotVO.AgtSubCfgVO subCfg = getAgentSubCfg(agentId);
            String tmplId = "TM000007";
            if (subCfg != null && subCfg.getAdditionalConfigMap() != null) {
                Object featuresObj = subCfg.getAdditionalConfigMap().get("features");
                if (featuresObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> features = (Map<String, Object>) featuresObj;
                    String cfgTmplId = (String) features.get("tmplId");
                    if (CommonUtil.isNotEmpty(cfgTmplId)) {
                        tmplId = cfgTmplId;
                    }
                }
            }

            TmplVO tmplSearchVO = new TmplVO();
            tmplSearchVO.setTmplId(tmplId);
            TmplVO tmpl = tmplService.selectTmplDetail(tmplSearchVO);
            List<LibraryVO.TmplFieldItem> tmplFieldList = new ArrayList<>();
            if (tmpl != null) {
                TmplVO fieldSearchVO = new TmplVO();
                fieldSearchVO.setTmplId(tmplId);
                List<TmplVO.TmplFieldVO> fieldVoList = tmplService.selectTmplFieldList(fieldSearchVO);
                if (fieldVoList != null) {
                    for (TmplVO.TmplFieldVO fv : fieldVoList) {
                        LibraryVO.TmplFieldItem item = new LibraryVO.TmplFieldItem();
                        item.setJsonKey(fv.getJsonKey());
                        item.setFieldNm(fv.getFieldNm());
                        item.setMultilineYn(fv.getMultilineYn());
                        item.setLayoutType(fv.getLayoutType());
                        tmplFieldList.add(item);
                    }
                }
            }

            // ③ enriched query 구성
            callback.onStatus("generating_report", "리포트 생성 중");
            String llmPrompt = (tmpl != null && CommonUtil.isNotEmpty(tmpl.getLlmPrompt()))
                    ? tmpl.getLlmPrompt()
                    : "다음 주제에 대해 JSON 형식으로 리서치 리포트를 작성하세요.";

            // dataset_id 배열 구성 (M 모드)
            List<String> datasetIds = new ArrayList<>();
            if (refId != null && !refId.trim().isEmpty()) {
                for (String part : refId.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        datasetIds.add(trimmed);
                    }
                }
            }

            // RAG 데이터셋의 실제 문서 (docFileId + fileName) 조회
            List<ChatbotVO> ragDocs = new ArrayList<>();
            StringBuilder ragDocNames = new StringBuilder();
            if (!datasetIds.isEmpty()) {
                try {
                    for (String dsId : datasetIds) {
                        ChatbotVO docSearchVO = new ChatbotVO();
                        docSearchVO.setRefId(dsId);
                        List<ChatbotVO> docFiles = chatbotDAO.selectDatasetDocFileNames(docSearchVO);
                        if (docFiles != null) {
                            for (ChatbotVO doc : docFiles) {
                                if (CommonUtil.isNotEmpty(doc.getFileName())) {
                                    ragDocs.add(doc);
                                    ragDocNames.append("- ").append(doc.getFileName()).append("\n");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("RAG 문서 파일명 조회 실패: {}", e.getMessage());
                }
            }

            // 템플릿 필드(TB_TMPL_FIELD)에서 JSON 키 지시문을 동적으로 생성한다.
            // → 어떤 템플릿/문서든 해당 템플릿의 정확한 키로 응답하게 강제하여 항상 템플릿 HTML대로 렌더링.
            String jsonKeyInstruction = buildTemplateJsonInstruction(tmplFieldList);

            // 출처는 LLM이 생성하지 않고 백엔드에서 직접 링크 HTML로 구성하므로
            // 프롬프트에서는 본문 인용 참고용으로만 문서/웹 목록을 전달한다.
            String enrichedQuery = llmPrompt.replace("{{web_search_results}}", webSearchAnswer)
                    + "\n\n## 참조 가능한 사내 문서 목록\n" + (ragDocNames.length() > 0 ? ragDocNames.toString() : "(없음)")
                    + "\n\n## 참조 가능한 웹 출처\n" + (CommonUtil.isNotEmpty(webSearchSource) ? webSearchSource : "(없음)")
                    + jsonKeyInstruction
                    + "\n\n## 사용자 질문\n" + query;

            // ④ RAG 매뉴얼 질의(9111/query) 동기 호출 — dataset_id 벡터 검색 + LLM 생성
            //    9111/query 명세 입력: query, dataset_id, model_id, room_id, agent_id, attachment_file_ids
            Map<String, Object> ragParams = new HashMap<>();
            ragParams.put("query", enrichedQuery);
            ragParams.put("dataset_id", datasetIds);
            ragParams.put("model_id", modelId != null ? modelId : "");
            ragParams.put("room_id", threadId != null ? threadId : "string");
            ragParams.put("agent_id", agentId != null ? agentId : "");
            ragParams.put("attachment_file_ids", new ArrayList<String>());

            String ragApiUrl = PropertyUtil.getProperty("Globals.chatbot.ragQuery.apiUrl");
            if (CommonUtil.isEmpty(ragApiUrl)) {
                callback.onError("RAG 질의 API URL이 설정되지 않았습니다.");
                return;
            }

            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(SUMMARY_QUERY_READ_TIMEOUT_LONG_SEC, java.util.concurrent.TimeUnit.SECONDS)
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            com.google.gson.Gson gson = new com.google.gson.Gson();
            String ragJsonBody = gson.toJson(ragParams);
            RequestBody ragBody = RequestBody.create(ragJsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request ragRequest = new Request.Builder()
                    .url(ragApiUrl)
                    .post(ragBody)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .build();

            logger.info("리서처 RAG 질의 호출 시작 - url: {}, dataset_id: {}", ragApiUrl, datasetIds);

            String aiResponse = null;
            List<ChatRefItem> chatRefItems = new ArrayList<>();
            try (okhttp3.Response response = client.newCall(ragRequest).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("리서처 RAG 질의 응답 오류: {}", response.code());
                    callback.onError("RAG 질의 API 응답 오류: " + response.code());
                    return;
                }
                try (okhttp3.ResponseBody responseBody = response.body()) {
                    // 9111/query SSE 응답: event: answer_delta(text) ... event: done(answer/답변, file_info)
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(responseBody.byteStream(), "UTF-8"));
                    StringBuilder answerBuilder = new StringBuilder();
                    String doneAnswer = "";
                    String line;
                    JSONParser jsonParser = new JSONParser();

                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event: ")) {
                            continue;
                        }
                        String jsonStr;
                        if (line.startsWith("data: ")) {
                            jsonStr = line.substring(6).trim();
                        } else if (line.trim().startsWith("{")) {
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
                            // done 이벤트 답변 키: "answer" 또는 한글 "답변"
                            Object answerObj = data.get("answer");
                            if (answerObj == null) {
                                answerObj = data.get("답변");
                            }
                            if (answerObj != null) {
                                doneAnswer = String.valueOf(answerObj);
                            }
                            // done 이벤트의 file_info → 실제 참조 문서(TB_CHAT_REF)
                            List<ChatRefItem> refs = extractChatRefItems(data);
                            if (!refs.isEmpty()) {
                                chatRefItems = refs;
                            }
                        } catch (Exception ignore) {
                            // 개별 라인 파싱 실패는 무시
                        }
                    }
                    aiResponse = CommonUtil.isNotEmpty(doneAnswer) ? doneAnswer : answerBuilder.toString();
                }
            }

            // RAG 응답이 참조 문서를 주지 않으면, 데이터셋에 연결된 문서를 참조로 저장(폴백)
            if (chatRefItems.isEmpty() && !ragDocs.isEmpty()) {
                for (ChatbotVO doc : ragDocs) {
                    if (doc == null || CommonUtil.isEmpty(doc.getDocFileId())) {
                        continue;
                    }
                    ChatRefItem item = new ChatRefItem();
                    item.docFileId = doc.getDocFileId();
                    item.mainPageNo = "1";
                    item.relatedPageNos = new ArrayList<>(Collections.singletonList(1));
                    chatRefItems.add(item);
                }
            }

            if (CommonUtil.isEmpty(aiResponse)) {
                callback.onError("리포트 생성에 실패했습니다. AI 응답이 비어 있습니다.");
                return;
            }

            // ⑤ JSON 파싱 → 템플릿 HTML 렌더링
            String reportHtml = "";
            String executiveSummary = "";
            try {
                // AI 응답에서 JSON 부분 추출 (마크다운 코드블록 처리)
                String jsonContent = aiResponse.trim();
                if (jsonContent.startsWith("```json")) {
                    jsonContent = jsonContent.substring(7);
                }
                if (jsonContent.startsWith("```")) {
                    jsonContent = jsonContent.substring(3);
                }
                if (jsonContent.endsWith("```")) {
                    jsonContent = jsonContent.substring(0, jsonContent.length() - 3);
                }
                jsonContent = jsonContent.trim();

                JSONParser parser = new JSONParser();
                JSONObject aiJson = (JSONObject) parser.parse(jsonContent);

                // 래퍼 키 벗기기: { "리서치리포트": { ... } } → 내부 객체 사용
                if (aiJson.size() == 1) {
                    Object onlyVal = aiJson.values().iterator().next();
                    if (onlyVal instanceof JSONObject) {
                        aiJson = (JSONObject) onlyVal;
                    }
                }

                // AI 한글 키 → 템플릿 영문 키 매핑
                JSONObject mappedJson = mapResearcherJsonKeys(aiJson);

                // 요약 텍스트 추출 (여러 후보 키 시도)
                for (String summaryKey : new String[]{"executive_summary", "요약", "summary", "핵심요약"}) {
                    Object summaryObj = mappedJson.get(summaryKey);
                    if (summaryObj != null && CommonUtil.isNotEmpty(String.valueOf(summaryObj))) {
                        executiveSummary = String.valueOf(summaryObj);
                        break;
                    }
                }

                // 출처는 LLM 출력을 버리고 백엔드에서 직접 링크 HTML로 구성한다.
                // 템플릿 렌더링 시 HTML escape를 우회하기 위해 토큰으로 치환 후 후처리에서 교체.
                mappedJson.put("sources", SOURCES_TOKEN);

                // 템플릿 HTML 렌더링
                if (tmpl != null && CommonUtil.isNotEmpty(tmpl.getTmplHtml()) && !tmplFieldList.isEmpty()) {
                    reportHtml = tmplHtmlRenderService.renderTemplateHtml(tmpl.getTmplHtml(), mappedJson, tmplFieldList);
                } else {
                    reportHtml = "<div>" + aiResponse + "</div>";
                }

                // 출처 토큰 → 실제 링크 HTML 교체
                String sourcesHtml = buildResearcherSourcesHtml(ragDocs, webSearchSource);
                reportHtml = reportHtml.replace(SOURCES_TOKEN, sourcesHtml);
            } catch (Exception e) {
                logger.warn("리서처 리포트 JSON 파싱/렌더링 오류: {}", e.getMessage());
                // fallback: 코드블록(<pre>) 대신 일반 문단으로 렌더 — 에디터 코드블록 다크 배경 방지
                String safe = aiResponse.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        .replace("\n", "<br/>");
                reportHtml = "<div><p>" + safe + "</p></div>";
                executiveSummary = aiResponse.length() > 200 ? aiResponse.substring(0, 200) + "..." : aiResponse;
            }

            // ⑥ 클라이언트에 결과 전송
            // 요약 텍스트 (채팅 버블)
            String summaryText = CommonUtil.isNotEmpty(executiveSummary) ? executiveSummary : "리서치 리포트가 생성되었습니다.";
            callback.onChunk(summaryText, summaryText, null);

            // 리포트 HTML (사이드 패널)
            if (CommonUtil.isNotEmpty(reportHtml)) {
                callback.onChunk(reportHtml, reportHtml, "report_html");
            }

            // 웹 검색 출처 (answer_source 문자열에서 URL 추출 → items 구성)
            JSONArray webSourceItems = extractWebSourceItems(webSearchSource);
            String webGroundingJson = "";
            if (!webSourceItems.isEmpty()) {
                JSONObject wgJson = new JSONObject();
                wgJson.put("items", webSourceItems);
                webGroundingJson = wgJson.toJSONString();

                // 클라이언트에 출처 청크 전송
                JSONObject sourcePayload = new JSONObject();
                sourcePayload.put("items", webSourceItems);
                callback.onChunk(webGroundingJson, sourcePayload.toJSONString(), "answer_source");
            }

            String savedLogId = this.doInsertAiLog(
                    threadId, agentId, query, summaryText,
                    0, 0, svcTy, modelId, refId, userId,
                    null, null, null, null,
                    null, null, chatRefItems,
                    webGroundingJson, null,
                    reportHtml);

            this.updateChatRoomLastChatDt(threadId);

            // ⑧ 완료 전송
            callback.onComplete(summaryText, "", "", new ArrayList<>(), threadId,
                    CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null,
                    "", "", "");

        } catch (Exception e) {
            logger.error("리서처 리포트 생성 중 오류: {}", e.getMessage(), e);
            callback.onError("리서치 리포트 생성 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    /**
     * RISK 에이전트(프로젝트 리스크진단): RFP(PDF) 업로드 → 텍스트 추출 → 템플릿 섹션 분리
     * → 섹션별 병렬 LLM 진단(자사 역량 RAG 결합) → 결과 통합 → 리포트 HTML 조립.
     */
    private void deliverRiskReportViaWebSocket(
            String query, String threadId, String userId, String svcTy, String modelId,
            String refId, String agentId, List<Long> attachmentFileIds,
            ChatbotWebSocketHandler.ChatbotStreamingCallback callback) {
        try {
            // ① 첨부 RFP 필수 검증 + 텍스트 추출
            if (!hasNonNullAttachmentId(attachmentFileIds)) {
                callback.onError("진단할 RFP 파일을 업로드하세요.");
                return;
            }
            callback.onStatus("extracting_file", "RFP 문서 분석 중");
            StringBuilder rfpTextBuilder = new StringBuilder();
            List<String> uploadedFileNames = new ArrayList<>();
            for (Long fileId : attachmentFileIds) {
                if (fileId == null) {
                    continue;
                }
                try {
                    ChatbotVO fileSearchVO = new ChatbotVO();
                    fileSearchVO.setChatFileId(fileId);
                    ChatbotVO fileVO = chatbotDAO.selectChatFileById(fileSearchVO);
                    if (fileVO == null || CommonUtil.isEmpty(fileVO.getFilePath())) {
                        continue;
                    }
                    String fileName = CommonUtil.nullToBlank(fileVO.getFileName());
                    byte[] bytes = fileService.downloadStorageObjectBytes(fileVO.getFilePath());
                    String text = extractPdfText(bytes, fileName);
                    if (CommonUtil.isNotEmpty(text)) {
                        if (CommonUtil.isNotEmpty(fileName)) {
                            uploadedFileNames.add(fileName);
                            rfpTextBuilder.append("\n\n===== 파일: ").append(fileName).append(" =====\n");
                        }
                        rfpTextBuilder.append(text);
                    }
                } catch (Exception e) {
                    logger.warn("RFP 파일 추출 실패 (chatFileId={}): {}", fileId, e.getMessage());
                }
            }
            String rfpText = rfpTextBuilder.toString().trim();
            if (CommonUtil.isEmpty(rfpText)) {
                callback.onError("업로드한 파일에서 텍스트를 추출하지 못했습니다. 텍스트가 포함된 PDF인지 확인하세요.");
                return;
            }
            // 캡 이하면 원문 그대로(빠른 경로), 초과(대용량 RFP)면 청크 병렬 요약으로 압축
            String rfpTextForPrompt = condenseRfpTextIfLarge(rfpText, modelId, callback);

            // ② 템플릿 + 섹션(필드) 로딩
            callback.onStatus("loading_template", "리포트 템플릿 준비 중");
            ChatbotVO.AgtSubCfgVO subCfg = getAgentSubCfg(agentId);
            String tmplId = RISK_DEFAULT_TMPL_ID;
            if (subCfg != null && subCfg.getAdditionalConfigMap() != null) {
                Object featuresObj = subCfg.getAdditionalConfigMap().get("features");
                if (featuresObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> features = (Map<String, Object>) featuresObj;
                    String cfgTmplId = (String) features.get("tmplId");
                    if (CommonUtil.isNotEmpty(cfgTmplId)) {
                        tmplId = cfgTmplId;
                    }
                }
            }
            TmplVO tmplSearchVO = new TmplVO();
            tmplSearchVO.setTmplId(tmplId);
            TmplVO tmpl = tmplService.selectTmplDetail(tmplSearchVO);
            List<LibraryVO.TmplFieldItem> tmplFieldList = new ArrayList<>();
            if (tmpl != null) {
                TmplVO fieldSearchVO = new TmplVO();
                fieldSearchVO.setTmplId(tmplId);
                List<TmplVO.TmplFieldVO> fieldVoList = tmplService.selectTmplFieldList(fieldSearchVO);
                if (fieldVoList != null) {
                    for (TmplVO.TmplFieldVO fv : fieldVoList) {
                        LibraryVO.TmplFieldItem item = new LibraryVO.TmplFieldItem();
                        item.setJsonKey(fv.getJsonKey());
                        item.setFieldNm(fv.getFieldNm());
                        item.setMultilineYn(fv.getMultilineYn());
                        item.setLayoutType(fv.getLayoutType());
                        tmplFieldList.add(item);
                    }
                }
            }
            if (tmpl == null || tmplFieldList.isEmpty()) {
                callback.onError("리스크진단 리포트 템플릿이 설정되지 않았습니다. (tmplId=" + tmplId + ")");
                return;
            }
            String llmPrompt = CommonUtil.isNotEmpty(tmpl.getLlmPrompt())
                    ? tmpl.getLlmPrompt()
                    : "당신은 RFP(제안요청서) 리스크 진단 전문가입니다. 업로드된 RFP와 자사 역량 자료를 근거로 진단하세요.";

            // ③ 자사 역량 RAG 데이터셋 문서 조회
            List<String> datasetIds = new ArrayList<>();
            if (refId != null && !refId.trim().isEmpty()) {
                for (String part : refId.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty() && !"all".equalsIgnoreCase(trimmed)) {
                        datasetIds.add(trimmed);
                    }
                }
            }
            List<ChatbotVO> ragDocs = new ArrayList<>();
            StringBuilder ragDocNames = new StringBuilder();
            if (!datasetIds.isEmpty()) {
                try {
                    for (String dsId : datasetIds) {
                        ChatbotVO docSearchVO = new ChatbotVO();
                        docSearchVO.setRefId(dsId);
                        List<ChatbotVO> docFiles = chatbotDAO.selectDatasetDocFileNames(docSearchVO);
                        if (docFiles != null) {
                            for (ChatbotVO doc : docFiles) {
                                if (CommonUtil.isNotEmpty(doc.getFileName())) {
                                    ragDocs.add(doc);
                                    ragDocNames.append("- ").append(doc.getFileName()).append("\n");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("리스크진단 RAG 문서 조회 실패: {}", e.getMessage());
                }
            }

            // ④-1 자사 역량 RAG 검색 — 항목별 '좁은' 쿼리 여러 개를 병렬로 던져 각 표/섹션을 정확히 끌어온다.
            //     (한 방 broad 쿼리는 임베딩이 여러 주제의 평균이 되어 인력표·인증표 등 특정 청크를 놓침)
            String companyContext = "";
            if (!datasetIds.isEmpty()) {
                callback.onStatus("searching_rag", "자사 역량 자료 검색 중");
                companyContext = retrieveCompanyContext(datasetIds, modelId, threadId, agentId);
                logger.info("리스크진단 자사역량 RAG 컨텍스트 수신 - 길이:{}",
                        companyContext != null ? companyContext.length() : 0);
            }
            if (CommonUtil.isEmpty(companyContext)) {
                // RAG 미연결/검색 실패 시 — 문서명 목록만 참고로 제공
                companyContext = ragDocNames.length() > 0
                        ? "(검색 결과 없음 — 참고 문서 목록)\n" + ragDocNames
                        : "(자사 역량 자료 없음)";
            }

            // ④-2 전체 리포트 생성 — 9000/query(대용량 컨텍스트)로 RFP 임베딩 없이 단일 생성(섹션 간 일관성 유지).
            //     모든 섹션을 [[SEC:키]]…[[/SEC]] 구분자로 한 번에 받는다.
            callback.onStatus("analyzing_sections", "리스크 진단 리포트 생성 중");
            String reportPrompt = buildRiskReportPrompt(llmPrompt, tmplFieldList, rfpTextForPrompt,
                    companyContext, CommonUtil.isNotEmpty(query) ? query : "(추가 요청 없음)");
            String aiResponse = callLlmQuerySync(reportPrompt, modelId, threadId);
            // 응답이 비면 1회 재시도
            if (CommonUtil.isEmpty(aiResponse)) {
                logger.warn("리스크진단 리포트 1차 응답 없음 — 재시도");
                aiResponse = callLlmQuerySync(reportPrompt, modelId, threadId);
            }

            // ⑤ 섹션 구분자([[SEC:키]]…[[/SEC]]) 파싱 → key별 HTML
            Map<String, String> sectionHtmlMap = parseRiskSections(aiResponse, tmplFieldList);
            int filledSections = 0;
            for (String v : sectionHtmlMap.values()) {
                if (CommonUtil.isNotEmpty(v)) {
                    filledSections++;
                }
            }
            logger.info("리스크진단 리포트 생성 완료 - 파싱된 섹션:{}/{}, 응답 길이:{}",
                    filledSections, sectionHtmlMap.size(), aiResponse != null ? aiResponse.length() : 0);

            // 요약 텍스트 (채팅 버블용) — HTML 태그 제거한 평문
            String executiveSummary = "";
            for (String summaryKey : new String[]{"executive_summary", "요약", "summary", "핵심요약"}) {
                String summaryVal = sectionHtmlMap.get(summaryKey);
                if (CommonUtil.isNotEmpty(summaryVal)) {
                    executiveSummary = stripHtmlTags(summaryVal);
                    break;
                }
            }

            // ⑥ 리포트 HTML 조립 — LLM이 생성한 HTML 조각을 escape 없이 {{key}}에 직접 주입
            //    (공용 TmplHtmlRenderService는 값을 escape하므로 리스크 리포트는 직접 치환한다)
            String reportHtml = CommonUtil.isNotEmpty(tmpl.getTmplHtml())
                    ? tmpl.getTmplHtml() : "<div class=\"risk-report\"></div>";
            for (LibraryVO.TmplFieldItem field : tmplFieldList) {
                if (field == null || CommonUtil.isEmpty(field.getJsonKey())) {
                    continue;
                }
                String key = field.getJsonKey();
                if ("sources".equalsIgnoreCase(key)) {
                    continue;
                }
                String v = sectionHtmlMap.get(key);
                String htmlVal = CommonUtil.isNotEmpty(v)
                        ? v
                        : "<p class=\"risk-empty\">※ 해당 항목 분석 결과가 비어 있습니다.</p>";
                reportHtml = reportHtml.replace("{{" + key + "}}", htmlVal);
            }
            String sourcesHtml = buildRiskSourcesHtml(uploadedFileNames, ragDocs);
            reportHtml = reportHtml.replace("{{sources}}", sourcesHtml);

            // ⑦ 전송 + 저장
            String summaryText = CommonUtil.isNotEmpty(executiveSummary)
                    ? executiveSummary : "프로젝트 리스크진단 리포트가 생성되었습니다.";
            callback.onChunk(summaryText, summaryText, null);
            if (CommonUtil.isNotEmpty(reportHtml)) {
                callback.onChunk(reportHtml, reportHtml, "report_html");
            }

            // ⑧ RAG 참조 문서를 TB_CHAT_REF에 저장하기 위해 chatRefItems 구성
            List<ChatRefItem> chatRefItems = new ArrayList<>();
            for (ChatbotVO doc : ragDocs) {
                if (doc == null || CommonUtil.isEmpty(doc.getDocFileId())) {
                    continue;
                }
                ChatRefItem item = new ChatRefItem();
                item.docFileId = doc.getDocFileId();
                item.mainPageNo = "1";
                item.relatedPageNos = new ArrayList<>(Collections.singletonList(1));
                chatRefItems.add(item);
            }

            String savedLogId = this.doInsertAiLog(
                    threadId, agentId, query, summaryText,
                    0, 0, svcTy, modelId, refId, userId,
                    null, null, null, null,
                    null, null, chatRefItems,
                    null, null,
                    reportHtml);

            this.updateChatRoomLastChatDt(threadId);

            // 첨부파일(RFP) LOG_ID 연결
            if (CommonUtil.isNotEmpty(savedLogId) && hasNonNullAttachmentId(attachmentFileIds)) {
                try {
                    List<Long> linkFileIds = new ArrayList<>();
                    for (Long id : attachmentFileIds) {
                        if (id != null) {
                            linkFileIds.add(id);
                        }
                    }
                    ChatbotVO linkVO = new ChatbotVO();
                    linkVO.setChatFileIdList(linkFileIds);
                    linkVO.setLogId(Long.parseLong(savedLogId));
                    chatbotDAO.linkChatFilesToLog(linkVO);
                } catch (Exception e) {
                    logger.warn("리스크진단 첨부파일 LOG_ID 연결 실패: {}", e.getMessage());
                }
            }

            callback.onComplete(summaryText, "", "", new ArrayList<>(), threadId,
                    CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null,
                    "", "", "");

        } catch (Exception e) {
            logger.error("리스크진단 리포트 생성 중 오류: {}", e.getMessage(), e);
            callback.onError("리스크진단 리포트 생성 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    /** PDFBox로 PDF byte[]에서 텍스트를 추출한다. PDF가 아니거나 실패 시 빈 문자열. */
    private String extractPdfText(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                     org.apache.pdfbox.pdmodel.PDDocument.load(bytes)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            // 좌표 순서로 추출 — 표/다단 레이아웃에서 같은 행의 셀이 흩어지지 않고 읽기 순서를 유지하도록 한다.
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            return text != null ? text.trim() : "";
        } catch (Exception e) {
            logger.warn("PDF 텍스트 추출 실패 (file={}): {}", fileName, e.getMessage());
            return "";
        }
    }

    /**
     * RFP 추출 텍스트가 캡(RISK_RFP_TEXT_MAX_CHARS) 이하면 그대로 반환(빠른 경로),
     * 초과(대용량 RFP)면 청크로 나눠 9000으로 병렬 요약 후 압축본을 반환한다.
     * 요약은 리스크 진단에 필요한 핵심(개요·과업·조건·평가·자격·일정·금액·보증/패널티·보안 등)만 추출한다.
     */
    private String condenseRfpTextIfLarge(String rfpText, String modelId,
            ChatbotWebSocketHandler.ChatbotStreamingCallback callback) {
        if (CommonUtil.isEmpty(rfpText)) {
            return "";
        }
        if (rfpText.length() <= RISK_RFP_TEXT_MAX_CHARS) {
            return rfpText;
        }
        callback.onStatus("condensing_rfp", "대용량 RFP 요약 중");

        int total = rfpText.length();
        int chunkSize = Math.max(RISK_SUMMARY_MIN_CHUNK_CHARS,
                (int) Math.ceil((double) total / RISK_SUMMARY_MAX_CHUNKS));
        // 인접 청크를 overlap만큼 겹쳐 잘라, 청크 경계에 걸친 표·항목(인력표·평가표·일정표 등)이 잘리지 않게 한다.
        int overlap = Math.min(RISK_SUMMARY_CHUNK_OVERLAP, chunkSize / 4);
        List<String> chunks = new ArrayList<>();
        int pos = 0;
        while (pos < total) {
            int end = Math.min(total, pos + chunkSize);
            chunks.add(rfpText.substring(pos, end));
            if (end >= total) {
                break;
            }
            pos = end - overlap;
        }
        logger.info("RFP 요약 시작 - 원본:{}자, 청크:{}개(청크당 ~{}자, overlap:{}자)",
                total, chunks.size(), chunkSize, overlap);

        // 청크별 병렬 요약 — 순서 보존(원문 순서대로 합쳐야 흐름 유지). 청크 진행 상황을 로그로 남긴다.
        final int chunkCount = chunks.size();
        List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
        for (int ci = 0; ci < chunkCount; ci++) {
            final int chunkNo = ci + 1;
            final String fChunk = chunks.get(ci);
            futures.add(riskSummarizeExecutor.submit(() -> {
                long chunkStart = System.currentTimeMillis();
                logger.info("RFP 청크 요약 시작 - {}/{} (입력:{}자)", chunkNo, chunkCount, fChunk.length());
                String out = callLlmQuerySync(buildRfpSummaryPrompt(fChunk), modelId, "");
                logger.info("RFP 청크 요약 완료 - {}/{} (입력:{}자 → 요약:{}자, {}ms)",
                        chunkNo, chunkCount, fChunk.length(), out != null ? out.length() : 0,
                        System.currentTimeMillis() - chunkStart);
                return out;
            }));
        }
        StringBuilder condensed = new StringBuilder();
        int idx = 0;
        for (java.util.concurrent.Future<String> f : futures) {
            idx++;
            try {
                String s = f.get(RISK_QUERY_READ_TIMEOUT_SEC + 20, java.util.concurrent.TimeUnit.SECONDS);
                if (CommonUtil.isNotEmpty(s)) {
                    condensed.append("===== 요약 ").append(idx).append(" =====\n")
                            .append(s.trim()).append("\n\n");
                }
            } catch (Exception e) {
                logger.warn("RFP 청크 {} 요약 실패: {}", idx, e.getMessage());
            }
        }

        String result = condensed.toString().trim();
        if (CommonUtil.isEmpty(result)) {
            // 요약이 전부 실패하면 앞부분만이라도 사용(폴백)
            logger.warn("RFP 요약 결과 없음 — 원문 앞부분으로 폴백");
            result = rfpText.substring(0, RISK_RFP_TEXT_MAX_CHARS) + "\n...(이하 생략)";
        } else if (result.length() > RISK_CONDENSED_MAX_CHARS) {
            // 압축본이 압축 상한(넉넉)을 넘는 극단적 경우에만 안전 절단 — 후반부 요약이 잘리지 않도록 캡을 크게 둔다
            logger.warn("RFP 압축본이 상한({}자) 초과 — 안전 절단", RISK_CONDENSED_MAX_CHARS);
            result = result.substring(0, RISK_CONDENSED_MAX_CHARS) + "\n...(요약 일부 생략)";
        }
        logger.info("RFP 요약 완료 - 원본:{}자 → 압축:{}자, 청크:{}개", total, result.length(), chunks.size());
        return result;
    }

    /** 대용량 RFP 청크 1개를 리스크 진단 관점에서 요약하는 프롬프트. */
    private String buildRfpSummaryPrompt(String chunk) {
        return "다음은 RFP(제안요청서)의 일부입니다. 리스크 진단에 필요한 핵심 정보만 항목형으로 요약하세요.\n"
                + "포함 대상: 사업 개요/목적, 과업 범위·요구사항, 입찰·계약 조건, 평가 기준·배점, 자격 요건, "
                + "일정·기간, 금액·예산, 보증금·패널티, 보안·개인정보 요건, "
                + "**붙임·별지·서식·평가표 등 후반부 첨부 내용**, 기타 제약/유의사항.\n"
                + "규칙:\n"
                + "- 해당 내용이 없는 항목은 생략.\n"
                + "- **표 형태 데이터(평가 배점표, 인력 구성표, 일정표, 자격·인증 목록과 만료일, 금액 내역 등)는 "
                + "요약·생략하지 말고 항목과 수치를 원문 그대로 보존**하세요(마크다운 표 또는 '항목: 값' 나열).\n"
                + "- 수치·일정·조항·금액·날짜·배점은 절대 생략·반올림하지 말 것.\n"
                + "- 이 조각이 붙임/별지/평가기준 등 후반부라도 빠짐없이 핵심을 추출할 것.\n"
                + "- 인사말·설명 없이 요약 본문만 출력.\n\n## RFP 일부\n" + chunk;
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

    /**
     * 자사 역량 RAG 검색 — 항목별 좁은 쿼리를 병렬로 던져 각 표/섹션 청크를 정확히 끌어와 합친다.
     * 한 방 broad 쿼리는 임베딩이 여러 주제의 평균이 되어 인력표·인증표 같은 특정 청크를 top-K에서 놓치므로,
     * 항목별 focused 쿼리로 분리한다(각 쿼리는 짧아 8192 무관, 병렬이라 빠름).
     */
    private String retrieveCompanyContext(List<String> datasetIds, String modelId, String threadId, String agentId) {
        // {제목, 검색 쿼리} 쌍 — 항목별 좁은 질의
        String[][] aspects = {
            {"인력 현황", "자사의 핵심 투입 예정 인력과 전사 인력 구성을 알려줘: 역할·성명·경력연수·보유 자격증·담당 업무, "
                    + "그리고 직군별 인원수와 비율. 인력표의 모든 행과 수치를 그대로."},
            {"인증·자격·특허", "자사가 보유한 인증·자격·특허·저작권을 알려줘: 인증/특허 명칭, 번호, 취득일, 유효기간(만료일), "
                    + "발급기관을 표의 항목과 날짜 그대로."},
            {"수행 실적", "자사의 주요 납품·구축 실적을 알려줘: 연도·발주기관·사업명·수행역할·계약금액을 표 그대로."},
            {"기술·솔루션·서비스", "자사의 핵심 기술 역량, 주요 솔루션, 서비스 포트폴리오와 수행 방식을 알려줘: "
                    + "기술 분야·세부 기술·수준, 솔루션명과 핵심 기능."},
            {"보안·운영(SLA) 역량", "자사의 보안·개인정보보호 대응 역량과 유지보수·운영(SLA) 역량을 알려줘."}
        };

        // 병렬 검색
        List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
        for (String[] aspect : aspects) {
            final String q = aspect[1] + " 자료에 있는 표·수치·날짜는 요약하지 말고 그대로 포함하고, "
                    + "없는 항목은 '해당 자료 없음'으로 표기.";
            futures.add(riskSummarizeExecutor.submit(
                    () -> callRagQuerySync(q, datasetIds, modelId, threadId, agentId)));
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < futures.size(); i++) {
            String title = aspects[i][0];
            try {
                String ans = futures.get(i).get(RISK_QUERY_READ_TIMEOUT_SEC + 20, java.util.concurrent.TimeUnit.SECONDS);
                logger.info("자사역량 RAG 검색 - [{}] 응답 길이:{}", title, ans != null ? ans.length() : 0);
                if (CommonUtil.isNotEmpty(ans)) {
                    sb.append("[").append(title).append("]\n").append(ans.trim()).append("\n\n");
                }
            } catch (Exception e) {
                logger.warn("자사역량 RAG 검색 실패 - [{}]: {}", title, e.getMessage());
            }
        }
        return sb.toString().trim();
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

    /** 9111/query(RAG)를 동기 호출하고 done 이벤트의 답변 문자열을 반환한다. dataset_id 벡터 검색 포함. */
    private String callRagQuerySync(String prompt, List<String> datasetIds, String modelId,
            String threadId, String agentId) {
        String ragApiUrl = PropertyUtil.getProperty("Globals.chatbot.ragQuery.apiUrl");
        if (CommonUtil.isEmpty(ragApiUrl)) {
            logger.warn("RAG 질의 API URL 미설정");
            return "";
        }
        Map<String, Object> ragParams = new HashMap<>();
        ragParams.put("query", prompt);
        ragParams.put("dataset_id", datasetIds != null ? datasetIds : new ArrayList<String>());
        ragParams.put("model_id", modelId != null ? modelId : "");
        ragParams.put("room_id", threadId != null ? threadId : "string");
        ragParams.put("agent_id", agentId != null ? agentId : "");
        ragParams.put("attachment_file_ids", new ArrayList<String>());

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(RISK_QUERY_READ_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        com.google.gson.Gson gson = new com.google.gson.Gson();
        String ragJsonBody = gson.toJson(ragParams);
        RequestBody ragBody = RequestBody.create(ragJsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));
        Request ragRequest = new Request.Builder()
                .url(ragApiUrl)
                .post(ragBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .build();
        logger.info("리스크 RAG 단일 호출 시작 - url:{}, readTimeout:{}s, 요청바디길이:{}",
                ragApiUrl, RISK_QUERY_READ_TIMEOUT_SEC, ragJsonBody.length());
        // AI 담당자 문의용 — 9111/query 요청 본문 전체 출력
        logger.info("리스크 RAG 요청 본문 전체:\n{}", ragJsonBody);
        long startMs = System.currentTimeMillis();
        try (okhttp3.Response response = client.newCall(ragRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String errBody = "";
                try {
                    if (response.body() != null) {
                        errBody = response.body().string();
                    }
                } catch (Exception ignore) {
                    // 본문 읽기 실패 무시
                }
                logger.warn("RAG 질의 응답 오류: {} - body: {}", response.code(), errBody);
                return "";
            }
            try (okhttp3.ResponseBody responseBody = response.body()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(responseBody.byteStream(), "UTF-8"));
                StringBuilder answerBuilder = new StringBuilder();
                String doneAnswer = "";
                String line;
                JSONParser jsonParser = new JSONParser();
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        continue;
                    }
                    String jsonStr;
                    if (line.startsWith("data: ")) {
                        jsonStr = line.substring(6).trim();
                    } else if (line.trim().startsWith("{")) {
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
                        if (answerObj == null) {
                            answerObj = data.get("답변");
                        }
                        if (answerObj != null) {
                            doneAnswer = String.valueOf(answerObj);
                        }
                    } catch (Exception ignore) {
                        // 개별 라인 파싱 실패는 무시
                    }
                }
                String result = CommonUtil.isNotEmpty(doneAnswer) ? doneAnswer : answerBuilder.toString();
                logger.info("리스크 RAG 응답 수신 완료 - 길이:{}, 소요:{}ms",
                        result != null ? result.length() : 0, System.currentTimeMillis() - startMs);
                return result;
            }
        } catch (java.net.SocketTimeoutException te) {
            logger.warn("리스크 RAG 호출 타임아웃 - {}s 초과({}ms 경과). 응답이 너무 길거나 AI 서버 지연일 수 있음: {}",
                    RISK_QUERY_READ_TIMEOUT_SEC, System.currentTimeMillis() - startMs, te.getMessage());
            return "";
        } catch (Exception e) {
            logger.warn("리스크 RAG 호출 실패 - {}({}ms 경과): {}",
                    e.getClass().getSimpleName(), System.currentTimeMillis() - startMs, e.getMessage());
            return "";
        }
    }

    /**
     * 일반 LLM 엔드포인트(9000/query)를 동기 호출하고 답변 문자열을 반환한다.
     * 데이터셋(RAG) 없이 RFP 본문만으로 진단할 때 사용 — dataset_id 불필요.
     */
    private String callLlmQuerySync(String prompt, String modelId, String threadId) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.gpt.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("리스크 LLM 질의 실패 - gpt.apiUrl 미설정");
            return "";
        }
        Map<String, Object> params = new HashMap<>();
        params.put("query", prompt);
        params.put("model_id", modelId != null ? modelId : "");
        params.put("room_id", threadId != null ? threadId : "");

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(RISK_QUERY_READ_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
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
        logger.info("리스크 LLM 단일 호출 시작 - url:{}, readTimeout:{}s, 요청바디길이:{}",
                apiUrl, RISK_QUERY_READ_TIMEOUT_SEC, jsonBody.length());
        long startMs = System.currentTimeMillis();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                logger.warn("리스크 LLM 질의 응답 오류: {}", response.code());
                return "";
            }
            try (okhttp3.ResponseBody responseBody = response.body()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(responseBody.byteStream(), "UTF-8"));
                StringBuilder answerBuilder = new StringBuilder();
                String doneAnswer = "";
                String line;
                JSONParser jsonParser = new JSONParser();
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        continue;
                    }
                    String jsonStr;
                    if (line.startsWith("data: ")) {
                        jsonStr = line.substring(6).trim();
                    } else if (line.trim().startsWith("{")) {
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
                        if (answerObj == null) {
                            answerObj = data.get("답변");
                        }
                        if (answerObj != null) {
                            doneAnswer = String.valueOf(answerObj);
                        }
                    } catch (Exception ignore) {
                        // 개별 라인 파싱 실패는 무시
                    }
                }
                String result = CommonUtil.isNotEmpty(doneAnswer) ? doneAnswer : answerBuilder.toString();
                logger.info("리스크 LLM 응답 수신 완료 - 길이:{}, 소요:{}ms",
                        result != null ? result.length() : 0, System.currentTimeMillis() - startMs);
                return result;
            }
        } catch (java.net.SocketTimeoutException te) {
            logger.warn("리스크 LLM 호출 타임아웃 - {}s 초과({}ms 경과). 응답이 너무 길거나 AI 서버 지연일 수 있음: {}",
                    RISK_QUERY_READ_TIMEOUT_SEC, System.currentTimeMillis() - startMs, te.getMessage());
            return "";
        } catch (Exception e) {
            logger.warn("리스크 LLM 호출 실패 - {}({}ms 경과): {}",
                    e.getClass().getSimpleName(), System.currentTimeMillis() - startMs, e.getMessage());
            return "";
        }
    }

    /** 리스크진단 출처 HTML — 업로드한 RFP 파일명 + 자사 역량 RAG 문서 링크. */
    private String buildRiskSourcesHtml(List<String> uploadedFileNames, List<ChatbotVO> ragDocs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<ul>");
        if (uploadedFileNames != null) {
            for (String name : uploadedFileNames) {
                if (CommonUtil.isEmpty(name)) {
                    continue;
                }
                sb.append("<li>업로드 RFP: ").append(htmlEscape(name)).append("</li>");
            }
        }
        if (ragDocs != null) {
            for (ChatbotVO doc : ragDocs) {
                if (doc == null || CommonUtil.isEmpty(doc.getFileName())) {
                    continue;
                }
                String fileName = htmlEscape(doc.getFileName());
                String docFileId = CommonUtil.nullToBlank(doc.getDocFileId());
                String href = RAG_DOC_LINK_PREFIX + docFileId;
                sb.append("<li>자사 역량 문서: <a href=\"").append(href).append("\">")
                        .append(fileName).append("</a></li>");
            }
        }
        sb.append("</ul>");
        return sb.toString();
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
     */
    public byte[] exportTranslationFile(String content, String fileType) throws Exception {
        if ("docx".equalsIgnoreCase(fileType)) {
            return TranslationDocUtil.textToDocxBytes(content);
        }
        return TranslationDocUtil.textToTxtBytes(content);
    }

    /**
     * 즉시번역(드래그 선택 번역) — 동기 1회 호출, 채팅 로그를 남기지 않는다.
     */
    public String instantTranslate(String content, String targetLang, String tone) {
        StringBuilder prompt = new StringBuilder(TRANSLATE_BASE_PROMPT);
        prompt.append("\n\n## 번역 조건");
        prompt.append("\n- 목표 언어: ").append(CommonUtil.isNotEmpty(targetLang) ? targetLang : "영어");
        if (CommonUtil.isNotEmpty(tone)) {
            prompt.append("\n- 톤: ").append(tone);
        }
        prompt.append("\n\n## 원문\n").append(content);

        return callAiSummary(prompt.toString(), "instant_translate");
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
     * 번역 에이전트 파일 모드: 첨부된 .docx/.txt 파일에서 텍스트를 추출 → AI 1회 호출로 번역 →
     * 일반 답변과 동일하게 번역된 텍스트를 채팅 응답으로 전달한다.
     */
    private void deliverTranslationFileViaWebSocket(String query, String threadId, String userId, String svcTy,
            String modelId, String refId, String agentId, List<Long> attachmentFileIds,
            ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {

        Long inputChatFileId = null;
        for (Long id : attachmentFileIds) {
            if (id != null) {
                inputChatFileId = id;
                break;
            }
        }
        if (inputChatFileId == null) {
            callback.onError("번역할 첨부파일을 찾을 수 없습니다.");
            return;
        }

        ChatbotVO searchVO = new ChatbotVO();
        searchVO.setChatFileId(inputChatFileId);
        ChatbotVO fileRow = chatbotDAO.selectChatFileById(searchVO);
        if (fileRow == null || CommonUtil.isEmpty(fileRow.getFilePath())) {
            callback.onError("첨부파일 정보를 찾을 수 없습니다.");
            return;
        }

        String ext = resolveTranslationFileExtension(fileRow.getFileName(), fileRow.getFileType());
        if (!"docx".equalsIgnoreCase(ext) && !"txt".equalsIgnoreCase(ext)) {
            callback.onError("지원하지 않는 파일 형식입니다. (.docx, .txt만 지원)");
            return;
        }

        byte[] originalBytes = fileService.downloadStorageObjectBytes(fileRow.getFilePath());
        List<TranslationDocUtil.Segment> segments = TranslationDocUtil.extractSegments(originalBytes, ext);
        if (segments.isEmpty()) {
            callback.onError("번역할 텍스트를 찾을 수 없습니다.");
            return;
        }

        String extractedText = segments.stream()
                .map(TranslationDocUtil.Segment::getText)
                .collect(Collectors.joining("\n"));

        String prompt = TRANSLATE_BASE_PROMPT
                + "\n\n" + (query != null ? query : "")
                + "\n\n## 원문\n" + extractedText;

        String translatedText = callAiSummary(prompt, "translate_file");
        if (CommonUtil.isEmpty(translatedText)) {
            callback.onError("번역에 실패했습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }

        callback.onChunk(translatedText, translatedText, null);

        String savedLogId = "";
        if (!"llmTest".equals(svcTy) && CommonUtil.isNotEmpty(threadId)) {
            try {
                savedLogId = doInsertAiLog(threadId, agentId, query, translatedText, 0, 0, svcTy, modelId, refId, userId,
                        "", "", "", "", "", "", new ArrayList<>(), "", "", "", "");
                updateChatRoomLastChatDt(threadId);
                if (CommonUtil.isNotEmpty(savedLogId)) {
                    try {
                        List<Long> linkFileIds = new ArrayList<>();
                        for (Long id : attachmentFileIds) {
                            if (id != null) {
                                linkFileIds.add(id);
                            }
                        }
                        ChatbotVO linkVO = new ChatbotVO();
                        linkVO.setChatFileIdList(linkFileIds);
                        linkVO.setLogId(Long.parseLong(savedLogId));
                        chatbotDAO.linkChatFilesToLog(linkVO);
                    } catch (Exception e) {
                        logger.warn("첨부파일 LOG_ID 연결 실패: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.warn("챗봇 로그 저장 실패: {}", e.getMessage());
            }
        }

        callback.onComplete(translatedText, "", "", new ArrayList<>(), CommonUtil.nullToBlank(threadId),
                CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null, null, null, null);
    }

    private String resolveTranslationFileExtension(String fileName, String fileType) {
        if (CommonUtil.isNotEmpty(fileName)) {
            int dotIdx = fileName.lastIndexOf('.');
            if (dotIdx >= 0 && dotIdx < fileName.length() - 1) {
                return fileName.substring(dotIdx + 1).toLowerCase();
            }
        }
        if (CommonUtil.isNotEmpty(fileType)) {
            String normalized = fileType.trim().toLowerCase();
            int slashIdx = normalized.lastIndexOf('/');
            return slashIdx >= 0 ? normalized.substring(slashIdx + 1) : normalized;
        }
        return "";
    }

    /**
     * 뉴스 큐레이션 에이전트 전용.
     * 관심 카테고리마다 RSS 수집 → AI 호출 → 서버에서 JSON 배열 병합.
     */
    @SuppressWarnings("unchecked")
    private void deliverNewsRecommendationViaWebSocket(String query, String threadId, String userId, String svcTy,
            String modelId, String refId, String agentId, List<Long> attachmentFileIds,
            Map<String, Object> additionalConfig, ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {
        String rawQuery = query != null ? query : "";
        List<String> interestCategories = selectNewsInterestCategoryCodeIds(userId);
        if (interestCategories.isEmpty()) {
            callback.onError("뉴스 관심 카테고리가 설정되지 않았습니다.");
            return;
        }

        Map<String, Object> config = additionalConfig != null ? additionalConfig : Collections.emptyMap();
        List<Map<String, Object>> candidateSources = (List<Map<String, Object>>) config.get("candidateSources");
        Map<String, List<NewsRssUtil.FeedSpec>> feedMap = NewsRssUtil.buildFeedMap(candidateSources);

        String pressLabel = "연합뉴스";
        int snippetMaxLength = 400;
        Object engineObj = config.get("engine");
        if (engineObj instanceof Map) {
            Object candidateFetchObj = ((Map<String, Object>) engineObj).get("candidateFetch");
            if (candidateFetchObj instanceof Map) {
                Map<String, Object> candidateFetch = (Map<String, Object>) candidateFetchObj;
                Object pressLabelObj = candidateFetch.get("pressLabel");
                if (pressLabelObj != null && CommonUtil.isNotEmpty(String.valueOf(pressLabelObj))) {
                    pressLabel = String.valueOf(pressLabelObj);
                }
                Object snippetMaxLengthObj = candidateFetch.get("snippetMaxLength");
                if (snippetMaxLengthObj instanceof Number) {
                    snippetMaxLength = ((Number) snippetMaxLengthObj).intValue();
                }
            }
        }

        List<String> categoryJsonList = new ArrayList<>();
        for (String codeId : interestCategories) {
            List<RssArticleRow> categoryRows = NewsRssUtil.collectCandidatesForCodeId(restApiManager, logger, codeId,
                    feedMap, pressLabel, snippetMaxLength);
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
                        "",
                        new ArrayList<>(),
                        "",
                        "",
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
                CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null, null, null, null);
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
