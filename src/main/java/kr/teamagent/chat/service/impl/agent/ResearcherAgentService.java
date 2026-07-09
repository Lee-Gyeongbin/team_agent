package kr.teamagent.chat.service.impl.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.chat.socket.ChatbotWebSocketHandler;
import kr.teamagent.chat.service.impl.ChatbotAgentSupport;
import kr.teamagent.chat.service.impl.ChatbotDAO;
import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.tmpl.service.TmplVO;
import kr.teamagent.tmpl.service.impl.TmplHtmlRenderService;
import kr.teamagent.tmpl.service.impl.TmplServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * RESEARCHER 에이전트 전용 서비스.
 * 웹 검색 + 사내 문서 RAG를 통합하여 리서치 리포트를 생성한다.
 */
@Service
public class ResearcherAgentService {

    private static final Logger logger = LoggerFactory.getLogger(ResearcherAgentService.class);

    /** 리서처 리포트 출처 섹션 — 템플릿 HTML escape 우회용 치환 토큰 */
    public static final String SOURCES_TOKEN = "@@RSRC_SOURCES@@";

    /**
     * RAG 문서 출처 링크 — 백엔드 GET 리다이렉트 엔드포인트.
     * native target=_blank로 새 탭이 열리면 presigned 파일 URL로 302 리다이렉트된다.
     */
    public static final String RAG_DOC_LINK_PREFIX = "/api/repository/viewDocRedirect.do?docFileId=";

    @Autowired
    private ChatbotAgentSupport agentSupport;

    @Autowired
    private ChatbotDAO chatbotDAO;

    @Autowired
    private TmplServiceImpl tmplService;

    @Autowired
    private TmplHtmlRenderService tmplHtmlRenderService;

    /** doInsertAiLog / updateChatRoomLastChatDt / callWebSearchSync 호출용 (@Lazy) */
    @Autowired
    @Lazy
    private ChatbotServiceImpl chatbotService;

    // ── 리서처 리포트 WebSocket 전달 ──────────────────────────────────────────

