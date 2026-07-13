package kr.teamagent.chat.service.impl.agent;

import java.util.ArrayList;
import java.util.Collections;
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
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.prompt.service.impl.PromptServiceImpl;

/**
 * PROPOSAL 에이전트(제안서 초안 생성) 전용 서비스.
 * 요구사항(RFP) 업로드 → 자사RAG + 경쟁사RAG/웹서치 → 제안 슬라이드 JSON 생성.
 */
@Service
public class ProposalAgentService {

    private static final Logger logger = LoggerFactory.getLogger(ProposalAgentService.class);

    // ── 상수 ──────────────────────────────────────────────────────────────────
    /** 제안서 초안 기본 리포트 템플릿 ID (subCfg.features.tmplId 미설정 시 폴백) */
    public static final String PROPOSAL_DEFAULT_TMPL_ID = "TM000009";
    /** 제안서 생성(9000) 프롬프트에 주입하는 요구사항 텍스트 최대 길이(문자). */
    public static final int PROPOSAL_RFP_TEXT_MAX_CHARS = 24000;

    // ── 의존성 ────────────────────────────────────────────────────────────────
    @Autowired
    private ChatbotAgentSupport agentSupport;

    @Autowired
    private ChatbotDAO chatbotDAO;

    @Autowired
    private FileServiceImpl fileService;

    @Autowired
    private PromptServiceImpl promptService;

    @Autowired
    private RiskDiagnosisAgentService riskService;

    /** doInsertAiLog / updateChatRoomLastChatDt / callWebSearchSync / callAiImageApi 호출용 (@Lazy) */
    @Autowired
    @Lazy
    private ChatbotServiceImpl chatbotService;

    // ── 제안서 초안 WebSocket 전달 ────────────────────────────────────────────

