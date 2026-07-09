package kr.teamagent.chat.service.impl.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import kr.teamagent.common.apilog.service.impl.ApiCallLogServiceImpl;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.tmpl.service.TmplVO;
import kr.teamagent.tmpl.service.impl.TmplServiceImpl;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * RISK 에이전트(프로젝트 리스크진단) 전용 서비스.
 * RFP(PDF) 업로드 → 텍스트 추출 → 청크 병렬 요약(대용량) → LLM 단일 호출 리포트 생성 → HTML 조립.
 */
@Service
public class RiskDiagnosisAgentService {

    private static final Logger logger = LoggerFactory.getLogger(RiskDiagnosisAgentService.class);

    // ── 상수 ──────────────────────────────────────────────────────────────────
    /** 리스크진단 기본 리포트 템플릿 ID (subCfg.features.tmplId 미설정 시 폴백) */
    public static final String RISK_DEFAULT_TMPL_ID = "TM000008";
    /** 리스크진단 생성(9000) 프롬프트에 주입하는 RFP 추출 텍스트 최대 길이(문자). */
    public static final int RISK_RFP_TEXT_MAX_CHARS = 24000;
    /** 대용량 RFP 요약 시 청크 최대 개수. */
    public static final int RISK_SUMMARY_MAX_CHUNKS = 16;
    /** 대용량 RFP 요약 시 청크 최소 길이(문자). */
    public static final int RISK_SUMMARY_MIN_CHUNK_CHARS = 15000;
    /** 청크 요약을 합친 '압축 RFP'의 최대 길이(문자). */
    public static final int RISK_CONDENSED_MAX_CHARS = 80000;
    /** 청크 경계에 걸친 표·항목이 잘리지 않도록 인접 청크 간 겹치는 길이(문자). */
    public static final int RISK_SUMMARY_CHUNK_OVERLAP = 800;
    /** 리스크진단 단일 호출은 전체 리포트를 한 번에 생성하므로 읽기 타임아웃을 길게 둔다(초). */
    public static final int RISK_QUERY_READ_TIMEOUT_SEC = 300;
    /** RAG 문서 출처 링크 */
    public static final String RAG_DOC_LINK_PREFIX = "/api/repository/viewDocRedirect.do?docFileId=";