    /**
     * RESEARCHER 에이전트: 웹 검색 + 사내 문서 RAG를 통합하여 리서치 리포트를 생성한다.
     * 1) query_search_only(9000) 동기 호출 → 웹 검색 답변 + 웹 출처(URL)
     * 2) 템플릿 조회 + 웹 결과를 주입한 enriched query 구성
     * 3) ragQuery(9111/query) 동기 호출 → dataset_id 벡터 검색 + JSON 리포트 생성 + file_info(참조 문서)
     * 4) TmplHtmlRenderService → 리포트 HTML 렌더링 (출처: 사내 문서 링크 + 웹 URL)
     * 5) TB_CHAT_LOG/TB_CHAT_REF 저장 + 결과 스트리밍 전송
     */
    public void deliverResearchReportViaWebSocket(
            String query, String threadId, String userId, String svcTy, String modelId,
            String refId, String agentId, List<Long> attachmentFileIds,
            ChatbotWebSocketHandler.ChatbotStreamingCallback callback) {
        try {
            // ① 웹 검색
            callback.onStatus("searching_web", "웹 검색 중");
            String[] webSearchResult = chatbotService.callWebSearchSync(query, modelId);
            String webSearchAnswer = (webSearchResult != null) ? webSearchResult[0] : "";
            String webSearchSource = (webSearchResult != null) ? webSearchResult[1] : "";

            // ② 템플릿 조회
            callback.onStatus("loading_template", "리포트 템플릿 준비 중");
            ChatbotVO.AgtSubCfgVO subCfg = agentSupport.getAgentSubCfg(agentId);
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

            String jsonKeyInstruction = chatbotService.buildTemplateJsonInstruction(tmplFieldList);

            String enrichedQuery = llmPrompt.replace("{{web_search_results}}", webSearchAnswer)
                    + "\n\n## 참조 가능한 사내 문서 목록\n"
                    + (ragDocNames.length() > 0 ? ragDocNames.toString() : "(없음)")
                    + "\n\n## 참조 가능한 웹 출처\n"
                    + (CommonUtil.isNotEmpty(webSearchSource) ? webSearchSource : "(없음)")
                    + jsonKeyInstruction
                    + "\n\n## 사용자 질문\n" + query;

            // ④ RAG 매뉴얼 질의(9111/query) 동기 호출
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
                    .readTimeout(ChatbotAgentSupport.SUMMARY_QUERY_READ_TIMEOUT_LONG_SEC,
                            java.util.concurrent.TimeUnit.SECONDS)
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            com.google.gson.Gson gson = new com.google.gson.Gson();
            String ragJsonBody = gson.toJson(ragParams);
            RequestBody ragBody = RequestBody.create(ragJsonBody,
                    okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request ragRequest = new Request.Builder()
                    .url(ragApiUrl)
                    .post(ragBody)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .build();

            logger.info("리서처 RAG 질의 호출 시작 - url: {}, dataset_id: {}", ragApiUrl, datasetIds);

            String aiResponse = null;
            List<ChatbotServiceImpl.ChatRefItem> chatRefItems = new ArrayList<>();
            try (okhttp3.Response response = client.newCall(ragRequest).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("리서처 RAG 질의 응답 오류: {}", response.code());
                    callback.onError("RAG 질의 API 응답 오류: " + response.code());
                    return;
                }
                try (okhttp3.ResponseBody responseBody = response.body()) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(responseBody.byteStream(), "UTF-8"));
                    StringBuilder answerBuilder = new StringBuilder();
                    String doneAnswer = "";
                    String line;
                    JSONParser jsonParser = new JSONParser();

                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event: ")) continue;
                        String jsonStr;
                        if (line.startsWith("data: ")) {
                            jsonStr = line.substring(6).trim();
                        } else if (line.trim().startsWith("{")) {
                            jsonStr = line.trim();
                        } else {
                            continue;
                        }
                        if (jsonStr.isEmpty()) continue;
                        try {
                            JSONObject data = (JSONObject) jsonParser.parse(jsonStr);
                            Object textObj = data.get("text");
                            if (textObj != null) answerBuilder.append(String.valueOf(textObj));
                            Object answerObj = data.get("answer");
                            if (answerObj == null) answerObj = data.get("답변");
                            if (answerObj != null) doneAnswer = String.valueOf(answerObj);
                            List<ChatbotServiceImpl.ChatRefItem> refs = chatbotService.extractChatRefItems(data);
                            if (!refs.isEmpty()) chatRefItems = refs;
                        } catch (Exception ignore) {
                            // 개별 라인 파싱 실패는 무시
                        }
                    }
                    aiResponse = CommonUtil.isNotEmpty(doneAnswer)
                            ? doneAnswer : answerBuilder.toString();
                }
            }

            // RAG 응답이 참조 문서를 주지 않으면 데이터셋에 연결된 문서를 참조로 저장(폴백)
            if (chatRefItems.isEmpty() && !ragDocs.isEmpty()) {
                for (ChatbotVO doc : ragDocs) {
                    if (doc == null || CommonUtil.isEmpty(doc.getDocFileId())) continue;
                    ChatbotServiceImpl.ChatRefItem item = new ChatbotServiceImpl.ChatRefItem();
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
                String jsonContent = aiResponse.trim();
                if (jsonContent.startsWith("```json")) jsonContent = jsonContent.substring(7);
                if (jsonContent.startsWith("```"))     jsonContent = jsonContent.substring(3);
                if (jsonContent.endsWith("```"))       jsonContent = jsonContent.substring(0, jsonContent.length() - 3);
                jsonContent = jsonContent.trim();

                JSONParser parser = new JSONParser();
                JSONObject aiJson = (JSONObject) parser.parse(jsonContent);

                // 래퍼 키 벗기기
                if (aiJson.size() == 1) {
                    Object onlyVal = aiJson.values().iterator().next();
                    if (onlyVal instanceof JSONObject) {
                        aiJson = (JSONObject) onlyVal;
                    }
                }

                JSONObject mappedJson = mapResearcherJsonKeys(aiJson);

                for (String summaryKey : new String[]{"executive_summary", "요약", "summary", "핵심요약"}) {
                    Object summaryObj = mappedJson.get(summaryKey);
                    if (summaryObj != null && CommonUtil.isNotEmpty(String.valueOf(summaryObj))) {
                        executiveSummary = String.valueOf(summaryObj);
                        break;
                    }
                }

                mappedJson.put("sources", SOURCES_TOKEN);

                if (tmpl != null && CommonUtil.isNotEmpty(tmpl.getTmplHtml()) && !tmplFieldList.isEmpty()) {
                    reportHtml = tmplHtmlRenderService.renderTemplateHtml(
                            tmpl.getTmplHtml(), mappedJson, tmplFieldList);
                } else {
                    reportHtml = "<div>" + aiResponse + "</div>";
                }

                String sourcesHtml = buildResearcherSourcesHtml(ragDocs, webSearchSource);
                reportHtml = reportHtml.replace(SOURCES_TOKEN, sourcesHtml);

            } catch (Exception e) {
                logger.warn("리서처 리포트 JSON 파싱/렌더링 오류: {}", e.getMessage());
                String safe = aiResponse.replace("&", "&amp;")
                        .replace("<", "&lt;").replace(">", "&gt;")
                        .replace("\n", "<br/>");
                reportHtml = "<div><p>" + safe + "</p></div>";
                executiveSummary = aiResponse.length() > 200
                        ? aiResponse.substring(0, 200) + "..." : aiResponse;
            }

            // ⑥ 클라이언트에 결과 전송
            String summaryText = CommonUtil.isNotEmpty(executiveSummary)
                    ? executiveSummary : "리서치 리포트가 생성되었습니다.";
            callback.onChunk(summaryText, summaryText, null);

            if (CommonUtil.isNotEmpty(reportHtml)) {
                callback.onChunk(reportHtml, reportHtml, "report_html");
            }

            JSONArray webSourceItems = extractWebSourceItems(webSearchSource);
            String webGroundingJson = "";
            if (!webSourceItems.isEmpty()) {
                JSONObject wgJson = new JSONObject();
                wgJson.put("items", webSourceItems);
                webGroundingJson = wgJson.toJSONString();
                JSONObject sourcePayload = new JSONObject();
                sourcePayload.put("items", webSourceItems);
                callback.onChunk(webGroundingJson, sourcePayload.toJSONString(), "answer_source");
            }

            String savedLogId = chatbotService.doInsertAiLog(
                    threadId, agentId, query, summaryText,
                    0, 0, svcTy, modelId, refId, userId,
                    null, null, null, null,
                    null, null, chatRefItems,
                    webGroundingJson, null,
                    reportHtml);

            chatbotService.updateChatRoomLastChatDt(threadId);

            callback.onComplete(summaryText, "", "", new ArrayList<>(), threadId,
                    CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null,
                    "", "", "");

        } catch (Exception e) {
            logger.error("리서처 리포트 생성 중 오류: {}", e.getMessage(), e);
            callback.onError("리서치 리포트 생성 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * 리서치 리포트 출처 섹션 HTML을 구성한다.
     */
    @SuppressWarnings("unchecked")
    private String buildResearcherSourcesHtml(List<ChatbotVO> ragDocs, String webSearchSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("<ul>");

        if (ragDocs != null) {
            for (ChatbotVO doc : ragDocs) {
                if (doc == null || CommonUtil.isEmpty(doc.getFileName())) continue;
                String fileName = htmlEscape(doc.getFileName());
                String docFileId = CommonUtil.nullToBlank(doc.getDocFileId());
                String href = RAG_DOC_LINK_PREFIX + docFileId;
                sb.append("<li>사내 문서: <a href=\"").append(href).append("\">")
                        .append(fileName).append("</a></li>");
            }
        }

        JSONArray webItems = extractWebSourceItems(webSearchSource);
        for (Object itemObj : webItems) {
            if (!(itemObj instanceof JSONObject)) continue;
            String url = String.valueOf(((JSONObject) itemObj).get("url"));
            if (CommonUtil.isEmpty(url)) continue;
            String urlEsc = htmlEscape(url);
            sb.append("<li>웹페이지: <a href=\"").append(urlEsc)
                    .append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
                    .append(urlEsc).append("</a></li>");
        }

        sb.append("</ul>");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private JSONArray extractWebSourceItems(String source) {
        JSONArray items = new JSONArray();
        if (CommonUtil.isEmpty(source)) return items;
        java.util.regex.Pattern urlPattern =
                java.util.regex.Pattern.compile("https?://[^\\s\"'<>\\)\\]]+");
        java.util.regex.Matcher matcher = urlPattern.matcher(source);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            String url = matcher.group().replaceAll("[.,;]+$", "");
            if (seen.add(url)) {
                JSONObject item = new JSONObject();
                item.put("url", url);
                items.add(item);
            }
        }
        return items;
    }

    /**
     * AI 응답 JSON의 한글/변형 키를 템플릿 필드의 영문 키로 매핑한다.
     */
    @SuppressWarnings("unchecked")
    private JSONObject mapResearcherJsonKeys(JSONObject src) {
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
            String normalizedKey = key.replaceAll("^\\d+\\.\\s*", "").trim();
            String compactKey = normalizedKey.replaceAll("[\\s_·.\\-]", "");

            String mappedKey = null;
            for (Map.Entry<String, String> entry : keyMap.entrySet()) {
                String candidate = entry.getKey().replaceAll("[\\s_·.\\-]", "");
                if (candidate.equalsIgnoreCase(compactKey)) {
                    mappedKey = entry.getValue();
                    break;
                }
            }

            String targetKey = mappedKey != null ? mappedKey : key;
            Object val = src.get(keyObj);
            if (val instanceof JSONObject) {
                mapped.put(targetKey, mapResearcherJsonKeys((JSONObject) val));
            } else {
                mapped.put(targetKey, val);
            }
        }
        return mapped;
    }

    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
