package kr.teamagent.proposal.service.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.web.multipart.MultipartFile;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.google.gson.*;

import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.chat.service.impl.ChatbotDAO;
import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.chat.service.impl.ChatbotAgentSupport;
import kr.teamagent.chat.service.impl.agent.RiskDiagnosisAgentService;
import kr.teamagent.common.apilog.service.impl.ApiCallLogServiceImpl;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.prompt.service.impl.PromptServiceImpl;
import kr.teamagent.proposal.service.ProposalVO;

/**
 * PT 에이전트 서비스 구현체
 * - Stage 1: RFP/평가표 분석 → 작성지침·요구사항·평가기준 추출
 */
@Service
public class ProposalServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(ProposalServiceImpl.class);

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final ExecutorService STAGE1_EXECUTOR = Executors.newFixedThreadPool(5);

    // ── RFP 대용량 청크 병렬 요약 상수 ────────────────────────────────────────────
    /** 이 길이 이하면 요약 없이 그대로 전달 (약 6,000 토큰 기준) */
    private static final int PT_RFP_TEXT_MAX_CHARS = 24000;
    /** 청크 최대 개수 */
    private static final int PT_SUMMARY_MAX_CHUNKS = 16;
    /** 청크 최소 길이 */
    private static final int PT_SUMMARY_MIN_CHUNK_CHARS = 15000;
    /** 청크 간 오버랩 (표·항목 경계 잘림 방지) */
    private static final int PT_SUMMARY_CHUNK_OVERLAP = 800;
    /** LLM 호출 타임아웃 (초) */
    private static final int PT_QUERY_TIMEOUT_SEC = 300;

    /** RFP 청크 병렬 요약 전용 스레드 풀 */
    private static final ExecutorService PT_SUMMARIZE_EXECUTOR =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "pt-rfp-summarize-worker");
                t.setDaemon(true);
                return t;
            });

    /** Step F 출력 빌드 전용 스레드 풀 */
    private static final ExecutorService EXPORT_EXECUTOR =
            Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "pt-export-worker");
                t.setDaemon(true);
                return t;
            });

    // ── 의존성 ─────────────────────────────────────────────────────────────────

    @Autowired
    private ProposalDAO proposalDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    @Autowired
    private ChatbotDAO chatbotDAO;

    @Autowired
    private FileServiceImpl fileService;

    @Autowired
    private PromptServiceImpl promptService;

    @Autowired
    private RiskDiagnosisAgentService riskDiagnosisAgentService;

    @Autowired
    private ChatbotAgentSupport agentSupport;

    @Autowired
    @Lazy
    private ChatbotServiceImpl chatbotService;

    @Autowired
    private AmazonS3 amazonS3;

    @Autowired
    private ApiCallLogServiceImpl apiCallLogService;


    // ── 프로젝트 조회 ───────────────────────────────────────────────────────────

    public List<ProposalVO.ProjectVO> selectPtProjectList(ProposalVO.ProjectVO searchVO) throws Exception {
        return proposalDAO.selectPtProjectList(searchVO);
    }

    // ── 프로젝트 생성 ───────────────────────────────────────────────────────────

    /**
     * PT 프로젝트 생성
     * @param vo projectNm, orgNm, projectOverview, targetTypeCd, dueDt
     * @return 생성된 ptProjectId
     * @throws Exception
     */
    public String createProject(ProposalVO.ProjectVO vo) throws Exception {
        String ptProjectId = keyGenerate.generateTableKey("PT", "TB_PT_PROJECT", "PT_PROJECT_ID");
        vo.setPtProjectId(ptProjectId);
        vo.setStatusCd("001");
        if (CommonUtil.isEmpty(vo.getTargetTypeCd())) {
            vo.setTargetTypeCd("G");
        }
        vo.setCreateUserId(SessionUtil.getUserId());

        proposalDAO.insertProject(vo);

        return ptProjectId;
    }

    // ── Stage 1 실행 (SSE 비동기) ───────────────────────────────────────────────

    /**
     * Stage 1 SSE 스트림: RFP(+평가표) 분석을 비동기로 실행하며 진행 상황을 SSE로 전달
     * @param ptProjectId 프로젝트 ID
     * @param modelId     사용할 LLM 모델 ID
     * @param agentId     에이전트 ID
     * @return SseEmitter
     */
    public SseEmitter streamExtractStage1(String ptProjectId, String modelId, String agentId) {
        SseEmitter emitter = new SseEmitter(0L); // 타임아웃 없음

        emitter.onTimeout(() -> {
            logger.warn("[PT Stage1] SSE timeout - ptProjectId={}", ptProjectId);
            emitter.complete();
        });
        emitter.onError(e -> logger.warn("[PT Stage1] SSE error - ptProjectId={}, msg={}", ptProjectId, e.getMessage()));
        emitter.onCompletion(() -> logger.info("[PT Stage1] SSE complete - ptProjectId={}", ptProjectId));

        // 연결 확정
        sendSseEvent(emitter, "connected", "{\"ptProjectId\":\"" + ptProjectId + "\"}");

        final String userId = SessionUtil.getUserId();

        STAGE1_EXECUTOR.execute(() -> {
            try {
                // Step 1: 파일 텍스트 추출
                sendSseEvent(emitter, "progress", "{\"step\":\"extract\",\"message\":\"RFP 파일 텍스트 추출 중\"}");
                ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
                if (project == null) {
                    sendSseEvent(emitter, "error", "{\"message\":\"프로젝트를 찾을 수 없습니다.\"}");
                    emitter.complete();
                    return;
                }

                // RFP 파일: FILE_PURPOSE_CD='001'로 조회 (RFP_FILE_ID 컬럼 제거 후 대체)
                String rfpFileId = resolvePtFileId(ptProjectId, "001");
                String rfpText = extractPtFileText(rfpFileId);
                if (CommonUtil.isEmpty(rfpText)) {
                    sendSseEvent(emitter, "error", "{\"message\":\"RFP 파일 텍스트 추출에 실패했습니다. 파일을 다시 첨부해 주세요.\"}");
                    emitter.complete();
                    return;
                }

                // 평가표 파일: FILE_PURPOSE_CD='002'로 조회 (EVAL_TABLE_FILE_ID 컬럼 제거 후 대체)
                String evalText = null;
                String evalFileId = resolvePtFileId(ptProjectId, "002");
                if (CommonUtil.isNotEmpty(evalFileId)) {
                    evalText = extractPtFileText(evalFileId);
                    if (CommonUtil.isEmpty(evalText)) {
                        logger.warn("[PT Stage1] 평가표 텍스트 추출 실패 (ptProjectId={}, filePurposeCd=002)", ptProjectId);
                    }
                }

                // Step 2: 프롬프트 조합
                sendSseEvent(emitter, "progress", "{\"step\":\"prompt\",\"message\":\"프롬프트 준비 중\"}");
                String promptContent = null;
                try {
                    promptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S1_EXTRACT");
                } catch (Exception e) {
                    logger.warn("[PT Stage1] 프롬프트 조회 실패, 기본 프롬프트 사용: {}", e.getMessage());
                }
                if (CommonUtil.isEmpty(promptContent)) {
                    promptContent = buildDefaultStage1Prompt();
                }

                // Step 3: LLM 호출 — 대용량 분기
                sendSseEvent(emitter, "progress", "{\"step\":\"llm\",\"message\":\"AI 분석 중\"}");
                ProposalVO.Stage1ResultVO parsed;
                if (rfpText.length() > PT_RFP_TEXT_MAX_CHARS) {
                    // ── 대용량 경로: 청크 완전 추출 + Java 병합 (LLM 재호출 없음) ────────
                    sendSseEvent(emitter, "progress",
                            "{\"step\":\"chunk_extract\",\"message\":\"대용량 RFP 청크 추출 시작\"}");
                    parsed = extractStage1FromLargeRfp(rfpText, promptContent, modelId, emitter, agentId);

                    // evalText 별도 처리
                    if (CommonUtil.isNotEmpty(evalText)) {
                        ProposalVO.Stage1ResultVO evalParsed = null;
                        try {
                            if (evalText.length() > PT_RFP_TEXT_MAX_CHARS) {
                                evalParsed = extractStage1FromLargeRfp(evalText, promptContent, modelId, null, agentId);
                            } else {
                                String evalPrompt = promptContent + "\n\n## 평가표\n" + evalText;
                                String evalResp = riskDiagnosisAgentService.callLlmQuerySync(evalPrompt, modelId, "", agentId);
                                if (CommonUtil.isNotEmpty(evalResp)) {
                                    evalParsed = parseStage1Response(evalResp);
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("[PT Stage1] 평가표 추출 실패 (무시): {}", e.getMessage());
                        }
                        if (evalParsed != null && evalParsed.getEvalCriteria() != null && !evalParsed.getEvalCriteria().isEmpty()) {
                            mergeEvalCriteriaInto(parsed, evalParsed.getEvalCriteria());
                        }
                    }
                } else {
                    // ── 단일 호출 경로 (rfpText가 임계값 이하) ──────────────────────────
                    StringBuilder fullPromptSb = new StringBuilder(promptContent);
                    fullPromptSb.append("\n\n## RFP 원문\n").append(rfpText);
                    if (CommonUtil.isNotEmpty(evalText)) {
                        fullPromptSb.append("\n\n## 평가표\n").append(evalText);
                    }
                    String fullPrompt = fullPromptSb.toString();

                    String aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
                    if (CommonUtil.isEmpty(aiResponse)) {
                        logger.warn("[PT Stage1] LLM 응답 없음, 1회 재시도 (ptProjectId={})", ptProjectId);
                        aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
                    }
                    if (CommonUtil.isEmpty(aiResponse)) {
                        sendSseEvent(emitter, "error", "{\"message\":\"AI 응답이 비어 있습니다. 잠시 후 다시 시도해 주세요.\"}");
                        emitter.complete();
                        return;
                    }

                    // Step 4: JSON 파싱 (실패 시 1회 재시도)
                    sendSseEvent(emitter, "progress", "{\"step\":\"parse\",\"message\":\"분석 결과 검증 중\"}");
                    try {
                        parsed = parseStage1Response(aiResponse);
                    } catch (RuntimeException e) {
                        logger.warn("[PT Stage1] JSON 파싱 실패, 1회 재시도: {}", e.getMessage());
                        aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
                        try {
                            parsed = parseStage1Response(aiResponse);
                        } catch (RuntimeException e2) {
                            logger.error("[PT Stage1] JSON 파싱 재시도 실패 (ptProjectId={}): {}\n응답원문: {}", ptProjectId, e2.getMessage(), aiResponse);
                            sendSseEvent(emitter, "error", "{\"message\":\"자동 추출에 실패했습니다. 직접 입력해 주세요.\"}");
                            emitter.complete();
                            return;
                        }
                    }
                }

                // sourceTypeCd null 체크 — null이면 재시도 대상 (이미 파싱 재시도로 처리됨, 경고만)
                if (parsed.getRequirements() != null) {
                    for (ProposalVO.RequirementVO req : parsed.getRequirements()) {
                        if (CommonUtil.isEmpty(req.getSourceTypeCd())) {
                            logger.warn("[PT Stage1] sourceTypeCd 누락 항목 발견 (reqNo={}), '001'로 채움", req.getReqNo());
                            req.setSourceTypeCd("001");
                        }
                    }
                }

                validateScoreSum(parsed.getEvalCriteria(), ptProjectId);
                validateWritingGuideline(parsed.getWritingGuidelineJson(), ptProjectId);

                // evalCriteria 빈 배열 플래그
                boolean evalCriteriaEmpty = (parsed.getEvalCriteria() == null || parsed.getEvalCriteria().isEmpty());
                if (evalCriteriaEmpty) {
                    sendSseEvent(emitter, "warn", "{\"message\":\"평가표를 찾지 못했습니다. 평가표 파일을 별도로 첨부하거나 직접 입력해 주세요.\"}");
                }

                // Step 5: DB 저장
                sendSseEvent(emitter, "progress", "{\"step\":\"save\",\"message\":\"결과 저장 중\"}");
                saveStage1Result(ptProjectId, parsed, userId);

                // Step 6: API 호출 로그 기록 (PT 제안은 채팅이 아니므로 apiCallLogService 사용)
                try {
                    String reqParam = "{\"ptProjectId\":\"" + ptProjectId + "\",\"stage\":\"S1_EXTRACT\"}";
                    apiCallLogService.insertSilently(agentId, null,
                            kr.teamagent.common.util.PropertyUtil.getProperty("Globals.chatbot.gpt.apiUrl"),
                            modelId, "S1_EXTRACT", reqParam, 0, 0, 0, "Y", null, userId);
                } catch (Exception e) {
                    logger.warn("[PT Stage1] API 호출 로그 기록 실패 (무시): {}", e.getMessage());
                }

                // 완료
                sendSseEvent(emitter, "done", "{\"ptProjectId\":\"" + ptProjectId + "\""
                        + ",\"requirementCount\":" + (parsed.getRequirements() != null ? parsed.getRequirements().size() : 0)
                        + ",\"evalCriteriaCount\":" + (parsed.getEvalCriteria() != null ? parsed.getEvalCriteria().size() : 0)
                        + ",\"evalCriteriaEmpty\":" + evalCriteriaEmpty + "}");

            } catch (Exception e) {
                logger.error("[PT Stage1] 처리 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
                sendSseEvent(emitter, "error", "{\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * Stage 1 내부 실행 (동기, 레거시 호환용 — 직접 호출 시 사용)
     */
    public ProposalVO.Stage1ResultVO executeStage1(String ptProjectId, String modelId, String agentId) throws Exception {

        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) {
            throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);
        }

        // RFP 파일: FILE_PURPOSE_CD='001'로 조회 (RFP_FILE_ID 컬럼 제거 후 대체)
        String rfpText = extractPtFileText(resolvePtFileId(ptProjectId, "001"));

        // 평가표 파일: FILE_PURPOSE_CD='002'로 조회 (EVAL_TABLE_FILE_ID 컬럼 제거 후 대체)
        String evalText = null;
        String evalFileId = resolvePtFileId(ptProjectId, "002");
        if (CommonUtil.isNotEmpty(evalFileId)) {
            evalText = extractPtFileText(evalFileId);
        }

        String promptContent = null;
        try {
            promptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S1_EXTRACT");
        } catch (Exception e) {
            logger.warn("[PT Stage1] 프롬프트 조회 실패, 기본 프롬프트 사용: {}", e.getMessage());
        }
        if (CommonUtil.isEmpty(promptContent)) {
            promptContent = buildDefaultStage1Prompt();
        }

        ProposalVO.Stage1ResultVO parsed;
        if (rfpText.length() > PT_RFP_TEXT_MAX_CHARS) {
            // ── 대용량 경로: 청크 완전 추출 + Java 병합 ──────────────────────────
            parsed = extractStage1FromLargeRfp(rfpText, promptContent, modelId, null, agentId);

            if (CommonUtil.isNotEmpty(evalText)) {
                ProposalVO.Stage1ResultVO evalParsed = null;
                try {
                    if (evalText.length() > PT_RFP_TEXT_MAX_CHARS) {
                        evalParsed = extractStage1FromLargeRfp(evalText, promptContent, modelId, null, agentId);
                    } else {
                        String evalPrompt = promptContent + "\n\n## 평가표\n" + evalText;
                        String evalResp = riskDiagnosisAgentService.callLlmQuerySync(evalPrompt, modelId, "", agentId);
                        if (CommonUtil.isNotEmpty(evalResp)) {
                            evalParsed = parseStage1Response(evalResp);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("[PT Stage1] 평가표 추출 실패 (무시): {}", e.getMessage());
                }
                if (evalParsed != null && evalParsed.getEvalCriteria() != null && !evalParsed.getEvalCriteria().isEmpty()) {
                    mergeEvalCriteriaInto(parsed, evalParsed.getEvalCriteria());
                }
            }
        } else {
            // ── 단일 호출 경로 ──────────────────────────────────────────────────
            StringBuilder fullPromptSb = new StringBuilder(promptContent);
            fullPromptSb.append("\n\n## RFP 원문\n").append(rfpText);
            if (CommonUtil.isNotEmpty(evalText)) {
                fullPromptSb.append("\n\n## 평가표\n").append(evalText);
            }
            String fullPrompt = fullPromptSb.toString();

            String aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
            if (CommonUtil.isEmpty(aiResponse)) {
                logger.warn("[PT Stage1] LLM 응답 없음, 1회 재시도 (ptProjectId={})", ptProjectId);
                aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
            }
            if (CommonUtil.isEmpty(aiResponse)) {
                throw new RuntimeException("LLM 응답이 비어 있습니다. Stage 1 분석을 완료할 수 없습니다.");
            }

            parsed = parseStage1Response(aiResponse);
        }
        validateScoreSum(parsed.getEvalCriteria(), ptProjectId);
        validateWritingGuideline(parsed.getWritingGuidelineJson(), ptProjectId);
        saveStage1Result(ptProjectId, parsed, SessionUtil.getUserId());

        parsed.setPtProjectId(ptProjectId);
        return parsed;
    }

    /**
     * Stage 1 결과 DB 쓰기 — 트랜잭션 분리
     * @param userId SSE 비동기 컨텍스트에서는 세션이 없으므로 명시적으로 받음
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveStage1Result(String ptProjectId, ProposalVO.Stage1ResultVO parsed, String userId) throws Exception {
        if (CommonUtil.isEmpty(userId)) userId = SessionUtil.getUserId();

        // 요구사항 초기화 후 재등록
        proposalDAO.deleteRequirementsByProject(ptProjectId);
        if (parsed.getRequirements() != null) {
            int sortOrd = 0;
            for (ProposalVO.RequirementVO req : parsed.getRequirements()) {
                req.setRequirementId(
                        keyGenerate.generateTableKey("PTQ", "TB_PT_REQUIREMENT", "REQUIREMENT_ID", 6));
                req.setPtProjectId(ptProjectId);
                req.setCreateUserId(userId);
                if (req.getSortOrd() == null) {
                    req.setSortOrd(sortOrd++);
                }
                proposalDAO.insertRequirement(req);
            }
        }

        // 평가기준 초기화 후 재등록
        proposalDAO.deleteEvalCriteriaByProject(ptProjectId);
        if (parsed.getEvalCriteria() != null) {
            int sortOrd = 0;
            for (ProposalVO.EvalCriteriaVO ec : parsed.getEvalCriteria()) {
                ec.setEvalCriteriaId(
                        keyGenerate.generateTableKey("PTE", "TB_PT_EVAL_CRITERIA", "EVAL_CRITERIA_ID", 6));
                ec.setPtProjectId(ptProjectId);
                ec.setCreateUserId(userId);
                if (ec.getSortOrd() == null) {
                    ec.setSortOrd(sortOrd++);
                }
                proposalDAO.insertEvalCriteria(ec);
            }
        }

        // 작성지침 저장
        ProposalVO.ProjectVO updateVO = new ProposalVO.ProjectVO();
        updateVO.setPtProjectId(ptProjectId);
        updateVO.setWritingGuidelineJson(parsed.getWritingGuidelineJson());
        proposalDAO.updateProjectWritingGuideline(updateVO);

        // 상태 → '002' 검수중
        ProposalVO.ProjectVO statusVO = new ProposalVO.ProjectVO();
        statusVO.setPtProjectId(ptProjectId);
        statusVO.setStatusCd("002");
        proposalDAO.updateProjectStatus(statusVO);
    }

    // ── Stage 1 결과 조회 ────────────────────────────────────────────────────────

    /**
     * Stage 1 저장 결과 조회
     * @param ptProjectId 프로젝트 ID
     * @return Stage1ResultVO
     * @throws Exception
     */
    public ProposalVO.Stage1ResultVO selectStage1Result(String ptProjectId) throws Exception {
        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) {
            throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);
        }

        List<ProposalVO.RequirementVO> requirements = proposalDAO.selectRequirements(ptProjectId);
        List<ProposalVO.EvalCriteriaVO> evalCriteria = proposalDAO.selectEvalCriteria(ptProjectId);

        ProposalVO.Stage1ResultVO result = new ProposalVO.Stage1ResultVO();
        result.setPtProjectId(ptProjectId);
        result.setWritingGuidelineJson(project.getWritingGuidelineJson());
        result.setRequirements(requirements);
        result.setEvalCriteria(evalCriteria);

        return result;
    }

    // ── 내부 유틸 ────────────────────────────────────────────────────────────────

    /**
     * SSE 이벤트 발송 헬퍼
     */
    private void sendSseEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            logger.warn("[PT Stage1] SSE 이벤트 전송 실패 (event={}): {}", eventName, e.getMessage());
        }
    }

    /**
     * 대용량 RFP/평가표 텍스트를 청크로 분할하여 S1_EXTRACT 프롬프트로 병렬 호출 후
     * 각 청크 응답을 즉시 파싱, Java에서 병합하여 Stage1ResultVO를 반환한다.
     * LLM 재호출(통합 단계) 없음 — JSON 잘림(max_tokens 초과) 문제 해소.
     *
     * @param text          RFP 또는 평가표 전체 텍스트
     * @param promptContent S1_EXTRACT 프롬프트 본문
     * @param modelId       LLM 모델 ID
     * @param emitter       SSE 진행 이벤트용 (null 허용 — sync 경로)
     * @param agentId       에이전트 ID
     * @return 병합된 Stage1ResultVO
     */
    private ProposalVO.Stage1ResultVO extractStage1FromLargeRfp(
            String text, String promptContent, String modelId,
            SseEmitter emitter, String agentId) {

        java.util.List<String> chunks = splitRfpIntoChunks(text);
        final int chunkCount = chunks.size();
        logger.info("[PT Stage1] 청크 분할 완료 - 원본:{}자, 청크:{}개", text.length(), chunkCount);

        final java.util.concurrent.atomic.AtomicInteger completedCount =
                new java.util.concurrent.atomic.AtomicInteger(0);

        java.util.List<java.util.concurrent.Future<ProposalVO.Stage1ResultVO>> futures =
                new java.util.ArrayList<>();

        for (int ci = 0; ci < chunkCount; ci++) {
            final int chunkNo = ci + 1;
            final String chunk = chunks.get(ci);
            futures.add(PT_SUMMARIZE_EXECUTOR.submit(() -> {
                long t0 = System.currentTimeMillis();
                logger.info("[PT Stage1] 청크 추출 시작 - {}/{} ({}자)", chunkNo, chunkCount, chunk.length());
                try {
                    String chunkPrompt = promptContent
                            + "\n\n## RFP 일부 (" + chunkNo + "/" + chunkCount + ")\n" + chunk;
                    String response = riskDiagnosisAgentService.callLlmQuerySync(chunkPrompt, modelId, "", agentId);
                    if (CommonUtil.isEmpty(response)) {
                        logger.warn("[PT Stage1] 청크 {}/{} LLM 응답 없음 — 스킵", chunkNo, chunkCount);
                        return null;
                    }
                    ProposalVO.Stage1ResultVO chunkResult = parseStage1Response(response);
                    int done = completedCount.incrementAndGet();
                    if (emitter != null) {
                        sendSseEvent(emitter, "progress",
                                String.format("{\"step\":\"chunk\",\"message\":\"청크 %d/%d 처리 중\",\"current\":%d,\"total\":%d}",
                                        done, chunkCount, done, chunkCount));
                    }
                    logger.info("[PT Stage1] 청크 추출 완료 - {}/{} (요구사항:{}건, {}ms)",
                            chunkNo, chunkCount,
                            chunkResult.getRequirements() != null ? chunkResult.getRequirements().size() : 0,
                            System.currentTimeMillis() - t0);
                    return chunkResult;
                } catch (Exception e) {
                    logger.warn("[PT Stage1] 청크 {}/{} 추출/파싱 실패 — 스킵: {}", chunkNo, chunkCount, e.getMessage());
                    return null;
                }
            }));
        }

        java.util.List<ProposalVO.Stage1ResultVO> chunkResults = new java.util.ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                ProposalVO.Stage1ResultVO r = futures.get(i).get(
                        PT_QUERY_TIMEOUT_SEC + 20L, java.util.concurrent.TimeUnit.SECONDS);
                if (r != null) chunkResults.add(r);
            } catch (Exception e) {
                logger.warn("[PT Stage1] 청크 {} 결과 수집 실패: {}", i + 1, e.getMessage());
            }
        }

        if (chunkResults.isEmpty()) {
            throw new RuntimeException("모든 청크 추출이 실패했습니다. 직접 입력해 주세요.");
        }

        ProposalVO.Stage1ResultVO merged = mergeStage1Results(chunkResults);
        logger.info("[PT Stage1] 청크 병합 완료 - 요구사항:{}건, 평가기준:{}건 (성공 청크:{}/{})",
                merged.getRequirements() != null ? merged.getRequirements().size() : 0,
                merged.getEvalCriteria() != null ? merged.getEvalCriteria().size() : 0,
                chunkResults.size(), chunkCount);
        return merged;
    }

    /**
     * RFP 텍스트를 페이지 경계(\f 폼피드) 또는 문자 수 기준으로 청크 분할.
     * PDF 추출 시 \f 가 포함된 경우 페이지 경계를 존중해 자른다.
     */
    private java.util.List<String> splitRfpIntoChunks(String text) {
        int total = text.length();
        int chunkSize = Math.max(PT_SUMMARY_MIN_CHUNK_CHARS,
                (int) Math.ceil((double) total / PT_SUMMARY_MAX_CHUNKS));
        int overlap = Math.min(PT_SUMMARY_CHUNK_OVERLAP, chunkSize / 4);

        // 폼피드(\f) 기준 페이지 분리 시도
        if (text.indexOf('\f') >= 0) {
            String[] pages = text.split("\\f", -1);
            if (pages.length > 1) {
                java.util.List<String> chunks = new java.util.ArrayList<>();
                StringBuilder current = new StringBuilder();
                for (String page : pages) {
                    if (current.length() > 0 && current.length() + page.length() > chunkSize) {
                        chunks.add(current.toString().trim());
                        // 오버랩: 이전 청크 끝부분 포함
                        String tail = current.length() > overlap
                                ? current.substring(current.length() - overlap) : current.toString();
                        current = new StringBuilder(tail).append('\f').append(page);
                    } else {
                        if (current.length() > 0) current.append('\f');
                        current.append(page);
                    }
                }
                if (current.length() > 0) chunks.add(current.toString().trim());
                // 최대 청크 수 초과 시 인접 청크 합산
                if (chunks.size() > PT_SUMMARY_MAX_CHUNKS) {
                    int mergeN = (int) Math.ceil((double) chunks.size() / PT_SUMMARY_MAX_CHUNKS);
                    java.util.List<String> merged = new java.util.ArrayList<>();
                    for (int i = 0; i < chunks.size(); i += mergeN) {
                        StringBuilder sb = new StringBuilder();
                        for (int j = i; j < Math.min(i + mergeN, chunks.size()); j++) {
                            if (sb.length() > 0) sb.append('\f');
                            sb.append(chunks.get(j));
                        }
                        merged.add(sb.toString());
                    }
                    return merged;
                }
                logger.info("[PT Stage1] 페이지 경계 기준 분할 - 페이지:{}, 청크:{}", pages.length, chunks.size());
                return chunks;
            }
        }

        // 폴백: 문자 수 기준 분할 (기존 로직 유지)
        java.util.List<String> chunks = new java.util.ArrayList<>();
        int pos = 0;
        while (pos < total) {
            int end = Math.min(total, pos + chunkSize);
            chunks.add(text.substring(pos, end));
            if (end >= total) break;
            pos = end - overlap;
        }
        logger.info("[PT Stage1] 문자 수 기준 분할 - 원본:{}자, 청크:{}개 (청크당 ~{}자, overlap:{}자)",
                total, chunks.size(), chunkSize, overlap);
        return chunks;
    }

    /**
     * 여러 청크에서 파싱한 Stage1ResultVO 목록을 Java에서 병합한다. LLM 재호출 없음.
     * - requirements: 전체 concat. reqNo 비null 항목은 첫 번째 유지(중복 경고)
     * - writingGuideline: tocMandatoryYn='Y'이거나 필드가 채워진 첫 값 채택
     * - evalCriteria: 전체 concat, 중복 evalItemNm 경고만
     */
    private ProposalVO.Stage1ResultVO mergeStage1Results(java.util.List<ProposalVO.Stage1ResultVO> chunkResults) {
        ProposalVO.Stage1ResultVO merged = new ProposalVO.Stage1ResultVO();

        // requirements 병합
        java.util.List<ProposalVO.RequirementVO> allReqs = new java.util.ArrayList<>();
        java.util.Set<String> seenReqNos = new java.util.LinkedHashSet<>();
        int reqSortBase = 0;
        for (ProposalVO.Stage1ResultVO cr : chunkResults) {
            if (cr.getRequirements() == null) continue;
            for (ProposalVO.RequirementVO req : cr.getRequirements()) {
                String rn = req.getReqNo();
                if (CommonUtil.isNotEmpty(rn)) {
                    if (seenReqNos.contains(rn)) {
                        logger.warn("[PT Stage1 Merge] 중복 reqNo={} — 첫 번째 유지, 후속 스킵", rn);
                        continue;
                    }
                    seenReqNos.add(rn);
                }
                if (req.getSortOrd() == null) req.setSortOrd(reqSortBase);
                reqSortBase++;
                allReqs.add(req);
            }
        }
        merged.setRequirements(allReqs);

        // writingGuideline 병합: tocMandatoryYn=Y 우선, 없으면 첫 번째 비어있지 않은 값
        String bestGuideline = null;
        for (ProposalVO.Stage1ResultVO cr : chunkResults) {
            String wg = cr.getWritingGuidelineJson();
            if (CommonUtil.isEmpty(wg)) continue;
            if (bestGuideline == null) {
                bestGuideline = wg;
            } else {
                try {
                    JsonObject wgObj = JsonParser.parseString(wg).getAsJsonObject();
                    if ("Y".equals(getStrOrNull(wgObj, "tocMandatoryYn"))) {
                        JsonObject bestObj = JsonParser.parseString(bestGuideline).getAsJsonObject();
                        if (!"Y".equals(getStrOrNull(bestObj, "tocMandatoryYn"))) {
                            bestGuideline = wg;
                        }
                    }
                } catch (Exception ignored) { }
            }
        }
        merged.setWritingGuidelineJson(bestGuideline);

        // evalCriteria 병합
        java.util.List<ProposalVO.EvalCriteriaVO> allEval = new java.util.ArrayList<>();
        java.util.Set<String> seenEvalNms = new java.util.LinkedHashSet<>();
        int evalSortBase = 0;
        for (ProposalVO.Stage1ResultVO cr : chunkResults) {
            if (cr.getEvalCriteria() == null) continue;
            for (ProposalVO.EvalCriteriaVO ec : cr.getEvalCriteria()) {
                String nm = ec.getEvalItemNm();
                if (CommonUtil.isNotEmpty(nm) && seenEvalNms.contains(nm)) {
                    logger.warn("[PT Stage1 Merge] 중복 evalItemNm='{}' — 경고만, 유지", nm);
                }
                if (CommonUtil.isNotEmpty(nm)) seenEvalNms.add(nm);
                if (ec.getSortOrd() == null) ec.setSortOrd(evalSortBase);
                evalSortBase++;
                allEval.add(ec);
            }
        }
        merged.setEvalCriteria(allEval);

        return merged;
    }

    /**
     * evalText 추출 결과의 evalCriteria를 기존 Stage1ResultVO에 병합(append)한다.
     * 중복 evalItemNm이 있으면 경고 로그만 남기고 유지.
     */
    private void mergeEvalCriteriaInto(ProposalVO.Stage1ResultVO target,
                                        java.util.List<ProposalVO.EvalCriteriaVO> evalItems) {
        if (evalItems == null || evalItems.isEmpty()) return;
        java.util.List<ProposalVO.EvalCriteriaVO> existing =
                target.getEvalCriteria() != null ? target.getEvalCriteria() : new java.util.ArrayList<>();

        java.util.Set<String> existingNms = new java.util.LinkedHashSet<>();
        for (ProposalVO.EvalCriteriaVO ec : existing) {
            if (CommonUtil.isNotEmpty(ec.getEvalItemNm())) existingNms.add(ec.getEvalItemNm());
        }

        int sortOrd = existing.size();
        for (ProposalVO.EvalCriteriaVO ec : evalItems) {
            String nm = ec.getEvalItemNm();
            if (CommonUtil.isNotEmpty(nm) && existingNms.contains(nm)) {
                logger.warn("[PT Stage1 Merge] evalText 병합 중 중복 evalItemNm='{}' — 경고만, 유지", nm);
            }
            if (CommonUtil.isNotEmpty(nm)) existingNms.add(nm);
            if (ec.getSortOrd() == null) ec.setSortOrd(sortOrd++);
            existing.add(ec);
        }
        target.setEvalCriteria(existing);
    }

    /**
     * 프로젝트의 용도별 파일 ID 조회 — RFP_FILE_ID/EVAL_TABLE_FILE_ID 컬럼 제거 후 대체 헬퍼.
     * FILE_PURPOSE_CD (PT000011): 001=RFP원문, 002=평가표, 003=템플릿, 004=기타참고자료, 005=자사정보, 006=경쟁사정보.
     * 프로젝트당 1건 전제이므로 목록 중 첫 번째 PT_FILE_ID를 반환한다.
     *
     * @param ptProjectId   프로젝트 ID
     * @param filePurposeCd PT000011 코드값
     * @return PT_FILE_ID 문자열, 없으면 null
     */
    private String resolvePtFileId(String ptProjectId, String filePurposeCd) {
        try {
            List<ProposalVO.PtFileVO> files = proposalDAO.selectPtFileByPurpose(ptProjectId, filePurposeCd);
            if (files != null && !files.isEmpty()) {
                return files.get(0).getPtFileId();
            }
        } catch (Exception e) {
            logger.warn("[PT] resolvePtFileId 조회 실패 (ptProjectId={}, filePurposeCd={}): {}", ptProjectId, filePurposeCd, e.getMessage());
        }
        return null;
    }

    /**
     * TB_PT_FILE에서 파일을 다운로드하여 텍스트 추출
     * @param ptFileId TB_PT_FILE.PT_FILE_ID
     * @return 추출된 텍스트 (실패 시 빈 문자열)
     */
    private String extractPtFileText(String ptFileId) {
        if (CommonUtil.isEmpty(ptFileId)) return "";
        try {
            ProposalVO.PtFileVO fileVO = proposalDAO.selectPtFileById(ptFileId);
            if (fileVO == null || CommonUtil.isEmpty(fileVO.getFilePath())) {
                logger.warn("[PT Stage1] ptFileId={} 에 해당하는 파일 경로 없음", ptFileId);
                return "";
            }
            String fileName = CommonUtil.nullToBlank(fileVO.getFileNm());
            byte[] bytes = fileService.downloadStorageObjectBytes(fileVO.getFilePath());
            String text = riskDiagnosisAgentService.extractPdfText(bytes, fileName);
            return CommonUtil.nullToBlank(text).trim();
        } catch (Exception e) {
            logger.warn("[PT Stage1] 파일 텍스트 추출 실패 (ptFileId={}): {}", ptFileId, e.getMessage());
            return "";
        }
    }

    /**
     * 여러 파일 ID의 텍스트를 순서대로 추출해 하나의 문자열로 합쳐 반환.
     * 추출 실패한 파일은 건너뜀.
     */
    private String extractMultiFileText(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String fileId : fileIds) {
            String text = extractPtFileText(fileId);
            if (CommonUtil.isNotEmpty(text)) {
                if (sb.length() > 0) sb.append("\n\n---\n\n");
                sb.append(text.trim());
            }
        }
        return sb.toString().trim();
    }

    /**
     * PT 파일 업로드 (TB_PT_FILE)
     * NCP 업로드 후 TB_PT_FILE에 메타데이터 저장
     * @param file            업로드 파일 (MultipartFile)
     * @param ptProjectId     프로젝트 ID (null 허용 — 생성 전 업로드 시)
     * @param filePurposeCd   PT000011 코드값 (001=RFP원문, 002=평가표, 003=템플릿 ...)
     * @return PtFileVO (ptFileId, filePath 포함)
     */
    public ProposalVO.PtFileVO uploadPtFile(MultipartFile file, String ptProjectId, String filePurposeCd) throws Exception {
        String ptFileId = keyGenerate.generateTableKey("PTF", "TB_PT_FILE", "PT_FILE_ID");

        String originalFilename = file.getOriginalFilename();
        if (CommonUtil.isEmpty(originalFilename)) originalFilename = "file";

        // NCP 오브젝트 키: pt-file/{ptProjectId}/{ptFileId}_{원본파일명}
        String projectPart = CommonUtil.isNotEmpty(ptProjectId) ? ptProjectId : "temp";
        String objectKey = "pt-file/" + projectPart + "/" + ptFileId + "_" + originalFilename;

        String bucket = kr.teamagent.common.util.PropertyUtil.getProperty("ncp.storage.bucket");
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        if (CommonUtil.isNotEmpty(file.getContentType())) {
            metadata.setContentType(file.getContentType());
        }

        amazonS3.putObject(bucket, objectKey, file.getInputStream(), metadata);

        ProposalVO.PtFileVO ptFileVO = new ProposalVO.PtFileVO();
        ptFileVO.setPtFileId(ptFileId);
        ptFileVO.setPtProjectId(ptProjectId);
        ptFileVO.setFilePurposeCd(filePurposeCd);
        ptFileVO.setFilePath(objectKey);
        ptFileVO.setFileNm(originalFilename);
        ptFileVO.setFileSize(file.getSize());
        ptFileVO.setFileType(CommonUtil.nullToBlank(file.getContentType()));
        ptFileVO.setCreateUserId(SessionUtil.getUserId());

        proposalDAO.insertPtFile(ptFileVO);

        return ptFileVO;
    }

    /**
     * PT 파일 업로드용 presigned URL 발급
     * - 요청 filePath를 스토리지 키로 사용
     */
    public java.util.Map<String, Object> savePtFileUploadUrl(ProposalVO.PtFileVO ptFileVO) {
        FileVO req = new FileVO();
        req.setFileName(ptFileVO.getFileNm());
        req.setFileType(ptFileVO.getFileType());
        if (ptFileVO.getFileSize() != null) {
            req.setFileSize(String.valueOf(ptFileVO.getFileSize()));
        }
        if (CommonUtil.isNotEmpty(ptFileVO.getFilePath())) {
            req.setKey(ptFileVO.getFilePath());
        }
        return fileService.createUploadPresignedUrl(req);
    }

    /**
     * PT 파일 메타 저장 (NCP 업로드 완료 후 TB_PT_FILE INSERT)
     */
    public java.util.Map<String, Object> savePtFile(ProposalVO.PtFileVO vo) throws Exception {
        java.util.Map<String, Object> resultMap = new java.util.HashMap<>();

        if (CommonUtil.isEmpty(vo.getFilePath())) {
            throw new RuntimeException("filePath는 필수입니다.");
        }
        if (CommonUtil.isEmpty(vo.getFileNm())) {
            throw new RuntimeException("fileName은 필수입니다.");
        }

        String ptFileId = keyGenerate.generateTableKey("PTF-", "TB_PT_FILE", "PT_FILE_ID", 6);

        ProposalVO.PtFileVO ptFileVO = new ProposalVO.PtFileVO();
        ptFileVO.setPtFileId(ptFileId);
        ptFileVO.setPtProjectId(vo.getPtProjectId());
        ptFileVO.setFilePurposeCd(CommonUtil.isNotEmpty(vo.getFilePurposeCd()) ? vo.getFilePurposeCd() : "001");
        ptFileVO.setFilePath(vo.getFilePath());
        ptFileVO.setFileNm(vo.getFileNm());
        ptFileVO.setFileSize(vo.getFileSize());
        if (CommonUtil.isNotEmpty(vo.getMimeType())) {
            ptFileVO.setFileType(vo.getMimeType());
        } else {
            ptFileVO.setFileType(CommonUtil.nullToBlank(vo.getFileType()));
        }
        ptFileVO.setCreateUserId(SessionUtil.getUserId());

        proposalDAO.insertPtFile(ptFileVO);

        resultMap.put("result", "OK");
        resultMap.put("ptFileId", ptFileId);
        resultMap.put("filePath", ptFileVO.getFilePath());
        resultMap.put("fileNm", ptFileVO.getFileNm());
        return resultMap;
    }

    // ── Step A: 템플릿 설정 저장 ─────────────────────────────────────────────────

    /**
     * Step A: 템플릿 설정 저장
     * PROJECT_CONFIG_JSON.template 키만 merge update (기존 settings 유지)
     *
     * @param vo ptProjectId, mode, templateFileId(선택), docSize
     * @throws Exception 입력값 오류 또는 파일 미존재/형식 오류
     */
    public void updateProjectTemplate(ProposalVO.TemplateConfigVO vo) throws Exception {
        // 1. mode 검증
        if (!"fix".equals(vo.getMode()) && !"new".equals(vo.getMode())) {
            throw new RuntimeException("mode는 'fix' 또는 'new'만 허용됩니다.");
        }
        // 2. docSize 검증
        if (!java.util.Arrays.asList("a4", "169", "43").contains(vo.getDocSize())) {
            throw new RuntimeException("docSize는 'a4', '169', '43' 중 하나여야 합니다.");
        }
        // 3. fix 모드에서 templateFileId 필수 체크
        if ("fix".equals(vo.getMode()) && CommonUtil.isEmpty(vo.getTemplateFileId())) {
            throw new RuntimeException("fix 모드에서는 templateFileId가 필수입니다.");
        }
        // 4. templateFileId 파일 존재 + .pptx/.docx 형식 검증
        if (CommonUtil.isNotEmpty(vo.getTemplateFileId())) {
            ProposalVO.PtFileVO fileVO = proposalDAO.selectPtFileById(vo.getTemplateFileId());
            if (fileVO == null) {
                throw new RuntimeException("templateFileId에 해당하는 파일이 존재하지 않습니다. templateFileId=" + vo.getTemplateFileId());
            }
            String fileNm = CommonUtil.nullToBlank(fileVO.getFileNm()).toLowerCase();
            if (!fileNm.endsWith(".pptx") && !fileNm.endsWith(".docx")) {
                throw new RuntimeException("템플릿 파일은 .pptx 또는 .docx 형식이어야 합니다. fileNm=" + fileVO.getFileNm());
            }
        }

        // 5. 기존 PROJECT_CONFIG_JSON 조회 → template 키만 merge (settings 등 기존 키 유지)
        String existingConfigJson = proposalDAO.selectProjectConfigJson(vo.getPtProjectId());
        JsonObject root;
        if (CommonUtil.isNotEmpty(existingConfigJson)) {
            try {
                root = JsonParser.parseString(existingConfigJson).getAsJsonObject();
            } catch (Exception e) {
                logger.warn("[PT StepA] PROJECT_CONFIG_JSON 파싱 실패, 빈 객체로 재시작 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage());
                root = new JsonObject();
            }
        } else {
            root = new JsonObject();
        }

        // template 객체 구성
        JsonObject templateObj = new JsonObject();
        templateObj.addProperty("mode", vo.getMode());
        if (CommonUtil.isNotEmpty(vo.getTemplateFileId())) {
            templateObj.addProperty("templateFileId", vo.getTemplateFileId());
        }
        templateObj.addProperty("docSize", vo.getDocSize());
        root.add("template", templateObj);

        // 6. 저장
        ProposalVO.ProjectVO updateVO = new ProposalVO.ProjectVO();
        updateVO.setPtProjectId(vo.getPtProjectId());
        updateVO.setProjectConfigJson(GSON.toJson(root));
        proposalDAO.updateProjectConfigJson(updateVO);

        logger.info("[PT StepA] 템플릿 설정 저장 완료 (ptProjectId={}, mode={}, docSize={})", vo.getPtProjectId(), vo.getMode(), vo.getDocSize());
    }

    /**
     * 요구사항 단건 수정 (사용자 수동 보정)
     */
    public void updateRequirement(ProposalVO.RequirementVO vo) throws Exception {
        vo.setModifyUserId(SessionUtil.getUserId());
        proposalDAO.updateRequirement(vo);
    }

    /**
     * 평가기준 단건 수정 (사용자 수동 보정)
     */
    public void updateEvalCriteria(ProposalVO.EvalCriteriaVO vo) throws Exception {
        vo.setModifyUserId(SessionUtil.getUserId());
        proposalDAO.updateEvalCriteria(vo);
    }

    /**
     * LLM 응답 JSON 파싱 → Stage1ResultVO
     * @param aiResponse LLM 원문 응답
     * @return Stage1ResultVO (writingGuidelineJson, requirements, evalCriteria)
     */
    private ProposalVO.Stage1ResultVO parseStage1Response(String aiResponse) {
        String json = aiResponse.trim();

        // 코드블록 제거 (LLM이 간혹 ```json ... ``` 으로 감쌀 경우 대비)
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            if (firstNewline != -1) {
                json = json.substring(firstNewline + 1);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.lastIndexOf("```"));
            }
            json = json.trim();
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("LLM 응답이 유효한 JSON이 아닙니다: " + e.getMessage());
        }

        // writingGuideline 검증
        if (!root.has("writingGuideline") || root.get("writingGuideline").isJsonNull()) {
            throw new RuntimeException("LLM 응답에 'writingGuideline' 필드가 없습니다.");
        }
        JsonElement writingGuidelineEl = root.get("writingGuideline");
        String writingGuidelineJson = GSON.toJson(writingGuidelineEl);

        // requirements 검증
        if (!root.has("requirements") || root.get("requirements").isJsonNull()) {
            throw new RuntimeException("LLM 응답에 'requirements' 필드가 없습니다.");
        }
        JsonArray requirementsArr = root.getAsJsonArray("requirements");

        // evalCriteria 검증
        if (!root.has("evalCriteria") || root.get("evalCriteria").isJsonNull()) {
            throw new RuntimeException("LLM 응답에 'evalCriteria' 필드가 없습니다.");
        }
        JsonArray evalCriteriaArr = root.getAsJsonArray("evalCriteria");

        // RequirementVO 변환
        java.util.List<ProposalVO.RequirementVO> requirements = new java.util.ArrayList<>();
        for (JsonElement el : requirementsArr) {
            JsonObject obj = el.getAsJsonObject();
            ProposalVO.RequirementVO req = new ProposalVO.RequirementVO();
            req.setReqNo(getStrOrNull(obj, "reqNo"));
            req.setReqCategoryCd(getStrOrNull(obj, "reqCategoryCd"));
            req.setReqContent(getStrOrNull(obj, "reqContent"));
            if (CommonUtil.isEmpty(req.getReqContent())) {
                throw new RuntimeException("requirements 항목에 reqContent 필드가 누락되었습니다.");
            }
            req.setMandatoryYn(getStrOrNull(obj, "mandatoryYn"));
            req.setSourceTypeCd(getStrOrNull(obj, "sourceTypeCd"));
            req.setRfpPageRef(getStrOrNull(obj, "rfpPageRef"));
            req.setEvalImpact(getStrOrNull(obj, "evalImpact"));
            req.setResponseDirection(getStrOrNull(obj, "responseDirection"));
            req.setRequiredEvidence(getStrOrNull(obj, "requiredEvidence"));
            req.setConfirmNeededYn(getStrOrNull(obj, "confirmNeededYn"));
            req.setConfirmNeededNote(getStrOrNull(obj, "confirmNeededNote"));
            if (obj.has("sortOrd") && !obj.get("sortOrd").isJsonNull()) {
                req.setSortOrd(obj.get("sortOrd").getAsInt());
            }
            requirements.add(req);
        }

        // EvalCriteriaVO 변환
        java.util.List<ProposalVO.EvalCriteriaVO> evalCriteria = new java.util.ArrayList<>();
        for (JsonElement el : evalCriteriaArr) {
            JsonObject obj = el.getAsJsonObject();
            ProposalVO.EvalCriteriaVO ec = new ProposalVO.EvalCriteriaVO();
            ec.setEvalItemNm(getStrOrNull(obj, "evalItemNm"));
            if (CommonUtil.isEmpty(ec.getEvalItemNm())) {
                throw new RuntimeException("evalCriteria 항목에 evalItemNm 필드가 누락되었습니다.");
            }
            if (obj.has("score") && !obj.get("score").isJsonNull()) {
                ec.setScore(obj.get("score").getAsDouble());
            }
            ec.setEvalIntent(getStrOrNull(obj, "evalIntent"));
            ec.setHighScoreCondition(getStrOrNull(obj, "highScoreCondition"));
            ec.setRequiredEvidence(getStrOrNull(obj, "requiredEvidence"));
            ec.setDifferentiationDirection(getStrOrNull(obj, "differentiationDirection"));
            ec.setSlideReflectPosition(getStrOrNull(obj, "slideReflectPosition"));
            if (obj.has("sortOrd") && !obj.get("sortOrd").isJsonNull()) {
                ec.setSortOrd(obj.get("sortOrd").getAsInt());
            }
            evalCriteria.add(ec);
        }

        ProposalVO.Stage1ResultVO result = new ProposalVO.Stage1ResultVO();
        result.setWritingGuidelineJson(writingGuidelineJson);
        result.setRequirements(requirements);
        result.setEvalCriteria(evalCriteria);

        return result;
    }

    /**
     * 배점 합계 검증 (합계 ≈ 100 이 아니면 경고 로그)
     */
    private void validateScoreSum(List<ProposalVO.EvalCriteriaVO> evalCriteria, String ptProjectId) {
        if (evalCriteria == null || evalCriteria.isEmpty()) return;
        double sum = 0;
        for (ProposalVO.EvalCriteriaVO ec : evalCriteria) {
            sum += ec.getScore();
        }
        if (Math.abs(sum - 100.0) > 5.0) {
            logger.warn("[PT Stage1] 배점 합계가 100점과 차이가 큽니다. sum={}, ptProjectId={}", sum, ptProjectId);
        }
    }

    /**
     * 작성지침 tocMandatoryYn=Y + mandatedToc 빈 배열 경고
     */
    private void validateWritingGuideline(String writingGuidelineJson, String ptProjectId) {
        if (CommonUtil.isEmpty(writingGuidelineJson)) return;
        try {
            JsonObject wg = JsonParser.parseString(writingGuidelineJson).getAsJsonObject();
            String tocMandatoryYn = getStrOrNull(wg, "tocMandatoryYn");
            if ("Y".equals(tocMandatoryYn)) {
                if (wg.has("mandatedToc") && wg.get("mandatedToc").isJsonArray()) {
                    JsonArray toc = wg.getAsJsonArray("mandatedToc");
                    if (toc.size() == 0) {
                        logger.warn("[PT Stage1] tocMandatoryYn=Y 이지만 mandatedToc가 빈 배열입니다. ptProjectId={}", ptProjectId);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("[PT Stage1] writingGuideline 검증 중 오류: {}", e.getMessage());
        }
    }

    /**
     * JsonObject에서 String 값을 안전하게 꺼내는 헬퍼
     */
    private String getStrOrNull(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    /**
     * DB에 사용할 최소 기본 프롬프트
     */
    private String buildDefaultStage1Prompt() {
        return "RFP 원문을 분석하여 writingGuideline, requirements, evalCriteria 를 포함하는 JSON을 반환하세요. "
                + "다른 설명 없이 JSON만 출력하세요. 코드블록(```)도 포함하지 마세요.";
    }

    // ── Step C: 제안 설정 ────────────────────────────────────────────────────────

    /** 허용 문체 코드 */
    private static final java.util.Set<String> VALID_WRITING_STYLES =
            new java.util.HashSet<>(java.util.Arrays.asList("formal", "plain", "persuasive"));

    /** 기본 기본색조 */
    private static final List<String> DEFAULT_BASE_COLORS =
            java.util.Arrays.asList("#5B4FE9", "#8B7FFF", "#EFECFE");

    /** 기본 강조색조 */
    private static final List<String> DEFAULT_ACCENT_COLORS =
            java.util.Arrays.asList("#E08A2C", "#22A06B");

    /** hex 컬러 코드 정규식 (#RGB 또는 #RRGGBB) */
    private static final java.util.regex.Pattern HEX_COLOR_PATTERN =
            java.util.regex.Pattern.compile("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})$");

    /**
     * Step C: 제안 설정 조회
     * PROJECT_CONFIG_JSON.settings + TARGET_TYPE_CD + 파일 메타데이터를 조합해 반환.
     * 설정이 없으면 기본값 반환.
     */
    public ProposalVO.ProjectSettingsResponseVO selectProjectSettings(String ptProjectId) throws Exception {
        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);

        ProposalVO.ProjectSettingsResponseVO result = new ProposalVO.ProjectSettingsResponseVO();
        result.setPtProjectId(ptProjectId);
        result.setTargetTypeCd(project.getTargetTypeCd());
        result.setWritingStyle("formal");
        result.setBaseColors(DEFAULT_BASE_COLORS);
        result.setAccentColors(DEFAULT_ACCENT_COLORS);
        result.setCompanyFiles(java.util.Collections.emptyList());
        result.setCompetitorFiles(java.util.Collections.emptyList());
        result.setEtcRefFiles(java.util.Collections.emptyList());

        String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);
        if (CommonUtil.isEmpty(configJson)) return result;

        JsonObject root;
        try {
            root = JsonParser.parseString(configJson).getAsJsonObject();
        } catch (Exception e) {
            logger.warn("[PT StepC] PROJECT_CONFIG_JSON 파싱 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
            return result;
        }

        if (!root.has("settings") || root.get("settings").isJsonNull()) return result;
        JsonObject settings = root.getAsJsonObject("settings");

        // 문체
        String ws = getStrOrNull(settings, "writingStyle");
        if (CommonUtil.isNotEmpty(ws)) result.setWritingStyle(ws);

        // 컬러
        if (settings.has("colors") && !settings.get("colors").isJsonNull()) {
            JsonObject colors = settings.getAsJsonObject("colors");
            if (colors.has("base") && !colors.get("base").isJsonNull()) {
                result.setBaseColors(jsonArrayToList(colors.getAsJsonArray("base")));
            }
            if (colors.has("accent") && !colors.get("accent").isJsonNull()) {
                result.setAccentColors(jsonArrayToList(colors.getAsJsonArray("accent")));
            }
        }

        // 파일 목록 (fileId별 메타데이터 조회)
        result.setCompanyFiles(fetchFileListFromIds(settings, "companyFileIds"));
        result.setCompetitorFiles(fetchFileListFromIds(settings, "competitorFileIds"));
        result.setEtcRefFiles(fetchFileListFromIds(settings, "etcRefFileIds"));

        return result;
    }

    /** JsonArray → List<String> */
    private List<String> jsonArrayToList(JsonArray arr) {
        List<String> list = new java.util.ArrayList<>();
        for (JsonElement el : arr) { if (!el.isJsonNull()) list.add(el.getAsString()); }
        return list;
    }

    /** settings JSON의 fileIds 배열에서 파일 메타데이터를 조회해 반환 */
    private List<ProposalVO.PtFileVO> fetchFileListFromIds(JsonObject settings, String key) {
        if (!settings.has(key) || settings.get(key).isJsonNull()) return java.util.Collections.emptyList();
        List<ProposalVO.PtFileVO> list = new java.util.ArrayList<>();
        for (JsonElement el : settings.getAsJsonArray(key)) {
            if (el.isJsonNull()) continue;
            ProposalVO.PtFileVO f = proposalDAO.selectPtFileById(el.getAsString());
            if (f != null) list.add(f);
        }
        return list;
    }

    /**
     * Step C: 제안 설정 저장
     * PROJECT_CONFIG_JSON.settings 키만 merge update (template 값 보존)
     *
     * @throws RuntimeException 입력값 검증 실패 (파일 미존재·용도코드 불일치·색상 오류)
     */
    public void updateProjectSettings(ProposalVO.ProjectSettingsVO vo) throws Exception {
        // 1. 문체 검증
        if (CommonUtil.isNotEmpty(vo.getWritingStyle()) && !VALID_WRITING_STYLES.contains(vo.getWritingStyle())) {
            throw new RuntimeException("writingStyle은 'formal', 'plain', 'persuasive' 중 하나여야 합니다.");
        }

        // 2. 컬러 개수 + hex 형식 검증
        validateColorList(vo.getBaseColors(), 3, "baseColors");
        validateColorList(vo.getAccentColors(), 2, "accentColors");

        // 3. 파일 ID 검증 (존재 여부 + 용도코드 일치)
        validateFileIds(vo.getCompanyFileIds(), "005", "companyFileIds");
        validateFileIds(vo.getCompetitorFileIds(), "006", "competitorFileIds");
        validateFileIds(vo.getEtcRefFileIds(), "004", "etcRefFileIds");

        // 4. PROJECT_CONFIG_JSON merge — settings 키만 교체, template 등 보존
        String existingConfigJson = proposalDAO.selectProjectConfigJson(vo.getPtProjectId());
        JsonObject root;
        if (CommonUtil.isNotEmpty(existingConfigJson)) {
            try {
                root = JsonParser.parseString(existingConfigJson).getAsJsonObject();
            } catch (Exception e) {
                logger.warn("[PT StepC] PROJECT_CONFIG_JSON 파싱 실패, 빈 객체로 재시작 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage());
                root = new JsonObject();
            }
        } else {
            root = new JsonObject();
        }

        // settings 객체 구성
        JsonObject settingsObj = new JsonObject();
        settingsObj.add("companyFileIds", listToJsonArray(vo.getCompanyFileIds()));
        settingsObj.add("competitorFileIds", listToJsonArray(vo.getCompetitorFileIds()));
        settingsObj.add("etcRefFileIds", listToJsonArray(vo.getEtcRefFileIds()));
        settingsObj.addProperty("writingStyle",
                CommonUtil.isNotEmpty(vo.getWritingStyle()) ? vo.getWritingStyle() : "formal");

        JsonObject colorsObj = new JsonObject();
        colorsObj.add("base", listToJsonArray(
                (vo.getBaseColors() != null && !vo.getBaseColors().isEmpty()) ? vo.getBaseColors() : DEFAULT_BASE_COLORS));
        colorsObj.add("accent", listToJsonArray(
                (vo.getAccentColors() != null && !vo.getAccentColors().isEmpty()) ? vo.getAccentColors() : DEFAULT_ACCENT_COLORS));
        settingsObj.add("colors", colorsObj);

        root.add("settings", settingsObj);

        ProposalVO.ProjectVO updateVO = new ProposalVO.ProjectVO();
        updateVO.setPtProjectId(vo.getPtProjectId());
        updateVO.setProjectConfigJson(GSON.toJson(root));
        proposalDAO.updateProjectConfigJson(updateVO);

        logger.info("[PT StepC] 설정 저장 완료 (ptProjectId={}, writingStyle={})", vo.getPtProjectId(), vo.getWritingStyle());
    }

    /** colors 개수 + hex 형식 검증 */
    private void validateColorList(List<String> colors, int expectedCount, String fieldName) {
        if (colors == null || colors.isEmpty()) return; // 미입력 시 기본값 사용 — 허용
        if (colors.size() != expectedCount) {
            throw new RuntimeException(fieldName + "은 정확히 " + expectedCount + "개여야 합니다. (현재 " + colors.size() + "개)");
        }
        for (String hex : colors) {
            if (CommonUtil.isEmpty(hex) || !HEX_COLOR_PATTERN.matcher(hex).matches()) {
                throw new RuntimeException(fieldName + "에 유효하지 않은 hex 색상 코드가 포함되어 있습니다: " + hex);
            }
        }
    }

    /** 파일 ID 목록 검증 — 존재 여부 + 용도코드 일치 확인 */
    private void validateFileIds(List<String> fileIds, String expectedPurposeCd, String fieldName) {
        if (fileIds == null || fileIds.isEmpty()) return;
        for (String ptFileId : fileIds) {
            ProposalVO.PtFileVO file = proposalDAO.selectPtFileById(ptFileId);
            if (file == null) {
                throw new RuntimeException(fieldName + "에 존재하지 않는 파일 ID가 포함되어 있습니다: " + ptFileId);
            }
            if (!expectedPurposeCd.equals(file.getFilePurposeCd())) {
                throw new RuntimeException(fieldName + "의 파일 용도코드가 일치하지 않습니다. "
                        + "기대=" + expectedPurposeCd + ", 실제=" + file.getFilePurposeCd() + " (ptFileId=" + ptFileId + ")");
            }
        }
    }

    /** List<String> → JsonArray */
    private JsonArray listToJsonArray(List<String> list) {
        JsonArray arr = new JsonArray();
        if (list != null) { for (String s : list) arr.add(s); }
        return arr;
    }

    /**
     * Step C: 제안 대상(TARGET_TYPE_CD) 업데이트
     * @param vo ptProjectId, targetTypeCd(G 또는 P)
     */
    public void updateProjectTargetType(ProposalVO.TargetTypeVO vo) throws Exception {
        if (!"G".equals(vo.getTargetTypeCd()) && !"P".equals(vo.getTargetTypeCd())) {
            throw new RuntimeException("targetTypeCd는 'G'(공공) 또는 'P'(민간)이어야 합니다.");
        }
        proposalDAO.updateProjectTargetType(vo);
        logger.info("[PT StepC] targetTypeCd 업데이트 (ptProjectId={}, targetTypeCd={})", vo.getPtProjectId(), vo.getTargetTypeCd());
    }

    /**
     * PT 프로젝트 단건 조회 (상세 페이지 진입 시 호출)
     */
    public ProposalVO.ProjectVO selectPtProject(String ptProjectId) throws Exception {
        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);
        return project;
    }

    // ── Step B: TOC(목차) CRUD ───────────────────────────────────────────────────

    /**
     * 프로젝트의 용도별 파일 단건 조회 (최근 등록 기준)
     * @param filePurposeCd 001=RFP원문, 002=평가표, 003=템플릿, 004=기타참고, 005=자사정보, 006=경쟁사정보
     */
    public ProposalVO.PtFileVO selectPtRfpFile(String ptProjectId, String filePurposeCd) {
        List<ProposalVO.PtFileVO> files = proposalDAO.selectPtFileByPurpose(ptProjectId, filePurposeCd);
        return (files != null && !files.isEmpty()) ? files.get(0) : null;
    }

    /**
     * Step B 자동추출: WRITING_GUIDELINE_JSON.mandatedToc → TB_PT_TOC
     * LLM 호출 없음. mandatedToc 배열을 2-pass로 insert (main → sub).
     *
     * @return 삽입된 TocVO 목록. tocMandatoryYn='N'이거나 mandatedToc가 비어 있으면 빈 리스트 반환.
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ProposalVO.TocVO> autoExtractToc(String ptProjectId) throws Exception {
        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);

        String guidelineJson = project.getWritingGuidelineJson();
        if (CommonUtil.isEmpty(guidelineJson)) return java.util.Collections.emptyList();

        JsonObject guideline;
        try {
            guideline = JsonParser.parseString(guidelineJson).getAsJsonObject();
        } catch (Exception e) {
            logger.warn("[PT StepB] writingGuidelineJson 파싱 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
            return java.util.Collections.emptyList();
        }

        // tocMandatoryYn 값과 무관하게 mandatedToc가 있으면 반환 (사용자 명시적 요청이므로)
        if (!guideline.has("mandatedToc") || guideline.get("mandatedToc").isJsonNull()) {
            return java.util.Collections.emptyList();
        }

        JsonArray mandatedArr = guideline.getAsJsonArray("mandatedToc");
        if (mandatedArr.size() == 0) return java.util.Collections.emptyList();

        // 기존 TOC 초기화
        proposalDAO.deleteTocByProject(ptProjectId);

        String userId = SessionUtil.getUserId();
        List<ProposalVO.TocVO> result = new java.util.ArrayList<>();

        // Step 1: 대목차만 선 스캔 → no → 메타데이터 맵 구성 (ID는 생성하지 않음)
        //         generateTableKey는 MAX(TOC_ID) 기반이므로 INSERT 직전에 호출해야 중복을 피할 수 있음
        java.util.Map<String, ProposalVO.TocVO> mainDataByNo = new java.util.LinkedHashMap<>();
        for (JsonElement el : mandatedArr) {
            JsonObject obj = el.getAsJsonObject();
            String level = getStrOrNull(obj, "level");
            if (!"main".equals(level) && !"1".equals(level)) continue;
            ProposalVO.TocVO toc = new ProposalVO.TocVO();
            toc.setPtProjectId(ptProjectId);
            String no = getStrOrNull(obj, "no");
            toc.setNo(no);
            toc.setSectionNo(no);
            toc.setSectionNm(getStrOrNull(obj, "title"));
            toc.setPlannedSlideCnt(1);
            toc.setCreateUserId(userId);
            if (CommonUtil.isNotEmpty(no)) {
                mainDataByNo.put(no, toc);
            }
        }

        // Step 2: mandatedToc 원본 순서대로 전체 처리
        //         대목차 tocId는 INSERT 직전 생성 → noToTocId에 저장 → 소목차 parentTocId 참조에 사용
        java.util.Map<String, String> noToTocId = new java.util.LinkedHashMap<>();
        int sortOrd = 0;
        for (JsonElement el : mandatedArr) {
            JsonObject obj = el.getAsJsonObject();
            String level = getStrOrNull(obj, "level");
            String no = getStrOrNull(obj, "no");

            if ("main".equals(level) || "1".equals(level)) {
                ProposalVO.TocVO toc = mainDataByNo.get(no);
                if (toc == null) continue;
                // INSERT 직전에 ID 생성 → selectMaxId가 이전 INSERT를 반영하여 고유값 보장
                toc.setTocId(keyGenerate.generateTableKey("PTT", "TB_PT_TOC", "TOC_ID", 6));
                toc.setSortOrd(sortOrd++);
                noToTocId.put(no, toc.getTocId());
                proposalDAO.insertToc(toc);
                result.add(toc);
            } else if ("sub".equals(level) || "2".equals(level)) {
                String parentNo = getStrOrNull(obj, "parentNo");
                String parentTocId = CommonUtil.isNotEmpty(parentNo) ? noToTocId.get(parentNo) : null;
                // buildTocVO 내부의 generateTableKey도 직전 INSERT 이후 호출되므로 고유값 보장
                ProposalVO.TocVO toc = buildTocVO(ptProjectId, parentTocId, obj, sortOrd++, userId);
                proposalDAO.insertToc(toc);
                result.add(toc);
            }
        }

        logger.info("[PT StepB] autoExtractToc 완료 (ptProjectId={}, count={})", ptProjectId, result.size());
        return result;
    }

    /** autoExtractToc 내부 헬퍼 — JsonObject → TocVO 변환 */
    private ProposalVO.TocVO buildTocVO(String ptProjectId, String parentTocId, JsonObject obj, int sortOrd, String userId) throws Exception {
        ProposalVO.TocVO toc = new ProposalVO.TocVO();
        toc.setTocId(keyGenerate.generateTableKey("PTT", "TB_PT_TOC", "TOC_ID", 6));
        toc.setPtProjectId(ptProjectId);
        toc.setParentTocId(parentTocId);
        String no = getStrOrNull(obj, "no");
        toc.setNo(no);
        toc.setSectionNo(no);
        toc.setSectionNm(getStrOrNull(obj, "title"));
        toc.setPlannedSlideCnt(1);
        toc.setSortOrd(sortOrd);
        toc.setCreateUserId(userId);
        return toc;
    }

    /**
     * Step B: TOC 목록 조회 (flat list, SORT_ORD 기준)
     */
    public List<ProposalVO.TocVO> selectTocListByProject(String ptProjectId) throws Exception {
        return proposalDAO.selectTocList(ptProjectId);
    }

    /**
     * Step B: TOC 항목 단건 추가
     * @param vo ptProjectId, parentTocId(null=대목차), sectionNm
     * @return 생성된 TocVO (tocId 포함)
     */
    public ProposalVO.TocVO insertTocItem(ProposalVO.TocVO vo) throws Exception {
        if (CommonUtil.isEmpty(vo.getSectionNm())) {
            vo.setSectionNm(CommonUtil.isEmpty(vo.getParentTocId()) ? "새 대목차" : "새 소목차");
        }
        vo.setTocId(keyGenerate.generateTableKey("PTT-", "TB_PT_TOC", "TOC_ID", 6));
        vo.setCreateUserId(SessionUtil.getUserId());
        if (vo.getPlannedSlideCnt() == 0) vo.setPlannedSlideCnt(1);
        // 형제 항목 개수를 sortOrd 기본값으로 사용
        if (vo.getSortOrd() == null) {
            List<ProposalVO.TocVO> siblings = proposalDAO.selectTocList(vo.getPtProjectId());
            vo.setSortOrd(siblings.size());
        }
        proposalDAO.insertToc(vo);
        return vo;
    }

    /**
     * Step B: TOC 항목 제목 수정 (단건)
     * @param vo tocId, sectionNm
     */
    public void updateTocItem(ProposalVO.TocVO vo) throws Exception {
        proposalDAO.updateTocItem(vo);
    }

    /**
     * Step B: TOC 항목 삭제 (소목차 연쇄 포함)
     * @param tocId TOC_ID
     */
    public void deleteTocItem(String tocId) throws Exception {
        proposalDAO.deleteTocItem(tocId);
    }

    /**
     * Step B: TOC 순서 일괄 업데이트
     * @param vo ptProjectId, items(tocId + sortOrd 목록)
     */
    public void reorderTocItems(ProposalVO.TocReorderVO vo) throws Exception {
        if (vo.getItems() == null || vo.getItems().isEmpty()) return;
        for (ProposalVO.TocVO item : vo.getItems()) {
            proposalDAO.updateTocSortOrd(item);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Stage 2: 전략 분석 (문제정의 + Win Theme + 목차)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Stage 2 실행: 전략 분석 (문제정의 + Win Theme + 목차)
     * @param ptProjectId      프로젝트 ID
     * @param totalSlideBudget 목표 슬라이드 수
     * @param modelId          사용할 LLM 모델 ID
     * @param agentId          에이전트 ID
     * @return Stage2ResultVO
     * @throws Exception
     */
    public ProposalVO.Stage2ResultVO executeStage2(String ptProjectId, int totalSlideBudget, String modelId, String agentId) throws Exception {

        // 1. Stage 1 결과 로드
        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);

        List<ProposalVO.RequirementVO> requirements = proposalDAO.selectRequirements(ptProjectId);
        List<ProposalVO.EvalCriteriaVO> evalCriteria = proposalDAO.selectEvalCriteria(ptProjectId);

        // 2. PROJECT_CONFIG_JSON.settings에서 파일 ID 목록 읽기
        List<String> companyFileIds   = java.util.Collections.emptyList();
        List<String> competitorFileIds = java.util.Collections.emptyList();
        List<String> etcRefFileIds    = java.util.Collections.emptyList();
        try {
            String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);
            if (CommonUtil.isNotEmpty(configJson)) {
                JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
                if (root.has("settings") && !root.get("settings").isJsonNull()) {
                    JsonObject settings = root.getAsJsonObject("settings");
                    if (settings.has("companyFileIds")    && !settings.get("companyFileIds").isJsonNull())
                        companyFileIds   = jsonArrayToList(settings.getAsJsonArray("companyFileIds"));
                    if (settings.has("competitorFileIds") && !settings.get("competitorFileIds").isJsonNull())
                        competitorFileIds = jsonArrayToList(settings.getAsJsonArray("competitorFileIds"));
                    if (settings.has("etcRefFileIds")     && !settings.get("etcRefFileIds").isJsonNull())
                        etcRefFileIds    = jsonArrayToList(settings.getAsJsonArray("etcRefFileIds"));
                }
            }
        } catch (Exception e) {
            logger.warn("[PT Stage2] 설정 파일 ID 파싱 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
        }

        // 3. 자사 파일 텍스트 추출
        String ownContext = extractMultiFileText(companyFileIds);
        if (CommonUtil.isEmpty(ownContext)) ownContext = "(자사 자료 없음)";

        // 4. 경쟁사 파일 텍스트 추출
        String competitorContext = extractMultiFileText(competitorFileIds);
        if (CommonUtil.isEmpty(competitorContext)) competitorContext = "(경쟁사 자료 없음)";

        // 5. 기타 참고자료 파일 텍스트 추출
        String etcRefContext = extractMultiFileText(etcRefFileIds);
        if (CommonUtil.isEmpty(etcRefContext)) etcRefContext = "(기타 참고자료 없음)";

        // 6. Stage 2 프롬프트 로드
        String promptContent = null;
        try { promptContent = promptService.getPrompt("PI000022", null); } catch (Exception e) { logger.warn("PI000022 프롬프트 조회 실패: {}", e.getMessage()); }
        if (CommonUtil.isEmpty(promptContent)) promptContent = buildDefaultStage2Prompt();

        // 7. 전체 프롬프트 조합
        String fullPrompt = buildStage2FullPrompt(promptContent, project, requirements, evalCriteria, ownContext, competitorContext, etcRefContext, totalSlideBudget);

        // 8. LLM 호출 (1회 재시도)
        String aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
        if (CommonUtil.isEmpty(aiResponse)) {
            logger.warn("[PT Stage2] LLM 응답 없음, 1회 재시도 (ptProjectId={})", ptProjectId);
            aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
        }
        if (CommonUtil.isEmpty(aiResponse)) throw new RuntimeException("LLM 응답이 비어 있습니다. Stage 2 분석을 완료할 수 없습니다.");

        // 9. JSON 파싱
        ProposalVO.Stage2ResultVO parsed = parseStage2Response(aiResponse);

        // 10. evidence 품질 경고
        validateStage2Evidence(parsed.getWinThemes(), ptProjectId);

        // 11. coveredReqNos 검증 (존재하지 않는 reqNo 무시)
        java.util.Set<String> validReqNos = new java.util.HashSet<>();
        if (requirements != null) for (ProposalVO.RequirementVO r : requirements) if (CommonUtil.isNotEmpty(r.getReqNo())) validReqNos.add(r.getReqNo());
        validateAndCleanTocReqNos(parsed.getToc(), validReqNos, ptProjectId);

        // 12. mandatedToc 강제 적용 (tocMandatoryYn=Y 면 LLM 결과를 원본으로 덮어씀)
        applyMandatedTocIfNeeded(project.getWritingGuidelineJson(), parsed);

        // 13. 미커버 요구사항 경고
        warnUncoveredRequirements(parsed.getToc(), validReqNos, ptProjectId);

        // 14. 슬라이드 수 Java 계산
        calculateSlideCounts(parsed.getToc(), evalCriteria, totalSlideBudget);

        // 15. 트랜잭션 저장
        saveStage2Result(ptProjectId, parsed, evalCriteria);

        parsed.setPtProjectId(ptProjectId);
        return parsed;
    }

    /**
     * Stage 2 결과 DB 쓰기 — 트랜잭션 분리
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveStage2Result(String ptProjectId, ProposalVO.Stage2ResultVO parsed, List<ProposalVO.EvalCriteriaVO> evalCriteriaFromDb) throws Exception {
        String userId = SessionUtil.getUserId();

        // 문제 정의 초기화 + 재등록
        proposalDAO.deleteProblemDefinitionsByProject(ptProjectId);
        if (parsed.getProblemDefinitions() != null) {
            int ord = 0;
            for (ProposalVO.ProblemDefinitionVO pd : parsed.getProblemDefinitions()) {
                pd.setProblemId(keyGenerate.generateTableKey("PTP-", "TB_PT_PROBLEM_DEFINITION", "PROBLEM_ID", 6));
                pd.setPtProjectId(ptProjectId);
                pd.setCreateUserId(userId);
                if (pd.getSortOrd() == null) pd.setSortOrd(ord++);
                if (CommonUtil.isEmpty(pd.getSourceTypeCd())) pd.setSourceTypeCd("002");
                proposalDAO.insertProblemDefinition(pd);
            }
        }

        // Win Theme 초기화 + 재등록
        proposalDAO.deleteWinThemesByProject(ptProjectId);
        if (parsed.getWinThemes() != null) {
            int ord = 0;
            for (ProposalVO.WinThemeVO wt : parsed.getWinThemes()) {
                wt.setWinThemeId(keyGenerate.generateTableKey("PTW-", "TB_PT_WIN_THEME", "WIN_THEME_ID", 6));
                wt.setPtProjectId(ptProjectId);
                wt.setCreateUserId(userId);
                if (wt.getSortOrd() == null) wt.setSortOrd(ord++);
                proposalDAO.insertWinTheme(wt);
            }
        }

        // TOC 초기화 + 재등록 (level 1 먼저, 그 다음 level 2)
        proposalDAO.deleteTocByProject(ptProjectId);
        java.util.Map<String, String> noToTocId = new java.util.HashMap<>(); // no → tocId (부모 참조용)
        if (parsed.getToc() != null) {
            // level 1 먼저
            int ord = 0;
            for (ProposalVO.TocVO toc : parsed.getToc()) {
                if (toc.getLevel() != 1) continue;
                String tocId = keyGenerate.generateTableKey("PTT-", "TB_PT_TOC", "TOC_ID", 6);
                toc.setTocId(tocId);
                if (CommonUtil.isNotEmpty(toc.getNo())) noToTocId.put(toc.getNo(), tocId);
                toc.setPtProjectId(ptProjectId);
                toc.setParentTocId(null);
                toc.setCreateUserId(userId);
                if (toc.getSortOrd() == null) toc.setSortOrd(ord++);
                proposalDAO.insertToc(toc);
            }
            // level 2
            for (ProposalVO.TocVO toc : parsed.getToc()) {
                if (toc.getLevel() != 2) continue;
                String tocId = keyGenerate.generateTableKey("PTT-", "TB_PT_TOC", "TOC_ID", 6);
                toc.setTocId(tocId);
                toc.setPtProjectId(ptProjectId);
                toc.setParentTocId(noToTocId.get(toc.getParentNo()));
                toc.setCreateUserId(userId);
                if (toc.getSortOrd() == null) toc.setSortOrd(ord++);
                proposalDAO.insertToc(toc);
            }
        }

        // 평가기준 SLIDE_REFLECT_POSITION 업데이트
        if (parsed.getToc() != null && evalCriteriaFromDb != null) {
            // evalItemNm → evalCriteriaId 맵
            java.util.Map<String, String> evalNmToId = new java.util.HashMap<>();
            for (ProposalVO.EvalCriteriaVO ec : evalCriteriaFromDb) {
                if (CommonUtil.isNotEmpty(ec.getEvalItemNm())) evalNmToId.put(ec.getEvalItemNm(), ec.getEvalCriteriaId());
            }
            // tocVO의 linkedEvalCriteriaNm으로 slideReflectPosition 결정
            java.util.Map<String, String> evalIdToPosition = new java.util.HashMap<>();
            for (ProposalVO.TocVO toc : parsed.getToc()) {
                if (CommonUtil.isEmpty(toc.getLinkedEvalCriteriaNm())) continue;
                String ecId = evalNmToId.get(toc.getLinkedEvalCriteriaNm());
                if (ecId == null) continue;
                String position = (toc.getSectionNo() != null ? toc.getSectionNo() : "") + " " + (toc.getSectionNm() != null ? toc.getSectionNm() : "");
                evalIdToPosition.merge(ecId, position.trim(), (a, b) -> a + ", " + b);
            }
            for (java.util.Map.Entry<String, String> e : evalIdToPosition.entrySet()) {
                ProposalVO.EvalCriteriaVO upd = new ProposalVO.EvalCriteriaVO();
                upd.setEvalCriteriaId(e.getKey());
                upd.setSlideReflectPosition(e.getValue());
                proposalDAO.updateEvalCriteriaSlideReflectPosition(upd);
            }
            // 매칭 안 된 evalCriteria 경고
            for (ProposalVO.EvalCriteriaVO ec : evalCriteriaFromDb) {
                if (!evalIdToPosition.containsKey(ec.getEvalCriteriaId())) {
                    logger.warn("[PT Stage2] 평가항목 SLIDE_REFLECT_POSITION 미매칭: evalItemNm={}, ptProjectId={}", ec.getEvalItemNm(), ptProjectId);
                }
            }
        }

        // 프로젝트 상태 업데이트
        ProposalVO.ProjectVO statusVO = new ProposalVO.ProjectVO();
        statusVO.setPtProjectId(ptProjectId);
        statusVO.setStatusCd("003"); // 003=완료(Stage 2까지)
        proposalDAO.updateProjectStatus(statusVO);
    }

    /**
     * Stage 2 저장 결과 조회
     * @param ptProjectId 프로젝트 ID
     * @return Stage2ResultVO (계층 구조 TOC 포함)
     * @throws Exception
     */
    public ProposalVO.Stage2ResultVO selectStage2Result(String ptProjectId) throws Exception {
        List<ProposalVO.ProblemDefinitionVO> problemDefs = proposalDAO.selectProblemDefinitions(ptProjectId);
        List<ProposalVO.WinThemeVO> winThemes = proposalDAO.selectWinThemes(ptProjectId);
        List<ProposalVO.TocVO> flatToc = proposalDAO.selectTocList(ptProjectId);

        // flat list → 계층 구조 (parentTocId 기준)
        java.util.Map<String, ProposalVO.TocVO> tocMap = new java.util.LinkedHashMap<>();
        for (ProposalVO.TocVO t : flatToc) tocMap.put(t.getTocId(), t);
        List<ProposalVO.TocVO> rootToc = new java.util.ArrayList<>();
        for (ProposalVO.TocVO t : flatToc) {
            t.setChildren(new java.util.ArrayList<>());
            if (CommonUtil.isEmpty(t.getParentTocId())) { rootToc.add(t); }
            else {
                ProposalVO.TocVO parent = tocMap.get(t.getParentTocId());
                if (parent != null) parent.getChildren().add(t);
                else rootToc.add(t);
            }
        }

        ProposalVO.Stage2ResultVO result = new ProposalVO.Stage2ResultVO();
        result.setPtProjectId(ptProjectId);
        result.setProblemDefinitions(problemDefs);
        result.setWinThemes(winThemes);
        result.setToc(rootToc);
        return result;
    }

    // ── Stage 2 private helper 메서드들 ────────────────────────────────────────

    /**
     * RAG 검색 쿼리 구성
     * - requirements의 reqNo 접두사(SFR, INR, PMR 등) 추출, 카테고리별로 그룹핑
     * - 평가항목 중 배점 상위 항목 포함
     * - 최대 4개 쿼리 반환
     */
    private List<String> buildRagSearchQueries(String projectNm, List<ProposalVO.RequirementVO> requirements, List<ProposalVO.EvalCriteriaVO> evalCriteria) {
        List<String> queries = new java.util.ArrayList<>();

        // reqNo 접두사 추출 (중복 제거, 최대 3개 카테고리)
        java.util.LinkedHashSet<String> prefixSet = new java.util.LinkedHashSet<>();
        if (requirements != null) {
            for (ProposalVO.RequirementVO r : requirements) {
                if (CommonUtil.isNotEmpty(r.getReqNo())) {
                    String prefix = r.getReqNo().replaceAll("[^A-Za-z가-힣].*", "").trim();
                    if (CommonUtil.isNotEmpty(prefix)) prefixSet.add(prefix);
                }
                if (prefixSet.size() >= 3) break;
            }
        }
        for (String prefix : prefixSet) {
            queries.add(prefix + " 관련 자사 구축 실적과 기술역량");
        }

        // 배점 상위 평가항목으로 추가 쿼리
        if (evalCriteria != null && !evalCriteria.isEmpty() && queries.size() < 4) {
            ProposalVO.EvalCriteriaVO topEc = evalCriteria.stream()
                    .max(java.util.Comparator.comparingDouble(ProposalVO.EvalCriteriaVO::getScore))
                    .orElse(null);
            if (topEc != null && CommonUtil.isNotEmpty(topEc.getEvalItemNm())) {
                queries.add(topEc.getEvalItemNm() + " 관련 자사 수행 실적 및 차별화 역량");
            }
        }

        // 기본 쿼리 (아무것도 없을 경우 대비)
        if (queries.isEmpty()) {
            queries.add(projectNm + " 유사 사업 자사 구축 실적 및 기술역량");
        }

        return queries.subList(0, Math.min(queries.size(), 4));
    }

    /**
     * LLM 응답 JSON 파싱 → Stage2ResultVO
     * - 코드블록 제거 로직 포함
     * - problemDefinitions: problemTypeCd, currentProblem 필수
     * - winThemes: coreMessage 필수
     * - toc: level, no, title, parentNo(level2만), linkedEvalCriteriaNm, coveredReqNos 파싱
     */
    private ProposalVO.Stage2ResultVO parseStage2Response(String aiResponse) {
        String json = aiResponse.trim();

        // 코드블록 제거 (LLM이 ```json ... ``` 으로 감쌀 경우 대비)
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            if (firstNewline != -1) {
                json = json.substring(firstNewline + 1);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.lastIndexOf("```"));
            }
            json = json.trim();
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("LLM 응답이 유효한 JSON이 아닙니다 (Stage2): " + e.getMessage());
        }

        // ── problemDefinitions 파싱 ──
        List<ProposalVO.ProblemDefinitionVO> problemDefs = new java.util.ArrayList<>();
        if (root.has("problemDefinitions") && !root.get("problemDefinitions").isJsonNull()) {
            for (JsonElement el : root.getAsJsonArray("problemDefinitions")) {
                JsonObject obj = el.getAsJsonObject();
                ProposalVO.ProblemDefinitionVO pd = new ProposalVO.ProblemDefinitionVO();
                pd.setProblemTypeCd(getStrOrNull(obj, "problemTypeCd"));
                pd.setCurrentProblem(getStrOrNull(obj, "currentProblem"));
                if (CommonUtil.isEmpty(pd.getCurrentProblem())) {
                    logger.warn("[PT Stage2] problemDefinitions 항목에 currentProblem 누락, 건너뜀");
                    continue;
                }
                pd.setRootCause(getStrOrNull(obj, "rootCause"));
                pd.setRiskIfIgnored(getStrOrNull(obj, "riskIfIgnored"));
                pd.setGoal(getStrOrNull(obj, "goal"));
                pd.setRequiredCapability(getStrOrNull(obj, "requiredCapability"));
                pd.setStrategySummary(getStrOrNull(obj, "strategySummary"));
                pd.setKpi(getStrOrNull(obj, "kpi"));
                pd.setSourceTypeCd("002");
                if (obj.has("sortOrd") && !obj.get("sortOrd").isJsonNull()) {
                    pd.setSortOrd(obj.get("sortOrd").getAsInt());
                }
                problemDefs.add(pd);
            }
        }

        // ── winThemes 파싱 ──
        List<ProposalVO.WinThemeVO> winThemes = new java.util.ArrayList<>();
        if (root.has("winThemes") && !root.get("winThemes").isJsonNull()) {
            for (JsonElement el : root.getAsJsonArray("winThemes")) {
                JsonObject obj = el.getAsJsonObject();
                ProposalVO.WinThemeVO wt = new ProposalVO.WinThemeVO();
                wt.setCoreMessage(getStrOrNull(obj, "coreMessage"));
                if (CommonUtil.isEmpty(wt.getCoreMessage())) {
                    logger.warn("[PT Stage2] winThemes 항목에 coreMessage 누락, 건너뜀");
                    continue;
                }
                wt.setCustomerProblem(getStrOrNull(obj, "customerProblem"));
                wt.setProposalStrategy(getStrOrNull(obj, "proposalStrategy"));
                wt.setEvidence(getStrOrNull(obj, "evidence"));
                wt.setExpectedEffect(getStrOrNull(obj, "expectedEffect"));
                wt.setDifferentiation(getStrOrNull(obj, "differentiation"));
                if (obj.has("sortOrd") && !obj.get("sortOrd").isJsonNull()) {
                    wt.setSortOrd(obj.get("sortOrd").getAsInt());
                }
                winThemes.add(wt);
            }
        }

        // ── toc 파싱 (flat list) ──
        List<ProposalVO.TocVO> tocList = new java.util.ArrayList<>();
        if (root.has("toc") && !root.get("toc").isJsonNull()) {
            int globalOrd = 0;
            for (JsonElement el : root.getAsJsonArray("toc")) {
                JsonObject obj = el.getAsJsonObject();
                ProposalVO.TocVO toc = new ProposalVO.TocVO();
                // level
                int level = 1;
                if (obj.has("level") && !obj.get("level").isJsonNull()) {
                    level = obj.get("level").getAsInt();
                }
                toc.setLevel(level);
                // no → sectionNo
                String no = getStrOrNull(obj, "no");
                toc.setNo(no);
                toc.setSectionNo(no);
                // title → sectionNm
                String title = getStrOrNull(obj, "title");
                toc.setSectionNm(title);
                // parentNo (level 2만)
                if (level == 2) {
                    toc.setParentNo(getStrOrNull(obj, "parentNo"));
                }
                // linkedEvalCriteriaNm
                toc.setLinkedEvalCriteriaNm(getStrOrNull(obj, "linkedEvalCriteriaNm"));
                // coveredReqNos
                if (obj.has("coveredReqNos") && !obj.get("coveredReqNos").isJsonNull() && obj.get("coveredReqNos").isJsonArray()) {
                    List<String> reqNos = new java.util.ArrayList<>();
                    for (JsonElement rn : obj.getAsJsonArray("coveredReqNos")) {
                        if (!rn.isJsonNull()) reqNos.add(rn.getAsString());
                    }
                    toc.setCoveredReqNos(reqNos);
                }
                toc.setPlannedSlideCnt(1); // 기본값, calculateSlideCounts에서 재계산
                toc.setSortOrd(globalOrd++);
                tocList.add(toc);
            }
        }

        ProposalVO.Stage2ResultVO result = new ProposalVO.Stage2ResultVO();
        result.setProblemDefinitions(problemDefs);
        result.setWinThemes(winThemes);
        result.setToc(tocList);
        return result;
    }

    /**
     * 슬라이드 수 Java 계산
     * - leaf TOC (level 2) 기준으로 계산
     * - 연결된 evalCriteria 없는 leaf는 MIN_SLIDES=2 배분
     * - 나머지 예산을 evalCriteria 배점 비율로 배분 (각 최소 1장)
     * - 마지막 leaf에 나머지 흡수 (합계 = totalSlideBudget 보장)
     * - level 1의 plannedSlideCnt는 children 합계
     */
    private void calculateSlideCounts(List<ProposalVO.TocVO> tocList, List<ProposalVO.EvalCriteriaVO> evalCriteria, int totalSlideBudget) {
        if (tocList == null || tocList.isEmpty()) return;

        final int MIN_SLIDES = 2;

        // evalItemNm → score 맵
        java.util.Map<String, Double> evalNmToScore = new java.util.HashMap<>();
        if (evalCriteria != null) {
            for (ProposalVO.EvalCriteriaVO ec : evalCriteria) {
                if (CommonUtil.isNotEmpty(ec.getEvalItemNm())) {
                    evalNmToScore.put(ec.getEvalItemNm(), ec.getScore());
                }
            }
        }

        // level 2 (leaf) 목록
        List<ProposalVO.TocVO> leaves = new java.util.ArrayList<>();
        for (ProposalVO.TocVO toc : tocList) {
            if (toc.getLevel() == 2) leaves.add(toc);
        }

        // level 2가 없으면 level 1 전체를 leaf로 처리
        if (leaves.isEmpty()) {
            leaves.addAll(tocList);
        }

        if (leaves.isEmpty()) return;

        // 연결 없는 leaf에 MIN_SLIDES 배분
        int reservedBudget = 0;
        List<ProposalVO.TocVO> linkedLeaves = new java.util.ArrayList<>();
        for (ProposalVO.TocVO leaf : leaves) {
            if (CommonUtil.isEmpty(leaf.getLinkedEvalCriteriaNm()) || !evalNmToScore.containsKey(leaf.getLinkedEvalCriteriaNm())) {
                leaf.setPlannedSlideCnt(MIN_SLIDES);
                reservedBudget += MIN_SLIDES;
            } else {
                linkedLeaves.add(leaf);
            }
        }

        int remainBudget = totalSlideBudget - reservedBudget;
        if (remainBudget < linkedLeaves.size()) remainBudget = linkedLeaves.size(); // 최소 1장 보장

        if (!linkedLeaves.isEmpty()) {
            // 배점 합계
            double totalScore = 0;
            for (ProposalVO.TocVO leaf : linkedLeaves) {
                totalScore += evalNmToScore.getOrDefault(leaf.getLinkedEvalCriteriaNm(), 0.0);
            }
            if (totalScore <= 0) totalScore = linkedLeaves.size(); // 0 나눗셈 방지

            // 비례 배분 (각 최소 1장)
            int allocated = 0;
            for (int i = 0; i < linkedLeaves.size() - 1; i++) {
                ProposalVO.TocVO leaf = linkedLeaves.get(i);
                double score = evalNmToScore.getOrDefault(leaf.getLinkedEvalCriteriaNm(), 1.0);
                int cnt = Math.max(1, (int) Math.round((score / totalScore) * remainBudget));
                leaf.setPlannedSlideCnt(cnt);
                allocated += cnt;
            }
            // 마지막 leaf에 나머지 흡수 (합계 = totalSlideBudget 보장)
            ProposalVO.TocVO lastLeaf = linkedLeaves.get(linkedLeaves.size() - 1);
            int lastCnt = Math.max(1, remainBudget - allocated);
            lastLeaf.setPlannedSlideCnt(lastCnt);
        }

        // level 1의 plannedSlideCnt = children 합계 (no 기준)
        java.util.Map<String, Integer> noToSumMap = new java.util.HashMap<>();
        for (ProposalVO.TocVO toc : tocList) {
            if (toc.getLevel() == 2 && CommonUtil.isNotEmpty(toc.getParentNo())) {
                noToSumMap.merge(toc.getParentNo(), toc.getPlannedSlideCnt(), Integer::sum);
            }
        }
        for (ProposalVO.TocVO toc : tocList) {
            if (toc.getLevel() == 1 && CommonUtil.isNotEmpty(toc.getNo())) {
                Integer sum = noToSumMap.get(toc.getNo());
                if (sum != null) toc.setPlannedSlideCnt(sum);
            }
        }
    }

    /**
     * mandatedToc 강제 적용
     * - writingGuidelineJson에 tocMandatoryYn=Y + mandatedToc 있으면
     * - parsed.toc의 sectionNo/sectionNm을 mandatedToc 원본으로 덮어씀
     */
    private void applyMandatedTocIfNeeded(String writingGuidelineJson, ProposalVO.Stage2ResultVO parsed) {
        if (CommonUtil.isEmpty(writingGuidelineJson)) return;
        try {
            JsonObject wg = JsonParser.parseString(writingGuidelineJson).getAsJsonObject();
            String tocMandatoryYn = getStrOrNull(wg, "tocMandatoryYn");
            if (!"Y".equals(tocMandatoryYn)) return;
            if (!wg.has("mandatedToc") || wg.get("mandatedToc").isJsonNull()) return;
            JsonArray mandatedArr = wg.getAsJsonArray("mandatedToc");
            if (mandatedArr.size() == 0) return;

            // mandatedToc no → {no, title} 맵 구성
            java.util.Map<String, JsonObject> mandatedMap = new java.util.LinkedHashMap<>();
            for (JsonElement el : mandatedArr) {
                JsonObject obj = el.getAsJsonObject();
                String no = getStrOrNull(obj, "no");
                if (CommonUtil.isNotEmpty(no)) mandatedMap.put(no, obj);
            }

            // parsed.toc 순회하며 sectionNo/sectionNm 덮어씀
            if (parsed.getToc() != null) {
                for (ProposalVO.TocVO toc : parsed.getToc()) {
                    String no = toc.getNo();
                    if (CommonUtil.isEmpty(no)) continue;
                    JsonObject mandated = mandatedMap.get(no);
                    if (mandated != null) {
                        String mandatedTitle = getStrOrNull(mandated, "title");
                        if (CommonUtil.isNotEmpty(mandatedTitle)) {
                            toc.setSectionNm(mandatedTitle);
                        }
                        toc.setSectionNo(no);
                    }
                }
            }
            logger.info("[PT Stage2] mandatedToc 강제 적용 완료 (tocCount={})", mandatedMap.size());
        } catch (Exception e) {
            logger.warn("[PT Stage2] mandatedToc 적용 중 오류: {}", e.getMessage());
        }
    }

    /**
     * coveredReqNos 검증 및 정리
     * - toc[].coveredReqNos에 validReqNos에 없는 값 제거 + 경고 로그
     */
    private void validateAndCleanTocReqNos(List<ProposalVO.TocVO> tocList, java.util.Set<String> validReqNos, String ptProjectId) {
        if (tocList == null || validReqNos == null) return;
        for (ProposalVO.TocVO toc : tocList) {
            if (toc.getCoveredReqNos() == null) continue;
            java.util.Iterator<String> it = toc.getCoveredReqNos().iterator();
            while (it.hasNext()) {
                String reqNo = it.next();
                if (!validReqNos.contains(reqNo)) {
                    logger.warn("[PT Stage2] TOC에 존재하지 않는 reqNo 제거: reqNo={}, sectionNm={}, ptProjectId={}",
                            reqNo, toc.getSectionNm(), ptProjectId);
                    it.remove();
                }
            }
        }
    }

    /**
     * 미커버 요구사항 경고
     * - 전체 coveredReqNos 합집합에 없는 reqNo가 있으면 경고 로그
     */
    private void warnUncoveredRequirements(List<ProposalVO.TocVO> tocList, java.util.Set<String> validReqNos, String ptProjectId) {
        if (tocList == null || validReqNos == null || validReqNos.isEmpty()) return;
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (ProposalVO.TocVO toc : tocList) {
            if (toc.getCoveredReqNos() != null) covered.addAll(toc.getCoveredReqNos());
        }
        for (String reqNo : validReqNos) {
            if (!covered.contains(reqNo)) {
                logger.warn("[PT Stage2] 미커버 요구사항 발견: reqNo={}, ptProjectId={}", reqNo, ptProjectId);
            }
        }
    }

    /**
     * evidence 품질 경고
     * - winThemes[].evidence 중 "[증빙자료 필요]" 포함 비율 > 50% 이면 경고
     */
    private void validateStage2Evidence(List<ProposalVO.WinThemeVO> winThemes, String ptProjectId) {
        if (winThemes == null || winThemes.isEmpty()) return;
        long needCount = winThemes.stream()
                .filter(wt -> CommonUtil.isNotEmpty(wt.getEvidence()) && wt.getEvidence().contains("[증빙자료 필요]"))
                .count();
        double ratio = (double) needCount / winThemes.size();
        if (ratio > 0.5) {
            logger.warn("[PT Stage2] Win Theme evidence '[증빙자료 필요]' 비율이 높습니다 ({}/{}), ptProjectId={}",
                    needCount, winThemes.size(), ptProjectId);
        }
    }

    /**
     * Stage 2 전체 프롬프트 조합
     * - promptContent에 Stage 1 데이터(requirements JSON, evalCriteria JSON, writingGuideline) 주입
     * - RAG 컨텍스트 삽입
     * - totalSlideBudget 명시
     */
    private String buildStage2FullPrompt(String promptContent,
            ProposalVO.ProjectVO project,
            List<ProposalVO.RequirementVO> requirements,
            List<ProposalVO.EvalCriteriaVO> evalCriteria,
            String ownContext,
            String competitorContext,
            String etcRefContext,
            int totalSlideBudget) {

        StringBuilder sb = new StringBuilder();
        sb.append(promptContent);
        sb.append("\n\n## 사업 기본 정보");
        sb.append("\n- 사업명: ").append(CommonUtil.nullToBlank(project.getProjectNm()));
        sb.append("\n- 목표 슬라이드 수: ").append(totalSlideBudget).append("장");

        if (CommonUtil.isNotEmpty(project.getWritingGuidelineJson())) {
            sb.append("\n\n## 제안서 작성지침\n").append(project.getWritingGuidelineJson());
        }

        if (requirements != null && !requirements.isEmpty()) {
            List<ProposalVO.RequirementLiteVO> reqLite = requirements.stream()
                    .map(this::toRequirementLite)
                    .collect(java.util.stream.Collectors.toList());
            sb.append("\n\n## 요구사항 목록 (JSON)\n").append(GSON.toJson(reqLite));
        }

        if (evalCriteria != null && !evalCriteria.isEmpty()) {
            List<ProposalVO.EvalCriteriaLiteVO> evalLite = evalCriteria.stream()
                    .map(this::toEvalCriteriaLite)
                    .collect(java.util.stream.Collectors.toList());
            sb.append("\n\n## 평가기준 목록 (JSON)\n").append(GSON.toJson(evalLite));
        }

        sb.append("\n\n## 자사 정보\n").append(ownContext);
        sb.append("\n\n## 경쟁사 정보\n").append(competitorContext);
        sb.append("\n\n## 기타 참고자료\n").append(etcRefContext);

        String prompt = sb.toString();
        logger.info("[PT Stage2] 프롬프트 길이: 원본 요구사항 {}건, 최종 프롬프트 {}자",
                requirements != null ? requirements.size() : 0, prompt.length());
        return prompt;
    }

    /**
     * RequirementVO → RequirementLiteVO 변환 (Stage2 프롬프트 전용)
     * reqContent 가 400자 초과이면 문장 경계에서 절삭
     */
    private ProposalVO.RequirementLiteVO toRequirementLite(ProposalVO.RequirementVO src) {
        ProposalVO.RequirementLiteVO lite = new ProposalVO.RequirementLiteVO();
        lite.setReqNo(src.getReqNo());
        lite.setReqCategoryCd(src.getReqCategoryCd());
        lite.setReqContent(truncateAtSentenceBoundary(src.getReqContent(), 400));
        lite.setMandatoryYn(src.getMandatoryYn());
        return lite;
    }

    /**
     * EvalCriteriaVO → EvalCriteriaLiteVO 변환 (Stage2 프롬프트 전용)
     */
    private ProposalVO.EvalCriteriaLiteVO toEvalCriteriaLite(ProposalVO.EvalCriteriaVO src) {
        ProposalVO.EvalCriteriaLiteVO lite = new ProposalVO.EvalCriteriaLiteVO();
        lite.setEvalItemNm(src.getEvalItemNm());
        lite.setScore(src.getScore());
        return lite;
    }

    /**
     * 문자열을 maxLen 자 이하로, 문장 경계(마침표·세미콜론·줄바꿈)에서 절삭.
     * 경계를 찾지 못하면 maxLen 위치에서 단순 절삭 후 "...(생략)" 부가.
     */
    private String truncateAtSentenceBoundary(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        // maxLen 이전 구간에서 가장 뒤쪽 문장 경계 탐색
        String candidate = text.substring(0, maxLen);
        int lastBoundary = -1;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (c == '.' || c == ';' || c == '\n' || c == '·') {
                lastBoundary = i;
                break;
            }
        }
        String truncated = lastBoundary > 0 ? text.substring(0, lastBoundary + 1) : candidate;
        return truncated + "...(생략)";
    }

    /**
     * DB에 PI000022가 없을 경우 사용할 최소 기본 프롬프트
     */
    private String buildDefaultStage2Prompt() {
        return "제공된 RFP 구조화 결과와 자사·경쟁사 검색 결과를 바탕으로 "
                + "problemDefinitions, winThemes, toc 를 포함하는 JSON을 반환하세요. "
                + "다른 설명 없이 JSON만 출력하세요. 코드블록(```)도 포함하지 마세요.";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step D-0: Stage2 SSE 스트림
    // ══════════════════════════════════════════════════════════════════════════

    private static final ExecutorService STAGE_D_EXECUTOR = Executors.newFixedThreadPool(5);

    /**
     * D-0: Stage2 전략 분석 SSE 스트림
     * - TB_PT_PROBLEM_DEFINITION 존재 시 skip하고 done 이벤트 즉시 전송
     * - 없으면 executeStage2() 비동기 실행 후 진행상황 SSE 전달
     *
     * @param ptProjectId      프로젝트 ID
     * @param totalSlideBudget 목표 슬라이드 수 (기본 20)
     * @param modelId          LLM 모델 ID
     * @param agentId          에이전트 ID
     */
    public SseEmitter streamAnalyzeStage2(String ptProjectId, int totalSlideBudget, String modelId, String agentId) {
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onTimeout(() -> {
            logger.warn("[PT D-0] SSE timeout - ptProjectId={}", ptProjectId);
            emitter.complete();
        });
        emitter.onError(e -> logger.warn("[PT D-0] SSE error - ptProjectId={}, msg={}", ptProjectId, e.getMessage()));

        sendSseEvent(emitter, "connected", "{\"ptProjectId\":\"" + ptProjectId + "\"}");

        final String userId = SessionUtil.getUserId();

        STAGE_D_EXECUTOR.execute(() -> {
            try {
                // Stage2 이미 실행됐는지 확인
                int pdCount = proposalDAO.countProblemDefinitions(ptProjectId);
                if (pdCount > 0) {
                    logger.info("[PT D-0] Stage2 이미 실행됨, skip (ptProjectId={}, problemDefCount={})", ptProjectId, pdCount);
                    sendSseEvent(emitter, "done", "{\"ptProjectId\":\"" + ptProjectId + "\",\"skipped\":true}");
                    emitter.complete();
                    return;
                }

                sendSseEvent(emitter, "progress", "{\"step\":\"analyze\",\"message\":\"전략 분석 시작\"}");

                // Stage2 실행 (동기 호출, 별도 스레드에서 실행 중이므로 OK)
                ProposalVO.Stage2ResultVO result = executeStage2(ptProjectId, totalSlideBudget, modelId, agentId);

                int tocCount = result.getToc() != null ? result.getToc().size() : 0;
                int wtCount  = result.getWinThemes() != null ? result.getWinThemes().size() : 0;
                int pdCountAfter = result.getProblemDefinitions() != null ? result.getProblemDefinitions().size() : 0;

                sendSseEvent(emitter, "done",
                        "{\"ptProjectId\":\"" + ptProjectId + "\""
                        + ",\"skipped\":false"
                        + ",\"tocCount\":" + tocCount
                        + ",\"winThemeCount\":" + wtCount
                        + ",\"problemDefCount\":" + pdCountAfter + "}");

            } catch (Exception e) {
                logger.error("[PT D-0] Stage2 처리 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
                sendSseEvent(emitter, "error", "{\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step D-1/D-2: 소목차 슬라이드 생성 (Stage3 + Stage3.5)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * D-1: 소목차 슬라이드 생성 SSE 스트림 (Stage3 + Stage3.5)
     * - 한 번의 LLM 호출로 PLANNED_SLIDE_CNT 장 배열 생성
     * - 이미 슬라이드가 있으면 삭제 후 재생성
     * - 각 슬라이드 insert → Stage3.5(스타일 조립) → RENDER_STATUS_CD='003'
     *
     * @param ptProjectId 프로젝트 ID
     * @param tocId       소목차 TOC_ID
     * @param modelId     LLM 모델 ID
     * @param agentId     에이전트 ID
     */
    public SseEmitter streamGenerateSection(String ptProjectId, String tocId, String modelId, String agentId) {
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onTimeout(() -> {
            logger.warn("[PT D-1] SSE timeout - tocId={}", tocId);
            emitter.complete();
        });
        emitter.onError(e -> logger.warn("[PT D-1] SSE error - tocId={}", tocId));

        sendSseEvent(emitter, "connected", "{\"tocId\":\"" + tocId + "\"}");

        final String userId = SessionUtil.getUserId();

        STAGE_D_EXECUTOR.execute(() -> {
            try {
                // 1. 소목차 정보 로드
                ProposalVO.TocVO tocVO = proposalDAO.selectTocById(tocId);
                if (tocVO == null) {
                    sendSseEvent(emitter, "error", "{\"message\":\"소목차를 찾을 수 없습니다. tocId=" + tocId + "\"}");
                    emitter.complete();
                    return;
                }

                sendSseEvent(emitter, "progress", "{\"step\":\"load\",\"message\":\"섹션 데이터 로드 중\"}");

                // 2. 연결 평가기준 조회
                ProposalVO.EvalCriteriaVO linkedEc = null;
                if (CommonUtil.isNotEmpty(tocVO.getLinkedEvalCriteriaId())) {
                    List<ProposalVO.EvalCriteriaVO> allEc = proposalDAO.selectEvalCriteria(ptProjectId);
                    for (ProposalVO.EvalCriteriaVO ec : allEc) {
                        if (tocVO.getLinkedEvalCriteriaId().equals(ec.getEvalCriteriaId())) {
                            linkedEc = ec;
                            break;
                        }
                    }
                }

                // 3. 요구사항·Win Theme·문제정의 로드
                List<ProposalVO.RequirementVO> requirements = proposalDAO.selectRequirements(ptProjectId);
                List<ProposalVO.WinThemeVO> winThemes = proposalDAO.selectWinThemes(ptProjectId);
                List<ProposalVO.ProblemDefinitionVO> problemDefs = proposalDAO.selectProblemDefinitions(ptProjectId);

                // 4. PROJECT_CONFIG_JSON 로드
                String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);
                ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);

                // 5. Stage3 프롬프트 조합 + LLM 호출
                sendSseEvent(emitter, "progress", "{\"step\":\"llm\",\"message\":\"슬라이드 내용 생성 중\"}");
                String promptContent = null;
                try {
                    promptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S3_SLIDE");
                } catch (Exception e) {
                    logger.warn("[PT D-1] S3_SLIDE 프롬프트 조회 실패: {}", e.getMessage());
                }
                if (CommonUtil.isEmpty(promptContent)) {
                    promptContent = buildDefaultStage3Prompt();
                }

                String fullPrompt = buildStage3FullPrompt(promptContent, tocVO, linkedEc,
                        requirements, winThemes, problemDefs, project, configJson);

                String aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
                if (CommonUtil.isEmpty(aiResponse)) {
                    logger.warn("[PT D-1] LLM 응답 없음, 1회 재시도 (tocId={})", tocId);
                    aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
                }
                if (CommonUtil.isEmpty(aiResponse)) {
                    sendSseEvent(emitter, "error", "{\"message\":\"AI 응답이 비어 있습니다. 잠시 후 다시 시도해 주세요.\"}");
                    emitter.complete();
                    return;
                }

                // 6. Stage3 응답 파싱 → slides 배열
                sendSseEvent(emitter, "progress", "{\"step\":\"parse\",\"message\":\"슬라이드 구조 파싱 중\"}");
                List<JsonObject> slideJsonObjects;
                try {
                    slideJsonObjects = parseStage3Response(aiResponse);
                } catch (RuntimeException e) {
                    logger.warn("[PT D-1] Stage3 JSON 파싱 실패, 1회 재시도: {}", e.getMessage());
                    aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
                    try {
                        slideJsonObjects = parseStage3Response(aiResponse);
                    } catch (RuntimeException e2) {
                        logger.error("[PT D-1] Stage3 파싱 재시도 실패 (tocId={}): {}", tocId, e2.getMessage());
                        sendSseEvent(emitter, "error", "{\"message\":\"슬라이드 생성 결과를 파싱할 수 없습니다.\"}");
                        emitter.complete();
                        return;
                    }
                }

                // 7. 기존 슬라이드 삭제 + SLIDE_NO 계산
                sendSseEvent(emitter, "progress", "{\"step\":\"save\",\"message\":\"슬라이드 저장 중\"}");
                proposalDAO.deleteSlidesByToc(tocId);

                // 기존 최대 SLIDE_NO 이후로 시작 (다른 소목차 슬라이드 포함 누적)
                // 단, 이 소목차에 속한 슬라이드는 방금 삭제했으므로 현재 전체 max를 기준으로 함
                int maxSlideNo = proposalDAO.selectMaxSlideNo(ptProjectId);

                // 8. 슬라이드 insert (RENDER_STATUS_CD='002' 생성중)
                List<ProposalVO.SlideVO> insertedSlides = new java.util.ArrayList<>();
                for (int i = 0; i < slideJsonObjects.size(); i++) {
                    JsonObject sObj = slideJsonObjects.get(i);
                    int slideNo = maxSlideNo + i + 1;
                    int colorIndex = slideNo % 3;

                    ProposalVO.SlideVO slide = new ProposalVO.SlideVO();
                    slide.setSlideId(keyGenerate.generateTableKey("PTS-", "TB_PT_SLIDE", "SLIDE_ID", 6));
                    slide.setPtProjectId(ptProjectId);
                    slide.setTocId(tocId);
                    slide.setSlideNo(slideNo);
                    slide.setColorIndex(colorIndex);
                    slide.setLayoutType(getStrOrNull(sObj, "layoutType"));
                    slide.setSlideJson(GSON.toJson(sObj));
                    slide.setRenderStatusCd("002"); // 생성중
                    slide.setCreateUserId(userId);

                    proposalDAO.insertSlide(slide);
                    insertedSlides.add(slide);
                }

                // 9. Stage3.5: 스타일 조립 + 이미지 생성 (슬라이드별 개별 처리)
                sendSseEvent(emitter, "progress", "{\"step\":\"render\",\"message\":\"슬라이드 스타일 조립 중\"}");
                int successCount = 0;
                for (ProposalVO.SlideVO slide : insertedSlides) {
                    try {
                        doStyleAssembly(slide, configJson);
                        successCount++;
                    } catch (Exception e) {
                        logger.warn("[PT D-2] 슬라이드 스타일 조립 실패 (slideId={}): {}", slide.getSlideId(), e.getMessage());
                        // 실패한 슬라이드만 004로 처리, 나머지는 계속
                        ProposalVO.SlideVO failVO = new ProposalVO.SlideVO();
                        failVO.setSlideId(slide.getSlideId());
                        failVO.setRenderStatusCd("004");
                        proposalDAO.updateSlide(failVO);
                    }
                }

                int failCount = insertedSlides.size() - successCount;
                String doneData = "{\"tocId\":\"" + tocId + "\""
                        + ",\"slideCount\":" + insertedSlides.size()
                        + ",\"successCount\":" + successCount
                        + ",\"failCount\":" + failCount + "}";
                sendSseEvent(emitter, "done", doneData);

            } catch (Exception e) {
                logger.error("[PT D-1] 소목차 생성 오류 (tocId={}): {}", tocId, e.getMessage(), e);
                sendSseEvent(emitter, "error", "{\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * Stage3.5: 슬라이드 스타일 조립 (LLM 호출 없음)
     * - PROJECT_CONFIG_JSON.template + settings.colors + slide JSON을 조합해 IMAGE_GEN_HINT 생성
     * - 이미지 생성 API 호출 (현재 스텁 — 실제 API 연동 시 아래 TODO 참고)
     * - RENDER_STATUS_CD='003'(완료)로 업데이트
     */
    private void doStyleAssembly(ProposalVO.SlideVO slide, String configJson) throws Exception {
        // 1. 설정 파싱
        String docSize = "a4";
        String writingStyle = "formal";
        List<String> baseColors = java.util.Arrays.asList("#5B4FE9", "#8B7FFF", "#EFECFE");
        List<String> accentColors = java.util.Arrays.asList("#E08A2C", "#22A06B");

        if (CommonUtil.isNotEmpty(configJson)) {
            try {
                JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
                if (root.has("template") && !root.get("template").isJsonNull()) {
                    JsonObject tmpl = root.getAsJsonObject("template");
                    String ds = getStrOrNull(tmpl, "docSize");
                    if (CommonUtil.isNotEmpty(ds)) docSize = ds;
                }
                if (root.has("settings") && !root.get("settings").isJsonNull()) {
                    JsonObject settings = root.getAsJsonObject("settings");
                    String ws = getStrOrNull(settings, "writingStyle");
                    if (CommonUtil.isNotEmpty(ws)) writingStyle = ws;
                    if (settings.has("colors") && !settings.get("colors").isJsonNull()) {
                        JsonObject colors = settings.getAsJsonObject("colors");
                        if (colors.has("base")) baseColors = jsonArrayToList(colors.getAsJsonArray("base"));
                        if (colors.has("accent")) accentColors = jsonArrayToList(colors.getAsJsonArray("accent"));
                    }
                }
            } catch (Exception e) {
                logger.warn("[PT D-2] configJson 파싱 실패 (slideId={}): {}", slide.getSlideId(), e.getMessage());
            }
        }

        // 2. COLOR_INDEX로 색상 선택
        int ci = slide.getColorIndex() % Math.max(1, baseColors.size());
        String baseColor = baseColors.get(ci);
        String accentColor = accentColors.isEmpty() ? "#E08A2C" : accentColors.get(0);

        // 3. IMAGE_GEN_HINT 조립
        String slideTitle = "";
        String layoutType = CommonUtil.nullToBlank(slide.getLayoutType());
        if (CommonUtil.isNotEmpty(slide.getSlideJson())) {
            try {
                JsonObject sObj = JsonParser.parseString(slide.getSlideJson()).getAsJsonObject();
                slideTitle = CommonUtil.nullToBlank(getStrOrNull(sObj, "title"));
            } catch (Exception ignored) {}
        }
        String imageGenHint = String.format(
                "layout=%s docSize=%s baseColor=%s accentColor=%s writingStyle=%s title=%s colorIndex=%d",
                layoutType, docSize, baseColor, accentColor, writingStyle, slideTitle, slide.getColorIndex());

        // 4. TODO: 실제 이미지 생성 API 호출
        // 현재는 이미지 생성 스텁 — IMAGE_GEN_HINT 조립까지만 구현
        // 이미지 생성 시: 아래 주석 해제 후 실제 API URL/파라미터 설정
        // String renderedPath = callImageGenApi(imageGenHint, slide.getSlideId());
        // slide.setRenderedImagePath(renderedPath);
        String renderedPath = null;

        // 5. DB 업데이트
        ProposalVO.SlideVO updateVO = new ProposalVO.SlideVO();
        updateVO.setSlideId(slide.getSlideId());
        updateVO.setImageGenHint(imageGenHint);
        updateVO.setRenderedImagePath(renderedPath);
        updateVO.setRenderStatusCd("003"); // 완료
        proposalDAO.updateSlide(updateVO);

        // 로컬 객체도 갱신 (호출부에서 읽을 수 있도록)
        slide.setImageGenHint(imageGenHint);
        slide.setRenderStatusCd("003");
    }

    /**
     * D-1: 소목차 슬라이드 목록 조회
     * @param tocId TOC_ID
     * @return List<SlideVO>
     */
    public List<ProposalVO.SlideVO> selectSectionSlides(String tocId) {
        return proposalDAO.selectSlidesByToc(tocId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step D-3: 소목차 보완 채팅
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * D-3: 소목차 보완요청 채팅
     * - 현재 소목차 슬라이드 목록 + 사용자 메시지 → LLM이 대상 slideId 배열 판단
     * - 판단된 슬라이드만 Stage3 재생성 (기존 슬라이드 덮어쓰기, 이력 없음)
     * - TB_CHAT_LOG에 대화 이력 저장 (svcTy='PTSC', refId=tocId)
     *
     * @param vo ptProjectId, tocId, message, modelId, agentId
     * @return SectionChatResultVO (재생성된 슬라이드 목록 + AI 요약 메시지)
     */
    public ProposalVO.SectionChatResultVO chatSection(ProposalVO.SectionChatVO vo) throws Exception {
        String ptProjectId = vo.getPtProjectId();
        String tocId = vo.getTocId();
        String userMessage = vo.getMessage();
        String modelId = vo.getModelId();
        String agentId = vo.getAgentId();

        // 1. 현재 소목차 슬라이드 목록 조회
        List<ProposalVO.SlideVO> currentSlides = proposalDAO.selectSlidesByToc(tocId);
        if (currentSlides == null || currentSlides.isEmpty()) {
            throw new RuntimeException("이 소목차에 생성된 슬라이드가 없습니다. 먼저 소목차를 생성해 주세요.");
        }

        // 2. 대상 슬라이드 판단 (LLM 호출)
        List<String> targetSlideIds = identifyTargetSlides(currentSlides, userMessage, modelId, agentId);

        // 특정 슬라이드 지목이 없으면 전체 대상
        if (targetSlideIds == null || targetSlideIds.isEmpty()) {
            for (ProposalVO.SlideVO s : currentSlides) {
                targetSlideIds = new java.util.ArrayList<>();
                for (ProposalVO.SlideVO cs : currentSlides) targetSlideIds.add(cs.getSlideId());
                break;
            }
        }

        // 3. 대상 슬라이드만 재생성
        ProposalVO.TocVO tocVO = proposalDAO.selectTocById(tocId);
        ProposalVO.EvalCriteriaVO linkedEc = null;
        if (tocVO != null && CommonUtil.isNotEmpty(tocVO.getLinkedEvalCriteriaId())) {
            List<ProposalVO.EvalCriteriaVO> allEc = proposalDAO.selectEvalCriteria(ptProjectId);
            for (ProposalVO.EvalCriteriaVO ec : allEc) {
                if (tocVO.getLinkedEvalCriteriaId().equals(ec.getEvalCriteriaId())) { linkedEc = ec; break; }
            }
        }

        List<ProposalVO.RequirementVO> requirements = proposalDAO.selectRequirements(ptProjectId);
        List<ProposalVO.WinThemeVO> winThemes = proposalDAO.selectWinThemes(ptProjectId);
        List<ProposalVO.ProblemDefinitionVO> problemDefs = proposalDAO.selectProblemDefinitions(ptProjectId);
        String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);
        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);

        String promptContent = null;
        try { promptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S3_SLIDE"); }
        catch (Exception e) { logger.warn("[PT D-3] S3_SLIDE 프롬프트 조회 실패: {}", e.getMessage()); }
        if (CommonUtil.isEmpty(promptContent)) promptContent = buildDefaultStage3Prompt();

        List<ProposalVO.SlideVO> updatedSlides = new java.util.ArrayList<>();
        final java.util.Set<String> targetSet = new java.util.HashSet<>(targetSlideIds);

        for (ProposalVO.SlideVO existingSlide : currentSlides) {
            if (!targetSet.contains(existingSlide.getSlideId())) continue;

            // 단일 슬라이드 재생성 프롬프트 (기존 내용 + 보완 요청 추가)
            String chatFullPrompt = buildStage3FullPrompt(promptContent, tocVO, linkedEc,
                    requirements, winThemes, problemDefs, project, configJson)
                    + "\n\n## 기존 슬라이드 내용\n" + CommonUtil.nullToBlank(existingSlide.getSlideJson())
                    + "\n\n## 보완 요청\n" + userMessage
                    + "\n\n슬라이드 1장을 재작성해 주세요. 형식은 위와 동일한 slides 배열 JSON으로 출력하세요.";

            try {
                String aiResp = riskDiagnosisAgentService.callLlmQuerySync(chatFullPrompt, modelId, "", agentId);
                if (CommonUtil.isNotEmpty(aiResp)) {
                    List<JsonObject> parsed = parseStage3Response(aiResp);
                    if (!parsed.isEmpty()) {
                        ProposalVO.SlideVO updateVO = new ProposalVO.SlideVO();
                        updateVO.setSlideId(existingSlide.getSlideId());
                        updateVO.setLayoutType(getStrOrNull(parsed.get(0), "layoutType"));
                        updateVO.setSlideJson(GSON.toJson(parsed.get(0)));
                        updateVO.setRenderStatusCd("002");
                        proposalDAO.updateSlide(updateVO);

                        // Stage3.5 스타일 재조립
                        existingSlide.setSlideJson(GSON.toJson(parsed.get(0)));
                        existingSlide.setLayoutType(updateVO.getLayoutType());
                        try { doStyleAssembly(existingSlide, configJson); }
                        catch (Exception re) { logger.warn("[PT D-3] 스타일 조립 실패 slideId={}: {}", existingSlide.getSlideId(), re.getMessage()); }

                        updatedSlides.add(existingSlide);
                    }
                }
            } catch (Exception e) {
                logger.warn("[PT D-3] 슬라이드 재생성 실패 (slideId={}): {}", existingSlide.getSlideId(), e.getMessage());
            }
        }

        // 4. 채팅 이력 저장 (TB_CHAT_LOG, svcTy='PTSC', refId=tocId)
        try {
            saveSectionChatLog(tocId, userMessage,
                    "슬라이드 " + updatedSlides.size() + "장이 보완 요청에 따라 재생성되었습니다.",
                    agentId, SessionUtil.getUserId());
        } catch (Exception e) {
            logger.warn("[PT D-3] 채팅 로그 저장 실패 (tocId={}): {}", tocId, e.getMessage());
        }

        ProposalVO.SectionChatResultVO result = new ProposalVO.SectionChatResultVO();
        result.setUpdatedSlides(updatedSlides);
        result.setAiMessage("보완 요청에 따라 슬라이드 " + updatedSlides.size() + "장이 수정되었습니다.");
        return result;
    }

    /**
     * D-3 내부 헬퍼: LLM을 통해 사용자 메시지가 지목하는 슬라이드 slideId 목록 판단
     * - 지목이 없거나 판단 실패 시 빈 리스트 반환 (호출부에서 전체 대상으로 처리)
     */
    private List<String> identifyTargetSlides(List<ProposalVO.SlideVO> slides, String userMessage, String modelId, String agentId) {
        // 슬라이드 목록 요약
        StringBuilder sbList = new StringBuilder();
        for (ProposalVO.SlideVO s : slides) {
            String title = "";
            if (CommonUtil.isNotEmpty(s.getSlideJson())) {
                try {
                    JsonObject sObj = JsonParser.parseString(s.getSlideJson()).getAsJsonObject();
                    title = CommonUtil.nullToBlank(getStrOrNull(sObj, "title"));
                } catch (Exception ignored) {}
            }
            sbList.append("- slideId:").append(s.getSlideId())
                  .append(" slideNo:").append(s.getSlideNo())
                  .append(" title:").append(title).append("\n");
        }

        String idPrompt = "아래 슬라이드 목록과 사용자 요청을 보고, 요청이 적용될 슬라이드 ID 배열을 JSON으로만 반환하세요.\n"
                + "특정 슬라이드를 지목하지 않으면 빈 배열 []을 반환하세요.\n"
                + "출력 형식: {\"targetSlideIds\": [\"PTS-000001\", ...]}\n\n"
                + "## 슬라이드 목록\n" + sbList
                + "\n## 사용자 요청\n" + userMessage;

        try {
            String resp = riskDiagnosisAgentService.callLlmQuerySync(idPrompt, modelId, "", agentId);
            if (CommonUtil.isEmpty(resp)) return java.util.Collections.emptyList();

            // 코드블록 제거
            resp = resp.trim();
            if (resp.startsWith("```")) {
                int nl = resp.indexOf('\n');
                if (nl != -1) resp = resp.substring(nl + 1);
                if (resp.endsWith("```")) resp = resp.substring(0, resp.lastIndexOf("```")).trim();
            }

            JsonObject parsed = JsonParser.parseString(resp).getAsJsonObject();
            if (!parsed.has("targetSlideIds") || parsed.get("targetSlideIds").isJsonNull()) {
                return java.util.Collections.emptyList();
            }
            List<String> result = new java.util.ArrayList<>();
            for (JsonElement el : parsed.getAsJsonArray("targetSlideIds")) {
                if (!el.isJsonNull()) result.add(el.getAsString());
            }
            return result;
        } catch (Exception e) {
            logger.warn("[PT D-3] 대상 슬라이드 판단 실패: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * D-3 채팅 이력 저장 (TB_CHAT_LOG)
     */
    private void saveSectionChatLog(String tocId, String qContent, String rContent, String agentId, String userId) {
        try {
            ChatbotVO log = new ChatbotVO();
            log.setAgentId(agentId);
            log.setSvcTy("PTSC"); // PT Section Chat
            log.setRefId(tocId);
            log.setQContent(qContent);
            log.setRContent(rContent);
            log.setUserId(userId);
            chatbotDAO.insertChatLog(log);
        } catch (Exception e) {
            logger.warn("[PT D-3] TB_CHAT_LOG insert 실패: {}", e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step D-4: 소목차 확인 → 다음 소목차 전환
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * D-4: 소목차 확인 처리
     * - 해당 소목차 슬라이드 전체가 RENDER_STATUS_CD='003'인지 검증
     * - 미완료 슬라이드가 있으면 confirm 거부 + 문제 슬라이드 목록 반환
     * - 모두 완료면 다음 미완료 소목차 반환, 없으면 done=true
     *
     * @param ptProjectId 프로젝트 ID
     * @param tocId       확인할 소목차 TOC_ID
     * @return SectionConfirmResultVO
     */
    public ProposalVO.SectionConfirmResultVO confirmSection(String ptProjectId, String tocId) throws Exception {
        List<ProposalVO.SlideVO> slides = proposalDAO.selectSlidesByToc(tocId);

        // 슬라이드가 하나도 없으면 거부
        if (slides == null || slides.isEmpty()) {
            ProposalVO.SectionConfirmResultVO reject = new ProposalVO.SectionConfirmResultVO();
            reject.setPtProjectId(ptProjectId);
            reject.setTocId(tocId);
            reject.setDone(false);
            reject.setRejectReason("이 소목차에 생성된 슬라이드가 없습니다. 먼저 생성해 주세요.");
            reject.setPendingSlides(java.util.Collections.emptyList());
            return reject;
        }

        // 미완료 슬라이드 확인
        List<ProposalVO.SlideVO> pending = new java.util.ArrayList<>();
        for (ProposalVO.SlideVO s : slides) {
            if (!"003".equals(s.getRenderStatusCd())) {
                pending.add(s);
            }
        }

        if (!pending.isEmpty()) {
            ProposalVO.SectionConfirmResultVO reject = new ProposalVO.SectionConfirmResultVO();
            reject.setPtProjectId(ptProjectId);
            reject.setTocId(tocId);
            reject.setDone(false);
            reject.setRejectReason("완료되지 않은 슬라이드가 " + pending.size() + "장 있습니다.");
            reject.setPendingSlides(pending);
            return reject;
        }

        // 다음 소목차 조회
        java.util.Map<String, Object> nextParam = new java.util.HashMap<>();
        nextParam.put("ptProjectId", ptProjectId);
        nextParam.put("tocId", tocId);
        ProposalVO.TocVO nextToc = proposalDAO.selectNextLeafToc(nextParam);

        ProposalVO.SectionConfirmResultVO result = new ProposalVO.SectionConfirmResultVO();
        result.setPtProjectId(ptProjectId);
        result.setTocId(tocId);
        result.setPendingSlides(java.util.Collections.emptyList());

        if (nextToc == null) {
            result.setDone(true); // 모든 소목차 완료 → Step E로
        } else {
            result.setDone(false);
            result.setNextTocId(nextToc.getTocId());
        }

        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Stage3 헬퍼 메서드
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Stage3 전체 프롬프트 조합
     */
    private String buildStage3FullPrompt(String promptContent,
            ProposalVO.TocVO tocVO,
            ProposalVO.EvalCriteriaVO linkedEc,
            List<ProposalVO.RequirementVO> requirements,
            List<ProposalVO.WinThemeVO> winThemes,
            List<ProposalVO.ProblemDefinitionVO> problemDefs,
            ProposalVO.ProjectVO project,
            String configJson) {

        StringBuilder sb = new StringBuilder(promptContent);

        sb.append("\n\n## 사업 기본 정보");
        if (project != null) {
            sb.append("\n- 사업명: ").append(CommonUtil.nullToBlank(project.getProjectNm()));
            sb.append("\n- 발주기관: ").append(CommonUtil.nullToBlank(project.getOrgNm()));
            sb.append("\n- 제안 구분: ").append("G".equals(project.getTargetTypeCd()) ? "공공" : "민간");
        }

        sb.append("\n\n## 현재 소목차");
        sb.append("\n- 소목차 ID: ").append(tocVO.getTocId());
        sb.append("\n- 섹션 번호: ").append(CommonUtil.nullToBlank(tocVO.getSectionNo()));
        sb.append("\n- 섹션명: ").append(CommonUtil.nullToBlank(tocVO.getSectionNm()));
        sb.append("\n- 목표 슬라이드 수: ").append(tocVO.getPlannedSlideCnt()).append("장");

        if (linkedEc != null) {
            sb.append("\n\n## 연결된 평가기준");
            sb.append("\n- 평가항목: ").append(CommonUtil.nullToBlank(linkedEc.getEvalItemNm()));
            sb.append("\n- 배점: ").append(linkedEc.getScore());
            sb.append("\n- 평가 의도: ").append(CommonUtil.nullToBlank(linkedEc.getEvalIntent()));
            sb.append("\n- 고득점 조건: ").append(CommonUtil.nullToBlank(linkedEc.getHighScoreCondition()));
            sb.append("\n- 차별화 방향: ").append(CommonUtil.nullToBlank(linkedEc.getDifferentiationDirection()));
        }

        // TODO: COVERED_REQ_IDS_JSON 컬럼 추가 확정 후 관련 요구사항만 필터링
        // 현재는 전체 요구사항을 전달하고 LLM이 관련 항목을 선택하도록 함
        if (requirements != null && !requirements.isEmpty()) {
            sb.append("\n\n## 요구사항 목록 (관련 항목 중심으로 활용)\n").append(GSON.toJson(requirements));
        }

        if (winThemes != null && !winThemes.isEmpty()) {
            sb.append("\n\n## Win Theme\n").append(GSON.toJson(winThemes));
        }

        if (problemDefs != null && !problemDefs.isEmpty()) {
            sb.append("\n\n## 문제 정의\n").append(GSON.toJson(problemDefs));
        }

        // 문체/색상 설정
        if (CommonUtil.isNotEmpty(configJson)) {
            try {
                JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
                if (root.has("settings") && !root.get("settings").isJsonNull()) {
                    JsonObject settings = root.getAsJsonObject("settings");
                    sb.append("\n\n## 제안서 스타일 설정");
                    sb.append("\n- 문체: ").append(CommonUtil.nullToBlank(getStrOrNull(settings, "writingStyle")));
                }
            } catch (Exception ignored) {}
        }

        sb.append("\n\n## 출력 형식");
        sb.append("\n슬라이드 배열 JSON으로만 출력하세요. 코드블록(```) 없이 JSON만 출력하세요.");
        sb.append("\n{\"slides\":[{\"layoutType\":\"infographic\",\"eyebrow\":\"...\","
                + "\"title\":\"...\",\"subtitle\":\"...\",\"highlightBanner\":\"...\","
                + "\"components\":[],\"stepFlowBar\":null,\"conclusionRibbon\":\"...\"},...]}"
        );

        return sb.toString();
    }

    /**
     * LLM 응답 JSON 파싱 → slides 배열
     */
    private List<JsonObject> parseStage3Response(String aiResponse) {
        String json = aiResponse.trim();
        if (json.startsWith("```")) {
            int nl = json.indexOf('\n');
            if (nl != -1) json = json.substring(nl + 1);
            if (json.endsWith("```")) json = json.substring(0, json.lastIndexOf("```")).trim();
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("Stage3 LLM 응답이 유효한 JSON이 아닙니다: " + e.getMessage());
        }

        if (!root.has("slides") || root.get("slides").isJsonNull() || !root.get("slides").isJsonArray()) {
            throw new RuntimeException("Stage3 LLM 응답에 'slides' 배열이 없습니다.");
        }

        List<JsonObject> result = new java.util.ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("slides")) {
            if (!el.isJsonNull() && el.isJsonObject()) {
                result.add(el.getAsJsonObject());
            }
        }
        if (result.isEmpty()) {
            throw new RuntimeException("Stage3 LLM 응답 slides 배열이 비어 있습니다.");
        }
        return result;
    }

    /**
     * Stage3 기본 프롬프트 (TB_PROMPT에 S3_SLIDE가 없을 경우 폴백)
     */
    private String buildDefaultStage3Prompt() {
        return "제공된 소목차 정보, 평가기준, Win Theme, 요구사항을 바탕으로 "
                + "제안서 슬라이드를 생성하세요. "
                + "반드시 slides 배열을 포함한 JSON만 출력하세요. "
                + "코드블록(```)은 포함하지 마세요.";
    }

    /**
     * D-3 채팅 이력 저장용 VO 헬퍼 (ChatbotVO 필드 없는 경우 대비)
     */
    private String getRefIdFromChatLog(Object refId) {
        return refId != null ? refId.toString() : "";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step E: 검토 — 전체 슬라이드 조회 / 전역 보완 채팅 / Stage4 평가 시뮬레이션
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * E — 전체 슬라이드 목록 조회 (SLIDE_NO 순)
     * @param ptProjectId 프로젝트 ID
     * @return List<SlideVO>
     */
    public List<ProposalVO.SlideVO> selectAllSlides(String ptProjectId) {
        return proposalDAO.selectAllSlidesByProject(ptProjectId);
    }

    /**
     * E — 전역 보완 채팅
     * 1. 사용자 메시지 + 전체 슬라이드 목록 → LLM이 대상 slideId 배열 판단
     * 2. 대상 슬라이드들을 tocId별로 그룹화 → 각 TOC의 컨텍스트 로드 후 재생성 (D-3 로직 재사용)
     * 3. TB_CHAT_LOG에 전역 채팅 이력 저장 (svcTy='PTRV', refId=ptProjectId)
     *
     * @param vo ptProjectId, message, modelId, agentId
     * @return ReviewChatResultVO (재생성된 슬라이드 목록 + AI 요약)
     */
    public ProposalVO.ReviewChatResultVO reviewChat(ProposalVO.ReviewChatVO vo) throws Exception {
        String ptProjectId = vo.getPtProjectId();
        String userMessage = vo.getMessage();
        String modelId = vo.getModelId();
        String agentId = vo.getAgentId();

        // 1. 전체 슬라이드 조회
        List<ProposalVO.SlideVO> allSlides = proposalDAO.selectAllSlidesByProject(ptProjectId);
        if (allSlides == null || allSlides.isEmpty()) {
            throw new RuntimeException("생성된 슬라이드가 없습니다. 먼저 Step D에서 슬라이드를 생성해 주세요.");
        }

        // 2. 대상 슬라이드 판단 (D-3의 identifyTargetSlides 재사용)
        List<String> targetSlideIds = identifyTargetSlides(allSlides, userMessage, modelId, agentId);
        if (targetSlideIds == null || targetSlideIds.isEmpty()) {
            // 전체 대상
            targetSlideIds = new java.util.ArrayList<>();
            for (ProposalVO.SlideVO s : allSlides) targetSlideIds.add(s.getSlideId());
        }
        final java.util.Set<String> targetSet = new java.util.HashSet<>(targetSlideIds);

        // 3. 대상 슬라이드를 tocId 별로 그룹화
        java.util.Map<String, List<ProposalVO.SlideVO>> byToc = new java.util.LinkedHashMap<>();
        for (ProposalVO.SlideVO s : allSlides) {
            if (!targetSet.contains(s.getSlideId())) continue;
            byToc.computeIfAbsent(s.getTocId(), k -> new java.util.ArrayList<>()).add(s);
        }

        // 4. 공통 컨텍스트 로드
        List<ProposalVO.RequirementVO> requirements = proposalDAO.selectRequirements(ptProjectId);
        List<ProposalVO.WinThemeVO> winThemes = proposalDAO.selectWinThemes(ptProjectId);
        List<ProposalVO.ProblemDefinitionVO> problemDefs = proposalDAO.selectProblemDefinitions(ptProjectId);
        String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);
        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        List<ProposalVO.EvalCriteriaVO> allEc = proposalDAO.selectEvalCriteria(ptProjectId);

        String promptContent = null;
        try { promptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S3_SLIDE"); }
        catch (Exception e) { logger.warn("[PT E] S3_SLIDE 프롬프트 조회 실패: {}", e.getMessage()); }
        if (CommonUtil.isEmpty(promptContent)) promptContent = buildDefaultStage3Prompt();

        // 5. tocId별 재생성
        List<ProposalVO.SlideVO> updatedSlides = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, List<ProposalVO.SlideVO>> entry : byToc.entrySet()) {
            String tocId = entry.getKey();
            List<ProposalVO.SlideVO> tocTargetSlides = entry.getValue();

            ProposalVO.TocVO tocVO = proposalDAO.selectTocById(tocId);
            if (tocVO == null) {
                logger.warn("[PT E] TOC 조회 실패 (tocId={}), 해당 슬라이드 건너뜀", tocId);
                continue;
            }

            ProposalVO.EvalCriteriaVO linkedEc = null;
            if (CommonUtil.isNotEmpty(tocVO.getLinkedEvalCriteriaId())) {
                for (ProposalVO.EvalCriteriaVO ec : allEc) {
                    if (tocVO.getLinkedEvalCriteriaId().equals(ec.getEvalCriteriaId())) { linkedEc = ec; break; }
                }
            }

            for (ProposalVO.SlideVO existingSlide : tocTargetSlides) {
                String chatFullPrompt = buildStage3FullPrompt(promptContent, tocVO, linkedEc,
                        requirements, winThemes, problemDefs, project, configJson)
                        + "\n\n## 기존 슬라이드 내용\n" + CommonUtil.nullToBlank(existingSlide.getSlideJson())
                        + "\n\n## 보완 요청\n" + userMessage
                        + "\n\n슬라이드 1장을 재작성해 주세요. 형식은 위와 동일한 slides 배열 JSON으로 출력하세요.";

                try {
                    String aiResp = riskDiagnosisAgentService.callLlmQuerySync(chatFullPrompt, modelId, "", agentId);
                    if (CommonUtil.isNotEmpty(aiResp)) {
                        List<JsonObject> parsed = parseStage3Response(aiResp);
                        if (!parsed.isEmpty()) {
                            ProposalVO.SlideVO updateVO = new ProposalVO.SlideVO();
                            updateVO.setSlideId(existingSlide.getSlideId());
                            updateVO.setLayoutType(getStrOrNull(parsed.get(0), "layoutType"));
                            updateVO.setSlideJson(GSON.toJson(parsed.get(0)));
                            updateVO.setRenderStatusCd("002");
                            proposalDAO.updateSlide(updateVO);

                            existingSlide.setSlideJson(GSON.toJson(parsed.get(0)));
                            existingSlide.setLayoutType(updateVO.getLayoutType());
                            try { doStyleAssembly(existingSlide, configJson); }
                            catch (Exception re) { logger.warn("[PT E] 스타일 조립 실패 slideId={}: {}", existingSlide.getSlideId(), re.getMessage()); }

                            updatedSlides.add(existingSlide);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("[PT E] 슬라이드 재생성 실패 (slideId={}): {}", existingSlide.getSlideId(), e.getMessage());
                }
            }
        }

        // 6. 채팅 이력 저장 (svcTy='PTRV', refId=ptProjectId)
        saveReviewChatLog(ptProjectId, userMessage,
                "슬라이드 " + updatedSlides.size() + "장이 보완 요청에 따라 재생성되었습니다.",
                agentId, SessionUtil.getUserId());

        ProposalVO.ReviewChatResultVO result = new ProposalVO.ReviewChatResultVO();
        result.setUpdatedSlides(updatedSlides);
        result.setAiMessage("보완 요청에 따라 슬라이드 " + updatedSlides.size() + "장이 수정되었습니다.");
        return result;
    }

    /**
     * E — Stage4 평가 시뮬레이션 실행
     * 1. 전체 슬라이드(slideNo 포함) + 평가기준을 LLM에 전달
     * 2. 시스템 프롬프트: STAGE_CD IN ('ALL','S4_REVIEW') 조합
     * 3. LLM 응답 파싱 → TB_PT_REVIEW INSERT (재실행 시 이전 이력은 남기고 새 row 추가)
     * 4. 심각도순 정렬 결과 반환
     *
     * @param ptProjectId 프로젝트 ID
     * @param modelId     LLM 모델 ID
     * @param agentId     에이전트 ID
     * @return EvalSimulationResultVO (저장 건수 + 심각도순 리뷰 목록)
     */
    public ProposalVO.EvalSimulationResultVO executeEvalSimulation(String ptProjectId, String modelId, String agentId) throws Exception {
        // 1. 전체 슬라이드 조회 (slideNo 기준 정렬)
        List<ProposalVO.SlideVO> allSlides = proposalDAO.selectAllSlidesByProject(ptProjectId);
        if (allSlides == null || allSlides.isEmpty()) {
            throw new RuntimeException("생성된 슬라이드가 없습니다. 먼저 Step D에서 슬라이드를 생성해 주세요.");
        }

        // slideNo → slideId 맵 (LLM 응답 매핑용)
        java.util.Map<Integer, String> slideNoToId = new java.util.LinkedHashMap<>();
        for (ProposalVO.SlideVO s : allSlides) {
            slideNoToId.put(s.getSlideNo(), s.getSlideId());
        }

        // 2. 평가기준 조회
        List<ProposalVO.EvalCriteriaVO> evalCriteriaList = proposalDAO.selectEvalCriteria(ptProjectId);

        // evalItemNm → evalCriteriaId 맵 (정확 매칭용)
        java.util.Map<String, String> evalNmToId = new java.util.LinkedHashMap<>();
        for (ProposalVO.EvalCriteriaVO ec : evalCriteriaList) {
            if (CommonUtil.isNotEmpty(ec.getEvalItemNm())) evalNmToId.put(ec.getEvalItemNm(), ec.getEvalCriteriaId());
        }

        // 3. S4_REVIEW 프롬프트 로드
        String promptContent = null;
        try { promptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S4_REVIEW"); }
        catch (Exception e) { logger.warn("[PT E Stage4] S4_REVIEW 프롬프트 조회 실패: {}", e.getMessage()); }
        if (CommonUtil.isEmpty(promptContent)) {
            promptContent = "아래 슬라이드 목록과 평가기준을 검토해 제안서를 평가위원 관점에서 심층 분석하세요.";
        }

        // 4. 슬라이드 요약 (slideNo + 제목 + 핵심 내용)
        StringBuilder sbSlides = new StringBuilder();
        for (ProposalVO.SlideVO s : allSlides) {
            String title = "";
            String headline = "";
            if (CommonUtil.isNotEmpty(s.getSlideJson())) {
                try {
                    JsonObject sObj = JsonParser.parseString(s.getSlideJson()).getAsJsonObject();
                    title = CommonUtil.nullToBlank(getStrOrNull(sObj, "title"));
                    headline = CommonUtil.nullToBlank(getStrOrNull(sObj, "headline"));
                    if (CommonUtil.isEmpty(headline)) headline = CommonUtil.nullToBlank(getStrOrNull(sObj, "highlightBanner"));
                } catch (Exception ignored) {}
            }
            sbSlides.append("slideNo:").append(s.getSlideNo())
                    .append(" layoutType:").append(CommonUtil.nullToBlank(s.getLayoutType()))
                    .append(" title:").append(title)
                    .append(" headline:").append(headline)
                    .append("\n");
        }

        // 5. 평가기준 요약
        StringBuilder sbEc = new StringBuilder();
        for (ProposalVO.EvalCriteriaVO ec : evalCriteriaList) {
            sbEc.append("- ").append(CommonUtil.nullToBlank(ec.getEvalItemNm()))
                .append(" (배점:").append(ec.getScore()).append(")")
                .append(" 평가의도:").append(CommonUtil.nullToBlank(ec.getEvalIntent()))
                .append(" 고득점조건:").append(CommonUtil.nullToBlank(ec.getHighScoreCondition()))
                .append("\n");
        }

        // 6. LLM 호출 프롬프트 조립
        String fullPrompt = promptContent
                + "\n\n## 슬라이드 목록 (slideNo 기준)\n" + sbSlides
                + "\n## 평가기준\n" + sbEc
                + "\n\n## 출력 형식 (JSON만, 코드블록 없이)\n"
                + "{\n  \"reviews\": [\n"
                + "    {\n"
                + "      \"severityCd\": \"001\",\n"
                + "      \"targetSlideNo\": 7,\n"
                + "      \"evalCriteriaNm\": \"사업이해도\",\n"
                + "      \"currentIssue\": \"...\",\n"
                + "      \"scoreImpact\": \"...\",\n"
                + "      \"fixDirection\": \"...\",\n"
                + "      \"fixSuggestionTxt\": \"...\",\n"
                + "      \"expectedScore\": 7.5\n"
                + "    }\n"
                + "  ]\n"
                + "}\n"
                + "severityCd: 001=치명적, 002=중요, 003=보완권고\n"
                + "targetSlideNo: 전체 단위 지적이면 null\n";

        // 7. LLM 호출
        String aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
        if (CommonUtil.isEmpty(aiResponse)) {
            throw new RuntimeException("Stage4 LLM 응답이 비어 있습니다.");
        }

        // 8. 응답 파싱
        String json = aiResponse.trim();
        if (json.startsWith("```")) {
            int nl = json.indexOf('\n');
            if (nl != -1) json = json.substring(nl + 1);
            if (json.endsWith("```")) json = json.substring(0, json.lastIndexOf("```")).trim();
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("Stage4 LLM 응답이 유효한 JSON이 아닙니다: " + e.getMessage());
        }
        if (!root.has("reviews") || root.get("reviews").isJsonNull() || !root.get("reviews").isJsonArray()) {
            throw new RuntimeException("Stage4 LLM 응답에 'reviews' 배열이 없습니다.");
        }

        // 9. DB 저장 (이전 이력은 남기고 새 row 추가 — 실행 시각으로 이력 구분)
        String userId = SessionUtil.getUserId();
        List<ProposalVO.ReviewVO> savedList = new java.util.ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("reviews")) {
            if (el.isJsonNull() || !el.isJsonObject()) continue;
            JsonObject item = el.getAsJsonObject();

            ProposalVO.ReviewVO rv = new ProposalVO.ReviewVO();
            rv.setReviewId(keyGenerate.generateTableKey("PTR-", "TB_PT_REVIEW", "REVIEW_ID", 6));
            rv.setPtProjectId(ptProjectId);
            rv.setSeverityCd(getStrOrNull(item, "severityCd"));
            rv.setEvalCriteriaNm(getStrOrNull(item, "evalCriteriaNm"));
            rv.setCurrentIssue(getStrOrNull(item, "currentIssue"));
            rv.setScoreImpact(getStrOrNull(item, "scoreImpact"));
            rv.setFixDirection(getStrOrNull(item, "fixDirection"));
            rv.setFixSuggestionTxt(getStrOrNull(item, "fixSuggestionTxt"));
            rv.setCreateUserId(userId);

            // expectedScore
            if (item.has("expectedScore") && !item.get("expectedScore").isJsonNull()) {
                try { rv.setExpectedScore(item.get("expectedScore").getAsDouble()); }
                catch (Exception ignored) {}
            }

            // targetSlideNo → SLIDE_ID 매핑
            if (item.has("targetSlideNo") && !item.get("targetSlideNo").isJsonNull()) {
                try {
                    int targetSlideNo = item.get("targetSlideNo").getAsInt();
                    String mappedSlideId = slideNoToId.get(targetSlideNo);
                    if (mappedSlideId != null) {
                        rv.setSlideId(mappedSlideId);
                    } else {
                        logger.warn("[PT E Stage4] 존재하지 않는 slideNo={} (ptProjectId={}), SLIDE_ID=NULL 처리", targetSlideNo, ptProjectId);
                    }
                } catch (Exception e) {
                    logger.warn("[PT E Stage4] targetSlideNo 파싱 실패: {}", e.getMessage());
                }
            }
            // null이면 rv.slideId = null (전체 단위 지적)

            // evalCriteriaNm → EVAL_CRITERIA_ID 매핑 (완전일치 → 부분일치 → NULL)
            if (CommonUtil.isNotEmpty(rv.getEvalCriteriaNm())) {
                String ecId = evalNmToId.get(rv.getEvalCriteriaNm());
                if (ecId == null) {
                    // 부분일치 시도 (contains)
                    String nm = rv.getEvalCriteriaNm().trim();
                    for (java.util.Map.Entry<String, String> entry : evalNmToId.entrySet()) {
                        String key = entry.getKey();
                        if (key.contains(nm) || nm.contains(key)) {
                            ecId = entry.getValue();
                            break;
                        }
                    }
                    if (ecId == null) {
                        logger.warn("[PT E Stage4] evalCriteriaNm 매칭 실패 (nm={}, ptProjectId={}), EVAL_CRITERIA_ID=NULL", rv.getEvalCriteriaNm(), ptProjectId);
                    }
                }
                rv.setEvalCriteriaId(ecId);
            }

            proposalDAO.insertReview(rv);
            savedList.add(rv);
        }

        // 10. 심각도순 정렬 (001>002>003)
        savedList.sort((a, b) -> {
            int oa = severityOrder(a.getSeverityCd());
            int ob = severityOrder(b.getSeverityCd());
            return Integer.compare(oa, ob);
        });

        ProposalVO.EvalSimulationResultVO result = new ProposalVO.EvalSimulationResultVO();
        result.setSavedCount(savedList.size());
        result.setReviews(savedList);
        return result;
    }

    /**
     * E — Stage4 평가 시뮬레이션 최근 실행 결과 조회
     * - CREATE_DT 분 단위로 최근 실행 배치를 식별해 반환
     * - 심각도순(치명적→중요→보완권고) 정렬
     *
     * @param ptProjectId 프로젝트 ID
     * @return List<ReviewVO> (없으면 빈 리스트)
     */
    public List<ProposalVO.ReviewVO> selectEvalSimulation(String ptProjectId) {
        String latestRunDt = proposalDAO.selectLatestReviewRunDt(ptProjectId);
        if (CommonUtil.isEmpty(latestRunDt)) return java.util.Collections.emptyList();
        return proposalDAO.selectReviewsByRunDt(ptProjectId, latestRunDt);
    }

    /** severityCd 정렬 순서 (001=1, 002=2, 003=3, 기타=4) */
    private int severityOrder(String cd) {
        if ("001".equals(cd)) return 1;
        if ("002".equals(cd)) return 2;
        if ("003".equals(cd)) return 3;
        return 4;
    }

    /** E — 전역 보완 채팅 로그 저장 (svcTy='PTRV', refId=ptProjectId) */
    private void saveReviewChatLog(String ptProjectId, String qContent, String rContent, String agentId, String userId) {
        try {
            ChatbotVO log = new ChatbotVO();
            log.setAgentId(agentId);
            log.setSvcTy("PTRV"); // PT Review Chat
            log.setRefId(ptProjectId);
            log.setQContent(qContent);
            log.setRContent(rContent);
            log.setUserId(userId);
            chatbotDAO.insertChatLog(log);
        } catch (Exception e) {
            logger.warn("[PT E] TB_CHAT_LOG insert 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step F: 출력 — PPTX/PDF 내보내기
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * F — 출력 시작
     * 1. 캐시 재사용 판단: 최근 완료 빌드의 COMPLETE_DT vs MAX(TB_PT_SLIDE.MODIFY_DT)
     *    → 캐시가 최신이면 새 presigned URL 발급 후 즉시 반환 (BUILD_STATUS_CD='003')
     * 2. 신규 빌드: TB_PT_EXPORT row 생성 → 비동기 빌드 시작 → exportId 즉시 반환
     *    비동기: ProposalPptxUtil.buildPptx() → (pdf 요청 시) LibreOffice 변환 → NCP 업로드
     *
     * @param vo ptProjectId, format('pdf'|'pptx'), agentId
     * @return ExportVO (캐시 재사용 시 즉시 완료, 신규 빌드 시 BUILD_STATUS_CD='002')
     */
    public ProposalVO.ExportVO startExport(ProposalVO.ExportRequestVO vo) throws Exception {
        String ptProjectId = vo.getPtProjectId();
        String format = CommonUtil.isNotEmpty(vo.getFormat()) ? vo.getFormat().toLowerCase().trim() : "pptx";
        if (!"pdf".equals(format) && !"pptx".equals(format)) {
            throw new RuntimeException("지원하는 포맷은 'pdf', 'pptx'입니다.");
        }

        // 1. 캐시 재사용 판단
        ProposalVO.ExportVO cached = proposalDAO.selectLatestCompletedExport(ptProjectId, format);
        if (cached != null && CommonUtil.isNotEmpty(cached.getCompleteDt()) && CommonUtil.isNotEmpty(cached.getFilePath())) {
            String maxSlideModifyDt = proposalDAO.selectMaxSlideModifyDt(ptProjectId);
            boolean cacheValid = CommonUtil.isEmpty(maxSlideModifyDt)
                    || cached.getCompleteDt().compareTo(maxSlideModifyDt) >= 0;
            if (cacheValid) {
                logger.info("[PT F] 캐시 재사용 (ptProjectId={}, format={}, completeDt={})", ptProjectId, format, cached.getCompleteDt());
                // 새 presigned URL 발급
                try {
                    String fileName = ptProjectId + "." + format;
                    String downloadUrl = fileService.createDownloadPresignedUrlStr(cached.getFilePath(), fileName);
                    if (CommonUtil.isNotEmpty(downloadUrl)) {
                        ProposalVO.ExportVO updateVO = new ProposalVO.ExportVO();
                        updateVO.setExportId(cached.getExportId());
                        updateVO.setBuildStatusCd("003"); // 캐시재사용
                        updateVO.setDownloadUrl(downloadUrl);
                        proposalDAO.updateExport(updateVO);
                        cached.setBuildStatusCd("003");
                        cached.setDownloadUrl(downloadUrl);
                    }
                } catch (Exception e) {
                    logger.warn("[PT F] 캐시 presigned URL 갱신 실패: {}", e.getMessage());
                }
                return cached;
            }
        }

        // 2. 신규 빌드 — TB_PT_EXPORT row 생성
        String exportId = keyGenerate.generateTableKey("PTE-", "TB_PT_EXPORT", "EXPORT_ID", 6);
        ProposalVO.ExportVO exportVO = new ProposalVO.ExportVO();
        exportVO.setExportId(exportId);
        exportVO.setPtProjectId(ptProjectId);
        exportVO.setFormatCd(format);
        exportVO.setBuildStatusCd("001"); // 대기
        exportVO.setCreateUserId(SessionUtil.getUserId());
        proposalDAO.insertExport(exportVO);

        // 3. 비동기 빌드 시작
        final String finalFormat = format;
        EXPORT_EXECUTOR.submit(() -> {
            runExportBuild(exportId, ptProjectId, finalFormat);
        });

        exportVO.setBuildStatusCd("002"); // 빌드중 (반환 시점 기준)
        return exportVO;
    }

    /**
     * F — 출력 상태 조회 (폴링용)
     * - BUILD_STATUS_CD가 003(캐시) 또는 004(완료)이면 presigned 다운로드 URL 재발급
     *
     * @param exportId EXPORT_ID
     * @return ExportVO (없으면 null)
     */
    public ProposalVO.ExportVO selectExportStatus(String exportId) {
        ProposalVO.ExportVO exportVO = proposalDAO.selectExportById(exportId);
        if (exportVO == null) return null;

        // 완료/캐시 상태이면 최신 다운로드 URL 발급
        if (("003".equals(exportVO.getBuildStatusCd()) || "004".equals(exportVO.getBuildStatusCd()))
                && CommonUtil.isNotEmpty(exportVO.getFilePath())) {
            try {
                String fileName = exportVO.getPtProjectId() + "." + exportVO.getFormatCd();
                String downloadUrl = fileService.createDownloadPresignedUrlStr(exportVO.getFilePath(), fileName);
                exportVO.setDownloadUrl(downloadUrl);
            } catch (Exception e) {
                logger.warn("[PT F] 다운로드 URL 발급 실패 (exportId={}): {}", exportId, e.getMessage());
            }
        }
        return exportVO;
    }

    /**
     * F — 비동기 출력 빌드 실행 (EXPORT_EXECUTOR 내부에서 호출)
     */
    private void runExportBuild(String exportId, String ptProjectId, String format) {
        try {
            // BUILD_STATUS_CD='002' (빌드중) 업데이트
            ProposalVO.ExportVO progVO = new ProposalVO.ExportVO();
            progVO.setExportId(exportId);
            progVO.setBuildStatusCd("002");
            proposalDAO.updateExport(progVO);

            // 1. 전체 슬라이드 조회 (SLIDE_NO 순)
            List<ProposalVO.SlideVO> allSlides = proposalDAO.selectAllSlidesByProject(ptProjectId);
            if (allSlides == null || allSlides.isEmpty()) {
                throw new RuntimeException("빌드할 슬라이드가 없습니다 (ptProjectId=" + ptProjectId + ")");
            }

            // 2. 프로젝트 정보 및 색상 설정 로드
            ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
            String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);

            String bgColor     = "#FFFFFF";
            String baseColor   = "#5B4FE9";
            String accentColor = "#E08A2C";
            if (CommonUtil.isNotEmpty(configJson)) {
                try {
                    JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
                    if (root.has("settings") && !root.get("settings").isJsonNull()) {
                        JsonObject settings = root.getAsJsonObject("settings");
                        if (settings.has("colors") && !settings.get("colors").isJsonNull()) {
                            JsonObject colors = settings.getAsJsonObject("colors");
                            List<String> bases   = colors.has("base")   && !colors.get("base").isJsonNull()   ? jsonArrayToList(colors.getAsJsonArray("base"))   : java.util.Collections.emptyList();
                            List<String> accents = colors.has("accent") && !colors.get("accent").isJsonNull() ? jsonArrayToList(colors.getAsJsonArray("accent")) : java.util.Collections.emptyList();
                            if (!bases.isEmpty())   baseColor   = bases.get(0);
                            if (bases.size() > 2)   bgColor     = bases.get(2);   // 3순위 = 배경색
                            if (!accents.isEmpty()) accentColor = accents.get(0);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("[PT F] configJson 색상 파싱 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
                }
            }

            // 3. 슬라이드 Map 목록 조립 (ProposalPptxUtil 입력 형식)
            List<java.util.Map<String, Object>> slideMaps = new java.util.ArrayList<>();
            for (ProposalVO.SlideVO s : allSlides) {
                java.util.Map<String, Object> slideMap = new java.util.LinkedHashMap<>();
                if (CommonUtil.isNotEmpty(s.getSlideJson())) {
                    try {
                        JsonObject sObj = JsonParser.parseString(s.getSlideJson()).getAsJsonObject();
                        // layoutType
                        slideMap.put("layoutType", CommonUtil.nullToBlank(getStrOrNull(sObj, "layoutType")));
                        // title, subtitle, headline, notes
                        slideMap.put("title",    getStrOrNull(sObj, "title"));
                        slideMap.put("subtitle", getStrOrNull(sObj, "subtitle"));
                        slideMap.put("headline", getStrOrNull(sObj, "highlightBanner") != null
                                ? getStrOrNull(sObj, "highlightBanner") : getStrOrNull(sObj, "headline"));
                        slideMap.put("notes",    getStrOrNull(sObj, "notes"));
                        // keywords, content → List<String>
                        if (sObj.has("keywords") && sObj.get("keywords").isJsonArray()) {
                            List<String> kws = new java.util.ArrayList<>();
                            for (JsonElement el : sObj.getAsJsonArray("keywords")) kws.add(el.isJsonNull() ? "" : el.getAsString());
                            slideMap.put("keywords", kws);
                        }
                        if (sObj.has("content") && sObj.get("content").isJsonArray()) {
                            List<String> ct = new java.util.ArrayList<>();
                            for (JsonElement el : sObj.getAsJsonArray("content")) ct.add(el.isJsonNull() ? "" : el.getAsString());
                            slideMap.put("content", ct);
                        }
                        // components (for infographic)
                        if (sObj.has("components")) slideMap.put("components", GSON.fromJson(sObj.get("components"), Object.class));
                    } catch (Exception e) {
                        logger.warn("[PT F] 슬라이드 JSON 파싱 실패 (slideId={}): {}", s.getSlideId(), e.getMessage());
                        slideMap.put("layoutType", CommonUtil.nullToBlank(s.getLayoutType()));
                    }
                } else {
                    slideMap.put("layoutType", CommonUtil.nullToBlank(s.getLayoutType()));
                }
                slideMaps.add(slideMap);
            }

            // 4. PPTX 빌드
            String projectTitle = project != null ? CommonUtil.nullToBlank(project.getProjectNm()) : ptProjectId;
            byte[] pptxBytes = kr.teamagent.common.util.ProposalPptxUtil.buildPptx(
                    projectTitle, slideMaps, bgColor, baseColor, accentColor);

            // 5. NCP 업로드 및 포맷별 처리
            String objectKey;
            byte[] uploadBytes;
            String contentType;

            if ("pdf".equals(format)) {
                // PPTX → PDF (LibreOffice 변환)
                String pptxFileName = "export_" + ptProjectId + ".pptx";
                byte[] pdfBytes = fileService.convertPptxBytesToPdf(pptxBytes, pptxFileName);
                objectKey   = "pt-export/" + ptProjectId + "/" + exportId + ".pdf";
                uploadBytes  = pdfBytes;
                contentType = "application/pdf";
            } else {
                objectKey   = "pt-export/" + ptProjectId + "/" + exportId + ".pptx";
                uploadBytes  = pptxBytes;
                contentType = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            }

            fileService.uploadBytes(objectKey, uploadBytes, contentType);
            logger.info("[PT F] NCP 업로드 완료 (exportId={}, key={}, size={})", exportId, objectKey, uploadBytes.length);

            // 6. presigned 다운로드 URL 발급
            String fileName = ptProjectId + "." + format;
            String downloadUrl = fileService.createDownloadPresignedUrlStr(objectKey, fileName);

            // 7. TB_PT_EXPORT 완료 업데이트
            ProposalVO.ExportVO doneVO = new ProposalVO.ExportVO();
            doneVO.setExportId(exportId);
            doneVO.setBuildStatusCd("004"); // 완료
            doneVO.setFilePath(objectKey);
            doneVO.setFileSize((long) uploadBytes.length);
            doneVO.setDownloadUrl(downloadUrl);
            doneVO.setCompleteDt(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            proposalDAO.updateExport(doneVO);
            logger.info("[PT F] 출력 빌드 완료 (exportId={}, format={}, ptProjectId={})", exportId, format, ptProjectId);

        } catch (Exception e) {
            logger.error("[PT F] 출력 빌드 실패 (exportId={}, ptProjectId={}): {}", exportId, ptProjectId, e.getMessage(), e);
            try {
                ProposalVO.ExportVO failVO = new ProposalVO.ExportVO();
                failVO.setExportId(exportId);
                failVO.setBuildStatusCd("005"); // 실패
                String errMsg = e.getMessage();
                if (errMsg != null && errMsg.length() > 2000) errMsg = errMsg.substring(0, 2000);
                failVO.setErrorMsg(errMsg);
                proposalDAO.updateExport(failVO);
            } catch (Exception ex) {
                logger.error("[PT F] TB_PT_EXPORT 실패 상태 업데이트 오류 (exportId={}): {}", exportId, ex.getMessage());
            }
        }
    }
}
