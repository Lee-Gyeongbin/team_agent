package kr.teamagent.chat.service.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.common.apilog.service.impl.ApiCallLogServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 에이전트 공통 지원 서비스.
 * <ul>
 *   <li>에이전트 SUB_CFG 조회 및 타입 판별 메서드</li>
 *   <li>AI 요약 동기 호출 ({@link #callAiSummary})</li>
 *   <li>모든 에이전트 서비스가 공유하는 SUB_TY 상수</li>
 * </ul>
 */
@Service
public class ChatbotAgentSupport {

    private static final Logger logger = LoggerFactory.getLogger(ChatbotAgentSupport.class);

    // ── SUB_TY 상수 ──────────────────────────────────────────────────────────
    public static final String AUTO_RECOMMEND_SUB_TY = "AUTO_RECOMMEND";
    public static final String RECOMMEND_SUB_TY      = "RECOMMEND";
    public static final String CURATION_SUB_TY       = "CURATION";
    public static final String TRANSLATE_SUB_TY      = "TRANSLATE";
    public static final String RESEARCHER_SUB_TY     = "RESEARCHER";
    public static final String RISK_SUB_TY           = "RISK";
    public static final String PROPOSAL_SUB_TY       = "PROPOSAL";

    /** summary_query 동기 호출 시 프롬프트·응답이 커 지연이 길어질 수 있는 경우의 OkHttp 읽기 타임아웃(초). */
    public static final int SUMMARY_QUERY_READ_TIMEOUT_LONG_SEC = 180;

    /** Gson 인스턴스 (news curation prompt 직렬화 등 공용) */
    public static final Gson NEWS_CURATE_PROMPT_GSON = new Gson();

    // ── 의존성 ────────────────────────────────────────────────────────────────
    @Autowired
    private ChatbotDAO chatbotDAO;

    @Autowired
    private ApiCallLogServiceImpl apiCallLogService;

    // ── 에이전트 SUB_CFG 조회 ─────────────────────────────────────────────────

    /**
     * 에이전트의 TB_AGT_SUB_CFG 조회 + ADDITIONAL_CONFIG 파싱.
     * SUB_TY 기반 분기 판별 공용 헬퍼.
     */
    public ChatbotVO.AgtSubCfgVO getAgentSubCfg(String agentId) {
        if (CommonUtil.isEmpty(agentId)) return null;
        try {
            ChatbotVO searchVO = new ChatbotVO();
            searchVO.setAgentIdList(Collections.singletonList(agentId));
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

    /** ADDITIONAL_CONFIG JSON → Map 파싱 (subCfg에 주입) */
    public void parseAgentSubAdditionalConfig(ChatbotVO.AgtSubCfgVO subCfg) {
        if (subCfg == null) return;
        String json = subCfg.getAdditionalConfig();
        if (CommonUtil.isEmpty(json)) {
            subCfg.setAdditionalConfigMap(null);
            return;
        }
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        subCfg.setAdditionalConfigMap(NEWS_CURATE_PROMPT_GSON.fromJson(json, type));
    }

    // ── 에이전트 타입 판별 ────────────────────────────────────────────────────

    public boolean isActiveSubCfg(ChatbotVO.AgtSubCfgVO subCfg, String subTy) {
        return subCfg != null && subTy.equals(subCfg.getSubTy()) && "Y".equals(subCfg.getUseYn());
    }

    public boolean isActiveSubCfgAgent(String agentId, String subTy) {
        return isActiveSubCfg(getAgentSubCfg(agentId), subTy);
    }

    /**
     * RECOMMEND·AUTO_RECOMMEND 공통 — 프론트 완성형 프롬프트·추천형 API URL 분기 대상.
     */
    public boolean isPromptReadyRecommendAgent(String agentId) {
        return isPromptReadyRecommendAgent(getAgentSubCfg(agentId));
    }

    public boolean isPromptReadyRecommendAgent(ChatbotVO.AgtSubCfgVO subCfg) {
        return isActiveSubCfg(subCfg, RECOMMEND_SUB_TY) || isActiveSubCfg(subCfg, AUTO_RECOMMEND_SUB_TY);
    }

    /**
     * AUTO_RECOMMEND 중 검색 전용 API를 사용할 대상.
     * ADDITIONAL_CONFIG.engine.apiMode == "searchOnly" 이면 query_search_only URL로 보낸다.
     */
    public boolean isAutoRecommendSearchOnlyAgent(ChatbotVO.AgtSubCfgVO subCfg) {
        if (!isActiveSubCfg(subCfg, AUTO_RECOMMEND_SUB_TY)) return false;
        if (subCfg.getAdditionalConfigMap() == null) return false;
        Object engineObj = subCfg.getAdditionalConfigMap().get("engine");
        if (!(engineObj instanceof Map)) return false;
        return "searchOnly".equals(((Map<?, ?>) engineObj).get("apiMode"));
    }

    /**
     * RECOMMEND 에이전트 중 ADDITIONAL_CONFIG.features.addressEnrichment == "kakao" 인지 판별.
     * 응답 처리(카카오 장소 URL 보강)용 — URL 라우팅에는 사용하지 않는다.
     */
    public boolean isKakaoAddressEnrichmentAgent(ChatbotVO.AgtSubCfgVO subCfg) {
        if (!isActiveSubCfg(subCfg, RECOMMEND_SUB_TY)) return false;
        Object featuresObj = subCfg.getAdditionalConfigMap() != null
                ? subCfg.getAdditionalConfigMap().get("features") : null;
        if (!(featuresObj instanceof Map)) return false;
        return "kakao".equals(((Map<?, ?>) featuresObj).get("addressEnrichment"));
    }

    /** SUB_TY=RESEARCHER 에이전트 여부 판별. */
    public boolean isResearcherAgent(String agentId) {
        return isActiveSubCfgAgent(agentId, RESEARCHER_SUB_TY);
    }

    /** SUB_TY=RISK 에이전트(프로젝트 리스크진단) 여부 판별. */
    public boolean isRiskDiagnosisAgent(String agentId) {
        return isActiveSubCfgAgent(agentId, RISK_SUB_TY);
    }

    /** SUB_TY=PROPOSAL 에이전트(제안서 초안 생성) 여부 판별. */
    public boolean isProposalAgent(String agentId) {
        ChatbotVO.AgtSubCfgVO subCfg = getAgentSubCfg(agentId);
        return subCfg != null && PROPOSAL_SUB_TY.equals(subCfg.getSubTy()) && "Y".equals(subCfg.getUseYn());
    }

    // ── AI 요약 동기 호출 ─────────────────────────────────────────────────────

    /**
     * GPT endpoint에 동기 호출하여 AI 응답 텍스트를 반환한다.
     * doInsertAiLog를 호출하지 않으므로 로그 테이블에 쌓이지 않는다.
     *
     * @param prompt    요청 프롬프트
     * @param purpose   로깅용 호출 목적 (title, tags, reAskReport 등).
     *                  reAskReport 등은 응답 지연 대비 읽기 타임아웃이 더 길다.
     * @param refLogId  참조 로그 ID
     * @return AI 응답 텍스트, 실패 시 null
     */
    public String callAiSummary(String prompt, String purpose, Long refLogId) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.summary.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("{} 생성 실패 - GPT API URL 미설정", purpose);
            return null;
        }

        int readTimeoutSec = ("reAskReport".equals(purpose)
                || "insightReport".equals(purpose)
                || "createDoc".equals(purpose)
                || "news_curate".equals(purpose)
                || "mtlcareReport".equals(purpose)
                || "translate_file".equals(purpose))
                ? SUMMARY_QUERY_READ_TIMEOUT_LONG_SEC
                : 60;

        Map<String, Object> params = new HashMap<>();
        params.put("query", prompt);
        params.put("room_id", "");

        Gson summaryGson = new Gson();
        String summaryReqJson = summaryGson.toJson(params);
        long summaryStartMs = System.currentTimeMillis();

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(readTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            String jsonBody = summaryReqJson;
            RequestBody body = RequestBody.create(jsonBody,
                    okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build();

            logger.info("AI {} 생성 호출 시작 - url: {}", purpose, apiUrl);

            try (okhttp3.Response response = client.newCall(request).execute()) {
                int summaryRespMs = (int) Math.min(System.currentTimeMillis() - summaryStartMs, Integer.MAX_VALUE);
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("AI {} 응답 오류: {}", purpose, response.code());
                    apiCallLogService.insertSilently(null, refLogId, apiUrl, "-", purpose, jsonBody,
                            0, 0, summaryRespMs, "N", "HTTP " + response.code(), null);
                    return null;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    String raw = responseBody.string();
                    if (CommonUtil.isEmpty(raw)) {
                        apiCallLogService.insertSilently(null, refLogId, apiUrl, "-", purpose, jsonBody,
                                0, 0, summaryRespMs, "N", "빈 응답", null);
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
                                apiCallLogService.insertSilently(null, refLogId, apiUrl, "-", purpose, jsonBody,
                                        0, 0, summaryRespMs, "N", errorCode + ": " + errorContent, null);
                                return null;
                            }
                        }

                        String answer = (String) data.get("answer");
                        if (CommonUtil.isNotEmpty(answer)) {
                            String result = answer.trim();
                            logger.info("AI {} 생성 완료", purpose);
                            apiCallLogService.insertSilently(null, refLogId, apiUrl, "-", purpose, jsonBody,
                                    0, 0, summaryRespMs, "Y", null, null);
                            return result;
                        }
                    } catch (Exception e) {
                        logger.warn("AI {} 응답 파싱 오류: {}", purpose, e.getMessage());
                        apiCallLogService.insertSilently(null, refLogId, apiUrl, "-", purpose, jsonBody,
                                0, 0, summaryRespMs, "N", "파싱 오류: " + e.getMessage(), null);
                    }
                }
            }
        } catch (Exception e) {
            int summaryRespMs = (int) Math.min(System.currentTimeMillis() - summaryStartMs, Integer.MAX_VALUE);
            logger.warn("AI {} 생성 중 오류 발생: {}", purpose, e.getMessage());
            apiCallLogService.insertSilently(null, refLogId, apiUrl, "-", purpose, summaryReqJson,
                    0, 0, summaryRespMs, "N", e.getMessage(), null);
        }

        return null;
    }
}