    /** 대용량 RFP 청크 병렬 요약용 스레드 풀 */
    private static final java.util.concurrent.ExecutorService riskSummarizeExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread thread = new Thread(r, "risk-summarize-worker");
                thread.setDaemon(true);
                return thread;
            });

    // ── 의존성 ────────────────────────────────────────────────────────────────
    @Autowired
    private ChatbotAgentSupport agentSupport;

    @Autowired
    private ChatbotDAO chatbotDAO;

    @Autowired
    private FileServiceImpl fileService;

    @Autowired
    private TmplServiceImpl tmplService;

    @Autowired
    private ApiCallLogServiceImpl apiCallLogService;

    /** doInsertAiLog / updateChatRoomLastChatDt / buildTemplateJsonInstruction 호출용 (@Lazy) */
    @Autowired
    @Lazy
    private ChatbotServiceImpl chatbotService;

    // ── 리스크진단 리포트 WebSocket 전달 ─────────────────────────────────────

    /**
     * RISK 에이전트(프로젝트 리스크진단): RFP(PDF) 업로드 → 텍스트 추출 → 템플릿 섹션 분리
     * → 섹션별 병렬 LLM 진단(자사 역량 RAG 결합) → 결과 통합 → 리포트 HTML 조립.
     */
    public void deliverRiskReportViaWebSocket(
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
                if (fileId == null) continue;
                try {
                    ChatbotVO fileSearchVO = new ChatbotVO();
                    fileSearchVO.setChatFileId(fileId);
                    ChatbotVO fileVO = chatbotDAO.selectChatFileById(fileSearchVO);
                    if (fileVO == null || CommonUtil.isEmpty(fileVO.getFilePath())) continue;
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

            String rfpTextForPrompt = condenseRfpTextIfLarge(rfpText, modelId, callback, agentId);

            // ② 템플릿 + 섹션(필드) 로딩
            callback.onStatus("loading_template", "리포트 템플릿 준비 중");
            ChatbotVO.AgtSubCfgVO subCfg = agentSupport.getAgentSubCfg(agentId);
            String tmplId = RISK_DEFAULT_TMPL_ID;
            if (subCfg != null && subCfg.getAdditionalConfigMap() != null) {
                Object featuresObj = subCfg.getAdditionalConfigMap().get("features");
                if (featuresObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> features = (Map<String, Object>) featuresObj;
                    String cfgTmplId = (String) features.get("tmplId");
                    if (CommonUtil.isNotEmpty(cfgTmplId)) tmplId = cfgTmplId;
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
                    if (!trimmed.isEmpty() && !"all".equalsIgnoreCase(trimmed)) datasetIds.add(trimmed);
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

            // ④-1 자사 역량 RAG 검색
            String companyContext = "";
            if (!datasetIds.isEmpty()) {
                callback.onStatus("searching_rag", "자사 역량 자료 검색 중");
                companyContext = retrieveCompanyContext(datasetIds, modelId, threadId, agentId);
                logger.info("리스크진단 자사역량 RAG 컨텍스트 수신 - 길이:{}",
                        companyContext != null ? companyContext.length() : 0);
            }
            if (CommonUtil.isEmpty(companyContext)) {
                companyContext = ragDocNames.length() > 0
                        ? "(검색 결과 없음 — 참고 문서 목록)\n" + ragDocNames
                        : "(자사 역량 자료 없음)";
            }

            // ④-2 전체 리포트 생성
            callback.onStatus("analyzing_sections", "리스크 진단 리포트 생성 중");
            String reportPrompt = buildRiskReportPrompt(llmPrompt, tmplFieldList, rfpTextForPrompt,
                    companyContext, CommonUtil.isNotEmpty(query) ? query : "(추가 요청 없음)");
            String aiResponse = callLlmQuerySync(reportPrompt, modelId, threadId, agentId);
            if (CommonUtil.isEmpty(aiResponse)) {
                logger.warn("리스크진단 리포트 1차 응답 없음 — 재시도");
                aiResponse = callLlmQuerySync(reportPrompt, modelId, threadId, agentId);
            }

            // ⑤ 섹션 구분자([[SEC:키]]…[[/SEC]]) 파싱
            Map<String, String> sectionHtmlMap = parseRiskSections(aiResponse, tmplFieldList);
            int filledSections = 0;
            for (String v : sectionHtmlMap.values()) {
                if (CommonUtil.isNotEmpty(v)) filledSections++;
            }
            logger.info("리스크진단 리포트 생성 완료 - 파싱된 섹션:{}/{}, 응답 길이:{}",
                    filledSections, sectionHtmlMap.size(), aiResponse != null ? aiResponse.length() : 0);

            String executiveSummary = "";
            for (String summaryKey : new String[]{"executive_summary", "요약", "summary", "핵심요약"}) {
                String summaryVal = sectionHtmlMap.get(summaryKey);
                if (CommonUtil.isNotEmpty(summaryVal)) {
                    executiveSummary = stripHtmlTags(summaryVal);
                    break;
                }
            }

            // ⑥ 리포트 HTML 조립
            String reportHtml = CommonUtil.isNotEmpty(tmpl.getTmplHtml())
                    ? tmpl.getTmplHtml() : "<div class=\"risk-report\"></div>";
            for (LibraryVO.TmplFieldItem field : tmplFieldList) {
                if (field == null || CommonUtil.isEmpty(field.getJsonKey())) continue;
                String key = field.getJsonKey();
                if ("sources".equalsIgnoreCase(key)) continue;
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
            List<ChatbotServiceImpl.ChatRefItem> chatRefItems = new ArrayList<>();
            for (ChatbotVO doc : ragDocs) {
                if (doc == null || CommonUtil.isEmpty(doc.getDocFileId())) continue;
                ChatbotServiceImpl.ChatRefItem item = new ChatbotServiceImpl.ChatRefItem();
                item.docFileId = doc.getDocFileId();
                item.mainPageNo = "1";
                item.relatedPageNos = new ArrayList<>(Collections.singletonList(1));
                chatRefItems.add(item);
            }

            String savedLogId = chatbotService.doInsertAiLog(
                    threadId, agentId, query, summaryText,
                    0, 0, svcTy, modelId, refId, userId,
                    null, null, null, null,
                    null, null, chatRefItems,
                    null, null,
                    reportHtml);

            chatbotService.updateChatRoomLastChatDt(threadId);

            if (CommonUtil.isNotEmpty(savedLogId) && hasNonNullAttachmentId(attachmentFileIds)) {
                try {
                    List<Long> linkFileIds = new ArrayList<>();
                    for (Long id : attachmentFileIds) {
                        if (id != null) linkFileIds.add(id);
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

    // ── 공유 헬퍼 (RISK/PROPOSAL 에이전트 공용) ────────────────────────────────

    /** PDFBox로 PDF byte[]에서 텍스트를 추출한다. */
    public String extractPdfText(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) return "";
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                     org.apache.pdfbox.pdmodel.PDDocument.load(bytes)) {
            org.apache.pdfbox.text.PDFTextStripper stripper =
                    new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            return text != null ? text.trim() : "";
        } catch (Exception e) {
            logger.warn("PDF 텍스트 추출 실패 (file={}): {}", fileName, e.getMessage());
            return "";
        }
    }

    /**
     * RFP 추출 텍스트가 캡 이하면 그대로 반환(빠른 경로),
     * 초과(대용량 RFP)면 청크로 나눠 9000으로 병렬 요약 후 압축본을 반환한다.
     */
    public String condenseRfpTextIfLarge(String rfpText, String modelId,
            ChatbotWebSocketHandler.ChatbotStreamingCallback callback, String agentId) {
        if (CommonUtil.isEmpty(rfpText)) return "";
        if (rfpText.length() <= RISK_RFP_TEXT_MAX_CHARS) return rfpText;
        callback.onStatus("condensing_rfp", "대용량 RFP 요약 중");

        int total = rfpText.length();
        int chunkSize = Math.max(RISK_SUMMARY_MIN_CHUNK_CHARS,
                (int) Math.ceil((double) total / RISK_SUMMARY_MAX_CHUNKS));
        int overlap = Math.min(RISK_SUMMARY_CHUNK_OVERLAP, chunkSize / 4);
        List<String> chunks = new ArrayList<>();
        int pos = 0;
        while (pos < total) {
            int end = Math.min(total, pos + chunkSize);
            chunks.add(rfpText.substring(pos, end));
            if (end >= total) break;
            pos = end - overlap;
        }
        logger.info("RFP 요약 시작 - 원본:{}자, 청크:{}개(청크당 ~{}자, overlap:{}자)",
                total, chunks.size(), chunkSize, overlap);

        final int chunkCount = chunks.size();
        List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
        for (int ci = 0; ci < chunkCount; ci++) {
            final int chunkNo = ci + 1;
            final String fChunk = chunks.get(ci);
            futures.add(riskSummarizeExecutor.submit(() -> {
                long chunkStart = System.currentTimeMillis();
                logger.info("RFP 청크 요약 시작 - {}/{} (입력:{}자)", chunkNo, chunkCount, fChunk.length());
                String out = callLlmQuerySync(buildRfpSummaryPrompt(fChunk), modelId, "", agentId);
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
            logger.warn("RFP 요약 결과 없음 — 원문 앞부분으로 폴백");
            result = rfpText.substring(0, RISK_RFP_TEXT_MAX_CHARS) + "\n...(이하 생략)";
        } else if (result.length() > RISK_CONDENSED_MAX_CHARS) {
            logger.warn("RFP 압축본이 상한({}자) 초과 — 안전 절단", RISK_CONDENSED_MAX_CHARS);
            result = result.substring(0, RISK_CONDENSED_MAX_CHARS) + "\n...(요약 일부 생략)";
        }
        logger.info("RFP 요약 완료 - 원본:{}자 → 압축:{}자, 청크:{}개", total, result.length(), chunks.size());
        return result;
    }

    /**
     * 자사 역량 RAG 검색 — 항목별 좁은 쿼리를 병렬로 던져 각 표/섹션 청크를 정확히 끌어와 합친다.
     */
    public String retrieveCompanyContext(List<String> datasetIds, String modelId,
            String threadId, String agentId) {
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
                String ans = futures.get(i).get(RISK_QUERY_READ_TIMEOUT_SEC + 20,
                        java.util.concurrent.TimeUnit.SECONDS);
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

    /** 9111/query(RAG)를 동기 호출하고 done 이벤트의 답변 문자열을 반환한다. */
    public String callRagQuerySync(String prompt, List<String> datasetIds, String modelId,
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
        RequestBody ragBody = RequestBody.create(ragJsonBody,
                okhttp3.MediaType.get("application/json; charset=utf-8"));
        Request ragRequest = new Request.Builder()
                .url(ragApiUrl).post(ragBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .build();
        logger.info("리스크 RAG 단일 호출 시작 - url:{}, readTimeout:{}s, 요청바디길이:{}",
                ragApiUrl, RISK_QUERY_READ_TIMEOUT_SEC, ragJsonBody.length());
        logger.info("리스크 RAG 요청 본문 전체:\n{}", ragJsonBody);
        long startMs = System.currentTimeMillis();
        try (okhttp3.Response response = client.newCall(ragRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String errBody = "";
                try { if (response.body() != null) errBody = response.body().string(); } catch (Exception ignore) {}
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
                    if (line.startsWith("event: ")) continue;
                    String jsonStr;
                    if (line.startsWith("data: ")) jsonStr = line.substring(6).trim();
                    else if (line.trim().startsWith("{")) jsonStr = line.trim();
                    else continue;
                    if (jsonStr.isEmpty()) continue;
                    try {
                        JSONObject data = (JSONObject) jsonParser.parse(jsonStr);
                        Object textObj = data.get("text");
                        if (textObj != null) answerBuilder.append(String.valueOf(textObj));
                        Object answerObj = data.get("answer");
                        if (answerObj == null) answerObj = data.get("답변");
                        if (answerObj != null) doneAnswer = String.valueOf(answerObj);
                    } catch (Exception ignore) {}
                }
                String result = CommonUtil.isNotEmpty(doneAnswer) ? doneAnswer : answerBuilder.toString();
                int ragRespMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
                logger.info("리스크 RAG 응답 수신 완료 - 길이:{}, 소요:{}ms",
                        result != null ? result.length() : 0, ragRespMs);
                apiCallLogService.insertSilently(agentId, null, ragApiUrl, modelId, "ragQuery",
                        ragJsonBody, 0, 0, ragRespMs, "Y", null, null);
                return result;
            }
        } catch (java.net.SocketTimeoutException te) {
            int ragRespMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
            logger.warn("리스크 RAG 호출 타임아웃 - {}s 초과({}ms 경과): {}",
                    RISK_QUERY_READ_TIMEOUT_SEC, ragRespMs, te.getMessage());
            apiCallLogService.insertSilently(agentId, null, ragApiUrl, modelId, "ragQuery",
                    ragJsonBody, 0, 0, ragRespMs, "N", "타임아웃: " + te.getMessage(), null);
            return "";
        } catch (Exception e) {
            int ragRespMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
            logger.warn("리스크 RAG 호출 실패 - {}({}ms 경과): {}",
                    e.getClass().getSimpleName(), ragRespMs, e.getMessage());
            apiCallLogService.insertSilently(agentId, null, ragApiUrl, modelId, "ragQuery",
                    ragJsonBody, 0, 0, ragRespMs, "N", e.getMessage(), null);
            return "";
        }
    }

    /**
     * 일반 LLM 엔드포인트(9000/query)를 동기 호출하고 답변 문자열을 반환한다.
     */
    public String callLlmQuerySync(String prompt, String modelId, String threadId, String agentId) {
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
        RequestBody body = RequestBody.create(jsonBody,
                okhttp3.MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(apiUrl).post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .build();
        logger.info("리스크 LLM 단일 호출 시작 - url:{}, readTimeout:{}s, 요청바디길이:{}",
                apiUrl, RISK_QUERY_READ_TIMEOUT_SEC, jsonBody.length());
        long startMs = System.currentTimeMillis();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                int llmRespMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
                logger.warn("리스크 LLM 질의 응답 오류: {}", response.code());
                apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "llmQuery",
                        jsonBody, 0, 0, llmRespMs, "N", "HTTP " + response.code(), null);
                return "";
            }
            try (okhttp3.ResponseBody responseBody = response.body()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(responseBody.byteStream(), "UTF-8"));
                StringBuilder answerBuilder = new StringBuilder();
                String currentEvent = null;
                int inputTokens = 0, outputTokens = 0;
                String doneAnswer = "";
                String line;
                JSONParser jsonParser = new JSONParser();
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        currentEvent = line.substring(7).trim();
                        continue;
                    }
                    if (!line.startsWith("data: ")) continue;
                    try {
                        String jsonStr = line.substring(6).trim();
                        JSONObject data = (JSONObject) jsonParser.parse(jsonStr);
                        if ("answer_delta".equals(currentEvent)) {
                            String text = getString(data.get("text"));
                            if (text != null && !text.isEmpty()) answerBuilder.append(text);
                        } else if ("done".equals(currentEvent)) {
                            String answerObj = getString(data.get("answer"));
                            doneAnswer = answerObj;
                            inputTokens = parseTokenCount(data.get("input_token"));
                            outputTokens = parseTokenCount(data.get("output_token"));
                        }
                    } catch (Exception ignore) {}
                }
                String result = CommonUtil.isNotEmpty(doneAnswer) ? doneAnswer : answerBuilder.toString();
                int llmRespMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
                logger.info("리스크 LLM 응답 수신 완료 - 길이:{}, 소요:{}ms",
                        result != null ? result.length() : 0, llmRespMs);
                apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "llmQuery",
                        jsonBody, inputTokens, outputTokens, llmRespMs, "Y", null, null);
                return result;
            }
        } catch (java.net.SocketTimeoutException te) {
            int llmRespMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
            logger.warn("리스크 LLM 호출 타임아웃 - {}s 초과({}ms 경과): {}",
                    RISK_QUERY_READ_TIMEOUT_SEC, llmRespMs, te.getMessage());
            apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "llmQuery",
                    jsonBody, 0, 0, llmRespMs, "N", "타임아웃: " + te.getMessage(), null);
            return "";
        } catch (Exception e) {
            int llmRespMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
            logger.warn("리스크 LLM 호출 실패 - {}({}ms 경과): {}",
                    e.getClass().getSimpleName(), llmRespMs, e.getMessage());
            apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "llmQuery",
                    jsonBody, 0, 0, llmRespMs, "N", e.getMessage(), null);
            return "";
        }
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

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

    private String buildRiskReportPrompt(String basePrompt, List<LibraryVO.TmplFieldItem> fields,
            String rfpText, String companyContext, String userQuery) {
        StringBuilder secSpec = new StringBuilder();
        for (LibraryVO.TmplFieldItem field : fields) {
            if (field == null || CommonUtil.isEmpty(field.getJsonKey())) continue;
            String key = field.getJsonKey();
            if ("sources".equalsIgnoreCase(key)) continue;
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

    private Map<String, String> parseRiskSections(String response, List<LibraryVO.TmplFieldItem> fields) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        String resp = response != null ? response : "";
        for (LibraryVO.TmplFieldItem field : fields) {
            if (field == null || CommonUtil.isEmpty(field.getJsonKey())) continue;
            String key = field.getJsonKey();
            if ("sources".equalsIgnoreCase(key)) continue;
            String html = "";
            try {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                        "\\[\\[\\s*SEC\\s*:\\s*" + java.util.regex.Pattern.quote(key)
                                + "\\s*\\]\\](.*?)\\[\\[\\s*/\\s*SEC\\s*\\]\\]",
                        java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
                java.util.regex.Matcher m = p.matcher(resp);
                if (m.find()) html = cleanSectionHtml(m.group(1));
            } catch (Exception e) {
                logger.warn("리스크진단 섹션 '{}' 파싱 실패: {}", key, e.getMessage());
            }
            map.put(key, html);
        }
        return map;
    }

    private String cleanSectionHtml(String answer) {
        if (CommonUtil.isEmpty(answer)) return "";
        String s = answer.trim();
        if (s.startsWith("```html")) s = s.substring(7);
        else if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        s = s.trim();
        s = s.replaceAll("(?is)<\\s*script[^>]*>.*?<\\s*/\\s*script\\s*>", "");
        s = s.replaceAll("(?is)<\\s*style[^>]*>.*?<\\s*/\\s*style\\s*>", "");
        s = s.replaceAll("(?is)<\\s*iframe[^>]*>.*?<\\s*/\\s*iframe\\s*>", "");
        s = s.replaceAll("(?is)\\son\\w+\\s*=\\s*\"[^\"]*\"", "");
        s = s.replaceAll("(?is)\\son\\w+\\s*=\\s*'[^']*'", "");
        s = s.replaceAll("(?is)javascript:", "");
        return s.trim();
    }

    private String buildRiskSourcesHtml(List<String> uploadedFileNames, List<ChatbotVO> ragDocs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<ul>");
        if (uploadedFileNames != null) {
            for (String name : uploadedFileNames) {
                if (CommonUtil.isEmpty(name)) continue;
                sb.append("<li>업로드 RFP: ").append(htmlEscape(name)).append("</li>");
            }
        }
        if (ragDocs != null) {
            for (ChatbotVO doc : ragDocs) {
                if (doc == null || CommonUtil.isEmpty(doc.getFileName())) continue;
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

    private String stripHtmlTags(String s) {
        if (CommonUtil.isEmpty(s)) return "";
        String text = s.replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String getString(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    private int parseTokenCount(Object tokenObj) {
        if (tokenObj == null) return 0;
        if (tokenObj instanceof Number) return ((Number) tokenObj).intValue();
        try {
            String tokenText = String.valueOf(tokenObj);
            return CommonUtil.isNotEmpty(tokenText) ? Integer.parseInt(tokenText) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean hasNonNullAttachmentId(List<Long> attachmentFileIds) {
        if (attachmentFileIds == null || attachmentFileIds.isEmpty()) return false;
        for (Long id : attachmentFileIds) {
            if (id != null) return true;
        }
        return false;
    }
}
