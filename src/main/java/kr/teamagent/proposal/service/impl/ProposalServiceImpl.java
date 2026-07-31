package kr.teamagent.proposal.service.impl;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import com.amazonaws.services.s3.model.S3Object;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import kr.teamagent.chat.service.impl.ChatbotAgentSupport;
import kr.teamagent.chat.service.impl.ChatbotDAO;
import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.chat.service.impl.agent.RiskDiagnosisAgentService;
import kr.teamagent.common.apilog.service.impl.ApiCallLogServiceImpl;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.prompt.service.impl.PromptServiceImpl;
import kr.teamagent.proposal.service.ProposalVO;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

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
    /** Stage2-A 문제정의용 샘플링 — 코드값 있는 카테고리(001~015)당 최대 건수 */
    private static final int CODED_CATEGORY_SAMPLE_LIMIT = 5;
    /** Stage2-A 문제정의용 샘플링 — null(미분류) 카테고리 최대 건수 */
    private static final int NULL_CATEGORY_SAMPLE_LIMIT = 20;

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
                        // 평가표 텍스트가 임계값 초과면 대용량 경로(청크 완전 추출 + Java 병합)
                        evalParsed = extractStage1FromLargeRfp(evalText, promptContent, modelId, null, agentId);
                    } else {
                        // 평가표 텍스트가 임계값 이하면 LLM 호출(단일 호출 (RFP ≤ 24,000자))
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

        String ptFileId = keyGenerate.generateTableKey("PTF", "TB_PT_FILE", "PT_FILE_ID", 6);

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

        // 7. Step A 완료 → TOC 단계(1) 해제
        advanceMaxStepNo(vo.getPtProjectId(), 1);

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

    /** LLM 출력 layoutType 명칭 → LAYOUT_TYPE_CD 코드ID (PT000005) 변환 */
    private String layoutTypeToCode(String name) {
        if (name == null) return "003";
        switch (name.trim().toLowerCase()) {
            case "cover":            return "001";
            case "section_divider":  return "002";
            case "infographic":      return "003";
            default:                 return "003";
        }
    }

    /** LAYOUT_TYPE_CD 코드ID → layoutType 명칭 역변환 (PPTX 빌더 / LLM 컨텍스트용) */
    private String codeToLayoutTypeName(String code) {
        if (code == null) return "infographic";
        switch (code.trim()) {
            case "001": return "cover";
            case "002": return "section_divider";
            case "003": return "infographic";
            default:    return "infographic";
        }
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

        // 제안사명
        String sn = getStrOrNull(settings, "submitterNm");
        if (CommonUtil.isNotEmpty(sn)) result.setSubmitterNm(sn);

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

        // 제안사명 (optional)
        if (CommonUtil.isNotEmpty(vo.getSubmitterNm())) {
            settingsObj.addProperty("submitterNm", vo.getSubmitterNm().trim());
        }

        root.add("settings", settingsObj);

        ProposalVO.ProjectVO updateVO = new ProposalVO.ProjectVO();
        updateVO.setPtProjectId(vo.getPtProjectId());
        updateVO.setProjectConfigJson(GSON.toJson(root));
        proposalDAO.updateProjectConfigJson(updateVO);

        // Step C 완료 → 본문 생성 단계(3) 해제
        advanceMaxStepNo(vo.getPtProjectId(), 3);

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
        vo.setTocId(keyGenerate.generateTableKey("PTT", "TB_PT_TOC", "TOC_ID", 6));
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
        return executeStage2(ptProjectId, totalSlideBudget, modelId, agentId, null);
    }

    /**
     * Stage 2 실행 (진행상황 콜백 지원 오버로드)
     * @param progressCallback Call 1/Call 2 진행 메시지 콜백 (null 허용)
     */
    public ProposalVO.Stage2ResultVO executeStage2(String ptProjectId, int totalSlideBudget, String modelId, String agentId,
            java.util.function.Consumer<String> progressCallback) throws Exception {

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

        // ── 분기: mandatedToc 유무에 따라 S2A 경로 선택 ──────────────────────────
        boolean hasMandatedToc = hasMandatedTocFlag(project.getWritingGuidelineJson());

        // ── Call 1: S2A (problemDefinitions + toc) ─────────────────────────────
        // 3. S2A 프롬프트 로드 (자사/경쟁사 정보 불필요)
        String s2aPromptContent = null;
        try { s2aPromptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S2A_PROBLEM_TOC"); }
        catch (Exception e) { logger.warn("[PT Stage2-A] 프롬프트 조회 실패: {}", e.getMessage()); }
        if (CommonUtil.isEmpty(s2aPromptContent))
            throw new RuntimeException("Stage2 프롬프트가 DB에 등록되어 있지 않습니다. STAGE_CD=S2A_PROBLEM_TOC 확인 필요");

        // 4. Call 1 프롬프트 조합
        //    - mandatedToc 있으면 slim(reqUltraLite 제외, toc 생성 생략)
        //    - 없으면 기존 full 프롬프트
        String s2aPrompt = hasMandatedToc
                ? buildStage2aPromptSlim(s2aPromptContent, project, requirements, evalCriteria, totalSlideBudget)
                : buildStage2aPrompt(s2aPromptContent, project, requirements, evalCriteria, totalSlideBudget);

        // 5. Call 1 LLM 호출 (1회 재시도)
        if (progressCallback != null) progressCallback.accept("Call 1 진행중 (문제정의" + (hasMandatedToc ? "" : " + 목차") + ")");
        long s2aStart = System.currentTimeMillis();
        logger.info("[PT Stage2-A] 호출 시작 - 프롬프트 길이: {}자, mandatedToc경로:{} (ptProjectId={})", s2aPrompt.length(), hasMandatedToc, ptProjectId);
        String s2aResponse = riskDiagnosisAgentService.callLlmQuerySync(s2aPrompt, modelId, "", agentId);
        if (CommonUtil.isEmpty(s2aResponse)) {
            logger.warn("[PT Stage2-A] LLM 응답 없음, 1회 재시도 (ptProjectId={})", ptProjectId);
            s2aResponse = riskDiagnosisAgentService.callLlmQuerySync(s2aPrompt, modelId, "", agentId);
        }
        if (CommonUtil.isEmpty(s2aResponse)) throw new RuntimeException("LLM 응답이 비어 있습니다. Stage 2-A(문제정의+목차) 분석을 완료할 수 없습니다.");
        logger.info("[PT Stage2-A] 완료 - 프롬프트 길이: {}자, 소요시간: {}ms (ptProjectId={})",
                s2aPrompt.length(), System.currentTimeMillis() - s2aStart, ptProjectId);

        // 6. Call 1 파싱 → problemDefinitions, (toc: mandatedToc 경로면 빈 배열)
        ProposalVO.Stage2ResultVO parsed = parseStage2aResponse(s2aResponse);

        // 7. validReqIds 구성 (requirementId 기반 — 001/002 타입 구분 없이 전체 포함)
        java.util.Set<String> validReqIds = new java.util.HashSet<>();
        if (requirements != null) for (ProposalVO.RequirementVO r : requirements) if (CommonUtil.isNotEmpty(r.getRequirementId())) validReqIds.add(r.getRequirementId());

        if (hasMandatedToc) {
            // ── mandatedToc 경로: Java에서 toc 구조 생성 + S2C로 coveredReqNos 매핑 ──

            // 7-A. mandatedToc → TocVO 리스트 (LLM 없음)
            List<ProposalVO.TocVO> mandatedTocList = buildTocListFromMandatedToc(project.getWritingGuidelineJson());
            parsed.setToc(mandatedTocList);
            logger.info("[PT Stage2-A] mandatedToc → TocVO 변환 완료 (tocCount={}, ptProjectId={})", mandatedTocList.size(), ptProjectId);

            // 7-B. Call 1B — S2C: coveredReqNos 매핑 전용 LLM 호출
            if (progressCallback != null) progressCallback.accept("Call 1B 진행중 (요구사항-목차 매핑)");
            String s2cPromptContent = null;
            try { s2cPromptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S2C_COVEREDREQNOS"); }
            catch (Exception e) { logger.warn("[PT Stage2-C] 프롬프트 조회 실패, 기본 프롬프트 사용: {}", e.getMessage()); }
            if (CommonUtil.isEmpty(s2cPromptContent)) s2cPromptContent = buildDefaultStage2cPrompt();

            String s2cPrompt = buildStage2cCoveredReqIdsPrompt(s2cPromptContent, parsed.getToc(), requirements);
            long s2cStart = System.currentTimeMillis();
            logger.info("[PT Stage2-C] 호출 시작 - 프롬프트 길이: {}자 (ptProjectId={})", s2cPrompt.length(), ptProjectId);
            String s2cResponse = riskDiagnosisAgentService.callLlmQuerySync(s2cPrompt, modelId, "", agentId);
            if (CommonUtil.isEmpty(s2cResponse)) {
                logger.warn("[PT Stage2-C] LLM 응답 없음, 1회 재시도 (ptProjectId={})", ptProjectId);
                s2cResponse = riskDiagnosisAgentService.callLlmQuerySync(s2cPrompt, modelId, "", agentId);
            }
            if (CommonUtil.isNotEmpty(s2cResponse)) {
                parseAndApplyStage2cResponse(parsed.getToc(), s2cResponse, ptProjectId);
                logger.info("[PT Stage2-C] 완료 - 소요시간: {}ms (ptProjectId={})", System.currentTimeMillis() - s2cStart, ptProjectId);
            } else {
                logger.warn("[PT Stage2-C] LLM 응답 없음, coveredReqNos 배정 생략 (ptProjectId={})", ptProjectId);
            }

            // 7-C. coveredReqNos 검증
            validateAndCleanTocReqIds(parsed.getToc(), validReqIds, ptProjectId);

        } else {
            // ── 기존 경로: LLM이 생성한 toc 사용 ─────────────────────────────────

            // 7. coveredReqNos 검증
            validateAndCleanTocReqIds(parsed.getToc(), validReqIds, ptProjectId);

            // 8. mandatedToc 강제 적용 (tocMandatoryYn=N 이므로 실질적으로 no-op, 안전망으로 유지)
            applyMandatedTocIfNeeded(project.getWritingGuidelineJson(), parsed);
        }

        // 9. 미커버 요구사항 경고
        warnUncoveredRequirements(parsed.getToc(), validReqIds, ptProjectId);

        // ── 자사/경쟁사/기타 파일 텍스트 추출 (Call 2 에서만 사용) ────────────────
        String ownContext = extractMultiFileText(companyFileIds);
        if (CommonUtil.isEmpty(ownContext)) ownContext = "(자사 자료 없음)";
        String competitorContext = extractMultiFileText(competitorFileIds);
        if (CommonUtil.isEmpty(competitorContext)) competitorContext = "(경쟁사 자료 없음)";
        String etcRefContext = extractMultiFileText(etcRefFileIds);
        if (CommonUtil.isEmpty(etcRefContext)) etcRefContext = "(기타 참고자료 없음)";

        // ── Call 2: S2B (winThemes) ────────────────────────────────────────────
        // 10. S2B 프롬프트 로드 (자사/경쟁사 정보 + Call 1 problemDefinitions 사용)
        String s2bPromptContent = null;
        try { s2bPromptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S2B_WINTHEME"); }
        catch (Exception e) { logger.warn("[PT Stage2-B] 프롬프트 조회 실패: {}", e.getMessage()); }
        if (CommonUtil.isEmpty(s2bPromptContent))
            throw new RuntimeException("Stage2 프롬프트가 DB에 등록되어 있지 않습니다. STAGE_CD=S2B_WINTHEME 확인 필요");

        // 11. Call 2 프롬프트 조합 (problemDefinitions + 자사/경쟁사/기타)
        String s2bPrompt = buildStage2bWinThemePrompt(s2bPromptContent, project, parsed.getProblemDefinitions(), ownContext, competitorContext, etcRefContext);

        // 12. Call 2 LLM 호출 (1회 재시도)
        if (progressCallback != null) progressCallback.accept("Call 2 진행중 (Win Theme)");
        long s2bStart = System.currentTimeMillis();
        logger.info("[PT Stage2-B] 호출 시작 - 프롬프트 길이: {}자 (ptProjectId={})", s2bPrompt.length(), ptProjectId);
        String s2bResponse = riskDiagnosisAgentService.callLlmQuerySync(s2bPrompt, modelId, "", agentId);
        if (CommonUtil.isEmpty(s2bResponse)) {
            logger.warn("[PT Stage2-B] LLM 응답 없음, 1회 재시도 (ptProjectId={})", ptProjectId);
            s2bResponse = riskDiagnosisAgentService.callLlmQuerySync(s2bPrompt, modelId, "", agentId);
        }
        if (CommonUtil.isEmpty(s2bResponse)) throw new RuntimeException("LLM 응답이 비어 있습니다. Stage 2-B(Win Theme) 분석을 완료할 수 없습니다.");
        logger.info("[PT Stage2-B] 완료 - 프롬프트 길이: {}자, 소요시간: {}ms (ptProjectId={})",
                s2bPrompt.length(), System.currentTimeMillis() - s2bStart, ptProjectId);

        // 13. Call 2 파싱 → winThemes
        List<ProposalVO.WinThemeVO> winThemes = parseStage2bResponse(s2bResponse);

        // 14. evidence 품질 경고
        validateStage2Evidence(winThemes, ptProjectId);

        // ── 병합 및 저장 ────────────────────────────────────────────────────────
        // 15. Call 1(problemDefinitions + toc) + Call 2(winThemes) 병합
        parsed.setWinThemes(winThemes);

        // 16. 슬라이드 수 Java 계산 (Call 1 toc + evalCriteria 기준)
        calculateSlideCounts(parsed.getToc(), evalCriteria, totalSlideBudget);

        // 17. 트랜잭션 저장
        saveStage2Result(ptProjectId, parsed, evalCriteria, requirements);

        parsed.setPtProjectId(ptProjectId);
        return parsed;
    }

    /**
     * Stage 2 결과 DB 쓰기 — 트랜잭션 분리
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveStage2Result(String ptProjectId, ProposalVO.Stage2ResultVO parsed,
            List<ProposalVO.EvalCriteriaVO> evalCriteriaFromDb,
            List<ProposalVO.RequirementVO> requirementsFromDb) throws Exception {
        String userId = SessionUtil.getUserId();

        logger.info("saveStage2Result userId: {}", userId);
        // 문제 정의 초기화 + 재등록
        proposalDAO.deleteProblemDefinitionsByProject(ptProjectId);
        if (parsed.getProblemDefinitions() != null) {
            int ord = 0;
            for (ProposalVO.ProblemDefinitionVO pd : parsed.getProblemDefinitions()) {
                pd.setProblemId(keyGenerate.generateTableKey("PTP", "TB_PT_PROBLEM_DEFINITION", "PROBLEM_ID", 6));
                pd.setPtProjectId(ptProjectId);
                pd.setCreateUserId(userId != null ? userId : "hj249");
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
                wt.setWinThemeId(keyGenerate.generateTableKey("PTW", "TB_PT_WIN_THEME", "WIN_THEME_ID", 6));
                wt.setPtProjectId(ptProjectId);
                wt.setCreateUserId(userId != null ? userId : "hj249");
                if (wt.getSortOrd() == null) wt.setSortOrd(ord++);
                proposalDAO.insertWinTheme(wt);
            }
        }

        // ── TOC 매핑 업데이트 (DELETE/INSERT 없음 — Step B 확정 목차 보존)
        // LINKED_EVAL_CRITERIA_ID, COVERED_REQ_IDS_JSON만 갱신
        List<ProposalVO.TocVO> existingTocList = proposalDAO.selectTocList(ptProjectId);
        logger.info("[PT Stage2] 기존 TOC 레코드 조회: {}건 (ptProjectId={})", existingTocList.size(), ptProjectId);

        // 기존 toc lookup 맵: sectionNo 우선, sectionNm 대체
        java.util.Map<String, ProposalVO.TocVO> dbBySectionNo = new java.util.LinkedHashMap<>();
        java.util.Map<String, ProposalVO.TocVO> dbBySectionNm = new java.util.LinkedHashMap<>();
        for (ProposalVO.TocVO dbToc : existingTocList) {
            if (CommonUtil.isNotEmpty(dbToc.getSectionNo()))
                dbBySectionNo.put(dbToc.getSectionNo().trim(), dbToc);
            if (CommonUtil.isNotEmpty(dbToc.getSectionNm()))
                dbBySectionNm.putIfAbsent(dbToc.getSectionNm().trim(), dbToc);
        }

        // evalItemNm → evalCriteriaId 맵
        java.util.Map<String, String> evalNmToId = new java.util.HashMap<>();
        if (evalCriteriaFromDb != null) {
            for (ProposalVO.EvalCriteriaVO ec : evalCriteriaFromDb) {
                if (CommonUtil.isNotEmpty(ec.getEvalItemNm())) evalNmToId.put(ec.getEvalItemNm(), ec.getEvalCriteriaId());
            }
        }

        // 유효한 requirementId Set (LLM 할루시네이션 검증용, 001/002 타입 모두 포함)
        java.util.Set<String> validReqIds = new java.util.HashSet<>();
        if (requirementsFromDb != null) {
            for (ProposalVO.RequirementVO req : requirementsFromDb) {
                if (CommonUtil.isNotEmpty(req.getRequirementId())) validReqIds.add(req.getRequirementId());
            }
        }

        // LLM 응답 toc 항목별 매칭 + UPDATE
        int tocMatchedCount = 0, tocUnmatchedCount = 0;
        java.util.Map<String, String> evalIdToPosition = new java.util.HashMap<>();

        if (parsed.getToc() != null) {
            for (ProposalVO.TocVO llmToc : parsed.getToc()) {
                // 1) DB toc 매칭: sectionNo 우선, sectionNm 대체
                ProposalVO.TocVO dbToc = null;
                String llmNo = llmToc.getSectionNo();
                String llmNm = llmToc.getSectionNm();
                if (CommonUtil.isNotEmpty(llmNo)) dbToc = dbBySectionNo.get(llmNo.trim());
                if (dbToc == null && CommonUtil.isNotEmpty(llmNm)) dbToc = dbBySectionNm.get(llmNm.trim());
                if (dbToc == null) {
                    logger.warn("[PT Stage2] TOC 매칭 실패 — LLM 항목 건너뜀 (sectionNo={}, sectionNm={}, ptProjectId={})",
                            llmNo, llmNm, ptProjectId);
                    tocUnmatchedCount++;
                    continue;
                }

                // 2) linkedEvalCriteriaNm → evalCriteriaId
                String evalCriteriaId = null;
                String linkedNm = llmToc.getLinkedEvalCriteriaNm();
                if (CommonUtil.isNotEmpty(linkedNm)) {
                    evalCriteriaId = evalNmToId.get(linkedNm);
                    if (evalCriteriaId == null)
                        logger.warn("[PT Stage2] evalCriteria 매칭 실패: linkedEvalCriteriaNm={}, ptProjectId={}", linkedNm, ptProjectId);
                }

                // 3) coveredReqIds(requirementId[]) → 유효성 검증 후 JSON 저장
                String coveredReqIdsJson = null;
                if (llmToc.getCoveredReqIds() != null) {
                    java.util.List<String> validatedIds = new java.util.ArrayList<>();
                    for (String reqId : llmToc.getCoveredReqIds()) {
                        if (validReqIds.contains(reqId)) {
                            validatedIds.add(reqId);
                        } else {
                            logger.warn("[PT Stage2] LLM 할루시네이션 — 존재하지 않는 requirementId 제거: reqId={}, ptProjectId={}", reqId, ptProjectId);
                        }
                    }
                    coveredReqIdsJson = GSON.toJson(validatedIds); // 0건이면 "[]"
                }

                // 4) UPDATE (TOC_ID 유지, 매핑 컬럼만 갱신)
                ProposalVO.TocVO updToc = new ProposalVO.TocVO();
                updToc.setTocId(dbToc.getTocId());
                updToc.setLinkedEvalCriteriaId(evalCriteriaId);
                updToc.setCoveredReqIdsJson(coveredReqIdsJson);
                proposalDAO.updateTocEvalLinkAndReqIds(updToc);
                tocMatchedCount++;

                // 5) SLIDE_REFLECT_POSITION 구성 — DB 기준 sectionNo/sectionNm 사용
                if (evalCriteriaId != null) {
                    String pos = (dbToc.getSectionNo() != null ? dbToc.getSectionNo() : "")
                            + " " + (dbToc.getSectionNm() != null ? dbToc.getSectionNm() : "");
                    evalIdToPosition.merge(evalCriteriaId, pos.trim(), (a, b) -> a + ", " + b);
                }
            }
        }
        logger.info("[PT Stage2] TOC 매핑 업데이트 완료: 매칭 {}건, 미매칭(건너뜀) {}건 (ptProjectId={})",
                tocMatchedCount, tocUnmatchedCount, ptProjectId);

        // 평가기준 SLIDE_REFLECT_POSITION 업데이트
        for (java.util.Map.Entry<String, String> e : evalIdToPosition.entrySet()) {
            ProposalVO.EvalCriteriaVO upd = new ProposalVO.EvalCriteriaVO();
            upd.setEvalCriteriaId(e.getKey());
            upd.setSlideReflectPosition(e.getValue());
            proposalDAO.updateEvalCriteriaSlideReflectPosition(upd);
        }
        // 매칭 안 된 evalCriteria 경고
        if (evalCriteriaFromDb != null) {
            for (ProposalVO.EvalCriteriaVO ec : evalCriteriaFromDb) {
                if (!evalIdToPosition.containsKey(ec.getEvalCriteriaId()))
                    logger.warn("[PT Stage2] 평가항목 SLIDE_REFLECT_POSITION 미매칭: evalItemNm={}, ptProjectId={}", ec.getEvalItemNm(), ptProjectId);
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
     * LLM 응답에서 코드블록(```json ... ```) 제거 후 trim
     */
    private String stripJsonCodeBlock(String aiResponse) {
        String json = aiResponse.trim();
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
        return json;
    }

    /**
     * Call 1(S2A) 응답 JSON 파싱 → Stage2ResultVO (problemDefinitions + toc)
     * - problemDefinitions: problemTypeCd, currentProblem 필수
     * - toc: level, no, title, parentNo(level2만), linkedEvalCriteriaNm, coveredReqNos 파싱
     * - winThemes 는 이 단계에서 비어 있음 (Call 2 에서 채움)
     */
    private ProposalVO.Stage2ResultVO parseStage2aResponse(String aiResponse) {
        String json = stripJsonCodeBlock(aiResponse);

        JsonObject root;
        try {
            JsonElement rootEl = JsonParser.parseString(json);
            if (rootEl.isJsonArray()) {
                // LLM이 problemDefinitions 배열만 직접 반환한 경우 보정
                // (slim 모드에서 toc=[] 지시를 오해하고 배열만 출력할 때 발생)
                logger.warn("[PT Stage2-A] LLM 응답이 최상위 배열 형태 — problemDefinitions 배열로 보정 처리 (ptProjectId 미전달)");
                root = new JsonObject();
                root.add("problemDefinitions", rootEl.getAsJsonArray());
                root.add("toc", new com.google.gson.JsonArray());
            } else {
                root = rootEl.getAsJsonObject();
            }
        } catch (Exception e) {
            throw new RuntimeException("[PT Stage2-A] problemDefinitions/toc 파싱 실패 - 유효한 JSON이 아닙니다: " + e.getMessage());
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
                    logger.warn("[PT Stage2-A] problemDefinitions 항목에 currentProblem 누락, 건너뜀");
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
                // coveredReqIds (requirementId 배열)
                if (obj.has("coveredReqIds") && !obj.get("coveredReqIds").isJsonNull() && obj.get("coveredReqIds").isJsonArray()) {
                    List<String> reqIds = new java.util.ArrayList<>();
                    for (JsonElement rn : obj.getAsJsonArray("coveredReqIds")) {
                        if (!rn.isJsonNull()) reqIds.add(rn.getAsString());
                    }
                    toc.setCoveredReqIds(reqIds);
                }
                toc.setPlannedSlideCnt(1); // 기본값, calculateSlideCounts에서 재계산
                toc.setSortOrd(globalOrd++);
                tocList.add(toc);
            }
        }

        ProposalVO.Stage2ResultVO result = new ProposalVO.Stage2ResultVO();
        result.setProblemDefinitions(problemDefs);
        result.setWinThemes(new java.util.ArrayList<>());
        result.setToc(tocList);
        return result;
    }

    /**
     * Call 2(S2B) 응답 JSON 파싱 → winThemes 목록
     * - winThemes: coreMessage 필수
     */
    private List<ProposalVO.WinThemeVO> parseStage2bResponse(String aiResponse) {
        String json = stripJsonCodeBlock(aiResponse);

        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("[PT Stage2-B] winThemes 파싱 실패 - 유효한 JSON이 아닙니다: " + e.getMessage());
        }

        List<ProposalVO.WinThemeVO> winThemes = new java.util.ArrayList<>();
        if (root.has("winThemes") && !root.get("winThemes").isJsonNull()) {
            for (JsonElement el : root.getAsJsonArray("winThemes")) {
                JsonObject obj = el.getAsJsonObject();
                ProposalVO.WinThemeVO wt = new ProposalVO.WinThemeVO();
                wt.setCoreMessage(getStrOrNull(obj, "coreMessage"));
                if (CommonUtil.isEmpty(wt.getCoreMessage())) {
                    logger.warn("[PT Stage2-B] winThemes 항목에 coreMessage 누락, 건너뜀");
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
        return winThemes;
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
     * coveredReqIds 검증 및 정리 (LLM 할루시네이션 방어)
     * - toc[].coveredReqIds에 validReqIds에 없는 requirementId 제거 + 경고 로그
     */
    private void validateAndCleanTocReqIds(List<ProposalVO.TocVO> tocList, java.util.Set<String> validReqIds, String ptProjectId) {
        if (tocList == null || validReqIds == null) return;
        for (ProposalVO.TocVO toc : tocList) {
            if (toc.getCoveredReqIds() == null) continue;
            java.util.Iterator<String> it = toc.getCoveredReqIds().iterator();
            while (it.hasNext()) {
                String reqId = it.next();
                if (!validReqIds.contains(reqId)) {
                    logger.warn("[PT Stage2] LLM 할루시네이션 — 존재하지 않는 requirementId 제거: reqId={}, sectionNm={}, ptProjectId={}",
                            reqId, toc.getSectionNm(), ptProjectId);
                    it.remove();
                }
            }
        }
    }

    /**
     * 미커버 요구사항 경고
     * - 전체 coveredReqIds 합집합에 없는 requirementId가 있으면 경고 로그
     */
    private void warnUncoveredRequirements(List<ProposalVO.TocVO> tocList, java.util.Set<String> validReqIds, String ptProjectId) {
        if (tocList == null || validReqIds == null || validReqIds.isEmpty()) return;
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (ProposalVO.TocVO toc : tocList) {
            if (toc.getCoveredReqIds() != null) covered.addAll(toc.getCoveredReqIds());
        }
        for (String reqId : validReqIds) {
            if (!covered.contains(reqId)) {
                logger.warn("[PT Stage2] 미커버 요구사항 발견: requirementId={}, ptProjectId={}", reqId, ptProjectId);
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
     * Call 1(S2A) 프롬프트 조합 — problemDefinitions + toc 생성용
     * - 입력: 작성지침 + 압축된 requirements(Lite) + 압축된 evalCriteria(Lite) + 사업 기본정보 + 목표 슬라이드 수
     * - 자사/경쟁사/기타 정보는 포함하지 않음
     */
    private String buildStage2aPrompt(String promptContent,
            ProposalVO.ProjectVO project,
            List<ProposalVO.RequirementVO> requirements,
            List<ProposalVO.EvalCriteriaVO> evalCriteria,
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
            int rawReqLen = GSON.toJson(requirements).length();
            logger.info("[PT Stage2-A] 압축 전 요구사항 개수: {}건, 원본 JSON 길이(추정): {}자", requirements.size(), rawReqLen);
            List<ProposalVO.RequirementLiteVO> reqLite = requirements.stream()
                    .map(this::toRequirementLite)
                    .collect(java.util.stream.Collectors.toList());
            long truncatedCount = reqLite.stream()
                    .filter(r -> r.getReqContent() != null && r.getReqContent().endsWith("...(생략)"))
                    .count();
            String reqLiteJson = GSON.toJson(reqLite);
            int pct = rawReqLen > 0 ? (int) Math.round(reqLiteJson.length() * 100.0 / rawReqLen) : 0;
            logger.info("[PT Stage2-A] 압축 후 요구사항 JSON 길이: {}자 (압축률: {}%, reqContent 300자 초과로 잘린 건수: {}건)",
                    reqLiteJson.length(), pct, truncatedCount);

            // ── 문제정의용 샘플링 ──────────────────────────────────────────────────
            List<ProposalVO.RequirementLiteVO> requirementsForProblemDef =
                    sampleRequirementsForProblemDef(reqLite);
            long codedCategoryCount = requirementsForProblemDef.stream()
                    .filter(r -> r.getReqCategoryCd() != null).map(ProposalVO.RequirementLiteVO::getReqCategoryCd)
                    .distinct().count();
            long nullSampleCount = requirementsForProblemDef.stream()
                    .filter(r -> r.getReqCategoryCd() == null).count();
            logger.info("[PT Stage2-A] 문제정의용 샘플링: 전체 {}건 → {}건 (코드분류 카테고리 {}개 × 최대{}건, null카테고리 {}건 → 최대{}건 샘플링)",
                    reqLite.size(), requirementsForProblemDef.size(),
                    codedCategoryCount, CODED_CATEGORY_SAMPLE_LIMIT,
                    nullSampleCount, NULL_CATEGORY_SAMPLE_LIMIT);
            // ── 목차매핑용 초경량 압축 (reqContent 80자 제한) ─────────────────────
            List<ProposalVO.RequirementLiteVO> reqUltraLite = requirements.stream()
                    .map(this::toRequirementUltraLite)
                    .collect(java.util.stream.Collectors.toList());
            String reqUltraLiteJson = GSON.toJson(reqUltraLite);
            int tocPct = reqLiteJson.length() > 0
                    ? (int) Math.round((1.0 - (double) reqUltraLiteJson.length() / reqLiteJson.length()) * 100)
                    : 0;
            logger.info("[PT Stage2-A] 목차매핑용 요구사항: {}건 (샘플링 없음, 전체 유지) → reqContent 80자 제한 적용, JSON 길이: {}자 (기존 400자 기준 대비 {}% 절감)",
                    reqUltraLite.size(), reqUltraLiteJson.length(), tocPct);

            sb.append("\n\n## 문제정의용 요구사항 샘플(카테고리별 대표) (JSON)");
            sb.append("\n※ 이 목록은 발주기관 문제 유형 파악(problemDefinitions)에만 사용하세요. 카테고리별 대표 샘플만 포함합니다.\n");
            sb.append(GSON.toJson(requirementsForProblemDef));

            sb.append("\n\n## 목차 매핑용 요구사항 전체 목록 (JSON)");
            sb.append("\n※ 이 목록은 toc.coveredReqIds 매핑 전용입니다. requirementId 값을 그대로 배정하세요. reqNo가 null인 항목(전략적해석)도 포함되어 있으며 동일하게 배정 대상입니다.\n");
            sb.append(reqUltraLiteJson);
        }

        if (evalCriteria != null && !evalCriteria.isEmpty()) {
            int rawEvalLen = GSON.toJson(evalCriteria).length();
            logger.info("[PT Stage2-A] 압축 전 평가기준 개수: {}건, 원본 JSON 길이(추정): {}자", evalCriteria.size(), rawEvalLen);
            List<ProposalVO.EvalCriteriaLiteVO> evalLite = evalCriteria.stream()
                    .map(this::toEvalCriteriaLite)
                    .collect(java.util.stream.Collectors.toList());
            String evalLiteJson = GSON.toJson(evalLite);
            int pct = rawEvalLen > 0 ? (int) Math.round(evalLiteJson.length() * 100.0 / rawEvalLen) : 0;
            logger.info("[PT Stage2-A] 압축 후 평가기준 JSON 길이: {}자 (압축률: {}%)", evalLiteJson.length(), pct);
            sb.append("\n\n## 평가기준 목록 (JSON)\n").append(evalLiteJson);
        }

        logger.info("[PT Stage2-A] 프롬프트 구성 분해 — 템플릿: {}자, 작성지침: {}자, 최종 합계: {}자",
                promptContent.length(),
                CommonUtil.isNotEmpty(project.getWritingGuidelineJson()) ? project.getWritingGuidelineJson().length() : 0,
                sb.length());
        return sb.toString();
    }

    /**
     * Call 1(S2A) 프롬프트 조합 — slim 버전 (mandatedToc 있을 때 사용)
     * - reqUltraLite(목차 매핑용 요구사항 전체) 제외 → 프롬프트 크기 절감
     * - toc 생성을 LLM에 요청하지 않음 (Java에서 mandatedToc 직접 변환 후 S2C로 coveredReqNos만 매핑)
     */
    private String buildStage2aPromptSlim(String promptContent,
            ProposalVO.ProjectVO project,
            List<ProposalVO.RequirementVO> requirements,
            List<ProposalVO.EvalCriteriaVO> evalCriteria,
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
            List<ProposalVO.RequirementLiteVO> requirementsForProblemDef = sampleRequirementsForProblemDef(reqLite);
            logger.info("[PT Stage2-A slim] 문제정의용 샘플링: 전체 {}건 → {}건", reqLite.size(), requirementsForProblemDef.size());
            sb.append("\n\n## 문제정의용 요구사항 샘플(카테고리별 대표) (JSON)");
            sb.append("\n※ 이 목록은 발주기관 문제 유형 파악(problemDefinitions)에만 사용하세요. 카테고리별 대표 샘플만 포함합니다.\n");
            sb.append(GSON.toJson(requirementsForProblemDef));
        }

        if (evalCriteria != null && !evalCriteria.isEmpty()) {
            List<ProposalVO.EvalCriteriaLiteVO> evalLite = evalCriteria.stream()
                    .map(this::toEvalCriteriaLite)
                    .collect(java.util.stream.Collectors.toList());
            sb.append("\n\n## 평가기준 목록 (JSON)\n").append(GSON.toJson(evalLite));
        }

        // toc는 S2C에서 별도 처리하므로 빈 배열 출력 지시
        sb.append("\n\n※ 이 사업은 RFP에 목차가 이미 지정되어 있습니다.")
          .append(" 출력 JSON 형식은 {\"problemDefinitions\":[...],\"toc\":[]} 를 반드시 그대로 유지하고,")
          .append(" toc는 빈 배열([])로만 출력하세요. problemDefinitions 배열만 내용을 채우면 됩니다.");

        logger.info("[PT Stage2-A slim] 프롬프트 구성 완료 — 합계: {}자 (reqUltraLite 제외)", sb.length());
        return sb.toString();
    }

    /**
     * writingGuidelineJson에 tocMandatoryYn=Y + mandatedToc(비어 있지 않음)가 있는지 확인
     */
    private boolean hasMandatedTocFlag(String writingGuidelineJson) {
        if (CommonUtil.isEmpty(writingGuidelineJson)) return false;
        try {
            JsonObject wg = JsonParser.parseString(writingGuidelineJson).getAsJsonObject();
            if (!"Y".equals(getStrOrNull(wg, "tocMandatoryYn"))) return false;
            if (!wg.has("mandatedToc") || wg.get("mandatedToc").isJsonNull()) return false;
            return wg.getAsJsonArray("mandatedToc").size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * writingGuidelineJson의 mandatedToc 배열을 in-memory TocVO 리스트로 변환.
     * DB insert 없음 — executeStage2의 mandatedToc 경로 전용.
     * coveredReqNos는 빈 리스트로 초기화 (S2C 호출 후 채워짐).
     */
    private List<ProposalVO.TocVO> buildTocListFromMandatedToc(String writingGuidelineJson) {
        List<ProposalVO.TocVO> result = new java.util.ArrayList<>();
        try {
            JsonObject wg = JsonParser.parseString(writingGuidelineJson).getAsJsonObject();
            JsonArray arr = wg.getAsJsonArray("mandatedToc");
            int sortOrd = 0;
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                ProposalVO.TocVO toc = new ProposalVO.TocVO();
                int level = 1;
                if (obj.has("level") && !obj.get("level").isJsonNull()) {
                    try { level = obj.get("level").getAsInt(); } catch (Exception ignored) {}
                }
                toc.setLevel(level);
                String no = getStrOrNull(obj, "no");
                toc.setNo(no);
                toc.setSectionNo(no);
                toc.setSectionNm(getStrOrNull(obj, "title"));
                if (level == 2) toc.setParentNo(getStrOrNull(obj, "parentNo"));
                toc.setPlannedSlideCnt(1);
                toc.setSortOrd(sortOrd++);
                toc.setCoveredReqIds(new java.util.ArrayList<>());
                result.add(toc);
            }
        } catch (Exception e) {
            logger.warn("[PT Stage2-C] mandatedToc → TocVO 변환 실패: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Call 1B(S2C) 프롬프트 조합 — coveredReqNos 매핑 전용
     * - 입력: mandatedToc 기반 리프 목록 + reqUltraLite 전체 요구사항
     * - 리프 = 자신의 no가 다른 항목의 parentNo로 사용되지 않는 노드
     * - LLM에 title 기반으로 응답하도록 요청 (파싱 시 title로 매칭)
     */
    private String buildStage2cCoveredReqIdsPrompt(String promptContent,
            List<ProposalVO.TocVO> tocList,
            List<ProposalVO.RequirementVO> requirements) {

        // 리프 노드 탐색: parentNo로 참조되는 no 집합 구성
        java.util.Set<String> parentNos = new java.util.HashSet<>();
        if (tocList != null) {
            for (ProposalVO.TocVO t : tocList) {
                if (CommonUtil.isNotEmpty(t.getParentNo())) parentNos.add(t.getParentNo());
            }
        }

        List<java.util.Map<String, Object>> leafList = new java.util.ArrayList<>();
        for (ProposalVO.TocVO t : (tocList != null ? tocList : java.util.Collections.<ProposalVO.TocVO>emptyList())) {
            String no = t.getNo();
            if (!parentNos.contains(no)) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("title", t.getSectionNm());
                if (CommonUtil.isNotEmpty(t.getParentNo())) m.put("parentNo", t.getParentNo());
                leafList.add(m);
            }
        }

        List<ProposalVO.RequirementLiteVO> reqUltraLite =
                (requirements != null ? requirements : java.util.Collections.<ProposalVO.RequirementVO>emptyList())
                .stream()
                .map(this::toRequirementUltraLite)
                .collect(java.util.stream.Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append(promptContent);
        sb.append("\n\n## 목차 소분류 목록 (JSON)\n");
        sb.append(GSON.toJson(leafList));
        sb.append("\n\n## 요구사항 전체 목록 (JSON)");
        sb.append("\n※ requirementId 값을 coveredReqIds에 그대로 사용하세요. reqNo가 null인 항목(전략적해석)도 포함되며 동일하게 배정 대상입니다.\n");
        sb.append(GSON.toJson(reqUltraLite));

        logger.info("[PT Stage2-C] 프롬프트 구성 완료 — 리프목차: {}개, 요구사항: {}건, 합계: {}자",
                leafList.size(), reqUltraLite.size(), sb.length());
        return sb.toString();
    }

    /**
     * Call 1B(S2C) 응답 JSON 파싱 후 tocList의 coveredReqIds에 적용.
     * 응답 형식: [{"title":"소분류제목","coveredReqIds":["PTQ000001",...]}, ...]
     * title로 TocVO를 매칭하므로 파싱 실패해도 non-fatal (경고 로그만).
     */
    private void parseAndApplyStage2cResponse(List<ProposalVO.TocVO> tocList, String s2cResponse, String ptProjectId) {
        String json = stripJsonCodeBlock(s2cResponse);
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();

            // sectionNm → TocVO 맵 구성
            java.util.Map<String, ProposalVO.TocVO> titleMap = new java.util.LinkedHashMap<>();
            if (tocList != null) {
                for (ProposalVO.TocVO t : tocList) {
                    if (CommonUtil.isNotEmpty(t.getSectionNm())) titleMap.put(t.getSectionNm(), t);
                }
            }

            int applied = 0;
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String title = getStrOrNull(obj, "title");
                ProposalVO.TocVO toc = titleMap.get(title);
                if (toc == null) {
                    logger.warn("[PT Stage2-C] S2C 응답에 알 수 없는 title='{}', 무시 (ptProjectId={})", title, ptProjectId);
                    continue;
                }
                List<String> reqIds = new java.util.ArrayList<>();
                if (obj.has("coveredReqIds") && obj.get("coveredReqIds").isJsonArray()) {
                    for (JsonElement rn : obj.getAsJsonArray("coveredReqIds")) {
                        if (!rn.isJsonNull() && CommonUtil.isNotEmpty(rn.getAsString())) {
                            reqIds.add(rn.getAsString());
                        }
                    }
                }
                toc.setCoveredReqIds(reqIds);
                applied++;
            }
            logger.info("[PT Stage2-C] coveredReqIds 배정 완료 — {}개 소목차 처리 (ptProjectId={})", applied, ptProjectId);
        } catch (Exception e) {
            logger.warn("[PT Stage2-C] S2C 응답 파싱 실패, coveredReqIds 배정 생략 (ptProjectId={}): {}", ptProjectId, e.getMessage());
        }
    }

    /**
     * S2C_COVEREDREQNOS 프롬프트 DB 미등록 시 사용할 기본 프롬프트
     */
    private String buildDefaultStage2cPrompt() {
        return "당신은 공공정보화 제안서 구조화 전문가입니다.\n"
                + "RFP 요구사항을 이미 확정된 제안서 목차의 각 소분류에 배정하세요.\n"
                + "목차 구조 변경이나 제목 수정은 하지 않습니다. 배정만 수행합니다.\n\n"
                + "## 원칙\n"
                + "1. 제공된 소분류 목록의 title을 그대로 사용하세요. 항목을 추가하거나 제거하지 마세요.\n"
                + "2. 요구사항 목록에 있는 requirementId 값을 그대로 coveredReqIds에 사용하세요. "
                + "requirementId를 새로 만들거나 변형하지 마세요. reqNo가 null인 항목(전략적해석)도 동일하게 배정 대상입니다.\n"
                + "3. 하나의 requirementId는 가장 관련성이 높은 소분류 1~2개에만 배정하세요. 중복 배정은 최소화하세요.\n"
                + "4. 소분류와 관련 요구사항이 없으면 coveredReqIds를 빈 배열([])로 두세요.\n"
                + "5. 배정 근거는 reqCategoryCd와 reqContent 키워드 기반으로 판단하세요.\n\n"
                + "## 출력 형식\n"
                + "다른 설명 없이 아래 형태의 JSON 배열만 출력하세요. 코드블록(```)은 포함하지 마세요.\n\n"
                + "[\n"
                + "  {\"title\": \"소분류 제목\", \"coveredReqIds\": [\"PTQ000001\", \"PTQ000005\"]},\n"
                + "  ...\n"
                + "]\n\n"
                + "목차 소분류 목록에 있는 모든 항목이 결과 배열에 포함되어야 합니다.";
    }

    /**
     * Call 2(S2B) 프롬프트 조합 — winThemes 생성용
     * - 입력: Call 1 problemDefinitions(GSON 원본) + 자사/경쟁사/기타 참고자료 원문
     * - requirements/evalCriteria 원문은 포함하지 않음 (문제 정의는 problemDefinitions 로 요약 전달됨)
     */
    private String buildStage2bWinThemePrompt(String promptContent,
            ProposalVO.ProjectVO project,
            List<ProposalVO.ProblemDefinitionVO> problemDefinitions,
            String ownContext,
            String competitorContext,
            String etcRefContext) {

        StringBuilder sb = new StringBuilder();
        sb.append(promptContent);
        sb.append("\n\n## 사업 기본 정보");
        sb.append("\n- 사업명: ").append(CommonUtil.nullToBlank(project.getProjectNm()));

        sb.append("\n\n## 발주기관 문제 정의 목록 (Call 1 결과, JSON)");
        sb.append("\n각 Win Theme 은 아래 problemDefinitions 항목(problemTypeCd 또는 배열 인덱스)과 연결지어 도출하세요.\n");
        sb.append(GSON.toJson(problemDefinitions != null ? problemDefinitions : new java.util.ArrayList<>()));

        sb.append("\n\n## 자사 정보\n").append(ownContext);
        sb.append("\n\n## 경쟁사 정보\n").append(competitorContext);
        sb.append("\n\n## 기타 참고자료\n").append(etcRefContext);

        return sb.toString();
    }

    /**
     * RequirementVO → RequirementLiteVO 변환 (Stage2 프롬프트 전용)
     * reqContent 가 300자 초과이면 문장 경계에서 절삭
     */
    private ProposalVO.RequirementLiteVO toRequirementLite(ProposalVO.RequirementVO src) {
        ProposalVO.RequirementLiteVO lite = new ProposalVO.RequirementLiteVO();
        lite.setReqNo(src.getReqNo());
        lite.setReqCategoryCd(src.getReqCategoryCd());
        lite.setReqContent(truncateAtSentenceBoundary(src.getReqContent(), 300));
        lite.setMandatoryYn(src.getMandatoryYn());
        return lite;
    }

    /**
     * RequirementVO → RequirementLiteVO 변환 (toc 매핑용 초경량 버전)
     * reqContent를 80자로 제한. 문장 경계 우선, 없으면 단어(공백) 경계까지만 절삭.
     * reqNo·reqCategoryCd는 절삭하지 않음 (toc 매핑 식별자).
     */
    private ProposalVO.RequirementLiteVO toRequirementUltraLite(ProposalVO.RequirementVO src) {
        ProposalVO.RequirementLiteVO lite = new ProposalVO.RequirementLiteVO();
        lite.setRequirementId(src.getRequirementId()); // LLM이 coveredReqIds에 그대로 반환
        lite.setReqNo(src.getReqNo());
        lite.setReqCategoryCd(src.getReqCategoryCd());
        lite.setReqContent(truncateAtWordBoundary(src.getReqContent(), 80));
        lite.setMandatoryYn(src.getMandatoryYn());
        return lite;
    }

    /**
     * 문자열을 maxLen 자 이하로 절삭 (toc 매핑용 초경량 버전).
     * 1) 문장 경계(마침표·세미콜론·줄바꿈) 우선 탐색
     * 2) 없으면 공백(단어 경계) 탐색
     * 3) 그것도 없으면 maxLen 위치에서 단순 절삭
     * 모두 "...(생략)" 부가.
     */
    private String truncateAtWordBoundary(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        String candidate = text.substring(0, maxLen);
        // 1) 문장 경계 탐색
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (c == '.' || c == ';' || c == '\n' || c == '·') {
                return text.substring(0, i + 1) + "...(생략)";
            }
        }
        // 2) 단어(공백) 경계 탐색
        for (int i = candidate.length() - 1; i >= 0; i--) {
            if (candidate.charAt(i) == ' ') {
                return text.substring(0, i) + "...(생략)";
            }
        }
        // 3) 그대로 maxLen 절삭
        return candidate + "...(생략)";
    }

    /**
     * Stage2-A 문제정의용 요구사항 샘플링 (reqLite 전체 → 카테고리별 대표 샘플)
     * - null(미분류) 카테고리: mandatoryYn='Y' 우선 선택, 최대 NULL_CATEGORY_SAMPLE_LIMIT 건
     * - 코드값 있는 카테고리(001~015 등): 카테고리당 mandatoryYn='Y' 우선, 최대 CODED_CATEGORY_SAMPLE_LIMIT 건
     * - 카테고리 내 순서는 입력 리스트 순서(sortOrd 기준으로 이미 정렬된 상태) 그대로 유지
     */
    private List<ProposalVO.RequirementLiteVO> sampleRequirementsForProblemDef(
            List<ProposalVO.RequirementLiteVO> reqLite) {

        // null 카테고리와 코드값 카테고리를 분리
        List<ProposalVO.RequirementLiteVO> nullGroup = reqLite.stream()
                .filter(r -> r.getReqCategoryCd() == null)
                .collect(java.util.stream.Collectors.toList());

        java.util.Map<String, List<ProposalVO.RequirementLiteVO>> codedGroups = reqLite.stream()
                .filter(r -> r.getReqCategoryCd() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        ProposalVO.RequirementLiteVO::getReqCategoryCd,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));

        List<ProposalVO.RequirementLiteVO> result = new java.util.ArrayList<>();

        // null 카테고리 샘플링: mandatoryYn='Y' 우선, 최대 NULL_CATEGORY_SAMPLE_LIMIT
        result.addAll(pickWithMandatoryFirst(nullGroup, NULL_CATEGORY_SAMPLE_LIMIT));

        // 코드값 카테고리 샘플링: 카테고리당 mandatoryYn='Y' 우선, 최대 CODED_CATEGORY_SAMPLE_LIMIT
        for (List<ProposalVO.RequirementLiteVO> group : codedGroups.values()) {
            result.addAll(pickWithMandatoryFirst(group, CODED_CATEGORY_SAMPLE_LIMIT));
        }

        return result;
    }

    /**
     * 리스트에서 mandatoryYn='Y' 항목을 우선 선택하고, 부족하면 나머지로 채워 최대 limit 건 반환.
     * 입력 리스트 순서(sortOrd 기준) 그대로 유지.
     */
    private List<ProposalVO.RequirementLiteVO> pickWithMandatoryFirst(
            List<ProposalVO.RequirementLiteVO> group, int limit) {
        if (group == null || group.isEmpty()) return java.util.Collections.emptyList();
        List<ProposalVO.RequirementLiteVO> mandatory = group.stream()
                .filter(r -> "Y".equals(r.getMandatoryYn()))
                .collect(java.util.stream.Collectors.toList());
        List<ProposalVO.RequirementLiteVO> others = group.stream()
                .filter(r -> !"Y".equals(r.getMandatoryYn()))
                .collect(java.util.stream.Collectors.toList());
        List<ProposalVO.RequirementLiteVO> picked = new java.util.ArrayList<>();
        for (ProposalVO.RequirementLiteVO r : mandatory) {
            if (picked.size() >= limit) break;
            picked.add(r);
        }
        for (ProposalVO.RequirementLiteVO r : others) {
            if (picked.size() >= limit) break;
            picked.add(r);
        }
        return picked;
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
                ProposalVO.Stage2ResultVO result = executeStage2(ptProjectId, totalSlideBudget, modelId, agentId,
                        msg -> sendSseEvent(emitter, "progress", "{\"step\":\"analyze\",\"message\":\"" + msg.replace("\"", "'") + "\"}"));

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

                // 3. 요구사항·Win Theme·문제정의 로드 및 슬림 변환
                // coveredReqIdsJson(Stage2에서 저장)으로 소목차 관련 요구사항만 필터링
                List<ProposalVO.RequirementVO> allRequirements = proposalDAO.selectRequirements(ptProjectId);
                Set<String> coveredReqIds = parseCoveredReqIds(tocVO.getCoveredReqIdsJson()); // Stage2에서 매핑된 requirementId 목록
                List<ProposalVO.RequirementVO> filteredReqs = coveredReqIds.isEmpty() ? allRequirements
                        : allRequirements.stream()
                                .filter(r -> coveredReqIds.contains(r.getRequirementId()))
                                .collect(java.util.stream.Collectors.toList());
                if (!coveredReqIds.isEmpty()) {
                    logger.info("[PT D-1] 요구사항 필터링: 전체 {} → 관련 {} (tocId={})",
                            allRequirements.size(), filteredReqs.size(), tocId);
                }
                List<ProposalVO.RequirementStage3VO> requirements = toStage3RequirementVOs(filteredReqs);
                List<ProposalVO.WinThemeStage3VO> winThemes = toStage3WinThemeVOs(proposalDAO.selectWinThemes(ptProjectId));
                List<ProposalVO.ProblemDefinitionStage3VO> problemDefs = toStage3ProblemDefVOs(proposalDAO.selectProblemDefinitions(ptProjectId));

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
                    slide.setSlideId(keyGenerate.generateTableKey("PTS", "TB_PT_SLIDE", "SLIDE_ID", 6));
                    slide.setPtProjectId(ptProjectId);
                    slide.setTocId(tocId);
                    slide.setSlideNo(slideNo);
                    slide.setColorIndex(colorIndex);
                    slide.setSortOrd(i);

                    slide.setLayoutType(layoutTypeToCode(getStrOrNull(sObj, "layoutType")));
                    slide.setTitleTxt(getStrOrNull(sObj, "title"));
                    slide.setEyebrowTxt(getStrOrNull(sObj, "eyebrow"));
                    slide.setSubtitleTxt(getStrOrNull(sObj, "subtitle"));
                    slide.setHighlightBannerTxt(getStrOrNull(sObj, "highlightBanner"));
                    slide.setConclusionRibbonTxt(getStrOrNull(sObj, "conclusionRibbon"));
                    slide.setReqIdsJson(sObj.has("reqNos") && !sObj.get("reqNos").isJsonNull()
                            ? GSON.toJson(sObj.get("reqNos")) : null);
                    slide.setComponentsJson(sObj.has("components") && !sObj.get("components").isJsonNull()
                            ? GSON.toJson(sObj.get("components")) : null);
                    slide.setStepFlowBarJson(sObj.has("stepFlowBar") && !sObj.get("stepFlowBar").isJsonNull()
                            ? GSON.toJson(sObj.get("stepFlowBar")) : null);

                    if (CommonUtil.isEmpty(slide.getTitleTxt())) {
                        logger.warn("[PT D-1] title 누락, 슬라이드 스킵 (tocId={}, idx={})", tocId, i);
                        continue;
                    }

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
     * components_json + 슬라이드 제목에서 이미지 생성 쿼리 문자열을 추출한다.
     * - 슬라이드 제목 + 컴포넌트 유형 + 핵심 텍스트 키워드 (최대 6개) + "minimal infographic, clean ui" 고정 접미어
     */
    private String buildImageQueryFromSlide(ProposalVO.SlideVO slide) {
        StringBuilder sb = new StringBuilder();

        // 슬라이드 제목 (주제어)
        String title = CommonUtil.nullToBlank(slide.getTitleTxt());
        if (CommonUtil.isNotEmpty(title)) {
            sb.append(title);
        }

        String compJson = slide.getComponentsJson();
        if (CommonUtil.isNotEmpty(compJson)) {
            try {
                JsonArray comps = JsonParser.parseString(compJson).getAsJsonArray();
                List<String> compTypes = new ArrayList<>();
                List<String> keywords  = new ArrayList<>();

                for (JsonElement el : comps) {
                    if (!el.isJsonObject()) continue;
                    JsonObject comp = el.getAsJsonObject();
                    String type = getStrOrNull(comp, "type");
                    if (CommonUtil.isEmpty(type)) continue;
                    JsonObject content = comp.has("content") && !comp.get("content").isJsonNull()
                            ? comp.getAsJsonObject("content") : null;

                    switch (type) {
                        case "process_flow":
                            compTypes.add("process flow");
                            if (content != null && content.has("steps")) {
                                for (JsonElement s : content.getAsJsonArray("steps")) {
                                    String t = getStrOrNull(s.getAsJsonObject(), "title");
                                    if (CommonUtil.isNotEmpty(t)) keywords.add(t);
                                }
                            }
                            break;
                        case "card_grid":
                            compTypes.add("card grid");
                            if (content != null && content.has("cards")) {
                                for (JsonElement c : content.getAsJsonArray("cards")) {
                                    String t = getStrOrNull(c.getAsJsonObject(), "title");
                                    if (CommonUtil.isNotEmpty(t)) keywords.add(t);
                                }
                            }
                            break;
                        case "icon_chip_group":
                            compTypes.add("icon chips");
                            if (content != null && content.has("chips")) {
                                for (JsonElement ch : content.getAsJsonArray("chips")) {
                                    if (ch.isJsonPrimitive()) keywords.add(ch.getAsString());
                                }
                            }
                            break;
                        case "callout_box":
                            compTypes.add("callout");
                            if (content != null) {
                                String text = getStrOrNull(content, "text");
                                if (CommonUtil.isNotEmpty(text) && text.length() <= 60) keywords.add(text);
                            }
                            break;
                        default:
                            compTypes.add(type.replace("_", " "));
                            break;
                    }
                }

                if (!compTypes.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(String.join(", ", compTypes));
                }
                // 핵심 키워드 최대 6개 (너무 길면 이미지 API 품질 저하)
                List<String> topKeywords = keywords.size() > 6 ? keywords.subList(0, 6) : keywords;
                if (!topKeywords.isEmpty()) {
                    sb.append(", ").append(String.join(", ", topKeywords));
                }

            } catch (Exception e) {
                logger.warn("[PT Image] components_json 파싱 실패 (slideId={}): {}", slide.getSlideId(), e.getMessage());
            }
        }

        // 이미지 스타일 기본 키워드
        if (sb.length() > 0) sb.append(", ");
        sb.append("minimal infographic, clean ui");

        return sb.toString();
    }

    /**
     * Stage3.5: 슬라이드 스타일 조립 (LLM 호출 없음)
     * - components_json + project_config_json(색상·docSize·문체)으로 IMAGE_GEN_HINT 조립 후 DB 저장
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

        // 3. IMAGE_GEN_HINT 조립 — components_json 실제 콘텐츠 기반
        String styleParams = String.format(
                "docSize=%s baseColor=%s accentColor=%s writingStyle=%s colorIndex=%d",
                docSize, baseColor, accentColor, writingStyle, slide.getColorIndex());

        String imageGenHint = buildImageQueryFromSlide(slide) + " | " + styleParams;

        // 4. DB 업데이트
        ProposalVO.SlideVO updateVO = new ProposalVO.SlideVO();
        updateVO.setSlideId(slide.getSlideId());
        updateVO.setImageGenHint(imageGenHint);
        updateVO.setRenderStatusCd("003");
        proposalDAO.updateSlide(updateVO);

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

        // 2. 대상 슬라이드 판단
        // 슬라이드가 2장 이상일 때만 LLM으로 대상 식별 (1장이면 항상 그 슬라이드가 대상)
        List<String> targetSlideIds;
        if (currentSlides.size() > 1) {
            targetSlideIds = identifyTargetSlides(currentSlides, userMessage, modelId, agentId);
            // 특정 슬라이드 지목이 없으면 전체 대상
            if (targetSlideIds == null || targetSlideIds.isEmpty()) {
                targetSlideIds = new java.util.ArrayList<>();
                for (ProposalVO.SlideVO cs : currentSlides) targetSlideIds.add(cs.getSlideId());
            }
        } else {
            targetSlideIds = new java.util.ArrayList<>();
            targetSlideIds.add(currentSlides.get(0).getSlideId());
        }

        // 3. 공통 데이터 조회 — 루프 외부에서 1회만 실행
        // configJson : doStyleAssembly에 필요
        // allRequirements : 사용자가 요구사항을 언급할 경우 슬라이드별 reqIdsJson으로 필터링하여 프롬프트에 포함
        String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);
        List<ProposalVO.RequirementVO> allRequirements = proposalDAO.selectRequirements(ptProjectId);

        // 4. 대상 슬라이드 보완
        List<ProposalVO.SlideVO> updatedSlides = new java.util.ArrayList<>();
        Set<String> targetSet = new java.util.HashSet<>(targetSlideIds);

        for (ProposalVO.SlideVO existingSlide : currentSlides) {
            if (!targetSet.contains(existingSlide.getSlideId())) continue;

            // 해당 슬라이드에 연결된 요구사항만 필터링 (reqIdsJson의 reqNo 기준)
            List<ProposalVO.RequirementStage3VO> slideReqs =
                    filterReqsBySlide(allRequirements, existingSlide.getReqIdsJson());

            String chatFullPrompt = buildSectionChatPrompt(existingSlide, slideReqs, userMessage);

            try {
                String aiResp = riskDiagnosisAgentService.callLlmQuerySync(chatFullPrompt, modelId, "", agentId);
                if (CommonUtil.isNotEmpty(aiResp)) {
                    List<JsonObject> parsed = parseStage3Response(aiResp);
                    if (!parsed.isEmpty()) {
                        JsonObject parsed0 = parsed.get(0);
                        ProposalVO.SlideVO updateVO = new ProposalVO.SlideVO();
                        updateVO.setSlideId(existingSlide.getSlideId());
                        updateVO.setLayoutType(layoutTypeToCode(getStrOrNull(parsed0, "layoutType")));
                        updateVO.setTitleTxt(getStrOrNull(parsed0, "title"));
                        updateVO.setEyebrowTxt(getStrOrNull(parsed0, "eyebrow"));
                        updateVO.setSubtitleTxt(getStrOrNull(parsed0, "subtitle"));
                        updateVO.setHighlightBannerTxt(getStrOrNull(parsed0, "highlightBanner"));
                        updateVO.setConclusionRibbonTxt(getStrOrNull(parsed0, "conclusionRibbon"));
                        updateVO.setReqIdsJson(parsed0.has("reqNos") && !parsed0.get("reqNos").isJsonNull()
                                ? GSON.toJson(parsed0.get("reqNos")) : null);
                        updateVO.setComponentsJson(parsed0.has("components") && !parsed0.get("components").isJsonNull()
                                ? GSON.toJson(parsed0.get("components")) : null);
                        updateVO.setStepFlowBarJson(parsed0.has("stepFlowBar") && !parsed0.get("stepFlowBar").isJsonNull()
                                ? GSON.toJson(parsed0.get("stepFlowBar")) : null);
                        updateVO.setRenderStatusCd("002");
                        proposalDAO.updateSlide(updateVO);

                        // Stage3.5 스타일 재조립 (IMAGE_GEN_HINT 갱신)
                        // doStyleAssembly 내부에서 RENDER_STATUS_CD를 "003"으로 올리므로,
                        // 완료 후 "001"(대기)로 리셋하여 이미지 재생성 대상임을 표시
                        existingSlide.setLayoutType(updateVO.getLayoutType());
                        existingSlide.setTitleTxt(updateVO.getTitleTxt());
                        existingSlide.setComponentsJson(updateVO.getComponentsJson());
                        try {
                            doStyleAssembly(existingSlide, configJson);
                            ProposalVO.SlideVO resetVO = new ProposalVO.SlideVO();
                            resetVO.setSlideId(existingSlide.getSlideId());
                            resetVO.setRenderStatusCd("001");
                            proposalDAO.updateSlide(resetVO);
                            existingSlide.setRenderStatusCd("001");
                        } catch (Exception re) {
                            logger.warn("[PT D-3] 스타일 조립 실패 slideId={}: {}", existingSlide.getSlideId(), re.getMessage());
                        }

                        updatedSlides.add(existingSlide);
                    }
                }
            } catch (Exception e) {
                logger.warn("[PT D-3] 슬라이드 재생성 실패 (slideId={}): {}", existingSlide.getSlideId(), e.getMessage());
            }
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
            String title = CommonUtil.nullToBlank(s.getTitleTxt());
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
     * D-3 내부 헬퍼: 슬라이드의 reqIdsJson(reqNo 배열)에 해당하는 요구사항만 추출하여 Stage3 경량 VO로 반환.
     * 요구사항을 언급하지 않는 보완 요청(문체 변경 등)은 빈 목록이 반환되어 프롬프트에 포함되지 않는다.
     */
    private List<ProposalVO.RequirementStage3VO> filterReqsBySlide(
            List<ProposalVO.RequirementVO> allRequirements, String reqIdsJson) {
        if (CommonUtil.isEmpty(reqIdsJson) || allRequirements == null || allRequirements.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        Set<String> reqNos = new java.util.HashSet<>();
        try {
            for (JsonElement el : JsonParser.parseString(reqIdsJson).getAsJsonArray()) {
                if (!el.isJsonNull()) reqNos.add(el.getAsString());
            }
        } catch (Exception e) {
            logger.warn("[PT D-3] reqIdsJson 파싱 실패: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
        return toStage3RequirementVOs(
                allRequirements.stream()
                        .filter(r -> reqNos.contains(r.getReqNo()))
                        .collect(java.util.stream.Collectors.toList())
        );
    }

    /**
     * D-3 내부 헬퍼: 섹션 보완용 LLM 프롬프트 조립.
     * - 기존 슬라이드 내용 (필드 전체) + 연결 요구사항 (있을 때만) + 사용자 보완 요청
     */
    private String buildSectionChatPrompt(
            ProposalVO.SlideVO existingSlide,
            List<ProposalVO.RequirementStage3VO> slideReqs,
            String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래 기존 슬라이드의 내용을 유지하면서, 보완 요청에 따라 필요한 부분만 수정하여 슬라이드 1장을 출력하세요.\n")
          .append("변경이 필요 없는 필드는 그대로 유지하세요.\n")
          .append("출력 형식: {\"slides\":[{...}]} JSON (코드블록 없이)\n");

        sb.append("\n## 기존 슬라이드")
          .append("\nlayoutType=").append(codeToLayoutTypeName(existingSlide.getLayoutType()))
          .append("\ntitle=").append(CommonUtil.nullToBlank(existingSlide.getTitleTxt()))
          .append("\neyebrow=").append(CommonUtil.nullToBlank(existingSlide.getEyebrowTxt()))
          .append("\nsubtitle=").append(CommonUtil.nullToBlank(existingSlide.getSubtitleTxt()))
          .append("\nhighlightBanner=").append(CommonUtil.nullToBlank(existingSlide.getHighlightBannerTxt()))
          .append("\nconclusionRibbon=").append(CommonUtil.nullToBlank(existingSlide.getConclusionRibbonTxt()));

        String existingComponents = CommonUtil.nullToBlank(existingSlide.getComponentsJson());
        String existingStepFlowBar = CommonUtil.nullToBlank(existingSlide.getStepFlowBarJson());
        if (CommonUtil.isNotEmpty(existingComponents))  sb.append("\ncomponents=").append(existingComponents);
        if (CommonUtil.isNotEmpty(existingStepFlowBar)) sb.append("\nstepFlowBar=").append(existingStepFlowBar);

        if (!slideReqs.isEmpty()) {
            sb.append("\n\n## 연결 요구사항\n").append(GSON.toJson(slideReqs));
        }

        sb.append("\n\n## 보완 요청\n").append(userMessage)
          .append("\n\n슬라이드 1장을 수정하여 {\"slides\":[{...}]} JSON으로만 출력하세요.");

        return sb.toString();
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
            // 모든 소목차 완료 → 검토 단계(4) 해제
            advanceMaxStepNo(ptProjectId, 4);
        } else {
            result.setDone(false);
            result.setNextTocId(nextToc.getTocId());
        }

        // 이미지 렌더링은 confirmSection 이후 프론트엔드가 streamRenderSectionImages SSE를 구독하여 처리
        // (fire-and-forget 제거 — 클라이언트가 진행 상황을 실시간으로 받을 수 있도록)
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 공통 헬퍼 메서드
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 최대 단계 번호 업데이트 — 컨트롤러 직접 호출용 (Step B·E 등 별도 저장 API 없는 단계)
     */
    public void updateMaxStepNo(String ptProjectId, int stepNo) {
        advanceMaxStepNo(ptProjectId, stepNo);
    }

    /**
     * 확인된 소목차의 슬라이드 이미지 일괄 렌더링 (confirmSection 이후 EXPORT_EXECUTOR에서 비동기 호출)
     * 슬라이드별로 doImageRender를 순차 실행하며, 실패 슬라이드만 '004'로 마킹하고 나머지는 계속 진행.
     *
     * @param ptProjectId 프로젝트 ID
     * @param tocId       확인된 소목차 TOC_ID
     */
    private void renderSectionImages(String ptProjectId, String tocId) {
        logger.info("[PT Image] 소목차 이미지 렌더링 시작 (tocId={})", tocId);
        try {
            List<ProposalVO.SlideVO> slides = proposalDAO.selectSlidesByToc(tocId);
            if (slides == null || slides.isEmpty()) {
                logger.warn("[PT Image] 렌더링할 슬라이드 없음 (tocId={})", tocId);
                return;
            }
            int done = 0, fail = 0;
            for (ProposalVO.SlideVO slide : slides) {
                try {
                    doImageRender(slide);
                    done++;
                } catch (Exception e) {
                    logger.warn("[PT Image] 슬라이드 이미지 생성 실패 (slideId={}): {}", slide.getSlideId(), e.getMessage());
                    fail++;
                }
            }
            logger.info("[PT Image] 소목차 이미지 렌더링 완료 (tocId={}, done={}, fail={})", tocId, done, fail);
        } catch (Exception e) {
            logger.error("[PT Image] 소목차 이미지 렌더링 오류 (tocId={}): {}", tocId, e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // D-5: 소목차 이미지 렌더링 SSE (confirmSection 이후 프론트에서 구독)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * D-5: 소목차 이미지 렌더링 SSE 스트림
     * confirmSection 완료 후 프론트엔드가 구독 → 슬라이드별 이미지 생성 진행 상황을 실시간 전송.
     * 슬라이드별 progress 이벤트(slideId, renderStatusCd, renderedImagePath) 후 done 이벤트로 완료.
     *
     * @param ptProjectId 프로젝트 ID
     * @param tocId       확인된 소목차 TOC_ID
     */
    public SseEmitter streamRenderSectionImages(String ptProjectId, String tocId) {
        SseEmitter emitter = new SseEmitter(0L);

        if (CommonUtil.isEmpty(ptProjectId) || CommonUtil.isEmpty(tocId)) {
            sendSseEvent(emitter, "error", "{\"message\":\"ptProjectId 또는 tocId가 없습니다.\"}");
            emitter.complete();
            return emitter;
        }

        emitter.onTimeout(() -> {
            logger.warn("[PT Image SSE] timeout - tocId={}", tocId);
            emitter.complete();
        });
        emitter.onError(e -> logger.warn("[PT Image SSE] error - tocId={}, msg={}", tocId, e.getMessage()));
        emitter.onCompletion(() -> logger.info("[PT Image SSE] complete - tocId={}", tocId));

        EXPORT_EXECUTOR.submit(() -> {
            try {
                List<ProposalVO.SlideVO> slides = proposalDAO.selectSlidesByToc(tocId);

                if (slides == null || slides.isEmpty()) {
                    logger.warn("[PT Image SSE] 렌더링할 슬라이드 없음 (tocId={})", tocId);
                    sendSseEvent(emitter, "done", GSON.toJson(buildPtImageDoneData(0, 0)));
                    return;
                }

                int successCount = 0;
                for (ProposalVO.SlideVO slide : slides) {
                    // 이미 완료된 슬라이드는 건너뜀
                    if ("003".equals(slide.getRenderStatusCd()) && CommonUtil.isNotEmpty(slide.getRenderedImagePath())) {
                        successCount++;
                        continue;
                    }
                    try {
                        String renderedPath = doImageRender(slide);

                        Map<String, Object> progressData = new HashMap<>();
                        progressData.put("slideId", slide.getSlideId());
                        if (renderedPath != null) {
                            progressData.put("renderStatusCd", "003");
                            progressData.put("renderedImagePath", renderedPath);
                            successCount++;
                        } else {
                            progressData.put("renderStatusCd", "004");
                        }
                        sendSseEvent(emitter, "progress", GSON.toJson(progressData));
                    } catch (Exception e) {
                        logger.error("[PT Image SSE] 슬라이드 이미지 생성 오류 (slideId={}): {}", slide.getSlideId(), e.getMessage());
                        Map<String, Object> failData = new HashMap<>();
                        failData.put("slideId", slide.getSlideId());
                        failData.put("renderStatusCd", "004");
                        sendSseEvent(emitter, "progress", GSON.toJson(failData));
                    }
                }

                logger.info("[PT Image SSE] 완료 - tocId={}, 성공: {}/{}", tocId, successCount, slides.size());
                sendSseEvent(emitter, "done", GSON.toJson(buildPtImageDoneData(slides.size(), successCount)));
            } catch (Exception e) {
                logger.error("[PT Image SSE] 스트림 오류 - tocId={}", tocId, e);
                sendSseEvent(emitter, "error", "{\"message\":\"이미지 생성 중 오류가 발생했습니다.\"}");
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 슬라이드 단건 이미지 생성 (D-5)
     * doStyleAssembly에서 조립된 IMAGE_GEN_HINT를 image API에 전달해 base64 이미지를 받고,
     * NCP에 업로드 후 URL을 반환한다.
     *
     * @param slide 슬라이드 VO (imageGenHint 필드 필요)
     * @return 렌더링된 이미지 NCP URL (실패 시 null)
     */
    private String doImageRender(ProposalVO.SlideVO slide) {
        if (CommonUtil.isEmpty(slide.getImageGenHint())) {
            logger.warn("[PT Image] imageGenHint 없음, 이미지 생성 스킵 (slideId={})", slide.getSlideId());
            return null;
        }

        // 생성 중 상태로 선행 업데이트
        ProposalVO.SlideVO startVO = new ProposalVO.SlideVO();
        startVO.setSlideId(slide.getSlideId());
        startVO.setRenderStatusCd("002");
        proposalDAO.updateSlide(startVO);

        try {
            // 이미지 생성 API 호출 (base64 반환)
            String base64Image = callPtImageApi(slide.getImageGenHint());

            ProposalVO.SlideVO doneVO = new ProposalVO.SlideVO();
            doneVO.setSlideId(slide.getSlideId());

            if (base64Image != null && !base64Image.isEmpty()) {
                // base64 → NCP 업로드 (원본 인포그래픽, PPTX 내보내기용)
                byte[] imageBytes = Base64.getDecoder().decode(base64Image);
                String renderedPath = uploadSlideImageToNcp(slide.getPtProjectId(), slide.getSlideId(), imageBytes);

                doneVO.setRenderedImagePath(renderedPath);
                doneVO.setRenderStatusCd("003");

                // 템플릿 프레임 합성 이미지 생성 (Step D 미리보기용, PPTX에는 원본 사용)
                try {
                    ProposalVO.PtTemplateVO tmpl = proposalDAO.selectPtTemplate(slide.getPtProjectId());
                    if (tmpl != null && tmpl.getFrameImagePath() != null) {
                        byte[] frameBytes   = downloadNcpObject(tmpl.getFrameImagePath());
                        byte[] composite    = stackFrameWithContent(frameBytes, imageBytes);
                        String compositeKey = "pt-slide-images/" + slide.getPtProjectId() + "/" + slide.getSlideId() + "_composite.png";
                        uploadNcpObject(compositeKey, composite);
                        doneVO.setCompositeImagePath(compositeKey);
                        logger.info("[PT Image] 합성 이미지 저장 완료 (slideId={}, key={})", slide.getSlideId(), compositeKey);
                    } else {
                        logger.info("[PT Image] 템플릿 프레임 미준비 — 합성 건너뜀 (slideId={})", slide.getSlideId());
                    }
                } catch (Exception ex) {
                    logger.warn("[PT Image] 합성 이미지 생성 실패 — 원본만 저장 (slideId={}): {}", slide.getSlideId(), ex.getMessage());
                }

                proposalDAO.updateSlide(doneVO);
                logger.info("[PT Image] 슬라이드 이미지 생성 완료 (slideId={}, path={})", slide.getSlideId(), renderedPath);
                return renderedPath;
            } else {
                doneVO.setRenderStatusCd("004");
                proposalDAO.updateSlide(doneVO);
                logger.warn("[PT Image] 이미지 API 응답 없음 (slideId={})", slide.getSlideId());
                return null;
            }
        } catch (Exception e) {
            logger.warn("[PT Image] 슬라이드 이미지 생성 실패 (slideId={}): {}", slide.getSlideId(), e.getMessage());
            ProposalVO.SlideVO failVO = new ProposalVO.SlideVO();
            failVO.setSlideId(slide.getSlideId());
            failVO.setRenderStatusCd("004");
            proposalDAO.updateSlide(failVO);
            throw new RuntimeException(e);
        }
    }

    /**
     * 이미지 생성 API 호출 (MeetingServiceImpl.callAiImageApi 동일 패턴)
     * imageGenHint를 query로 전달 → base64 이미지 문자열 반환 (data: 접두사 제거 완료).
     *
     * @param imageGenHint doStyleAssembly에서 조립된 이미지 생성 힌트
     * @return 순수 base64 문자열 (실패 시 null)
     */
    private String callPtImageApi(String imageGenHint) {
        String apiUrl = kr.teamagent.common.util.PropertyUtil.getProperty("Globals.chatbot.image.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("[PT Image] 이미지 API URL 미설정 (Globals.chatbot.image.apiUrl)");
            return null;
        }

        // docSize → aspect_ratio 매핑 (imageGenHint 내 docSize= 파싱)
        // docSize 저장값: a4(A4 세로) / 169(16:9 와이드) / 43(4:3 가로)
        String aspectRatio = "3:4";
        java.util.regex.Matcher docSizeMatcher = java.util.regex.Pattern.compile("docSize=(\\S+)").matcher(imageGenHint);
        if (docSizeMatcher.find()) {
            String docSize = docSizeMatcher.group(1);
            if ("169".equals(docSize)) aspectRatio = "16:9";
            else if ("43".equals(docSize)) aspectRatio = "4:3";
        }

        Map<String, Object> params = new HashMap<>();
        params.put("query", "모든 텍스트는 반드시 한국어로 작성. " + imageGenHint);
        params.put("quality", "medium");
        params.put("room_id", "");
        params.put("aspect_ratio", aspectRatio);

        String reqParamJson = GSON.toJson(params);
        long startMs = System.currentTimeMillis();

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(120, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build();

            RequestBody body = RequestBody.create(reqParamJson, okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("[PT Image] 이미지 API 응답 오류: {}", response.code());
                    apiCallLogService.insertSilently(null, null, apiUrl, "-", "pt_slide_image", reqParamJson, 0, 0, respMs, "N", "HTTP " + response.code(), null);
                    return null;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    String raw = responseBody.string();
                    if (CommonUtil.isEmpty(raw)) {
                        apiCallLogService.insertSilently(null, null, apiUrl, "-", "pt_slide_image", reqParamJson, 0, 0, respMs, "N", "빈 응답", null);
                        return null;
                    }

                    // SSE data: 접두사 처리
                    String jsonStr = raw.trim();
                    if (jsonStr.startsWith("data: ")) {
                        jsonStr = jsonStr.substring(6).trim();
                        int nl = jsonStr.indexOf('\n');
                        if (nl >= 0) jsonStr = jsonStr.substring(0, nl).trim();
                    }

                    JSONParser parser = new JSONParser();
                    JSONObject data = (JSONObject) parser.parse(jsonStr);

                    int imgInTokens = parsePtTokenCount(data.get("input_token"));
                    int imgOutTokens = parsePtTokenCount(data.get("output_token"));

                    Object errCode = data.get("errorCode");
                    if (errCode != null) {
                        String code = String.valueOf(errCode).trim();
                        if (!code.isEmpty() && !"None".equalsIgnoreCase(code)) {
                            logger.warn("[PT Image] 이미지 API 오류코드: {}", code);
                            apiCallLogService.insertSilently(null, null, apiUrl, "-", "pt_slide_image", reqParamJson, 0, 0, respMs, "N", "API 오류: " + code, null);
                            return null;
                        }
                    }

                    Object imageObj = data.get("image");
                    if (imageObj == null) {
                        apiCallLogService.insertSilently(null, null, apiUrl, "-", "pt_slide_image", reqParamJson, 0, 0, respMs, "N", "이미지 필드 없음", null);
                        return null;
                    }

                    apiCallLogService.insertSilently(null, null, apiUrl, "-", "pt_slide_image", reqParamJson, imgInTokens, imgOutTokens, respMs, "Y", null, null);
                    return stripPtBase64Prefix(String.valueOf(imageObj));
                }
            }
        } catch (Exception e) {
            int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
            logger.warn("[PT Image] 이미지 API 호출 실패: {}", e.getMessage());
            apiCallLogService.insertSilently(null, null, apiUrl, "-", "pt_slide_image", reqParamJson, 0, 0, respMs, "N", e.getMessage(), null);
        }
        return null;
    }

    /**
     * 슬라이드 이미지 미리보기
     * @param dataVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> viewSlideImage(ProposalVO.SlideVO dataVO) throws Exception {
        ProposalVO.SlideVO row = proposalDAO.selectSlideById(dataVO);
        if (row == null || row.getRenderedImagePath() == null || row.getRenderedImagePath().trim().isEmpty()) {
            Map<String, Object> notFound = new HashMap<>();
            notFound.put("viewType", "DOWNLOAD");
            notFound.put("reason", "FILE_NOT_FOUND");
            notFound.put("fileName", "");
            notFound.put("downloadUrl", "");
            return notFound;
        }
        // 합성 이미지(템플릿 프레임 + 인포그래픽)가 있으면 우선 반환, 없으면 원본
        String imagePath = (row.getCompositeImagePath() != null && !row.getCompositeImagePath().isEmpty())
                ? row.getCompositeImagePath()
                : row.getRenderedImagePath();
        FileVO fileVo = new FileVO();
        fileVo.setFilePath(imagePath);
        fileVo.setFileName(row.getSlideId() + ".png");
        fileVo.setFileType("image/png");
        return fileService.createViewPresignedUrlForStorageObject(fileVo);

    }

    /**
     * base64 이미지를 NCP 오브젝트 스토리지에 업로드 후 공개 URL 반환.
     * 저장 경로: pt-slide-images/{ptProjectId}/{slideId}.png
     */
    private String uploadSlideImageToNcp(String ptProjectId, String slideId, byte[] imageBytes) {
        String bucket = PropertyUtil.getProperty("ncp.storage.bucket");
        String objectKey = "pt-slide-images/" + ptProjectId + "/" + slideId + ".png";

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(imageBytes.length);
        metadata.setContentType("image/png");

        amazonS3.putObject(bucket, objectKey, new ByteArrayInputStream(imageBytes), metadata);
        logger.info("[PT Image] NCP 업로드 완료 (slideId={}, key={})", slideId, objectKey);

        return objectKey;
    }

    // ── 템플릿 프레임 이미지 생성 ────────────────────────────────────────────────────

    /**
     * Step E 확정 직후 비동기 실행.
     * 템플릿 헤더/푸터 디자인을 LLM 이미지 API로 생성한 뒤 NCP에 저장하고
     * TB_PT_TEMPLATE.FRAME_IMAGE_PATH를 업데이트한다.
     */
    private void generateTemplateFrame(ProposalVO.PtTemplateVO template) {
        String ptProjectId = template.getPtProjectId();
        logger.info("[PT Frame] 프레임 이미지 생성 시작 (ptProjectId={})", ptProjectId);

        // 1. docSize 조회 (PROJECT_CONFIG_JSON)
        String docSize = "169";
        try {
            String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);
            if (configJson != null) {
                JsonObject cfg = JsonParser.parseString(configJson).getAsJsonObject();
                if (cfg.has("template") && cfg.getAsJsonObject("template").has("docSize")) {
                    docSize = cfg.getAsJsonObject("template").get("docSize").getAsString();
                }
            }
        } catch (Exception e) {
            logger.warn("[PT Frame] docSize 조회 실패, 기본값(169) 사용: {}", e.getMessage());
        }

        // 2. 프롬프트 빌드 및 이미지 API 호출
        String prompt = buildTemplateFramePrompt(template, docSize);
        String base64Image = callPtImageApi(prompt);
        if (base64Image == null || base64Image.isEmpty()) {
            logger.warn("[PT Frame] 프레임 이미지 API 응답 없음 (ptProjectId={})", ptProjectId);
            return;
        }

        // 3. NCP 업로드
        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
        String objectKey  = "pt-template-images/" + ptProjectId + "/frame.png";
        uploadNcpObject(objectKey, imageBytes);

        // 4. FRAME_IMAGE_PATH DB 저장
        ProposalVO.PtTemplateVO patch = new ProposalVO.PtTemplateVO();
        patch.setPtProjectId(ptProjectId);
        patch.setFrameImagePath(objectKey);
        proposalDAO.updateTemplateFramePath(patch);

        logger.info("[PT Frame] 프레임 이미지 저장 완료 (ptProjectId={}, key={})", ptProjectId, objectKey);
    }

    /**
     * 템플릿 JSON(헤더/푸터/컬러)을 기반으로 LLM 이미지 API 프롬프트를 생성한다.
     *
     * <p>프레임 이미지 구성 (docSize 비율):
     * <ul>
     *   <li>상단 9%: 헤더 디자인 — 배지·제목·프로젝트명·구분선</li>
     *   <li>중앙 86%: 순수 흰색 빈 공간</li>
     *   <li>하단 5%: 푸터 디자인 — 기관명·페이지번호·제안사명</li>
     * </ul>
     */
    private String buildTemplateFramePrompt(ProposalVO.PtTemplateVO template, String docSize) {
        String baseColor   = "#5B4FE9";
        String accentColor = "#E08A2C";
        String projectNm   = "";
        String chapterBadge = "I";
        String companyNm   = "";
        String orgNm       = "";

        // 컬러 파싱
        try {
            JSONObject cj = (JSONObject) new JSONParser().parse(template.getColorJson());
            if (cj.get("baseColor")   != null) baseColor   = (String) cj.get("baseColor");
            if (cj.get("accentColor") != null) accentColor = (String) cj.get("accentColor");
        } catch (Exception ignored) {}

        // 헤더 컴포넌트에서 텍스트 추출
        try {
            JSONObject hj   = (JSONObject) new JSONParser().parse(template.getHeaderComponentsJson());
            JSONObject body = (JSONObject) hj.get("body");
            if (body != null) {
                JSONObject proj  = (JSONObject) body.get("projectNm");
                JSONObject badge = (JSONObject) body.get("chapterBadge");
                if (proj  != null && proj.get("text")  != null) projectNm    = (String) proj.get("text");
                if (badge != null && badge.get("text") != null) chapterBadge = (String) badge.get("text");
            }
        } catch (Exception ignored) {}

        // 푸터 컴포넌트에서 텍스트 추출
        try {
            JSONObject fj   = (JSONObject) new JSONParser().parse(template.getFooterComponentsJson());
            JSONObject body = (JSONObject) fj.get("body");
            if (body != null) {
                for (Object key : body.keySet()) {
                    JSONObject comp = (JSONObject) body.get(key);
                    if (comp == null) continue;
                    String type = (String) comp.get("type");
                    String text = (String) comp.get("text");
                    if ("company_name".equals(type)) companyNm = (text != null) ? text : "";
                    if ("org_name".equals(type))     orgNm     = (text != null) ? text : "";
                }
            }
        } catch (Exception ignored) {}

        String ratio = "169".equals(docSize) ? "16:9" : "43".equals(docSize) ? "4:3" : "3:4";
        String safeOrg     = orgNm.isEmpty()     ? "발주기관" : orgNm;
        String safeCompany = companyNm.isEmpty() ? "제안사"   : companyNm;
        String safeProject = projectNm.isEmpty() ? "제안서"   : projectNm;

        return String.format(
            "기업 제안서 슬라이드 템플릿 프레임 이미지. 비율 %s. 미니멀하고 전문적인 비즈니스 디자인.\n\n" +
            "【상단 헤더 — 이미지 높이의 상단 9%%】\n" +
            "배경색 흰색(#FFFFFF). 왼쪽에 색상 %s 의 사각형 배지 안에 흰색 굵은 텍스트 \"%s\"," +
            " 그 바로 오른쪽에 색상 %s 굵은 제목 텍스트 영역. 오른쪽 끝에 색상 %s 소형 텍스트 \"%s\"." +
            " 헤더 최하단에 색상 %s 얇은 수평선.\n\n" +
            "【중앙 — 이미지 높이의 86%%】\n" +
            "완전히 순수한 흰색(#FFFFFF). 아무 내용이나 장식도 없는 빈 공간.\n\n" +
            "【하단 푸터 — 이미지 높이의 하단 5%%】\n" +
            "배경색 밝은 회색(#F8F9FA). 상단에 회색(#E0E0E0) 얇은 수평 경계선." +
            " 좌측에 색상 %s 소형 텍스트 \"%s\", 중앙에 색상 %s 소형 텍스트 \"1\", 우측에 색상 %s 소형 텍스트 \"%s\".\n\n" +
            "전체적으로 깔끔하고 여백이 있는 기업 제안서 스타일. 모든 텍스트는 한국어." +
            " | docSize=%s",
            ratio,
            accentColor, chapterBadge,
            baseColor, baseColor, safeProject,
            baseColor,
            baseColor, safeOrg,
            baseColor,
            baseColor, safeCompany,
            docSize
        );
    }

    // ── 이미지 스택 합성 ──────────────────────────────────────────────────────────

    /**
     * 프레임 이미지에서 헤더·푸터 스트립을 잘라내고 인포그래픽과 수직 스택 합성한다.
     *
     * <pre>
     *  ┌──────────────────────────┐  ← 프레임 상단 9% (헤더 스트립)
     *  ├──────────────────────────┤
     *  │   인포그래픽 이미지         │  ← AI 생성 원본
     *  ├──────────────────────────┤
     *  └──────────────────────────┘  ← 프레임 하단 5% (푸터 스트립)
     * </pre>
     *
     * @param frameBytes   템플릿 프레임 PNG (NCP 저장본)
     * @param contentBytes 인포그래픽 PNG (API 생성 원본)
     */
    private byte[] stackFrameWithContent(byte[] frameBytes, byte[] contentBytes) throws Exception {
        BufferedImage frame   = ImageIO.read(new ByteArrayInputStream(frameBytes));
        BufferedImage content = ImageIO.read(new ByteArrayInputStream(contentBytes));
        if (frame == null || content == null) throw new IllegalArgumentException("이미지 디코딩 실패");

        int fw = frame.getWidth();
        int fh = frame.getHeight();
        int cw = content.getWidth();
        int ch = content.getHeight();

        // 프레임에서 헤더(상단 9%)·푸터(하단 5%) 스트립 추출
        int headerH = Math.max(1, (int) (fh * 0.09));
        int footerH = Math.max(1, (int) (fh * 0.05));
        BufferedImage headerStrip = frame.getSubimage(0, 0,          fw, headerH);
        BufferedImage footerStrip = frame.getSubimage(0, fh - footerH, fw, footerH);

        // 콘텐츠 너비 기준으로 스트립 높이 비례 재계산
        int scaledHeaderH = (int) ((double) headerH * cw / fw);
        int scaledFooterH = (int) ((double) footerH * cw / fw);
        int totalH        = scaledHeaderH + ch + scaledFooterH;

        // 합성 캔버스 (흰 배경)
        BufferedImage canvas = new BufferedImage(cw, totalH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, cw, totalH);

        // 헤더 → 콘텐츠 → 푸터 순서로 그리기
        g.drawImage(headerStrip, 0, 0,                          cw, scaledHeaderH, null);
        g.drawImage(content,     0, scaledHeaderH,              cw, ch,            null);
        g.drawImage(footerStrip, 0, scaledHeaderH + ch,         cw, scaledFooterH, null);

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(canvas, "PNG", baos);
        return baos.toByteArray();
    }

    // ── NCP 유틸 ─────────────────────────────────────────────────────────────────

    /** NCP 오브젝트 스토리지에서 바이트 배열로 다운로드 */
    private byte[] downloadNcpObject(String objectKey) throws Exception {
        String bucket = PropertyUtil.getProperty("ncp.storage.bucket");
        try (S3Object s3obj = amazonS3.getObject(bucket, objectKey);
             InputStream is = s3obj.getObjectContent()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            return baos.toByteArray();
        }
    }

    /** NCP 오브젝트 스토리지에 objectKey 경로로 PNG 업로드 */
    private void uploadNcpObject(String objectKey, byte[] imageBytes) {
        String bucket = PropertyUtil.getProperty("ncp.storage.bucket");
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(imageBytes.length);
        metadata.setContentType("image/png");
        amazonS3.putObject(bucket, objectKey, new ByteArrayInputStream(imageBytes), metadata);
        logger.info("[PT NCP] 업로드 완료 (key={})", objectKey);
    }

    /** data:image/...;base64, 접두사 제거 후 순수 base64 반환 */
    private static String stripPtBase64Prefix(String image) {
        if (image == null) return null;
        String s = image.trim();
        int comma = s.indexOf("base64,");
        return comma >= 0 ? s.substring(comma + "base64,".length()).trim() : s;
    }

    /** API 응답 토큰 수 파싱 (Number·String 모두 처리, 파싱 실패 시 0) */
    private static int parsePtTokenCount(Object tokenObj) {
        if (tokenObj == null) return 0;
        try {
            if (tokenObj instanceof Number) return ((Number) tokenObj).intValue();
            return Integer.parseInt(String.valueOf(tokenObj).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** SSE done 이벤트 데이터 맵 생성 */
    private static Map<String, Object> buildPtImageDoneData(int total, int success) {
        Map<String, Object> data = new HashMap<>();
        data.put("total", total);
        data.put("success", success);
        return data;
    }

    /**
     * MAX_STEP_NO를 진행 방향으로만 업데이트 (역행 방지 — DB의 GREATEST로 보장)
     * @param ptProjectId 프로젝트 ID
     * @param stepNo      달성한 단계 번호 (0~5)
     */
    private void advanceMaxStepNo(String ptProjectId, int stepNo) {
        try {
            ProposalVO.ProjectVO vo = new ProposalVO.ProjectVO();
            vo.setPtProjectId(ptProjectId);
            vo.setMaxStepNo(stepNo);
            proposalDAO.updateMaxStepNo(vo);
        } catch (Exception e) {
            logger.warn("[PT] advanceMaxStepNo 실패 (ptProjectId={}, stepNo={}): {}", ptProjectId, stepNo, e.getMessage());
        }
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
            List<ProposalVO.RequirementStage3VO> requirements,
            List<ProposalVO.WinThemeStage3VO> winThemes,
            List<ProposalVO.ProblemDefinitionStage3VO> problemDefs,
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

        if (requirements != null && !requirements.isEmpty()) {
            sb.append("\n\n## 요구사항 목록\n").append(GSON.toJson(requirements));
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
     * coveredReqIdsJson(Stage2 산출물) 파싱 → requirementId Set.
     * null/빈 값이면 빈 Set 반환 → 호출부에서 전체 목록 사용으로 폴백.
     */
    private Set<String> parseCoveredReqIds(String coveredReqIdsJson) {
        if (CommonUtil.isEmpty(coveredReqIdsJson)) return java.util.Collections.emptySet();
        try {
            Set<String> ids = new java.util.HashSet<>();
            for (JsonElement el : JsonParser.parseString(coveredReqIdsJson).getAsJsonArray()) {
                if (!el.isJsonNull()) ids.add(el.getAsString());
            }
            return ids;
        } catch (Exception e) {
            logger.warn("[PT D-1] coveredReqIdsJson 파싱 실패, 요구사항 전체 사용: {}", e.getMessage());
            return java.util.Collections.emptySet();
        }
    }

    private List<ProposalVO.RequirementStage3VO> toStage3RequirementVOs(List<ProposalVO.RequirementVO> src) {
        if (src == null) return java.util.Collections.emptyList();
        List<ProposalVO.RequirementStage3VO> result = new java.util.ArrayList<>(src.size());
        for (ProposalVO.RequirementVO r : src) {
            ProposalVO.RequirementStage3VO v = new ProposalVO.RequirementStage3VO();
            v.setReqNo(r.getReqNo());
            v.setReqCategoryCd(r.getReqCategoryCd());
            v.setReqContent(r.getReqContent());
            v.setMandatoryYn(r.getMandatoryYn());
            v.setSourceTypeCd(r.getSourceTypeCd());
            v.setRfpPageRef(r.getRfpPageRef());
            v.setEvalImpact(r.getEvalImpact());
            v.setResponseDirection(r.getResponseDirection());
            v.setRequiredEvidence(r.getRequiredEvidence());
            result.add(v);
        }
        return result;
    }

    private List<ProposalVO.WinThemeStage3VO> toStage3WinThemeVOs(List<ProposalVO.WinThemeVO> src) {
        if (src == null) return java.util.Collections.emptyList();
        List<ProposalVO.WinThemeStage3VO> result = new java.util.ArrayList<>(src.size());
        for (ProposalVO.WinThemeVO w : src) {
            ProposalVO.WinThemeStage3VO v = new ProposalVO.WinThemeStage3VO();
            v.setCoreMessage(w.getCoreMessage());
            v.setCustomerProblem(w.getCustomerProblem());
            v.setProposalStrategy(w.getProposalStrategy());
            v.setEvidence(w.getEvidence());
            v.setExpectedEffect(w.getExpectedEffect());
            v.setDifferentiation(w.getDifferentiation());
            result.add(v);
        }
        return result;
    }

    private List<ProposalVO.ProblemDefinitionStage3VO> toStage3ProblemDefVOs(List<ProposalVO.ProblemDefinitionVO> src) {
        if (src == null) return java.util.Collections.emptyList();
        List<ProposalVO.ProblemDefinitionStage3VO> result = new java.util.ArrayList<>(src.size());
        for (ProposalVO.ProblemDefinitionVO p : src) {
            ProposalVO.ProblemDefinitionStage3VO v = new ProposalVO.ProblemDefinitionStage3VO();
            v.setProblemTypeCd(p.getProblemTypeCd());
            v.setCurrentProblem(p.getCurrentProblem());
            v.setRootCause(p.getRootCause());
            v.setRiskIfIgnored(p.getRiskIfIgnored());
            v.setGoal(p.getGoal());
            v.setRequiredCapability(p.getRequiredCapability());
            v.setStrategySummary(p.getStrategySummary());
            v.setKpi(p.getKpi());
            result.add(v);
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

        // 1. docSize 기반 내보내기 형식 코드 결정 (a4 → "002"/PDF, 그 외 → "001"/PPTX)
        String exportTypeCd = resolveExportTypeCd(ptProjectId);

        // 2. 캐시 재사용 판단: 가장 최근 완료(004) 빌드의 COMPLETE_DT vs 슬라이드 최신 수정일
        ProposalVO.ExportVO cached = proposalDAO.selectLatestCompletedExport(ptProjectId, exportTypeCd);
        if (cached != null && CommonUtil.isNotEmpty(cached.getCompleteDt())
                && CommonUtil.isNotEmpty(cached.getFilePath())) {
            String maxSlideModifyDt = proposalDAO.selectMaxSlideModifyDt(ptProjectId);
            boolean cacheValid = CommonUtil.isEmpty(maxSlideModifyDt)
                    || cached.getCompleteDt().compareTo(maxSlideModifyDt) >= 0;
            if (cacheValid) {
                logger.info("[PT F] 캐시 재사용 (ptProjectId={}, exportTypeCd={}, completeDt={})",
                        ptProjectId, exportTypeCd, cached.getCompleteDt());
                try {
                    String downloadUrl = fileService.createDownloadPresignedUrlStr(
                            cached.getFilePath(), cached.getFileNm());
                    cached.setDownloadUrl(downloadUrl);
                } catch (Exception e) {
                    logger.warn("[PT F] 캐시 presigned URL 발급 실패: {}", e.getMessage());
                }
                return cached;
            }
        }

        // 3. 신규 빌드 — TB_PT_EXPORT row 생성
        List<ProposalVO.SlideVO> slides = proposalDAO.selectAllSlidesByProject(ptProjectId);
        int totalSlideCnt = slides != null ? slides.size() : 0;
        String exportId = keyGenerate.generateTableKey("PTX", "TB_PT_EXPORT", "EXPORT_ID", 6);
        ProposalVO.ExportVO exportVO = new ProposalVO.ExportVO();
        exportVO.setExportId(exportId);
        exportVO.setPtProjectId(ptProjectId);
        exportVO.setExportTypeCd(exportTypeCd);
        exportVO.setTotalSlideCnt(totalSlideCnt);
        exportVO.setCreateUserId(SessionUtil.getUserId());
        proposalDAO.insertExport(exportVO);

        // 4. 비동기 빌드 시작
        final String finalExportTypeCd = exportTypeCd;
        EXPORT_EXECUTOR.submit(() -> {
            runExportBuild(exportId, ptProjectId, finalExportTypeCd);
        });

        exportVO.setBuildStatusCd("003"); // 반환 시점: PPT조립중으로 표시
        return exportVO;
    }

    /**
     * PROJECT_CONFIG_JSON의 template.docSize를 읽어 내보내기 형식 코드를 반환한다.
     * docSize "a4"/"43" → "002"(PDF), 그 외(169 등) → "001"(PPTX)
     */
    private String resolveExportTypeCd(String ptProjectId) {
        try {
            String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);
            if (CommonUtil.isNotEmpty(configJson)) {
                JsonObject cfgRoot = JsonParser.parseString(configJson).getAsJsonObject();
                if (cfgRoot.has("template") && !cfgRoot.get("template").isJsonNull()) {
                    JsonObject tmpl = cfgRoot.getAsJsonObject("template");
                    if (tmpl.has("docSize") && !tmpl.get("docSize").isJsonNull()) {
                        String docSize = tmpl.get("docSize").getAsString();
                        return ("a4".equalsIgnoreCase(docSize) || "43".equals(docSize)) ? "002" : "001";
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("[PT F] resolveExportTypeCd 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
        }
        return "001"; // 기본값: PPTX
    }

    /**
     * F — 출력 상태 조회 (폴링용)
     * - BUILD_STATUS_CD=004(완료) 이고 FILE_PATH 가 있으면 presigned 다운로드 URL 동적 발급
     *
     * @param exportId EXPORT_ID
     * @return ExportVO (없으면 null)
     */
    public ProposalVO.ExportVO selectExportStatus(String exportId) {
        ProposalVO.ExportVO exportVO = proposalDAO.selectExportById(exportId);
        if (exportVO == null) return null;

        // 완료 상태이면 최신 다운로드 URL 동적 발급
        if ("004".equals(exportVO.getBuildStatusCd())
                && CommonUtil.isNotEmpty(exportVO.getFilePath())) {
            try {
                String downloadUrl = fileService.createDownloadPresignedUrlStr(
                        exportVO.getFilePath(), exportVO.getFileNm());
                exportVO.setDownloadUrl(downloadUrl);
            } catch (Exception e) {
                logger.warn("[PT F] 다운로드 URL 발급 실패 (exportId={}): {}", exportId, e.getMessage());
            }
        }
        return exportVO;
    }

    /**
     * F — 비동기 출력 빌드 실행 (EXPORT_EXECUTOR 내부에서 호출).
     *
     * <p>렌더링된 이미지(RENDERED_IMAGE_PATH)가 있는 슬라이드가 하나라도 있으면
     * 이미지 기반 빌드(헤더·푸터 템플릿 포함)를 수행하고, 모두 없으면
     * 텍스트 기반 폴백 빌드를 수행한다.
     */
    private void runExportBuild(String exportId, String ptProjectId, String exportTypeCd) {
        try {
            // BUILD_STATUS_CD='003' (PPT조립중) 업데이트
            ProposalVO.ExportVO progVO = new ProposalVO.ExportVO();
            progVO.setExportId(exportId);
            progVO.setBuildStatusCd("003");
            proposalDAO.updateExport(progVO);

            // ── 1. 전체 슬라이드 조회 (SLIDE_NO 순) ──────────────────────────
            List<ProposalVO.SlideVO> allSlides = proposalDAO.selectAllSlidesByProject(ptProjectId);
            if (allSlides == null || allSlides.isEmpty()) {
                throw new RuntimeException("빌드할 슬라이드가 없습니다 (ptProjectId=" + ptProjectId + ")");
            }

            // ── 2. 프로젝트 정보 로드 ────────────────────────────────────────
            ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
            String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);

            String bgColor     = "#FFFFFF";
            String baseColor   = "#5B4FE9";
            String accentColor = "#E08A2C";
            String docSize     = "169";
            String submitterNm = "";

            if (CommonUtil.isNotEmpty(configJson)) {
                try {
                    JsonObject cfgRoot = JsonParser.parseString(configJson).getAsJsonObject();
                    // template → docSize
                    if (cfgRoot.has("template") && !cfgRoot.get("template").isJsonNull()) {
                        JsonObject tmpl = cfgRoot.getAsJsonObject("template");
                        if (tmpl.has("docSize") && !tmpl.get("docSize").isJsonNull()) {
                            docSize = tmpl.get("docSize").getAsString();
                        }
                    }
                    // settings → colors + submitterNm
                    if (cfgRoot.has("settings") && !cfgRoot.get("settings").isJsonNull()) {
                        JsonObject settings = cfgRoot.getAsJsonObject("settings");
                        if (settings.has("colors") && !settings.get("colors").isJsonNull()) {
                            JsonObject colors = settings.getAsJsonObject("colors");
                            List<String> bases   = colors.has("base")   && !colors.get("base").isJsonNull()   ? jsonArrayToList(colors.getAsJsonArray("base"))   : java.util.Collections.emptyList();
                            List<String> accents = colors.has("accent") && !colors.get("accent").isJsonNull() ? jsonArrayToList(colors.getAsJsonArray("accent")) : java.util.Collections.emptyList();
                            if (!bases.isEmpty())   baseColor   = bases.get(0);
                            if (bases.size() > 2)   bgColor     = bases.get(2);
                            if (!accents.isEmpty()) accentColor = accents.get(0);
                        }
                        String sn = getStrOrNull(settings, "submitterNm");
                        if (CommonUtil.isNotEmpty(sn)) submitterNm = sn;
                    }
                } catch (Exception e) {
                    logger.warn("[PT F] configJson 파싱 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
                }
            }

            String projectNm = project != null ? CommonUtil.nullToBlank(project.getProjectNm()) : ptProjectId;
            String orgNm     = project != null ? CommonUtil.nullToBlank(project.getOrgNm())     : "";

            // ── 3. TOC 계층 조회 → 챕터 로마숫자 · 소목차 제목 맵 ────────────
            List<ProposalVO.TocVO> tocList = proposalDAO.selectTocList(ptProjectId);
            Map<String, String> tocRomanMap     = new java.util.LinkedHashMap<>();
            Map<String, String> tocTitleMap     = new HashMap<>();
            Map<String, String> leafToParentMap = new HashMap<>();
            int chapterIdx = 0;
            for (ProposalVO.TocVO toc : tocList) {
                tocTitleMap.put(toc.getTocId(), CommonUtil.nullToBlank(toc.getSectionNm()));
                if (CommonUtil.isEmpty(toc.getParentTocId())) {
                    chapterIdx++;
                    tocRomanMap.put(toc.getTocId(),
                            kr.teamagent.common.util.ProposalPptxUtil.toRomanNumeral(chapterIdx));
                } else {
                    leafToParentMap.put(toc.getTocId(), toc.getParentTocId());
                }
            }

            // ── 4. 빌드 방식 분기 ────────────────────────────────────────────
            boolean hasRendered = allSlides.stream()
                    .anyMatch(s -> CommonUtil.isNotEmpty(s.getRenderedImagePath()));
            byte[] pptxBytes;

            if (hasRendered) {
                // ── 이미지 기반 빌드 ─────────────────────────────────────────
                // TB_PT_TEMPLATE 조회 (Step E에서 생성한 헤더/푸터 JSON 레이아웃)
                ProposalVO.PtTemplateVO ptTemplate = proposalDAO.selectPtTemplate(ptProjectId);
                if (ptTemplate == null) {
                    throw new RuntimeException("헤더/푸터 템플릿이 없습니다. Step E(템플릿 생성)를 먼저 완료해 주세요. (ptProjectId=" + ptProjectId + ")");
                }
                String headerComponentsJson = ptTemplate.getHeaderComponentsJson();
                String footerComponentsJson = ptTemplate.getFooterComponentsJson();

                List<kr.teamagent.common.util.ProposalPptxUtil.PageInfo> pages =
                        new java.util.ArrayList<>();
                Map<String, Integer> chapterSlideCount = new HashMap<>();

                for (ProposalVO.SlideVO s : allSlides) {
                    String tocId       = s.getTocId();
                    String parentTocId = leafToParentMap.getOrDefault(tocId, tocId);
                    String roman       = tocRomanMap.getOrDefault(parentTocId, "Ⅰ");
                    String secTitle    = tocTitleMap.getOrDefault(tocId, "");
                    int slideNoInChapter = chapterSlideCount.merge(parentTocId, 1, Integer::sum);
                    String pageLabel   = roman + "-" + slideNoInChapter;

                    byte[] imageBytes = null;
                    if (CommonUtil.isNotEmpty(s.getRenderedImagePath())) {
                        try {
                            imageBytes = downloadNcpObject(s.getRenderedImagePath());
                        } catch (Exception e) {
                            logger.warn("[PT F] 렌더링 이미지 다운로드 실패 (slideId={}, path={}): {}",
                                    s.getSlideId(), s.getRenderedImagePath(), e.getMessage());
                        }
                    }

                    pages.add(new kr.teamagent.common.util.ProposalPptxUtil.PageInfo(
                            imageBytes, roman, secTitle, pageLabel, projectNm, orgNm, submitterNm,
                            s.getLayoutType()));   // layoutTypeCd 추가 — cover(001)/divider(002) 제외 처리용
                }

                // PPTX는 코드 기반 헤더/푸터 렌더링 사용 (슬라이드별 동적 텍스트 반영)
                pptxBytes = kr.teamagent.common.util.ProposalPptxUtil.buildProposalDocWithImages(
                        pages, docSize, bgColor, baseColor, accentColor,
                        headerComponentsJson, footerComponentsJson,
                        null);
                logger.info("[PT F] 이미지 기반 빌드 완료 (templateId={}, exportId={}, pages={})",
                        ptTemplate.getTemplateId(), exportId, pages.size());

            } else {
                // ── 텍스트 기반 폴백 빌드 ────────────────────────────────────
                List<java.util.Map<String, Object>> slideMaps = new java.util.ArrayList<>();
                for (ProposalVO.SlideVO s : allSlides) {
                    java.util.Map<String, Object> slideMap = new java.util.LinkedHashMap<>();
                    slideMap.put("layoutType", codeToLayoutTypeName(s.getLayoutType()));
                    slideMap.put("title",    s.getTitleTxt());
                    slideMap.put("subtitle", s.getSubtitleTxt());
                    slideMap.put("headline", s.getHighlightBannerTxt());
                    if (CommonUtil.isNotEmpty(s.getComponentsJson())) {
                        try {
                            slideMap.put("components", GSON.fromJson(s.getComponentsJson(), Object.class));
                        } catch (Exception e) {
                            logger.warn("[PT F] components JSON 파싱 실패 (slideId={}): {}", s.getSlideId(), e.getMessage());
                        }
                    }
                    slideMaps.add(slideMap);
                }
                pptxBytes = kr.teamagent.common.util.ProposalPptxUtil.buildPptx(
                        projectNm, slideMaps, bgColor, baseColor, accentColor);
                logger.info("[PT F] 텍스트 기반 폴백 빌드 완료 (exportId={}, slides={})", exportId, slideMaps.size());
            }

            // ── 5. NCP 업로드 및 형식별 처리 ────────────────────────────────
            String objectKey;
            byte[] uploadBytes;
            String contentType;
            String fileNm;

            if ("002".equals(exportTypeCd)) {
                // PDF
                String pptxFileName = "export_" + ptProjectId + ".pptx";
                byte[] pdfBytes = fileService.convertPptxBytesToPdf(pptxBytes, pptxFileName);
                fileNm      = ptProjectId + ".pdf";
                objectKey   = "pt-export/" + ptProjectId + "/" + exportId + ".pdf";
                uploadBytes = pdfBytes;
                contentType = "application/pdf";
            } else {
                // PPTX (001)
                fileNm      = ptProjectId + ".pptx";
                objectKey   = "pt-export/" + ptProjectId + "/" + exportId + ".pptx";
                uploadBytes = pptxBytes;
                contentType = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            }

            fileService.uploadBytes(objectKey, uploadBytes, contentType);
            logger.info("[PT F] NCP 업로드 완료 (exportId={}, key={}, size={})", exportId, objectKey, uploadBytes.length);

            // ── 6. TB_PT_EXPORT 완료 업데이트 ──────────────────────────────
            ProposalVO.ExportVO doneVO = new ProposalVO.ExportVO();
            doneVO.setExportId(exportId);
            doneVO.setBuildStatusCd("004");
            doneVO.setFileNm(fileNm);
            doneVO.setFilePath(objectKey);
            doneVO.setFileSize((long) uploadBytes.length);
            doneVO.setCompleteDt(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            proposalDAO.updateExport(doneVO);
            logger.info("[PT F] 출력 빌드 완료 (exportId={}, exportTypeCd={}, ptProjectId={})", exportId, exportTypeCd, ptProjectId);

        } catch (Exception e) {
            logger.error("[PT F] 출력 빌드 실패 (exportId={}, ptProjectId={}): {}", exportId, ptProjectId, e.getMessage(), e);
            try {
                ProposalVO.ExportVO failVO = new ProposalVO.ExportVO();
                failVO.setExportId(exportId);
                failVO.setBuildStatusCd("005");
                String errMsg = e.getMessage();
                if (errMsg != null && errMsg.length() > 1000) errMsg = errMsg.substring(0, 1000);
                failVO.setErrorMsg(errMsg);
                proposalDAO.updateExport(failVO);
            } catch (Exception ex) {
                logger.error("[PT F] TB_PT_EXPORT 실패 상태 업데이트 오류 (exportId={}): {}", exportId, ex.getMessage());
            }
        }
    }

    // ── Step E: 템플릿 생성 ──────────────────────────────────────────────────────

    /**
     * PT 템플릿 단건 조회 (PT_PROJECT_ID 기준)
     */
    public ProposalVO.PtTemplateVO selectPtTemplate(String ptProjectId) {
        return proposalDAO.selectPtTemplate(ptProjectId);
    }

    /**
     * PT 템플릿 생성 (LLM 호출 → upsert)
     * - Step3 확정 컬러/스타일 + 프로젝트 정보 + 최상위 TOC 목록을 조합해 프롬프트 구성
     * - 내부 FastAPI(9000) 또는 Claude LLM 호출
     * - 응답을 HEADER_COMPONENTS_JSON / FOOTER_COMPONENTS_JSON으로 파싱 → upsert
     *
     * 프롬프트는 TB_PROMPT에서 agentId + stageCd 'S3_TEMPLATE'으로 조회.
     * (프롬프트 미등록 시 RuntimeException 발생)
     */
    public void updatePtTemplate(ProposalVO.PtTemplateVO vo) {
        proposalDAO.updatePtTemplate(vo);
        // 확정 즉시 프레임 이미지를 비동기 생성 (30~120초 소요 → 완료 전 슬라이드 생성 시 합성 건너뜀)
        final ProposalVO.PtTemplateVO snapshot = vo;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                generateTemplateFrame(snapshot);
            } catch (Exception e) {
                logger.warn("[PT Frame] 프레임 이미지 생성 실패 (ptProjectId={}): {}", snapshot.getPtProjectId(), e.getMessage());
            }
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ProposalVO.PtTemplateVO generateTemplate(String ptProjectId, String modelId, String agentId) throws Exception {
        String userId = SessionUtil.getUserId();

        // 기존 템플릿 조회 (upsert 분기용)
        ProposalVO.PtTemplateVO existing = proposalDAO.selectPtTemplate(ptProjectId);

        // 상태를 생성중(002)으로 설정
        ProposalVO.PtTemplateVO statusVO = new ProposalVO.PtTemplateVO();
        statusVO.setPtProjectId(ptProjectId);
        statusVO.setGenStatusCd("002");
        statusVO.setHeaderComponentsJson(null);
        statusVO.setFooterComponentsJson(null);
        statusVO.setColorJson(null);
        statusVO.setErrorMsg(null);
        statusVO.setModifyUserId(userId);

        if (existing == null) {
            String templateId = keyGenerate.generateTableKey("PTM", "TB_PT_TEMPLATE", "TEMPLATE_ID");
            statusVO.setTemplateId(templateId);
            statusVO.setCreateUserId(userId);
            proposalDAO.insertPtTemplate(statusVO);
        } else {
            proposalDAO.updatePtTemplate(statusVO);
        }

        try {
            // 프로젝트 정보 조회
            ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
            if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다: " + ptProjectId);

            String projectNm = CommonUtil.nullToBlank(project.getProjectNm());
            String orgNm     = CommonUtil.nullToBlank(project.getOrgNm());

            // 설정(컬러) 조회
            String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);
            String baseColor   = "#5B4FE9";
            String accentColor = "#E08A2C";
            String submitterNm = "";

            if (CommonUtil.isNotEmpty(configJson)) {
                try {
                    JsonObject cfgRoot = JsonParser.parseString(configJson).getAsJsonObject();
                    if (cfgRoot.has("settings") && !cfgRoot.get("settings").isJsonNull()) {
                        JsonObject settings = cfgRoot.getAsJsonObject("settings");
                        if (settings.has("colors") && !settings.get("colors").isJsonNull()) {
                            JsonObject colors = settings.getAsJsonObject("colors");
                            List<String> bases   = colors.has("base") && !colors.get("base").isJsonNull() ? jsonArrayToList(colors.getAsJsonArray("base")) : java.util.Collections.emptyList();
                            List<String> accents = colors.has("accent") && !colors.get("accent").isJsonNull() ? jsonArrayToList(colors.getAsJsonArray("accent")) : java.util.Collections.emptyList();
                            if (!bases.isEmpty()) baseColor = bases.get(0);
                            if (!accents.isEmpty()) accentColor = accents.get(0);
                        }
                        String sn = getStrOrNull(settings, "submitterNm");
                        if (CommonUtil.isNotEmpty(sn)) submitterNm = sn;
                    }
                } catch (Exception e) {
                    logger.warn("[PT Template] configJson 파싱 실패: {}", e.getMessage());
                }
            }

            // TOC 최상위 목록 조회
            List<ProposalVO.TocVO> tocList = proposalDAO.selectTocList(ptProjectId);
            StringBuilder tocSb = new StringBuilder();
            for (ProposalVO.TocVO toc : tocList) {
                if (CommonUtil.isEmpty(toc.getParentTocId())) {
                    tocSb.append(toc.getSectionNo()).append(". ").append(toc.getSectionNm()).append("\n");
                }
            }

            // 프롬프트 조회 (agentId + stageCd 'S3_TEMPLATE')
            String promptTemplate = null;
            try {
                promptTemplate = promptService.getPromptsByAgentIdAndStageCd(agentId, "S3_TEMPLATE");
            } catch (Exception e) {
                logger.warn("[PT Template] TB_PROMPT 'S3_TEMPLATE' 조회 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
            }
            if (CommonUtil.isEmpty(promptTemplate)) {
                throw new RuntimeException("템플릿 생성 프롬프트가 등록되지 않았습니다. TB_PROMPT에 STAGE_CD='S3_TEMPLATE'인 프롬프트를 등록해 주세요.");
            }

            // 변수 치환
            String prompt = promptTemplate
                    .replace("{project_nm}", projectNm)
                    .replace("{org_nm}", orgNm)
                    .replace("{submitter_nm}", submitterNm)
                    .replace("{base_color}", baseColor)
                    .replace("{accent_color}", accentColor)
                    .replace("{toc_list}", tocSb.toString().trim());

            // LLM 호출
            String aiResponse = riskDiagnosisAgentService.callLlmQuerySync(prompt, modelId, "", agentId);
            if (CommonUtil.isEmpty(aiResponse)) {
                throw new RuntimeException("LLM 응답이 비어 있습니다.");
            }

            // JSON 파싱
            String headerJson = extractJsonBlock(aiResponse, "header");
            String footerJson = extractJsonBlock(aiResponse, "footer");

            if (CommonUtil.isEmpty(headerJson) || CommonUtil.isEmpty(footerJson)) {
                throw new RuntimeException("LLM 응답에서 header/footer JSON을 추출할 수 없습니다.");
            }

            // 컬러 JSON 구성
            JsonObject colorObj = new JsonObject();
            colorObj.addProperty("baseColor", baseColor);
            colorObj.addProperty("accentColor", accentColor);

            // upsert (완료 상태로)
            ProposalVO.PtTemplateVO result = new ProposalVO.PtTemplateVO();
            result.setPtProjectId(ptProjectId);
            result.setHeaderComponentsJson(headerJson);
            result.setFooterComponentsJson(footerJson);
            result.setColorJson(GSON.toJson(colorObj));
            result.setGenStatusCd("003");
            result.setErrorMsg(null);
            result.setModifyUserId(userId);
            proposalDAO.updatePtTemplate(result);

            result.setTemplateId(proposalDAO.selectPtTemplate(ptProjectId).getTemplateId());
            return result;

        } catch (Exception e) {
            logger.error("[PT Template] generateTemplate 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            ProposalVO.PtTemplateVO failVO = new ProposalVO.PtTemplateVO();
            failVO.setPtProjectId(ptProjectId);
            failVO.setGenStatusCd("004");
            failVO.setErrorMsg(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 900)) : "알 수 없는 오류");
            failVO.setModifyUserId(userId);
            proposalDAO.updatePtTemplate(failVO);
            throw e;
        }
    }

    /** LLM 응답에서 코드블록 태그로 감싼 JSON 추출
     *  1순위: ```tag {...} ``` 패턴
     *  2순위: 응답 전체를 JSON으로 파싱 후 tag 키 값 추출 (LLM이 단일 JSON으로 반환한 경우)
     */
    private String extractJsonBlock(String response, String tag) {
        if (CommonUtil.isEmpty(response) || CommonUtil.isEmpty(tag)) return null;

        // 1순위: ```tag {...} ``` 패턴
        String startTag = "```" + tag;
        int start = response.indexOf(startTag);
        if (start < 0) {
            start = response.toLowerCase().indexOf(startTag.toLowerCase());
        }
        if (start >= 0) {
            int jsonStart = response.indexOf("{", start + startTag.length());
            if (jsonStart >= 0) {
                int end = response.indexOf("```", jsonStart);
                if (end < 0) end = response.length();
                String candidate = response.substring(jsonStart, end).trim();
                int lastBrace = candidate.lastIndexOf("}");
                if (lastBrace >= 0) return candidate.substring(0, lastBrace + 1);
            }
        }

        // 2순위: 응답 전체(또는 코드블록 안)를 JSON으로 파싱 후 tag 키 추출
        try {
            String jsonStr = response.trim();
            // ```json ... ``` 또는 ``` ... ``` 코드블록 벗기기
            if (jsonStr.startsWith("```")) {
                int nl = jsonStr.indexOf('\n');
                if (nl >= 0) jsonStr = jsonStr.substring(nl + 1);
                int closing = jsonStr.lastIndexOf("```");
                if (closing >= 0) jsonStr = jsonStr.substring(0, closing).trim();
            }
            JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();
            if (root.has(tag) && !root.get(tag).isJsonNull()) {
                return GSON.toJson(root.get(tag));
            }
        } catch (Exception ignored) {}

        return null;
    }

}