    /**
     * PROPOSAL 에이전트: 요구사항(RFP) 업로드 → 자사 RAG + 경쟁사 RAG/웹서치 → 제안 슬라이드 JSON 생성.
     */
    public void deliverProposalDraftViaWebSocket(
            String query, String threadId, String userId, String svcTy, String modelId,
            String refId, String agentId, List<Long> attachmentFileIds,
            ChatbotWebSocketHandler.ChatbotStreamingCallback callback) {
        try {
            // ① ADDITIONAL_CONFIG 읽기
            ChatbotVO.AgtSubCfgVO subCfg = agentSupport.getAgentSubCfg(agentId);
            String persona        = "제안서 전문 작성가";
            String audience       = "발주기관 평가위원";
            String lang           = "ko";
            String tmplId         = PROPOSAL_DEFAULT_TMPL_ID;
            String competitorDsId = "";
            boolean webSearch     = true;

            if (subCfg != null && subCfg.getAdditionalConfigMap() != null) {
                Object featuresObj = subCfg.getAdditionalConfigMap().get("features");
                if (featuresObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> features = (Map<String, Object>) featuresObj;
                    if (features.get("persona")   != null) persona   = String.valueOf(features.get("persona"));
                    if (features.get("audience")  != null) audience  = String.valueOf(features.get("audience"));
                    if (features.get("lang")      != null) lang      = String.valueOf(features.get("lang"));
                    if (features.get("tmplId")    != null
                            && CommonUtil.isNotEmpty(String.valueOf(features.get("tmplId"))))
                        tmplId = String.valueOf(features.get("tmplId"));
                    if (features.get("competitorDatasetId") != null)
                        competitorDsId = String.valueOf(features.get("competitorDatasetId"));
                    if (features.get("webSearch") != null)
                        webSearch = Boolean.TRUE.equals(features.get("webSearch"));
                }
            }

            // ② 요구사항 텍스트 추출 (첨부 파일 우선, 없으면 query 그대로 사용)
            String requirementsText = "";
            List<String> uploadedFileNames = new ArrayList<>();
            if (hasNonNullAttachmentId(attachmentFileIds)) {
                callback.onStatus("extracting_file", "요구사항 문서 분석 중");
                StringBuilder rfpBuilder = new StringBuilder();
                for (Long fileId : attachmentFileIds) {
                    if (fileId == null) continue;
                    try {
                        ChatbotVO fileSearchVO = new ChatbotVO();
                        fileSearchVO.setChatFileId(fileId);
                        ChatbotVO fileVO = chatbotDAO.selectChatFileById(fileSearchVO);
                        if (fileVO == null || CommonUtil.isEmpty(fileVO.getFilePath())) continue;
                        String fileName = CommonUtil.nullToBlank(fileVO.getFileName());
                        byte[] bytes = fileService.downloadStorageObjectBytes(fileVO.getFilePath());
                        String text  = riskService.extractPdfText(bytes, fileName);
                        if (CommonUtil.isNotEmpty(text)) {
                            if (CommonUtil.isNotEmpty(fileName)) {
                                uploadedFileNames.add(fileName);
                                rfpBuilder.append("\n\n===== 파일: ").append(fileName).append(" =====\n");
                            }
                            rfpBuilder.append(text);
                        }
                    } catch (Exception e) {
                        logger.warn("PROPOSAL 요구사항 파일 추출 실패 (chatFileId={}): {}", fileId, e.getMessage());
                    }
                }
                requirementsText = rfpBuilder.toString().trim();
            }
            if (CommonUtil.isEmpty(requirementsText)) {
                requirementsText = CommonUtil.isNotEmpty(query) ? query : "";
            }
            if (requirementsText.length() > PROPOSAL_RFP_TEXT_MAX_CHARS) {
                requirementsText = riskService.condenseRfpTextIfLarge(
                        requirementsText, modelId, callback, agentId);
            }

            // ③ 자사 역량 RAG 검색
            List<String> companyDatasetIds = new ArrayList<>();
            if (refId != null && !refId.trim().isEmpty()) {
                for (String part : refId.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty() && !"all".equalsIgnoreCase(trimmed))
                        companyDatasetIds.add(trimmed);
                }
            }
            String companyContext = "";
            List<ChatbotVO> ragDocs = new ArrayList<>();
            if (!companyDatasetIds.isEmpty()) {
                callback.onStatus("searching_rag", "자사 역량 자료 검색 중");
                try {
                    for (String dsId : companyDatasetIds) {
                        ChatbotVO docSearchVO = new ChatbotVO();
                        docSearchVO.setRefId(dsId);
                        List<ChatbotVO> docFiles = chatbotDAO.selectDatasetDocFileNames(docSearchVO);
                        if (docFiles != null) ragDocs.addAll(docFiles);
                    }
                } catch (Exception e) {
                    logger.warn("PROPOSAL 자사RAG 문서 조회 실패: {}", e.getMessage());
                }
                companyContext = riskService.retrieveCompanyContext(
                        companyDatasetIds, modelId, threadId, agentId);
                logger.info("PROPOSAL 자사역량 RAG 컨텍스트 수신 - 길이:{}",
                        companyContext != null ? companyContext.length() : 0);
            }
            if (CommonUtil.isEmpty(companyContext)) companyContext = "(자사 역량 자료 없음)";

            // ④ 경쟁사 정보 수집
            String competitorContext = "";
            String webSearchSource   = "";
            if (CommonUtil.isNotEmpty(competitorDsId)) {
                callback.onStatus("searching_competitor_rag", "경쟁사 정보 검색 중");
                List<String> competitorDsIds = new ArrayList<>();
                competitorDsIds.add(competitorDsId);
                String competitorQuery = "경쟁사 기술 역량, 주요 솔루션, 수주 실적, 제안 전략, 강점과 약점을 알려줘.";
                try {
                    competitorContext = riskService.callRagQuerySync(
                            competitorQuery, competitorDsIds, modelId, threadId, agentId);
                } catch (Exception e) {
                    logger.warn("PROPOSAL 경쟁사 RAG 검색 실패: {}", e.getMessage());
                }
                if (CommonUtil.isEmpty(competitorContext) && webSearch) {
                    String[] webResult = chatbotService.callWebSearchSync(query, modelId);
                    if (webResult != null) {
                        competitorContext = webResult[0];
                        webSearchSource   = webResult[1];
                    }
                }
            } else if (webSearch) {
                callback.onStatus("searching_web", "경쟁사 정보 웹 검색 중");
                String[] webResult = chatbotService.callWebSearchSync(query, modelId);
                if (webResult != null) {
                    competitorContext = webResult[0];
                    webSearchSource   = webResult[1];
                }
            }
            if (CommonUtil.isEmpty(competitorContext)) competitorContext = "(경쟁사 정보 없음)";

            // ⑤ 슬라이드 프롬프트 구성
            callback.onStatus("generating_slides", "제안 슬라이드 초안 생성 중");
            String langLabel = "ko".equals(lang) ? "한국어" : "English";

            // 에이전트 시스템 프롬프트 — DB(tb_prompt)에서 조회 (관리 화면에서 수정 가능)
            String basePrompt = "";
            try {
                basePrompt = promptService.getPromptByAgentId(agentId);
            } catch (Exception e) {
                logger.warn("PROPOSAL 에이전트 프롬프트 조회 실패 (agentId={}): {}", agentId, e.getMessage());
            }
            if (CommonUtil.isEmpty(basePrompt)) {
                logger.warn("PROPOSAL 에이전트 프롬프트가 비어 있습니다. DB에 등록 여부를 확인하세요. (agentId={})", agentId);
            }

            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append(basePrompt)
                    .append("\n\n## 슬라이드 구성 및 레이아웃 지침")
                    .append("\n슬라이드는 아래 구조를 따르되, 각 슬라이드마다 제공된 컨텍스트 내용을 최대한 상세하고 풍부하게 채워 넣으세요.")
                    .append("\n\n### 필수 슬라이드 구조")
                    .append("\n- 1장(cover): 사업(과제)명 · 핵심 슬로건 · 컨소시엄 및 추진 단계 키워드 요약 → layoutType: cover")
                    .append("\n- 2장(toc): 전체 제안서 목차 — keywords[]에 섹션명(예: '사업의 이해와 필요성'), content[]에 각 섹션 한 줄 설명. 섹션 번호는 자동 부여됨 → layoutType: toc")
                    .append("\n\n### 섹션별 구조 (간지 + 세부 장표)")
                    .append("\n각 주제마다 반드시 ① 섹션 간지(section_divider) → ② 세부 내용 슬라이드 1~2장 순서로 구성하세요.")
                    .append("\n\n[섹션 간지 작성 규칙]")
                    .append("\n- title: 섹션 번호 (예: '01', '02', '03' ...)")
                    .append("\n- subtitle: 섹션 제목 (예: '사업의 이해와 필요성')")
                    .append("\n- headline: 섹션 핵심 메시지 한 문장")
                    .append("\n- layoutType: section_divider")
                    .append("\n\n[세부 내용 슬라이드 — 간지 직후 1~2장]")
                    .append("\n- 모든 세부 내용 슬라이드는 예외 없이 layoutType: infographic 으로 지정하세요.")
                    .append("\n\n[권장 섹션 구성 예시]")
                    .append("\n- 01 사업의 이해와 필요성: section_divider → infographic(문제 구조도) → infographic(핵심 요구사항)")
                    .append("\n- 02 추진 전략: section_divider → infographic(전략 개념도) → infographic(단계별 방법론)")
                    .append("\n- 03 핵심 기술 구현 방안: section_divider → infographic(기술 아키텍처) → infographic(핵심 기술 역량)")
                    .append("\n- 04 응용 솔루션 및 기능: section_divider → infographic(기능 시나리오) → infographic(주요 기능 정의)")
                    .append("\n- 05 수행 조직 및 인력: section_divider → infographic(조직도) → infographic(인력 역량)")
                    .append("\n- 06 사업화 및 확산 전략: section_divider → infographic(확산 단계) → infographic(수익화 방안)")
                    .append("\n- 07 차별화 포인트 및 파급효과: section_divider → infographic(Win Theme) → infographic(정량적 기대효과)")
                    .append("\n- (선택): RFP상 특수 요구사항(보안·인프라·연구윤리 등)이 강조되면 섹션 추가")
                    .append("\n\n## 입력 데이터 (참조 컨텍스트)")
                    .append("\n아래 데이터를 기반으로 내용을 생성하되, 가상의 내용을 지어내지 말고 주어진 텍스트 내의 전문 용어와 수치를 적극 활용하세요.")
                    .append("\n\n[1] RFP (요구사항 명세서):\n*(여기에 사용자가 입력하거나 파일에서 추출한 RFP 텍스트가 바인딩됩니다)*")
                    .append("\n\n[2] 자사 역량 자료 (RAG 검색 결과):\n").append(companyContext)
                    .append("\n\n[3] 경쟁사 정보:\n").append(competitorContext);

            if (CommonUtil.isNotEmpty(requirementsText)) {
                promptBuilder.append("\n\n## 요구사항 / RFP 원문\n").append(requirementsText);
            }
            if (!hasNonNullAttachmentId(attachmentFileIds)
                    && CommonUtil.isNotEmpty(query)
                    && !query.equals(requirementsText)) {
                promptBuilder.append("\n\n## 사용자 추가 요청\n").append(query);
            }

            promptBuilder.append("\n\n## 응답 형식 (중요)")
                    .append("\n반드시 아래 JSON 구조로만 응답하세요. 마크다운 코드블록, 설명 텍스트 없이 순수 JSON만 출력하세요.")
                    .append("\n{")
                    .append("\n  \"title\": \"제안서 전체 제목\",")
                    .append("\n  \"slides\": [")
                    .append("\n    {")
                    .append("\n      \"title\": \"슬라이드 제목\",")
                    .append("\n      \"subtitle\": \"서브제목 (선택)\",")
                    .append("\n      \"headline\": \"핵심 메시지 한 문장\",")
                    .append("\n      \"keywords\": [\"키워드1\", \"키워드2\", \"키워드3\"],")
                    .append("\n      \"content\": [\"본문 항목1\", \"본문 항목2\", \"본문 항목3\"],")
                    .append("\n      \"notes\": \"발표자 노트\",")
                    .append("\n      \"layoutType\": \"cover | toc | section_divider | keyword_list | process_cards | grid_cards | infographic\"")
                    .append("\n    },")
                    .append("\n    ...")
                    .append("\n  ]")
                    .append("\n}")
                    .append("\n\n## layoutType 선택 기준")
                    .append("\n- cover: 첫 슬라이드(표지). 반드시 1개, 첫 번째 슬라이드에만 사용.")
                    .append("\n- toc: 목차 슬라이드. 반드시 1개, cover 바로 다음에만 사용. keywords[]에 섹션명, content[]에 섹션 설명 (1:1 대응).")
                    .append("\n- section_divider: 섹션 간지. 각 섹션 시작 전 1장. title=섹션번호('01'~), subtitle=섹션제목, headline=핵심메시지.")
                    .append("\n- keyword_list: 개요·요약 슬라이드. keywords에 태그(2~5개), content에 항목 설명.")
                    .append("\n- process_cards: 2~4개 요소가 단계/전환/비교 관계일 때. keywords에 카드 제목(2~4개), content에 각 카드 설명 순서대로.")
                    .append("\n- grid_cards: 전략·역량·체크리스트 등 병렬 나열 항목(4~6개). keywords에 카드 소제목, content에 카드별 설명 한 줄씩(1:1 대응).")
                    .append("\n- infographic: 조직도·시스템 아키텍처·순환 프로세스·다층 계층 구조. AI 이미지로 생성됨. keywords[]는 다이어그램 주요 구성요소, content[]는 각 요소 설명. notes[]에 이미지 생성 힌트(어떤 다이어그램인지 영문으로) 작성.");

            // ⑥ LLM 호출
            String aiResponse = riskService.callLlmQuerySync(
                    promptBuilder.toString(), modelId, threadId, agentId);
            if (CommonUtil.isEmpty(aiResponse)) {
                logger.warn("PROPOSAL 슬라이드 1차 응답 없음 — 재시도");
                aiResponse = riskService.callLlmQuerySync(
                        promptBuilder.toString(), modelId, threadId, agentId);
            }
            if (CommonUtil.isEmpty(aiResponse)) {
                callback.onError("제안서 슬라이드 초안 생성에 실패했습니다. AI 응답이 비어 있습니다.");
                return;
            }

            // ⑦ 슬라이드 JSON 정리
            String jsonContent = aiResponse.trim();
            if (jsonContent.startsWith("```json")) jsonContent = jsonContent.substring(7);
            if (jsonContent.startsWith("```"))     jsonContent = jsonContent.substring(3);
            if (jsonContent.endsWith("```"))       jsonContent = jsonContent.substring(0, jsonContent.length() - 3);
            jsonContent = jsonContent.trim();

            String presentationTitle;
            if (!uploadedFileNames.isEmpty()) {
                String fn = uploadedFileNames.get(0);
                fn = fn.contains(".") ? fn.substring(0, fn.lastIndexOf('.')) : fn;
                presentationTitle = fn + " 제안서";
            } else if (CommonUtil.isNotEmpty(query)) {
                presentationTitle = query;
            } else {
                presentationTitle = "제안서";
            }
            try {
                JSONParser parser = new JSONParser();
                JSONObject aiJson = (JSONObject) parser.parse(jsonContent);
                Object t = aiJson.get("title");
                if (t != null && CommonUtil.isNotEmpty(String.valueOf(t))) {
                    presentationTitle = String.valueOf(t);
                }
                if (subCfg != null && subCfg.getAdditionalConfigMap() != null) {
                    Object featuresObj2 = subCfg.getAdditionalConfigMap().get("features");
                    if (featuresObj2 instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> features2 = (Map<String, Object>) featuresObj2;
                        Object sdObj = features2.get("slideDesign");
                        if (sdObj instanceof Map) {
                            aiJson.put("slideDesign", sdObj);
                            jsonContent = aiJson.toJSONString();
                        }
                    }
                }
            } catch (Exception ignore) {
                logger.warn("PROPOSAL JSON 파싱 오류 (raw 전송): {}", ignore.getMessage());
            }

            // ⑧ WebSocket 전송
            String summaryText = "제안서 슬라이드 초안이 생성되었습니다: " + presentationTitle;
            callback.onChunk(summaryText, summaryText, null);
            callback.onChunk(jsonContent, jsonContent, "pptx_data");

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

            // ⑨ RAG 참조 문서 구성
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
                    jsonContent);

            chatbotService.updateChatRoomLastChatDt(threadId);

            if (CommonUtil.isNotEmpty(savedLogId) && hasNonNullAttachmentId(attachmentFileIds)) {
                try {
                    List<Long> linkFileIds = new ArrayList<>();
                    for (Long id : attachmentFileIds) { if (id != null) linkFileIds.add(id); }
                    ChatbotVO linkVO = new ChatbotVO();
                    linkVO.setChatFileIdList(linkFileIds);
                    linkVO.setLogId(Long.parseLong(savedLogId));
                    chatbotDAO.linkChatFilesToLog(linkVO);
                } catch (Exception e) {
                    logger.warn("PROPOSAL 첨부파일 LOG_ID 연결 실패: {}", e.getMessage());
                }
            }

            callback.onComplete(summaryText, "", "", new ArrayList<>(), threadId,
                    CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null,
                    "", "", "");

        } catch (Exception e) {
            logger.error("PROPOSAL 제안서 초안 생성 중 오류: {}", e.getMessage(), e);
            callback.onError("제안서 초안 생성 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    // ── 제안서 PPTX 내보내기 ─────────────────────────────────────────────────

    /**
     * PROPOSAL 에이전트 — 슬라이드 JSON을 PPTX로 변환.
     */
    @SuppressWarnings("unchecked")
    public byte[] exportProposalPptx(String slidesJson, String agentId) throws Exception {
        JSONParser parser = new JSONParser();
        JSONObject root = (JSONObject) parser.parse(slidesJson.trim());
        String title = root.get("title") != null ? String.valueOf(root.get("title")) : "제안서";

        java.util.List<java.util.Map<String, Object>> slides =
                root.get("slides") instanceof org.json.simple.JSONArray
                        ? (java.util.List<java.util.Map<String, Object>>) root.get("slides")
                        : new java.util.ArrayList<>();

        String bgColor     = "#FFFFFF";
        String baseColor   = "#1B2559";
        String accentColor = "#6C63F6";
        Object sdObj = root.get("slideDesign");
        if (sdObj instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> sd = (java.util.Map<String, Object>) sdObj;
            if (sd.get("bgColor")     != null) bgColor     = String.valueOf(sd.get("bgColor"));
            if (sd.get("baseColor")   != null) baseColor   = String.valueOf(sd.get("baseColor"));
            if (sd.get("accentColor") != null) accentColor = String.valueOf(sd.get("accentColor"));
        }

        final String finalBaseColor   = baseColor;
        final String finalAccentColor = accentColor;
        java.util.concurrent.ConcurrentHashMap<Integer, String> infographicImageMap =
                new java.util.concurrent.ConcurrentHashMap<>();
        List<Integer> infographicIdxList = new java.util.ArrayList<>();
        for (int i = 0; i < slides.size(); i++) {
            Object lt = slides.get(i).get("layoutType");
            if ("infographic".equals(lt != null ? String.valueOf(lt).trim() : "")) {
                infographicIdxList.add(i);
            }
        }
        if (!infographicIdxList.isEmpty()) {
            java.util.concurrent.ExecutorService exec =
                    java.util.concurrent.Executors.newFixedThreadPool(
                            Math.min(infographicIdxList.size(), 2));
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int idx : infographicIdxList) {
                final int i = idx;
                final java.util.Map<String, Object> sl = slides.get(i);
                futures.add(exec.submit(() -> {
                    try {
                        String q = buildProposalInfographicQuery(sl, finalBaseColor, finalAccentColor);
                        String b64 = chatbotService.callAiImageApi(q, agentId);
                        if (b64 != null && !b64.isEmpty()) {
                            infographicImageMap.put(i, b64);
                        } else {
                            logger.warn("PROPOSAL 인포그래픽 이미지 생성 실패 (idx={}) — keyword_list 폴백", i);
                        }
                    } catch (Exception e) {
                        logger.warn("PROPOSAL 인포그래픽 이미지 생성 오류 (idx={}): {}", i, e.getMessage());
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(130, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
            exec.shutdown();
            logger.info("PROPOSAL 인포그래픽 이미지 생성 - 요청:{}, 성공:{}",
                    infographicIdxList.size(), infographicImageMap.size());
        }

        logger.info("PROPOSAL PPTX 생성 - 슬라이드:{}, 인포그래픽:{}",
                slides.size(), infographicImageMap.size());
        return kr.teamagent.common.util.ProposalPptxUtil.buildPptx(
                title, slides, bgColor, baseColor, accentColor, infographicImageMap);
    }

    // ── 질의 진단 ─────────────────────────────────────────────────────────────

    /**
     * 데이터분석(SVC_TY='S') 질의 품질 진단 — 위임 메서드.
     * 실제 로직은 ChatbotServiceImpl.diagnoseQuestion에 있다.
     */
    public java.util.HashMap<String, Object> diagnoseQuestion(ChatbotVO searchVO) throws Exception {
        return chatbotService.diagnoseQuestion(searchVO);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String buildProposalInfographicQuery(
            java.util.Map<String, Object> slide, String baseColor, String accentColor) {

        String sTitle   = slide.get("title")    != null ? String.valueOf(slide.get("title")).trim()    : "";
        String subtitle = slide.get("subtitle") != null ? String.valueOf(slide.get("subtitle")).trim()  : "";
        String headline = slide.get("headline") != null ? String.valueOf(slide.get("headline")).trim()  : "";
        String layoutType = slide.get("layoutType") != null ? String.valueOf(slide.get("layoutType")).trim() : "";
        String notes    = slide.get("notes")    != null ? String.valueOf(slide.get("notes")).trim()    : "";

        List<String> keywords = slide.get("keywords") instanceof List
                ? (List<String>) slide.get("keywords") : java.util.Collections.emptyList();
        List<String> content  = slide.get("content")  instanceof List
                ? (List<String>) slide.get("content")  : java.util.Collections.emptyList();

        String bc = (baseColor   != null && !baseColor.startsWith("#"))   ? "#" + baseColor   : baseColor;
        String ac = (accentColor != null && !accentColor.startsWith("#")) ? "#" + accentColor : accentColor;

        StringBuilder q = new StringBuilder();
        q.append("역할: 당신은 대기업 및 공공기관 전문 UI/UX 디자이너이자 제안서 인포그래픽 전문가입니다.\n");
        q.append("목적: 다음 제공된 비즈니스 데이터를 바탕으로, 제안서 발표 슬라이드 본문에 바로 삽입할 수 있는 최상위 퀄리티의 한국어 인포그래픽 이미지를 생성하세요.\n\n");
        q.append("[시각 디자인 가이드라인]\n");
        q.append("- 규격 및 비율: 슬라이드 표준 규격인 **16:9 와이드스크린 비율**에 맞추어 안정적인 구도를 잡으세요.\n");
        q.append("- 스타일: 현대적인 테크 비즈니스 감성의 2D 및 3D 하이브리드 스타일. 정보를 구조화하는 세련된 사각형 카드(Card) 및 대시보드 UI 요소를 활용하세요.\n");
        q.append("- 배경: 슬라이드 본문용에 맞게 **흰색(White) 또는 아주 밝은 연한 회색/연한 파란색 계열**의 깔끔한 배경으로 설정하세요.\n");
        if (bc != null && !bc.isEmpty()) {
            q.append("- 컬러 스키마: 전달된 주요 색상 브랜드 테마를 철저히 따르세요.\n");
            q.append("  * 테마 기본 색상(Base Color): ").append(bc).append("\n");
            q.append("  * 포인트 강조 색상(Accent Color): ").append(ac).append("\n");
            q.append("  * 디자인 내의 주요 아이콘, 카드 테두리, 핵심 텍스트 하이라이트에 이 색상 조합을 적용하세요.\n");
        }
        q.append("- 디테일: 각 정보 영역마다 주제와 일치하는 고해상도 정보 테크 아이콘(예: 데이터, AI 뇌, 보안 방패 등)을 배치하고, 은은한 하이라이트 조명 효과를 주어 가독성을 극대화하세요.\n\n");
        q.append("[텍스트 제약 조건 - 필수 준수]\n");
        q.append("- **절대 원칙**: 이미지에 포함되는 모든 타이틀, 서브타이틀, 키워드, 본문 설명은 **반드시 100% 한국어(Korean)**로만 표기해야 합니다.\n");
        q.append("- 깨진 문자열, 의미 없는 더미 영문 텍스트(Lorem Ipsum 등), 무작위 알파벳 라벨 생성을 엄격히 금지합니다.\n");
        q.append("- 밝은 배경 위에 텍스트가 올라가므로, 글자가 흐려지지 않도록 대조(Contrast)가 확실한 짙은 색상의 또렷한 폰트를 사용하세요.\n\n");
        q.append("[인포그래픽에 포함할 실제 데이터]\n");
        if (!headline.isEmpty()) q.append("- 핵심 메시지: ").append(headline).append("\n");
        if (!layoutType.isEmpty()) q.append("- 권장 다이어그램 유형: ").append(layoutType).append("\n");
        if (!notes.isEmpty()) q.append("- 핵심 강조 노트(강조 박스로 분리 표시): ").append(notes).append("\n\n");
        if (!keywords.isEmpty()) {
            q.append("- 상단 핵심 키워드 태그 목록: ").append(String.join(", ", keywords)).append("\n");
        }
        if (!content.isEmpty()) {
            q.append("- 본문 세부 내용 (총 ").append(content.size()).append("개의 독립된 카드 섹션으로 분할 배치):\n");
            for (int i = 0; i < content.size(); i++) {
                q.append("  [").append(i + 1).append("] ").append(content.get(i)).append("\n");
            }
        }
        return q.toString();
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

    private static boolean hasNonNullAttachmentId(List<Long> attachmentFileIds) {
        if (attachmentFileIds == null || attachmentFileIds.isEmpty()) return false;
        for (Long id : attachmentFileIds) { if (id != null) return true; }
        return false;
    }
}
