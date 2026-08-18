package kr.teamagent.proposal.service.impl;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import org.springframework.transaction.support.TransactionTemplate;
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
    /** Stage2-A 문제정의용 샘플링 — 전체 요구사항 최대 건수 (mandatoryYn='Y' 우선) */
    private static final int PROBLEM_DEF_REQ_LIMIT = 20;

    /** 슬라이드 이미지 렌더 상태 (PT000007) — 완료 */
    private static final String SLIDE_RENDER_DONE = "003";
    /** 슬라이드 이미지 렌더 상태 (PT000007) — 실패 */
    private static final String SLIDE_RENDER_FAIL = "004";

    /** Stage2 진행 상태 — 미시작 */
    private static final String STAGE2_STATUS_NOT_STARTED = "001";
    /** Stage2 진행 상태 — 문제정의 저장 완료(S2C/S2B 진행·재개 대상) */
    private static final String STAGE2_STATUS_PROBLEM_SAVED = "002";
    /** Stage2 진행 상태 — 완료(Win Theme까지 저장) */
    private static final String STAGE2_STATUS_DONE = "003";
    /** Stage2 진행 상태 — 실패 */
    private static final String STAGE2_STATUS_FAILED = "004";
    /** Stage2 진행 상태 — 전략(문제정의+WinTheme) 완료, 세부목차 미생성 */
    private static final String STAGE2_STATUS_STRATEGY_DONE = "005";

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

    /** S2C 대목차 배치 병렬 호출 전용 스레드 풀 */
    private static final ExecutorService STAGE_S2C_BATCH_EXECUTOR =
            Executors.newFixedThreadPool(6, r -> {
                Thread t = new Thread(r, "pt-s2c-batch-worker");
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

    /** Stage2 조기/후반 저장의 독립 커밋용 (self-invocation @Transactional 우회) */
    @Autowired
    private TransactionTemplate transactionTemplate;


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
                sendSseEvent(emitter, "progress", "{\"step\":\"extract\"}");
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

                // Step 2: 프롬프트 조합
                sendSseEvent(emitter, "progress", "{\"step\":\"prompt\"}");
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
                sendSseEvent(emitter, "progress", "{\"step\":\"llm\"}");
                ProposalVO.Stage1ResultVO parsed;
                if (rfpText.length() > PT_RFP_TEXT_MAX_CHARS) {
                    // ── 대용량 경로: 청크 완전 추출 + Java 병합 (LLM 재호출 없음) ────────
                    sendSseEvent(emitter, "progress", "{\"step\":\"chunk_extract\"}");
                    parsed = extractStage1FromLargeRfp(rfpText, promptContent, modelId, emitter, agentId);
                } else {
                    // ── 단일 호출 경로 (rfpText가 임계값 이하) ──────────────────────────
                    String fullPrompt = promptContent + "\n\n## RFP 원문\n" + rfpText;

                    String aiResponse = callLlmWithRetry(fullPrompt, modelId, agentId, "[PT Stage1]");
                    if (CommonUtil.isEmpty(aiResponse)) {
                        sendSseEvent(emitter, "error", "{\"message\":\"AI 응답이 비어 있습니다. 잠시 후 다시 시도해 주세요.\"}");
                        emitter.complete();
                        return;
                    }

                    // Step 4: JSON 파싱 (실패 시 1회 재시도)
                    sendSseEvent(emitter, "progress", "{\"step\":\"parse\"}");
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
                sendSseEvent(emitter, "progress", "{\"step\":\"save\"}");
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
        } else {
            // ── 단일 호출 경로 ──────────────────────────────────────────────────
            String fullPrompt = promptContent + "\n\n## RFP 원문\n" + rfpText;

            String aiResponse = callLlmWithRetry(fullPrompt, modelId, agentId, "[PT Stage1]");
            if (CommonUtil.isEmpty(aiResponse))
                throw new RuntimeException("LLM 응답이 비어 있습니다. Stage 1 분석을 완료할 수 없습니다.");

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
                req.setReqCategoryTxt(trimToNull(req.getReqCategoryTxt()));
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

        // RFP 이슈 초기화 후 재등록 (빈 리스트도 정상 케이스 — RFP에 문제점 서술이 없는 경우)
        proposalDAO.deleteRfpIssuesByProject(ptProjectId);
        if (parsed.getRfpIssues() != null) {
            for (ProposalVO.RfpIssueVO issue : parsed.getRfpIssues()) {
                issue.setIssueId(keyGenerate.generateTableKey("PTI", "tb_pt_rfp_issue", "ISSUE_ID", 6));
                issue.setPtProjectId(ptProjectId);
                issue.setCreateUserId(userId);
                proposalDAO.insertRfpIssue(issue);
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

        // 작성지침 저장 (pageLimit, formatRules)
        ProposalVO.ProjectVO updateVO = new ProposalVO.ProjectVO();
        updateVO.setPtProjectId(ptProjectId);
        updateVO.setWritingGuidelineJson(parsed.getWritingGuidelineJson());
        updateVO.setModifyUserId(userId);
        proposalDAO.updateProjectWritingGuideline(updateVO);

        // TOC 초기화 + 재등록 (Stage1에서 직접 TB_PT_TOC insert — guideContent 포함)
        proposalDAO.deleteTocByProject(ptProjectId);
        if (parsed.getTocList() != null && !parsed.getTocList().isEmpty()) {
            java.util.Map<String, String> noToTocId = new java.util.LinkedHashMap<>();
            int tocSortOrd = 0;
            // 1-pass: 대목차(level=1) 선행 insert
            for (ProposalVO.TocVO toc : parsed.getTocList()) {
                if (toc.getLevel() != 1) continue;
                toc.setTocId(keyGenerate.generateTableKey("PTT", "TB_PT_TOC", "TOC_ID", 6));
                toc.setPtProjectId(ptProjectId);
                toc.setPlannedSlideCnt(1);
                toc.setSortOrd(tocSortOrd++);
                toc.setCreateUserId(userId);
                toc.setOriginTypeCd("001"); // Stage1 RFP 추출
                if (CommonUtil.isNotEmpty(toc.getNo())) noToTocId.put(toc.getNo(), toc.getTocId());
                proposalDAO.insertToc(toc);
            }
            // 2-pass: 소목차(level=2) insert — parentTocId 참조
            for (ProposalVO.TocVO toc : parsed.getTocList()) {
                if (toc.getLevel() != 2) continue;
                toc.setTocId(keyGenerate.generateTableKey("PTT", "TB_PT_TOC", "TOC_ID", 6));
                toc.setPtProjectId(ptProjectId);
                toc.setParentTocId(CommonUtil.isNotEmpty(toc.getParentNo()) ? noToTocId.get(toc.getParentNo()) : null);
                toc.setPlannedSlideCnt(1);
                toc.setSortOrd(tocSortOrd++);
                toc.setCreateUserId(userId);
                toc.setOriginTypeCd("001"); // Stage1 RFP 추출
                proposalDAO.insertToc(toc);
            }
            logger.info("[PT Stage1] TOC 저장 완료: {}건 (ptProjectId={})", parsed.getTocList().size(), ptProjectId);
        } else {
            logger.info("[PT Stage1] toc 없음 — TB_PT_TOC insert 생략 (ptProjectId={})", ptProjectId);
        }

        // 상태 → '002' 검수중
        ProposalVO.ProjectVO statusVO = new ProposalVO.ProjectVO();
        statusVO.setPtProjectId(ptProjectId);
        statusVO.setStatusCd("002");
        statusVO.setModifyUserId(userId);
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
        List<ProposalVO.RfpIssueVO> rfpIssues = proposalDAO.selectRfpIssues(ptProjectId);

        ProposalVO.Stage1ResultVO result = new ProposalVO.Stage1ResultVO();
        result.setPtProjectId(ptProjectId);
        result.setWritingGuidelineJson(project.getWritingGuidelineJson());
        result.setRequirements(requirements);
        result.setEvalCriteria(evalCriteria);
        result.setRfpIssues(rfpIssues != null ? rfpIssues : new java.util.ArrayList<>());

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
                                String.format("{\"step\":\"chunk\",\"current\":%d,\"total\":%d}",
                                        done, chunkCount));
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

        // writingGuideline 병합: 첫 번째 비어있지 않은 값 채택 (pageLimit/formatRules만 포함)
        String bestGuideline = null;
        for (ProposalVO.Stage1ResultVO cr : chunkResults) {
            String wg = cr.getWritingGuidelineJson();
            if (CommonUtil.isNotEmpty(wg)) { bestGuideline = wg; break; }
        }
        merged.setWritingGuidelineJson(bestGuideline);

        // tocList 병합: 첫 번째 비어있지 않은 tocList를 베이스로, 이후 청크에서 guideContent 보완
        // (세부 작성 지침이 여러 페이지에 걸칠 경우 청크가 달라져 guideContent가 null이 되는 문제 방지)
        List<ProposalVO.TocVO> bestTocList = new java.util.ArrayList<>();
        int bestTocChunkIdx = -1;
        for (int i = 0; i < chunkResults.size(); i++) {
            ProposalVO.Stage1ResultVO cr = chunkResults.get(i);
            if (cr.getTocList() != null && !cr.getTocList().isEmpty()) {
                bestTocList = new java.util.ArrayList<>(cr.getTocList());
                bestTocChunkIdx = i;
                break;
            }
        }
        if (bestTocChunkIdx >= 0) {
            // sectionNm → 인덱스 맵 (null guideContent 보완을 위한 빠른 조회)
            java.util.Map<String, Integer> nmToIdx = new java.util.LinkedHashMap<>();
            for (int i = 0; i < bestTocList.size(); i++) {
                String nm = bestTocList.get(i).getSectionNm();
                if (CommonUtil.isNotEmpty(nm) && !nmToIdx.containsKey(nm)) {
                    nmToIdx.put(nm, i);
                }
            }
            // 이후 청크들에서 guideContent가 있는 항목으로 null 보완
            for (int ci = bestTocChunkIdx + 1; ci < chunkResults.size(); ci++) {
                ProposalVO.Stage1ResultVO cr = chunkResults.get(ci);
                if (cr.getTocList() == null || cr.getTocList().isEmpty()) continue;
                for (ProposalVO.TocVO other : cr.getTocList()) {
                    if (!CommonUtil.isNotEmpty(other.getGuideContent())) continue;
                    String nm = other.getSectionNm();
                    if (!CommonUtil.isNotEmpty(nm)) continue;
                    Integer idx = nmToIdx.get(nm);
                    if (idx != null && !CommonUtil.isNotEmpty(bestTocList.get(idx).getGuideContent())) {
                        bestTocList.get(idx).setGuideContent(other.getGuideContent());
                        logger.info("[PT Stage1 Merge] tocList guideContent 보완: sectionNm='{}' (청크 {}→{})", nm, bestTocChunkIdx, ci);
                    }
                }
            }
        }
        merged.setTocList(bestTocList);

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

        // rfpIssues 병합 (중복 issueContent 기준 dedup)
        java.util.List<ProposalVO.RfpIssueVO> allIssues = new java.util.ArrayList<>();
        java.util.Set<String> seenIssueContents = new java.util.LinkedHashSet<>();
        int issueSortBase = 0;
        for (ProposalVO.Stage1ResultVO cr : chunkResults) {
            if (cr.getRfpIssues() == null) continue;
            for (ProposalVO.RfpIssueVO issue : cr.getRfpIssues()) {
                String content = CommonUtil.nullToBlank(issue.getIssueContent()).trim();
                if (content.isEmpty()) continue;
                if (seenIssueContents.contains(content)) {
                    logger.warn("[PT Stage1 Merge] 중복 rfpIssue — 스킵: {}",
                            content.substring(0, Math.min(50, content.length())));
                    continue;
                }
                seenIssueContents.add(content);
                issue.setSortOrd(issueSortBase++);
                allIssues.add(issue);
            }
        }
        merged.setRfpIssues(allIssues);

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

    /**
     * PT 파일 다운로드 presigned URL 발급 (TB_PT_FILE.PT_FILE_ID 기준)
     */
    public Map<String, Object> downloadPtFile(ProposalVO.PtFileVO dataVO) throws Exception {
        if (dataVO == null || CommonUtil.isEmpty(dataVO.getPtFileId())) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("url", "");
            return empty;
        }

        ProposalVO.PtFileVO row = proposalDAO.selectPtFileById(dataVO.getPtFileId());
        if (row == null || CommonUtil.isEmpty(row.getFilePath())) {
            Map<String, Object> notFound = new HashMap<>();
            notFound.put("url", "");
            return notFound;
        }

        FileVO fileVo = new FileVO();
        fileVo.setFilePath(row.getFilePath());
        fileVo.setFileName(row.getFileNm());
        fileVo.setFileType(row.getFileType());
        return fileService.createDownloadPresignedUrlForStorageObject(fileVo);
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
        updateVO.setModifyUserId(SessionUtil.getUserId());
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

        // writingGuideline 파싱 (선택적 — pageLimit, formatRules만 포함)
        String writingGuidelineJson = null;
        if (root.has("writingGuideline") && !root.get("writingGuideline").isJsonNull()) {
            writingGuidelineJson = GSON.toJson(root.get("writingGuideline"));
        }

        // toc 파싱 (최상위 "toc" 배열, 선택적)
        List<ProposalVO.TocVO> tocList = new java.util.ArrayList<>();
        if (root.has("toc") && root.get("toc").isJsonArray()) {
            for (JsonElement tocEl : root.getAsJsonArray("toc")) {
                JsonObject obj = tocEl.getAsJsonObject();
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
                toc.setParentNo(getStrOrNull(obj, "parentNo"));
                toc.setGuideContent(getStrOrNull(obj, "guideContent"));
                tocList.add(toc);
            }
        }

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
            req.setReqCategoryTxt(trimToNull(getStrOrNull(obj, "reqCategoryTxt")));
            req.setReqContent(getStrOrNull(obj, "reqContent"));
            if (CommonUtil.isEmpty(req.getReqContent())) {
                throw new RuntimeException("requirements 항목에 reqContent 필드가 누락되었습니다.");
            }
            req.setReqDetailTxt(getStrOrNull(obj, "reqDetailTxt")); // null 허용
            req.setMandatoryYn(getStrOrNull(obj, "mandatoryYn"));
            req.setSourceTypeCd(getStrOrNull(obj, "sourceTypeCd"));
            req.setConfirmNeededYn(getStrOrNull(obj, "confirmNeededYn"));
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

        // rfpIssues 파싱 (최상위 "rfpIssues" 배열, 선택적 — 빈 배열([])이 정상 케이스)
        List<ProposalVO.RfpIssueVO> rfpIssues = new java.util.ArrayList<>();
        if (root.has("rfpIssues") && root.get("rfpIssues").isJsonArray()) {
            int issueSortOrd = 0;
            for (JsonElement el : root.getAsJsonArray("rfpIssues")) {
                JsonObject obj = el.getAsJsonObject();
                String issueContent = getStrOrNull(obj, "issueContent");
                if (CommonUtil.isEmpty(issueContent)) continue;
                ProposalVO.RfpIssueVO issue = new ProposalVO.RfpIssueVO();
                String issueTypeCd = getStrOrNull(obj, "issueTypeCd");
                issue.setIssueTypeCd(CommonUtil.isNotEmpty(issueTypeCd) ? issueTypeCd : "003"); // fallback: 추진배경/필요성
                issue.setIssueContent(issueContent);
                issue.setIssueLabel(getStrOrNull(obj, "issueLabel"));
                issue.setSourceSection(getStrOrNull(obj, "sourceSection"));
                if (obj.has("sourcePage") && !obj.get("sourcePage").isJsonNull()) {
                    try { issue.setSourcePage(obj.get("sourcePage").getAsInt()); } catch (Exception ignored) {}
                }
                issue.setSortOrd(issueSortOrd++);
                rfpIssues.add(issue);
            }
        }

        ProposalVO.Stage1ResultVO result = new ProposalVO.Stage1ResultVO();
        result.setWritingGuidelineJson(writingGuidelineJson);
        result.setRequirements(requirements);
        result.setEvalCriteria(evalCriteria);
        result.setTocList(tocList);
        result.setRfpIssues(rfpIssues);

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
        if (CommonUtil.isEmpty(writingGuidelineJson)) {
            logger.info("[PT Stage1] writingGuideline 없음 (pageLimit/formatRules 미추출) ptProjectId={}", ptProjectId);
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
        updateVO.setModifyUserId(SessionUtil.getUserId());
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
        vo.setModifyUserId(SessionUtil.getUserId());
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
        vo.setOriginTypeCd("003"); // 사용자 수동 추가
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
        vo.setModifyUserId(SessionUtil.getUserId());
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
        String modifyUserId = SessionUtil.getUserId();
        for (ProposalVO.TocVO item : vo.getItems()) {
            item.setModifyUserId(modifyUserId);
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
     * Stage 2 실행 (진행상황 콜백 지원 오버로드) — 전략 오케스트레이터 (S2A + S2B만).
     * S2A → S2B를 순차 호출하며, 세부목차(S2C)는 {@link #executeStage2Toc}에서 별도 실행한다.
     * @param progressCallback step 코드 콜백 (null 허용).
     *                         step: load | prompt | problem_def | parse | win_theme | save
     * STAGE2_STATUS_CD: 001미시작 | 002문제정의저장(S2B 재개 대상) | 005전략완료(세부목차 미생성) | 003완료 | 004실패
     */
    public ProposalVO.Stage2ResultVO executeStage2(String ptProjectId, int totalSlideBudget, String modelId, String agentId,
            java.util.function.Consumer<String> progressCallback) throws Exception {

        if (progressCallback != null) progressCallback.accept("load");
        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);

        String stage2StatusCd = CommonUtil.isNotEmpty(project.getStage2StatusCd())
                ? project.getStage2StatusCd() : STAGE2_STATUS_NOT_STARTED;
        if (STAGE2_STATUS_STRATEGY_DONE.equals(stage2StatusCd) || STAGE2_STATUS_DONE.equals(stage2StatusCd)) {
            logger.info("[PT Stage2] 전략 이미 완료(STAGE2_STATUS_CD={}), 저장 결과 반환 (ptProjectId={})", stage2StatusCd, ptProjectId);
            return selectStage2Result(ptProjectId);
        }
        boolean resumeFromS2b = STAGE2_STATUS_PROBLEM_SAVED.equals(stage2StatusCd);

        try {
            if (resumeFromS2b) {
                logger.info("[PT Stage2] STAGE2_STATUS_CD=002 — S2B(WinTheme)부터 재개 (ptProjectId={})", ptProjectId);
                if (progressCallback != null) progressCallback.accept("parse");
                List<ProposalVO.ProblemDefinitionVO> existing =
                        proposalDAO.selectProblemDefinitions(ptProjectId);
                if (existing == null || existing.isEmpty()) {
                    logger.warn("[PT Stage2] STATUS=002 이나 문제정의 없음 — S2A부터 재실행 (ptProjectId={})", ptProjectId);
                    resumeFromS2b = false;
                }
            }

            if (!resumeFromS2b) {
                runS2a(ptProjectId, totalSlideBudget, modelId, agentId, progressCallback);
            }
            runS2b(ptProjectId, null, modelId, agentId, progressCallback);

            ProposalVO.Stage2ResultVO result = new ProposalVO.Stage2ResultVO();
            result.setPtProjectId(ptProjectId);
            result.setProblemDefinitions(proposalDAO.selectProblemDefinitions(ptProjectId));
            result.setWinThemes(proposalDAO.selectWinThemes(ptProjectId));
            return result;
        } catch (Exception e) {
            // 002(문제정의 저장됨) 또는 005(전략 완료)면 재개 가능하므로 유지, 그 외는 004
            try {
                ProposalVO.ProjectVO cur = proposalDAO.selectProject(ptProjectId);
                String curStatus = cur != null ? cur.getStage2StatusCd() : null;
                if (!STAGE2_STATUS_PROBLEM_SAVED.equals(curStatus)
                        && !STAGE2_STATUS_STRATEGY_DONE.equals(curStatus)
                        && !STAGE2_STATUS_DONE.equals(curStatus)) {
                    updateStage2StatusCd(ptProjectId, STAGE2_STATUS_FAILED);
                }
            } catch (Exception statusEx) {
                logger.warn("[PT Stage2] STAGE2_STATUS_CD=004 갱신 실패 (ptProjectId={}): {}", ptProjectId, statusEx.getMessage());
            }
            throw e;
        }
    }

    /**
     * Stage2-A: 문제정의 LLM 생성 + 조기 저장(STAGE2_STATUS_CD=002).
     * 개별 엔드포인트·오케스트레이터 공용.
     */
    public List<ProposalVO.ProblemDefinitionVO> runS2a(String ptProjectId, int totalSlideBudget,
            String modelId, String agentId) throws Exception {
        return runS2a(ptProjectId, totalSlideBudget, modelId, agentId, null, null);
    }

    public List<ProposalVO.ProblemDefinitionVO> runS2a(String ptProjectId, int totalSlideBudget,
            String modelId, String agentId, java.util.function.Consumer<String> progressCallback) throws Exception {
        return runS2a(ptProjectId, totalSlideBudget, modelId, agentId, progressCallback, null);
    }

    public List<ProposalVO.ProblemDefinitionVO> runS2a(String ptProjectId, int totalSlideBudget,
            String modelId, String agentId, java.util.function.Consumer<String> progressCallback,
            String userFeedback) throws Exception {

        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);

        List<ProposalVO.RequirementVO> requirements = proposalDAO.selectRequirements(ptProjectId);
        List<ProposalVO.RfpIssueVO> rfpIssues = proposalDAO.selectRfpIssues(ptProjectId);
        List<ProposalVO.TocVO> existingTocInDb = proposalDAO.selectTocList(ptProjectId);

        if (progressCallback != null) progressCallback.accept("prompt");
        String s2aPromptContent = null;
        try { s2aPromptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S2A_PROBLEM_TOC"); }
        catch (Exception e) { logger.warn("[PT Stage2-A] 프롬프트 조회 실패: {}", e.getMessage()); }
        if (CommonUtil.isEmpty(s2aPromptContent))
            throw new RuntimeException("Stage2 프롬프트가 DB에 등록되어 있지 않습니다. STAGE_CD=S2A_PROBLEM_TOC 확인 필요");

        Stage2aPromptContext s2aPromptCtx = buildStage2aPromptSlim(
                s2aPromptContent, project, requirements, rfpIssues, totalSlideBudget);
        String s2aPrompt = s2aPromptCtx.getPromptText();
        if (CommonUtil.isNotEmpty(userFeedback)) {
            s2aPrompt = s2aPrompt + "\n\n## 사용자 보완 요청\n"
                    + "아래 요청을 반영해 문제정의 전체를 재작성하세요.\n" + userFeedback;
        }
        List<ProposalVO.RequirementLiteVO> shownReqs = s2aPromptCtx.getSampledReqs();

        if (progressCallback != null) progressCallback.accept("problem_def");
        long s2aStart = System.currentTimeMillis();
        logger.info("[PT Stage2-A] 호출 시작 - 프롬프트 길이: {}자 (ptProjectId={})", s2aPrompt.length(), ptProjectId);
        String s2aResponse = callLlmWithRetry(s2aPrompt, modelId, agentId, "[PT Stage2-A]");
        if (CommonUtil.isEmpty(s2aResponse))
            throw new RuntimeException("LLM 응답이 비어 있습니다. Stage 2-A(문제정의) 분석을 완료할 수 없습니다.");
        logger.info("[PT Stage2-A] 완료 - 프롬프트 길이: {}자, 소요시간: {}ms (ptProjectId={})",
                s2aPrompt.length(), System.currentTimeMillis() - s2aStart, ptProjectId);

        if (progressCallback != null) progressCallback.accept("parse");
        ProposalVO.Stage2ResultVO parsed = parseStage2aResponse(s2aResponse);

        java.util.Set<String> validIssueIds = new java.util.HashSet<>();
        if (rfpIssues != null)
            for (ProposalVO.RfpIssueVO i : rfpIssues)
                if (CommonUtil.isNotEmpty(i.getIssueId())) validIssueIds.add(i.getIssueId());
        java.util.Set<String> validProblemSourceReqIds = new java.util.HashSet<>();
        if (shownReqs != null)
            for (ProposalVO.RequirementLiteVO r : shownReqs)
                if (CommonUtil.isNotEmpty(r.getRequirementId())) validProblemSourceReqIds.add(r.getRequirementId());
        if (parsed.getProblemDefinitions() != null) {
            for (ProposalVO.ProblemDefinitionVO pd : parsed.getProblemDefinitions()) {
                pd.setSourceIssueIdsJson(filterValidIds(pd.getSourceIssueIdsJson(), validIssueIds));
                pd.setSourceRequirementIdsJson(filterValidIds(pd.getSourceRequirementIdsJson(), validProblemSourceReqIds));
            }
        }

        saveStage2ProblemDefinitions(ptProjectId, parsed.getProblemDefinitions());
        logger.info("[PT Stage2-A] TB_PT_TOC 목차 사용 (tocCount={}, ptProjectId={})",
                existingTocInDb.size(), ptProjectId);
        return parsed.getProblemDefinitions();
    }

    /**
     * Stage2-C: coveredReqIds/linkedEvalCriteriaId 매핑 + 슬라이드 배분 + TOC 저장.
     * 개별 엔드포인트·오케스트레이터 공용. 전제: 문제정의·TOC가 DB에 있어야 한다.
     */
    public List<ProposalVO.TocVO> runS2c(String ptProjectId, int totalSlideBudget,
            String modelId, String agentId) throws Exception {
        return runS2c(ptProjectId, totalSlideBudget, modelId, agentId, null);
    }

    public List<ProposalVO.TocVO> runS2c(String ptProjectId, int totalSlideBudget,
            String modelId, String agentId, java.util.function.Consumer<String> progressCallback) throws Exception {

        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);

        List<ProposalVO.RequirementVO> requirements = proposalDAO.selectRequirements(ptProjectId);
        List<ProposalVO.EvalCriteriaVO> evalCriteria = proposalDAO.selectEvalCriteria(ptProjectId);
        List<ProposalVO.TocVO> tocList = proposalDAO.selectTocList(ptProjectId);
        if (tocList == null || tocList.isEmpty())
            throw new RuntimeException("목차가 없습니다. Stage1 완료 후 다시 시도하세요. ptProjectId=" + ptProjectId);

        List<ProposalVO.WinThemeVO> winThemes = null;
        try { winThemes = proposalDAO.selectWinThemes(ptProjectId); }
        catch (Exception e) { logger.warn("[PT Stage2-C] Win Theme 조회 실패, 프롬프트에서 제외 (ptProjectId={}): {}", ptProjectId, e.getMessage()); }

        java.util.Set<String> validReqIds = new java.util.HashSet<>();
        if (requirements != null)
            for (ProposalVO.RequirementVO r : requirements)
                if (CommonUtil.isNotEmpty(r.getRequirementId())) validReqIds.add(r.getRequirementId());

        if (progressCallback != null) progressCallback.accept("req_mapping");
        String s2cPromptContent = null;
        try { s2cPromptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S2C_COVEREDREQNOS"); }
        catch (Exception e) { logger.warn("[PT Stage2-C] 프롬프트 조회 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage()); }
        if (CommonUtil.isEmpty(s2cPromptContent))
            throw new RuntimeException("Stage2 프롬프트가 DB에 등록되어 있지 않습니다. STAGE_CD=S2C_COVEREDREQNOS 확인 필요");

        // ── 요구사항 → 대목차 배치 라우팅 ──────────────────────────────────────
        List<ProposalVO.RequirementVO> unmatchedReqs = new java.util.ArrayList<>();
        java.util.Map<String, List<ProposalVO.RequirementVO>> batchReqMap =
                routeRequirementsToTocGroups(tocList, requirements, unmatchedReqs);

        // 고정 프리픽스 (캐싱 구조 대비 분리) + 공통 경량 리스트 + WinTheme (배치 간 공통)
        String fixedPrefix = buildS2cFixedPrefix(s2cPromptContent, evalCriteria);
        List<java.util.Map<String, Object>> unmatchedReqsLite = unmatchedReqs.stream()
                .map(this::toRequirementMinimalLite)
                .collect(java.util.stream.Collectors.toList());
        List<java.util.Map<String, Object>> winThemeLite =
                (winThemes != null ? winThemes : java.util.Collections.<ProposalVO.WinThemeVO>emptyList())
                .stream().map(this::toWinThemeUltraLite)
                .collect(java.util.stream.Collectors.toList());

        // ── 대목차별 배치 구성 및 병렬 제출 ─────────────────────────────────────
        java.util.Map<String, String> tocIdToSectionNm = new java.util.LinkedHashMap<>();
        for (ProposalVO.TocVO t : tocList)
            if (CommonUtil.isNotEmpty(t.getTocId())) tocIdToSectionNm.put(t.getTocId(), t.getSectionNm());

        long s2cStart = System.currentTimeMillis();
        java.util.List<java.util.concurrent.Future<String>> batchFutures = new java.util.ArrayList<>();
        java.util.List<String> batchParentTocIds = new java.util.ArrayList<>();

        for (Map.Entry<String, List<ProposalVO.RequirementVO>> entry : batchReqMap.entrySet()) {
            String parentTocId = entry.getKey();
            List<ProposalVO.RequirementVO> matchedReqs = entry.getValue();
            String parentSectionNm = tocIdToSectionNm.getOrDefault(parentTocId, parentTocId);

            // 배치 노드: 대목차 자신 + 직계 소분류
            List<ProposalVO.TocVO> batchNodes = new java.util.ArrayList<>();
            for (ProposalVO.TocVO t : tocList) {
                if (parentTocId.equals(t.getTocId()) || parentTocId.equals(t.getParentTocId()))
                    batchNodes.add(t);
            }

            // 스킵: 매칭 요구사항 0건 && 공통 미매칭도 없음
            if (matchedReqs.isEmpty() && unmatchedReqsLite.isEmpty()) {
                logger.info("[PT Stage2-C] 배치 스킵 — 대목차='{}' (요구사항 없음, ptProjectId={})",
                        parentSectionNm, ptProjectId);
                continue;
            }

            batchParentTocIds.add(parentTocId);
            final String batchPrompt = buildStage2cBatchPrompt(
                    fixedPrefix, batchNodes, matchedReqs, unmatchedReqsLite, winThemeLite, parentSectionNm);
            final String batchNm = parentSectionNm;
            final String pId = ptProjectId;

            batchFutures.add(STAGE_S2C_BATCH_EXECUTOR.submit(() -> {
                logger.info("[PT Stage2-C][배치={}] LLM 호출 시작 — 프롬프트:{}자 (ptProjectId={})",
                        batchNm, batchPrompt.length(), pId);
                try {
                    String resp = callLlmWithRetry(batchPrompt, modelId, agentId,
                            "[PT Stage2-C][배치=" + batchNm + "]");
                    if (CommonUtil.isEmpty(resp))
                        logger.warn("[PT Stage2-C][배치={}] LLM 응답 없음, 배치 결과 없음 (ptProjectId={})", batchNm, pId);
                    return resp;
                } catch (Exception e) {
                    logger.warn("[PT Stage2-C][배치={}] LLM 호출 예외, 배치 스킵 (ptProjectId={}): {}",
                            batchNm, pId, e.getMessage());
                    return null;
                }
            }));
        }

        // ── 병렬 배치 결과 수집 + parseAndApplyStage2cResponse 반복 적용 ────────
        java.util.Set<String> validEvalCriteriaIds = new java.util.HashSet<>();
        if (evalCriteria != null)
            for (ProposalVO.EvalCriteriaVO ec : evalCriteria)
                if (CommonUtil.isNotEmpty(ec.getEvalCriteriaId())) validEvalCriteriaIds.add(ec.getEvalCriteriaId());

        int batchSuccess = 0, batchFail = 0;
        for (int i = 0; i < batchFutures.size(); i++) {
            String parentTocId = batchParentTocIds.get(i);
            String parentSectionNm = tocIdToSectionNm.getOrDefault(parentTocId, parentTocId);
            try {
                String resp = batchFutures.get(i).get(PT_QUERY_TIMEOUT_SEC + 30L, java.util.concurrent.TimeUnit.SECONDS);
                if (CommonUtil.isNotEmpty(resp)) {
                    parseAndApplyStage2cResponse(tocList, resp, ptProjectId, validEvalCriteriaIds, parentTocId, parentSectionNm);
                    batchSuccess++;
                } else {
                    batchFail++;
                }
            } catch (Exception e) {
                logger.warn("[PT Stage2-C][배치={}] 결과 수집 실패 (ptProjectId={}): {}",
                        parentSectionNm, ptProjectId, e.getMessage());
                batchFail++;
            }
        }
        logger.info("[PT Stage2-C] 전체 배치 완료 — 성공:{}개, 실패/스킵:{}개, 소요시간:{}ms (ptProjectId={})",
                batchSuccess, batchFail, System.currentTimeMillis() - s2cStart, ptProjectId);

        // 배치 병렬 호출로 인한 동일 requirementId 중복 배정 제거 (대목차 순서 우선 유지)
        deduplicateS2cCoveredReqIds(tocList, ptProjectId);

        validateAndCleanTocReqIds(tocList, validReqIds, ptProjectId);
        if (!tocList.isEmpty()) {
            warnUncoveredRequirements(tocList, validReqIds, ptProjectId);
        }

        calculateSlideCounts(tocList, evalCriteria, totalSlideBudget);
        saveStage2TocMapping(ptProjectId, tocList, evalCriteria, requirements);
        return tocList;
    }

    /**
     * Stage2-B: Win Theme LLM 생성 + 저장(STAGE2_STATUS_CD=003).
     * 개별 엔드포인트·오케스트레이터 공용. feedback이 있으면 보완 요청으로 프롬프트에 포함한다.
     */
    public List<ProposalVO.WinThemeVO> runS2b(String ptProjectId, String feedback,
            String modelId, String agentId) throws Exception {
        return runS2b(ptProjectId, feedback, modelId, agentId, null);
    }

    public List<ProposalVO.WinThemeVO> runS2b(String ptProjectId, String feedback,
            String modelId, String agentId, java.util.function.Consumer<String> progressCallback) throws Exception {

        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);

        List<ProposalVO.ProblemDefinitionVO> problemDefinitions =
                proposalDAO.selectProblemDefinitions(ptProjectId);
        if (problemDefinitions == null || problemDefinitions.isEmpty())
            throw new RuntimeException("문제정의가 없습니다. Stage2-A 완료 후 다시 시도하세요. ptProjectId=" + ptProjectId);

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
            logger.warn("[PT Stage2-B] 설정 파일 ID 파싱 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
        }

        if (progressCallback != null) progressCallback.accept("extract_ref");
        String ownContext = extractMultiFileText(companyFileIds);
        if (CommonUtil.isEmpty(ownContext)) ownContext = "(자사 자료 없음)";
        String competitorContext = extractMultiFileText(competitorFileIds);
        if (CommonUtil.isEmpty(competitorContext)) competitorContext = "(경쟁사 자료 없음)";
        String etcRefContext = extractMultiFileText(etcRefFileIds);
        if (CommonUtil.isEmpty(etcRefContext)) etcRefContext = "(기타 참고자료 없음)";

        String s2bPromptContent = null;
        try { s2bPromptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S2B_WINTHEME"); }
        catch (Exception e) { logger.warn("[PT Stage2-B] 프롬프트 조회 실패: {}", e.getMessage()); }
        if (CommonUtil.isEmpty(s2bPromptContent))
            throw new RuntimeException("Stage2 프롬프트가 DB에 등록되어 있지 않습니다. STAGE_CD=S2B_WINTHEME 확인 필요");

        String s2bPrompt = buildStage2bWinThemePrompt(
                s2bPromptContent, project, problemDefinitions, ownContext, competitorContext, etcRefContext, feedback);

        if (progressCallback != null) progressCallback.accept("win_theme");
        long s2bStart = System.currentTimeMillis();
        logger.info("[PT Stage2-B] 호출 시작 - 프롬프트 길이: {}자 (ptProjectId={})", s2bPrompt.length(), ptProjectId);
        String s2bResponse = callLlmWithRetry(s2bPrompt, modelId, agentId, "[PT Stage2-B]");
        if (CommonUtil.isEmpty(s2bResponse))
            throw new RuntimeException("LLM 응답이 비어 있습니다. Stage 2-B(Win Theme) 분석을 완료할 수 없습니다.");
        logger.info("[PT Stage2-B] 완료 - 프롬프트 길이: {}자, 소요시간: {}ms (ptProjectId={})",
                s2bPrompt.length(), System.currentTimeMillis() - s2bStart, ptProjectId);

        List<ProposalVO.WinThemeVO> winThemes = parseStage2bResponse(s2bResponse);

        java.util.Set<String> validProblemIds = new java.util.HashSet<>();
        for (ProposalVO.ProblemDefinitionVO pd : problemDefinitions) {
            if (CommonUtil.isNotEmpty(pd.getProblemId())) validProblemIds.add(pd.getProblemId());
        }
        if (winThemes != null) {
            for (ProposalVO.WinThemeVO wt : winThemes) {
                wt.setSourceProblemIdsJson(filterValidIds(wt.getSourceProblemIdsJson(), validProblemIds));
                if (CommonUtil.isEmpty(wt.getSourceProblemIdsJson())) {
                    logger.warn("[PT Stage2-B] sourceProblemDefinitionIds 비어 있음 — Win Theme stale 추적 불가 (coreMessage={})",
                            wt.getCoreMessage());
                }
            }
        }

        validateStage2Evidence(winThemes, ptProjectId);

        if (progressCallback != null) progressCallback.accept("save");
        saveStage2WinThemes(ptProjectId, winThemes);
        return winThemes;
    }

    /**
     * Stage2-A 직후 문제정의만 조기 저장 (TransactionTemplate 독립 커밋).
     * 커밋 후 STAGE2_STATUS_CD=002 — 동시 Stage2의 SELECT MAX가 이 키를 볼 수 있다.
     */
    public void saveStage2ProblemDefinitions(String ptProjectId,
            List<ProposalVO.ProblemDefinitionVO> problemDefinitions) {
        String userId = SessionUtil.getUserId();
        logger.info("[PT Stage2] 문제정의 조기 저장 시작 (ptProjectId={}, count={})",
                ptProjectId, problemDefinitions != null ? problemDefinitions.size() : 0);

        transactionTemplate.execute(status -> {
            try {
                proposalDAO.deleteProblemDefinitionsByProject(ptProjectId);
                if (problemDefinitions != null) {
                    int ord = 0;
                    for (ProposalVO.ProblemDefinitionVO pd : problemDefinitions) {
                        pd.setProblemId(keyGenerate.generateTableKey("PTP", "TB_PT_PROBLEM_DEFINITION", "PROBLEM_ID", 6));
                        pd.setPtProjectId(ptProjectId);
                        pd.setCreateUserId(userId != null ? userId : "hj249");
                        if (pd.getSortOrd() == null) pd.setSortOrd(ord++);
                        if (CommonUtil.isEmpty(pd.getSourceTypeCd())) pd.setSourceTypeCd("002");
                        proposalDAO.insertProblemDefinition(pd);
                    }
                }
                ProposalVO.ProjectVO statusVO = new ProposalVO.ProjectVO();
                statusVO.setPtProjectId(ptProjectId);
                statusVO.setStage2StatusCd(STAGE2_STATUS_PROBLEM_SAVED);
                statusVO.setModifyUserId(userId);
                proposalDAO.updateStage2StatusCd(statusVO);
                return null;
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException("[PT Stage2] 문제정의 조기 저장 실패: " + e.getMessage(), e);
            }
        });
        logger.info("[PT Stage2] 문제정의 조기 저장 완료 (STAGE2_STATUS_CD=002, ptProjectId={})", ptProjectId);
    }

    /**
     * Stage2-C 직후 TOC 매핑만 저장 (TransactionTemplate 독립 커밋).
     * LINKED_EVAL_CRITERIA_ID, COVERED_REQ_IDS_JSON, PLANNED_SLIDE_CNT + 평가기준 SLIDE_REFLECT_POSITION.
     * 기존 값과 동일한 노드는 UPDATE를 건너뛰어 MODIFY_DT를 보존한다 (Step6 stale 오염 방지).
     * STAGE2_STATUS_CD는 변경하지 않는다 (003은 {@link #saveStage2WinThemes}에서 설정).
     */
    public void saveStage2TocMapping(String ptProjectId, List<ProposalVO.TocVO> tocList,
            List<ProposalVO.EvalCriteriaVO> evalCriteriaFromDb,
            List<ProposalVO.RequirementVO> requirementsFromDb) {
        logger.info("[PT Stage2-C] TOC 매핑 저장 시작 (ptProjectId={}, tocCount={})",
                ptProjectId, tocList != null ? tocList.size() : 0);
        transactionTemplate.execute(status -> {
            try {
                saveStage2TocMappingInternal(ptProjectId, tocList, evalCriteriaFromDb, requirementsFromDb);
                return null;
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException("[PT Stage2] TOC 매핑 저장 실패: " + e.getMessage(), e);
            }
        });
        logger.info("[PT Stage2-C] TOC 매핑 저장 완료 (ptProjectId={})", ptProjectId);
    }

    /** {@link #saveStage2TocMapping} TransactionTemplate 콜백 본문 */
    private void saveStage2TocMappingInternal(String ptProjectId, List<ProposalVO.TocVO> tocList,
            List<ProposalVO.EvalCriteriaVO> evalCriteriaFromDb,
            List<ProposalVO.RequirementVO> requirementsFromDb) throws Exception {

        List<ProposalVO.TocVO> existingTocList = proposalDAO.selectTocList(ptProjectId);
        logger.info("[PT Stage2] 기존 TOC 레코드 조회: {}건 (ptProjectId={})", existingTocList.size(), ptProjectId);

        java.util.Map<String, ProposalVO.TocVO> dbById = new java.util.LinkedHashMap<>();
        java.util.Map<String, ProposalVO.TocVO> dbBySectionNo = new java.util.LinkedHashMap<>();
        java.util.Map<String, ProposalVO.TocVO> dbBySectionNm = new java.util.LinkedHashMap<>();
        for (ProposalVO.TocVO dbToc : existingTocList) {
            if (CommonUtil.isNotEmpty(dbToc.getTocId()))
                dbById.put(dbToc.getTocId(), dbToc);
            if (CommonUtil.isNotEmpty(dbToc.getSectionNo()))
                dbBySectionNo.put(dbToc.getSectionNo().trim(), dbToc);
            if (CommonUtil.isNotEmpty(dbToc.getSectionNm()))
                dbBySectionNm.putIfAbsent(dbToc.getSectionNm().trim(), dbToc);
        }

        java.util.Map<String, String> evalNmToId = new java.util.HashMap<>();
        if (evalCriteriaFromDb != null) {
            for (ProposalVO.EvalCriteriaVO ec : evalCriteriaFromDb) {
                if (CommonUtil.isNotEmpty(ec.getEvalItemNm())) evalNmToId.put(ec.getEvalItemNm(), ec.getEvalCriteriaId());
            }
        }

        java.util.Set<String> validEvalIds = new java.util.HashSet<>();
        if (evalCriteriaFromDb != null) {
            for (ProposalVO.EvalCriteriaVO ec : evalCriteriaFromDb) {
                if (CommonUtil.isNotEmpty(ec.getEvalCriteriaId())) validEvalIds.add(ec.getEvalCriteriaId());
            }
        }

        java.util.Set<String> validReqIds = new java.util.HashSet<>();
        if (requirementsFromDb != null) {
            for (ProposalVO.RequirementVO req : requirementsFromDb) {
                if (CommonUtil.isNotEmpty(req.getRequirementId())) validReqIds.add(req.getRequirementId());
            }
        }

        int tocMatchedCount = 0, tocUpdatedCount = 0, tocSkippedCount = 0, tocUnmatchedCount = 0;
        int tocInsertedCount = 0;
        if (tocList != null) {
            for (ProposalVO.TocVO llmToc : tocList) {
                // tocId가 없는 항목 = AI 생성 세부목차 (INSERT 대상) → 별도 처리
                if (CommonUtil.isEmpty(llmToc.getTocId())) continue;

                // tocId 직접 조회 (1순위): llmToc은 selectTocList에서 조회된 DB 레코드이므로
                // tocId가 이미 올바른 권위값임. sectionNm 기반 재조회는 002 항목과의 이름 충돌로
                // 엉뚱한 TOC_ID를 반환할 수 있어 사용하지 않음.
                ProposalVO.TocVO dbToc = dbById.get(llmToc.getTocId());
                if (dbToc == null) {
                    // tocId 직접 조회 실패 시 sectionNo/sectionNm 폴백 (비정상 케이스 대비)
                    String llmNo = llmToc.getSectionNo();
                    String llmNm = llmToc.getSectionNm();
                    if (CommonUtil.isNotEmpty(llmNo)) dbToc = dbBySectionNo.get(llmNo.trim());
                    if (dbToc == null && CommonUtil.isNotEmpty(llmNm)) dbToc = dbBySectionNm.get(llmNm.trim());
                    if (dbToc != null) {
                        logger.warn("[PT Stage2] TOC tocId 직접조회 실패, sectionNm 폴백 사용 — tocId={}, 조회결과 tocId={}, sectionNm={}, ptProjectId={}",
                                llmToc.getTocId(), dbToc.getTocId(), llmNm, ptProjectId);
                    }
                }
                if (dbToc == null) {
                    logger.warn("[PT Stage2] TOC 매칭 실패 — LLM 항목 건너뜀 (tocId={}, sectionNo={}, sectionNm={}, ptProjectId={})",
                            llmToc.getTocId(), llmToc.getSectionNo(), llmToc.getSectionNm(), ptProjectId);
                    tocUnmatchedCount++;
                    continue;
                }
                tocMatchedCount++;

                // evalCriteriaId: S2C linkedEvalCriteriaId 우선, 없으면 linkedEvalCriteriaNm lookup
                String evalCriteriaId = null;
                if (CommonUtil.isNotEmpty(llmToc.getLinkedEvalCriteriaId())) {
                    evalCriteriaId = validEvalIds.contains(llmToc.getLinkedEvalCriteriaId())
                            ? llmToc.getLinkedEvalCriteriaId() : null;
                    if (evalCriteriaId == null)
                        logger.warn("[PT Stage2] evalCriteriaId 유효하지 않음: id={}, ptProjectId={}", llmToc.getLinkedEvalCriteriaId(), ptProjectId);
                } else {
                    String linkedNm = llmToc.getLinkedEvalCriteriaNm();
                    if (CommonUtil.isNotEmpty(linkedNm)) {
                        evalCriteriaId = evalNmToId.get(linkedNm);
                        if (evalCriteriaId == null)
                            logger.warn("[PT Stage2] evalCriteria 매칭 실패: linkedEvalCriteriaNm={}, ptProjectId={}", linkedNm, ptProjectId);
                    }
                }

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
                    // 순서 무관 비교·재커밋 안정성을 위해 정렬 후 직렬화
                    java.util.Collections.sort(validatedIds);
                    coveredReqIdsJson = validatedIds.isEmpty() ? null : GSON.toJson(validatedIds);
                }
                int plannedSlideCnt = llmToc.getPlannedSlideCnt() > 0 ? llmToc.getPlannedSlideCnt() : 1;

                // write-before-compare: 값이 동일하면 UPDATE 생략 → MODIFY_DT 오염 방지 (Step6 stale)
                if (java.util.Objects.equals(dbToc.getLinkedEvalCriteriaId(), evalCriteriaId)
                        && sameCoveredReqIdsJson(dbToc.getCoveredReqIdsJson(), coveredReqIdsJson)
                        && dbToc.getPlannedSlideCnt() == plannedSlideCnt) {
                    tocSkippedCount++;
                } else {
                    ProposalVO.TocVO updToc = new ProposalVO.TocVO();
                    updToc.setTocId(dbToc.getTocId());
                    updToc.setLinkedEvalCriteriaId(evalCriteriaId);
                    updToc.setCoveredReqIdsJson(coveredReqIdsJson);
                    updToc.setPlannedSlideCnt(plannedSlideCnt);
                    updToc.setModifyUserId(SessionUtil.getUserId());
                    logger.info("[PT Stage2] 001 UPDATE 실행: TOC_ID='{}', title(기대값)='{}', evalCriteriaId='{}' (ptProjectId={})",
                            updToc.getTocId(), llmToc.getSectionNm(), evalCriteriaId, ptProjectId);
                    proposalDAO.updateTocEvalLinkAndReqIds(updToc);
                    tocUpdatedCount++;
                }

            }
        }
        logger.info("[PT Stage2] TOC 매핑 업데이트 완료: 매칭 {}건, 변경UPDATE {}건, 동일스킵 {}건, 미매칭 {}건 (ptProjectId={})",
                tocMatchedCount, tocUpdatedCount, tocSkippedCount, tocUnmatchedCount, ptProjectId);

        // AI 생성 세부목차 INSERT (ORIGIN_TYPE_CD=002, tocId=null인 항목)
        // 기존 002 레코드를 먼저 삭제 후 재삽입 (멱등성 보장)
        try {
            proposalDAO.deleteTocByOriginTypeCd(ptProjectId, "002");
        } catch (Exception e) {
            // deleteTocByOriginTypeCd가 없을 경우 대비 (첫 실행 시 스킵)
            logger.warn("[PT Stage2] ORIGIN_TYPE_CD=002 삭제 스킵 (미구현 또는 오류): {}", e.getMessage());
        }

        if (tocList != null) {
            for (ProposalVO.TocVO llmToc : tocList) {
                if (CommonUtil.isNotEmpty(llmToc.getTocId())) continue; // 기존 항목은 스킵
                if (!"002".equals(llmToc.getOriginTypeCd())) continue;

                // coveredReqIds 검증
                String coveredReqIdsJson = null;
                if (llmToc.getCoveredReqIds() != null) {
                    java.util.List<String> validatedIds = new java.util.ArrayList<>();
                    for (String reqId : llmToc.getCoveredReqIds()) {
                        if (validReqIds.contains(reqId)) validatedIds.add(reqId);
                        else logger.warn("[PT Stage2] INSERT 세부목차 할루시네이션 제거: reqId={}, ptProjectId={}", reqId, ptProjectId);
                    }
                    java.util.Collections.sort(validatedIds);
                    coveredReqIdsJson = validatedIds.isEmpty() ? null : GSON.toJson(validatedIds);
                }

                // evalCriteriaId 검증
                String evalCriteriaId = llmToc.getLinkedEvalCriteriaId();
                if (CommonUtil.isNotEmpty(evalCriteriaId) && !validEvalIds.contains(evalCriteriaId)) {
                    evalCriteriaId = null;
                }

                int plannedSlideCnt = llmToc.getPlannedSlideCnt() > 0 ? llmToc.getPlannedSlideCnt() : 1;

                ProposalVO.TocVO insToc = new ProposalVO.TocVO();
                insToc.setTocId(keyGenerate.generateTableKey("PTT", "TB_PT_TOC", "TOC_ID", 6));
                insToc.setPtProjectId(ptProjectId);
                insToc.setParentTocId(llmToc.getParentTocId());
                insToc.setSectionNm(llmToc.getSectionNm());
                insToc.setLinkedEvalCriteriaId(evalCriteriaId);
                insToc.setCoveredReqIdsJson(coveredReqIdsJson);
                insToc.setPlannedSlideCnt(plannedSlideCnt);
                insToc.setSortOrd(llmToc.getSortOrd());
                insToc.setOriginTypeCd("002");
                insToc.setCreateUserId("SYSTEM");
                proposalDAO.insertToc(insToc);
                tocInsertedCount++;

            }
        }
        logger.info("[PT Stage2] 세부목차 INSERT 완료: {}건 (ptProjectId={})", tocInsertedCount, ptProjectId);

        // ── SLIDE_REFLECT_POSITION clear-and-rebuild ────────────────────────────
        // 002 INSERT/DELETE 완료 후 DB에서 신선하게 전체 TOC를 재조회해서 재계산한다.
        // in-memory tocList의 stale 001 eval 링크와 섞이지 않도록 DB 기준으로 통째로 재작성.
        // 모든 평가기준에 대해 갱신 — 연결 TOC가 없는 항목은 null로 초기화(이전 값 제거).
        List<ProposalVO.TocVO> freshTocList = proposalDAO.selectTocList(ptProjectId);
        java.util.Map<String, String> evalIdToPosition = new java.util.LinkedHashMap<>();
        for (ProposalVO.TocVO t : freshTocList) {
            if (CommonUtil.isEmpty(t.getLinkedEvalCriteriaId())) continue;
            String pos = CommonUtil.isNotEmpty(t.getSectionNo())
                    ? (t.getSectionNo().trim() + " " + CommonUtil.nullToBlank(t.getSectionNm())).trim()
                    : CommonUtil.nullToBlank(t.getSectionNm()).trim();
            if (CommonUtil.isNotEmpty(pos))
                evalIdToPosition.merge(t.getLinkedEvalCriteriaId(), pos, (a, b) -> a + ", " + b);
        }
        if (evalCriteriaFromDb != null) {
            for (ProposalVO.EvalCriteriaVO ec : evalCriteriaFromDb) {
                if (CommonUtil.isEmpty(ec.getEvalCriteriaId())) continue;
                ProposalVO.EvalCriteriaVO upd = new ProposalVO.EvalCriteriaVO();
                upd.setEvalCriteriaId(ec.getEvalCriteriaId());
                upd.setSlideReflectPosition(evalIdToPosition.get(ec.getEvalCriteriaId())); // null이면 초기화
                proposalDAO.updateEvalCriteriaSlideReflectPosition(upd);
            }
        }
        logger.info("[PT Stage2] SLIDE_REFLECT_POSITION 재계산 완료 — 매칭 평가항목:{}개 (ptProjectId={})",
                evalIdToPosition.size(), ptProjectId);
    }

    /**
     * Stage2-B 직후 Win Theme 저장 (TransactionTemplate 독립 커밋).
     * 커밋 시 STAGE2_STATUS_CD=005(전략완료, 세부목차 미생성) + 프로젝트 statusCd=003.
     */
    public void saveStage2WinThemes(String ptProjectId, List<ProposalVO.WinThemeVO> winThemes) {
        String userId = SessionUtil.getUserId();
        logger.info("[PT Stage2-B] Win Theme 저장 시작 (ptProjectId={}, count={})",
                ptProjectId, winThemes != null ? winThemes.size() : 0);

        transactionTemplate.execute(status -> {
            try {
                proposalDAO.deleteWinThemesByProject(ptProjectId);
                if (winThemes != null) {
                    int ord = 0;
                    for (ProposalVO.WinThemeVO wt : winThemes) {
                        wt.setWinThemeId(keyGenerate.generateTableKey("PTW", "TB_PT_WIN_THEME", "WIN_THEME_ID", 6));
                        wt.setPtProjectId(ptProjectId);
                        wt.setCreateUserId(userId != null ? userId : "hj249");
                        if (wt.getSortOrd() == null) wt.setSortOrd(ord++);
                        proposalDAO.insertWinTheme(wt);
                    }
                }
                ProposalVO.ProjectVO statusVO = new ProposalVO.ProjectVO();
                statusVO.setPtProjectId(ptProjectId);
                statusVO.setStatusCd("003"); // 003=완료(Stage 2까지)
                statusVO.setModifyUserId(userId);
                proposalDAO.updateProjectStatus(statusVO);
                statusVO.setStage2StatusCd(STAGE2_STATUS_STRATEGY_DONE);
                proposalDAO.updateStage2StatusCd(statusVO);
                return null;
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException("[PT Stage2] Win Theme 저장 실패: " + e.getMessage(), e);
            }
        });
        logger.info("[PT Stage2-B] Win Theme 저장 완료 (STAGE2_STATUS_CD=005, ptProjectId={})", ptProjectId);
    }

    /** Stage2 진행 상태만 독립 커밋으로 갱신 (실패 마킹 등) */
    private void updateStage2StatusCd(String ptProjectId, String stage2StatusCd) {
        transactionTemplate.execute(status -> {
            ProposalVO.ProjectVO vo = new ProposalVO.ProjectVO();
            vo.setPtProjectId(ptProjectId);
            vo.setStage2StatusCd(stage2StatusCd);
            vo.setModifyUserId(SessionUtil.getUserId());
            proposalDAO.updateStage2StatusCd(vo);
            return null;
        });
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
        // 트리 메타데이터(level, no) 재계산 — 3단계 계층 대응 (대목차=0, 소목차=1, 세부목차=2)
        setTocTreeMeta(rootToc, 0);

        ProposalVO.Stage2ResultVO result = new ProposalVO.Stage2ResultVO();
        result.setPtProjectId(ptProjectId);
        result.setProblemDefinitions(problemDefs);
        result.setWinThemes(winThemes);
        result.setToc(rootToc);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Stage2 전략검토 — 조회 / CRUD / 재실행 / 단건 refine
    // ══════════════════════════════════════════════════════════════════════════

    public ProposalVO.Stage2SummaryVO selectStage2Summary(String ptProjectId) throws Exception {
        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);

        List<ProposalVO.ProblemDefinitionVO> pds = proposalDAO.selectProblemDefinitions(ptProjectId);
        List<ProposalVO.WinThemeVO> wts = proposalDAO.selectWinThemes(ptProjectId);
        List<ProposalVO.WinThemeResponseVO> wtResp = toWinThemeResponses(wts, pds);
        List<ProposalVO.RequirementVO> reqs = proposalDAO.selectRequirements(ptProjectId);
        List<ProposalVO.TocVO> tocList = proposalDAO.selectTocList(ptProjectId);

        java.util.Set<String> covered = new java.util.HashSet<>();
        if (tocList != null) {
            for (ProposalVO.TocVO t : tocList) {
                covered.addAll(parseIdList(t.getCoveredReqIdsJson()));
            }
        }
        int uncovered = 0;
        if (reqs != null) {
            for (ProposalVO.RequirementVO r : reqs) {
                if (CommonUtil.isNotEmpty(r.getRequirementId()) && !covered.contains(r.getRequirementId())) {
                    uncovered++;
                }
            }
        }

        int staleCnt = 0;
        for (ProposalVO.WinThemeResponseVO w : wtResp) {
            if (w.isStale()) staleCnt++;
        }

        String pdGenDt = null;
        if (pds != null) {
            for (ProposalVO.ProblemDefinitionVO pd : pds) {
                if (CommonUtil.isNotEmpty(pd.getCreateDt())) {
                    if (pdGenDt == null || pd.getCreateDt().compareTo(pdGenDt) > 0) pdGenDt = pd.getCreateDt();
                }
            }
        }
        String wtGenDt = null;
        if (wts != null) {
            for (ProposalVO.WinThemeVO wt : wts) {
                if (CommonUtil.isNotEmpty(wt.getCreateDt())) {
                    if (wtGenDt == null || wt.getCreateDt().compareTo(wtGenDt) > 0) wtGenDt = wt.getCreateDt();
                }
            }
        }

        ProposalVO.Stage2SummaryVO summary = new ProposalVO.Stage2SummaryVO();
        summary.setStage2StatusCd(CommonUtil.isNotEmpty(project.getStage2StatusCd())
                ? project.getStage2StatusCd() : STAGE2_STATUS_NOT_STARTED);
        summary.setProblemDefinitionCount(pds != null ? pds.size() : 0);
        summary.setWinThemeCount(wts != null ? wts.size() : 0);
        summary.setWinThemeStaleCount(staleCnt);
        summary.setUncoveredRequirementCount(uncovered);
        summary.setProblemDefinitionsGeneratedDt(pdGenDt);
        summary.setWinThemesGeneratedDt(wtGenDt);
        return summary;
    }

    public List<ProposalVO.ProblemDefinitionResponseVO> selectStage2ProblemDefinitions(String ptProjectId) {
        return toProblemDefinitionResponses(proposalDAO.selectProblemDefinitions(ptProjectId));
    }

    public List<ProposalVO.WinThemeResponseVO> selectStage2WinThemes(String ptProjectId) {
        List<ProposalVO.ProblemDefinitionVO> pds = proposalDAO.selectProblemDefinitions(ptProjectId);
        return toWinThemeResponses(proposalDAO.selectWinThemes(ptProjectId), pds);
    }

    public ProposalVO.TocMappingResponseVO selectStage2TocMapping(String ptProjectId) {
        List<ProposalVO.TocVO> tocList = proposalDAO.selectTocList(ptProjectId);
        List<ProposalVO.RequirementVO> reqs = proposalDAO.selectRequirements(ptProjectId);
        List<ProposalVO.EvalCriteriaVO> ecs = proposalDAO.selectEvalCriteria(ptProjectId);

        List<ProposalVO.TocMappingNodeVO> nodes = new java.util.ArrayList<>();
        java.util.Set<String> covered = new java.util.HashSet<>();
        if (tocList != null) {
            for (ProposalVO.TocVO t : tocList) {
                ProposalVO.TocMappingNodeVO n = new ProposalVO.TocMappingNodeVO();
                n.setTocId(t.getTocId());
                n.setTitle(t.getSectionNm());
                n.setParentTocId(t.getParentTocId());
                n.setSortOrd(t.getSortOrd() != null ? t.getSortOrd() : 0);
                List<String> ids = parseIdList(t.getCoveredReqIdsJson());
                n.setCoveredReqIds(ids);
                covered.addAll(ids);
                n.setLinkedEvalCriteriaId(t.getLinkedEvalCriteriaId());
                nodes.add(n);
            }
        }

        List<String> unassigned = new java.util.ArrayList<>();
        if (reqs != null) {
            for (ProposalVO.RequirementVO r : reqs) {
                if (CommonUtil.isNotEmpty(r.getRequirementId()) && !covered.contains(r.getRequirementId())) {
                    unassigned.add(r.getRequirementId());
                }
            }
        }

        List<ProposalVO.EvalCriteriaOptionVO> options = new java.util.ArrayList<>();
        if (ecs != null) {
            for (ProposalVO.EvalCriteriaVO ec : ecs) {
                ProposalVO.EvalCriteriaOptionVO o = new ProposalVO.EvalCriteriaOptionVO();
                o.setEvalCriteriaId(ec.getEvalCriteriaId());
                o.setEvalItemNm(ec.getEvalItemNm());
                o.setScore(ec.getScore());
                options.add(o);
            }
        }

        ProposalVO.TocMappingResponseVO resp = new ProposalVO.TocMappingResponseVO();
        resp.setTocNodes(nodes);
        resp.setUnassignedRequirementIds(unassigned);
        resp.setEvalCriteriaOptions(options);
        return resp;
    }

    public List<ProposalVO.ProblemDefinitionResponseVO> regenerateStage2ProblemDefinitions(
            ProposalVO.Stage2RegenerateVO vo) throws Exception {
        int budget = vo.getTotalSlideBudget() > 0 ? vo.getTotalSlideBudget() : 40;
        runS2a(vo.getPtProjectId(), budget, vo.getModelId(), vo.getAgentId(), null, vo.getUserFeedback());
        return selectStage2ProblemDefinitions(vo.getPtProjectId());
    }

    public List<ProposalVO.WinThemeResponseVO> regenerateStage2WinThemes(
            ProposalVO.Stage2RegenerateVO vo) throws Exception {
        ProposalVO.ProjectVO project = proposalDAO.selectProject(vo.getPtProjectId());
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다.");
        String status = CommonUtil.isNotEmpty(project.getStage2StatusCd())
                ? project.getStage2StatusCd() : STAGE2_STATUS_NOT_STARTED;
        if (!STAGE2_STATUS_PROBLEM_SAVED.equals(status) && !STAGE2_STATUS_STRATEGY_DONE.equals(status) && !STAGE2_STATUS_DONE.equals(status)) {
            throw new IllegalStateException("PROBLEM_DEFINITION_REQUIRED");
        }
        runS2b(vo.getPtProjectId(), vo.getUserFeedback(), vo.getModelId(), vo.getAgentId());
        return selectStage2WinThemes(vo.getPtProjectId());
    }

    public ProposalVO.TocMappingResponseVO regenerateStage2Mapping(
            ProposalVO.Stage2RegenerateVO vo) throws Exception {
        int budget = vo.getTotalSlideBudget() > 0 ? vo.getTotalSlideBudget() : 40;
        runS2c(vo.getPtProjectId(), budget, vo.getModelId(), vo.getAgentId());
        return selectStage2TocMapping(vo.getPtProjectId());
    }

    /** STAGE2_STATUS_CD를 001로 리셋 후 전체 Stage2 재실행용 */
    public void resetStage2Status(String ptProjectId) {
        updateStage2StatusCd(ptProjectId, STAGE2_STATUS_NOT_STARTED);
    }

    public ProposalVO.ProblemDefinitionResponseVO updateStage2ProblemDefinition(
            String ptProjectId, String problemId, ProposalVO.ProblemDefinitionUpdateVO vo) {
        ProposalVO.ProblemDefinitionVO existing = proposalDAO.selectProblemDefinitionById(problemId);
        if (existing == null || !ptProjectId.equals(existing.getPtProjectId())) {
            throw new RuntimeException("문제정의를 찾을 수 없습니다. problemId=" + problemId);
        }
        ProposalVO.ProblemDefinitionVO upd = new ProposalVO.ProblemDefinitionVO();
        upd.setProblemId(problemId);
        upd.setProblemTypeCd(vo.getProblemTypeCd());
        upd.setCurrentProblem(vo.getCurrentProblem());
        upd.setRootCause(vo.getRootCause());
        upd.setRiskIfIgnored(vo.getRiskIfIgnored());
        upd.setGoal(vo.getGoal());
        upd.setRequiredCapability(vo.getRequiredCapability());
        upd.setStrategySummary(vo.getStrategySummary());
        upd.setKpi(vo.getKpi());
        upd.setModifyUserId(SessionUtil.getUserId());
        proposalDAO.updateProblemDefinition(upd);
        return toProblemDefinitionResponse(proposalDAO.selectProblemDefinitionById(problemId));
    }

    public ProposalVO.ProblemDefinitionResponseVO insertStage2ProblemDefinition(
            String ptProjectId, ProposalVO.ProblemDefinitionUpdateVO vo) throws Exception {
        String userId = SessionUtil.getUserId();
        List<ProposalVO.ProblemDefinitionVO> existing = proposalDAO.selectProblemDefinitions(ptProjectId);
        int sortOrd = existing != null ? existing.size() : 0;
        ProposalVO.ProblemDefinitionVO pd = new ProposalVO.ProblemDefinitionVO();
        pd.setProblemId(keyGenerate.generateTableKey("PTP", "TB_PT_PROBLEM_DEFINITION", "PROBLEM_ID", 6));
        pd.setPtProjectId(ptProjectId);
        pd.setProblemTypeCd(CommonUtil.isNotEmpty(vo.getProblemTypeCd()) ? vo.getProblemTypeCd() : "001");
        pd.setCurrentProblem(vo.getCurrentProblem());
        pd.setRootCause(vo.getRootCause());
        pd.setRiskIfIgnored(vo.getRiskIfIgnored());
        pd.setGoal(vo.getGoal());
        pd.setRequiredCapability(vo.getRequiredCapability());
        pd.setStrategySummary(vo.getStrategySummary());
        pd.setKpi(vo.getKpi());
        pd.setSourceTypeCd("999");
        pd.setSourceIssueIdsJson(null);
        pd.setSourceRequirementIdsJson(null);
        pd.setSortOrd(sortOrd);
        pd.setCreateUserId(userId != null ? userId : "system");
        proposalDAO.insertProblemDefinition(pd);
        return toProblemDefinitionResponse(proposalDAO.selectProblemDefinitionById(pd.getProblemId()));
    }

    public void deleteStage2ProblemDefinition(String ptProjectId, String problemId) {
        ProposalVO.ProblemDefinitionVO existing = proposalDAO.selectProblemDefinitionById(problemId);
        if (existing == null || !ptProjectId.equals(existing.getPtProjectId())) {
            throw new RuntimeException("문제정의를 찾을 수 없습니다. problemId=" + problemId);
        }
        proposalDAO.deleteProblemDefinition(problemId);
    }

    public ProposalVO.WinThemeResponseVO updateStage2WinTheme(
            String ptProjectId, String winThemeId, ProposalVO.WinThemeUpdateVO vo) {
        ProposalVO.WinThemeVO existing = proposalDAO.selectWinThemeById(winThemeId);
        if (existing == null || !ptProjectId.equals(existing.getPtProjectId())) {
            throw new RuntimeException("Win Theme를 찾을 수 없습니다. winThemeId=" + winThemeId);
        }
        ProposalVO.WinThemeVO upd = new ProposalVO.WinThemeVO();
        upd.setWinThemeId(winThemeId);
        upd.setCoreMessage(vo.getCoreMessage());
        upd.setCustomerProblem(vo.getCustomerProblem());
        upd.setProposalStrategy(vo.getProposalStrategy());
        upd.setEvidence(vo.getEvidence());
        upd.setExpectedEffect(vo.getExpectedEffect());
        upd.setDifferentiation(vo.getDifferentiation());
        if (vo.getSourceProblemDefinitionIds() != null) {
            if (vo.getSourceProblemDefinitionIds().isEmpty()) {
                throw new IllegalArgumentException("sourceProblemDefinitionIds는 비어 있을 수 없습니다.");
            }
            upd.setSourceProblemIdsJson(GSON.toJson(vo.getSourceProblemDefinitionIds()));
        }
        upd.setModifyUserId(SessionUtil.getUserId());
        proposalDAO.updateWinTheme(upd);
        List<ProposalVO.ProblemDefinitionVO> pds = proposalDAO.selectProblemDefinitions(ptProjectId);
        return toWinThemeResponse(proposalDAO.selectWinThemeById(winThemeId), pds);
    }

    public ProposalVO.WinThemeResponseVO insertStage2WinTheme(
            String ptProjectId, ProposalVO.WinThemeUpdateVO vo) throws Exception {
        if (vo.getSourceProblemDefinitionIds() == null || vo.getSourceProblemDefinitionIds().isEmpty()) {
            throw new IllegalArgumentException("sourceProblemDefinitionIds는 필수입니다.");
        }
        String userId = SessionUtil.getUserId();
        List<ProposalVO.WinThemeVO> existing = proposalDAO.selectWinThemes(ptProjectId);
        int sortOrd = existing != null ? existing.size() : 0;
        ProposalVO.WinThemeVO wt = new ProposalVO.WinThemeVO();
        wt.setWinThemeId(keyGenerate.generateTableKey("PTW", "TB_PT_WIN_THEME", "WIN_THEME_ID", 6));
        wt.setPtProjectId(ptProjectId);
        wt.setCoreMessage(vo.getCoreMessage());
        wt.setCustomerProblem(vo.getCustomerProblem());
        wt.setProposalStrategy(vo.getProposalStrategy());
        wt.setEvidence(vo.getEvidence());
        wt.setExpectedEffect(vo.getExpectedEffect());
        wt.setDifferentiation(vo.getDifferentiation());
        wt.setSourceProblemIdsJson(GSON.toJson(vo.getSourceProblemDefinitionIds()));
        wt.setSortOrd(sortOrd);
        wt.setCreateUserId(userId != null ? userId : "system");
        proposalDAO.insertWinTheme(wt);
        List<ProposalVO.ProblemDefinitionVO> pds = proposalDAO.selectProblemDefinitions(ptProjectId);
        return toWinThemeResponse(proposalDAO.selectWinThemeById(wt.getWinThemeId()), pds);
    }

    public void deleteStage2WinTheme(String ptProjectId, String winThemeId) {
        ProposalVO.WinThemeVO existing = proposalDAO.selectWinThemeById(winThemeId);
        if (existing == null || !ptProjectId.equals(existing.getPtProjectId())) {
            throw new RuntimeException("Win Theme를 찾을 수 없습니다. winThemeId=" + winThemeId);
        }
        proposalDAO.deleteWinTheme(winThemeId);
    }

    public ProposalVO.TocMappingNodeVO updateStage2TocMapping(
            String ptProjectId, String tocId, ProposalVO.TocMappingUpdateVO vo) {
        ProposalVO.TocVO dbToc = proposalDAO.selectTocById(tocId);
        if (dbToc == null || !ptProjectId.equals(dbToc.getPtProjectId())) {
            throw new RuntimeException("목차를 찾을 수 없습니다. tocId=" + tocId);
        }
        List<String> covered = vo.getCoveredReqIds() != null ? new java.util.ArrayList<>(vo.getCoveredReqIds()) : new java.util.ArrayList<>();
        java.util.Collections.sort(covered);
        String coveredJson = covered.isEmpty() ? null : GSON.toJson(covered);
        String evalId = vo.getLinkedEvalCriteriaId();

        if (java.util.Objects.equals(dbToc.getLinkedEvalCriteriaId(), evalId)
                && sameCoveredReqIdsJson(dbToc.getCoveredReqIdsJson(), coveredJson)) {
            // no-op
        } else {
            ProposalVO.TocVO upd = new ProposalVO.TocVO();
            upd.setTocId(tocId);
            upd.setLinkedEvalCriteriaId(evalId);
            upd.setCoveredReqIdsJson(coveredJson);
            upd.setModifyUserId(SessionUtil.getUserId());
            proposalDAO.updateTocMappingUser(upd);
            dbToc = proposalDAO.selectTocById(tocId);
        }

        ProposalVO.TocMappingNodeVO node = new ProposalVO.TocMappingNodeVO();
        node.setTocId(dbToc.getTocId());
        node.setTitle(dbToc.getSectionNm());
        node.setParentTocId(dbToc.getParentTocId());
        node.setSortOrd(dbToc.getSortOrd() != null ? dbToc.getSortOrd() : 0);
        node.setCoveredReqIds(parseIdList(dbToc.getCoveredReqIdsJson()));
        node.setLinkedEvalCriteriaId(dbToc.getLinkedEvalCriteriaId());
        return node;
    }

    /**
     * 문제정의 단건 보완 — 해당 PD의 sourceIssueIds/sourceRequirementIds만 LLM에 전달.
     * ID 유지 + MODIFY_DT 갱신 (전체 runS2a와 다름).
     */
    public ProposalVO.ProblemDefinitionResponseVO refineStage2ProblemDefinition(
            ProposalVO.ProblemDefinitionRefineVO vo) throws Exception {
        String ptProjectId = vo.getPtProjectId();
        String problemId = vo.getProblemId();
        ProposalVO.ProblemDefinitionVO existing = proposalDAO.selectProblemDefinitionById(problemId);
        if (existing == null || !ptProjectId.equals(existing.getPtProjectId())) {
            throw new RuntimeException("문제정의를 찾을 수 없습니다. problemId=" + problemId);
        }
        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다.");

        List<String> sourceIssueIds = parseIdList(existing.getSourceIssueIdsJson());
        List<String> sourceReqIds = parseIdList(existing.getSourceRequirementIdsJson());

        List<ProposalVO.RfpIssueVO> allIssues = proposalDAO.selectRfpIssues(ptProjectId);
        List<ProposalVO.RequirementVO> allReqs = proposalDAO.selectRequirements(ptProjectId);
        java.util.Set<String> issueSet = new java.util.HashSet<>(sourceIssueIds);
        java.util.Set<String> reqSet = new java.util.HashSet<>(sourceReqIds);

        List<ProposalVO.RfpIssueVO> filteredIssues = new java.util.ArrayList<>();
        if (allIssues != null) {
            for (ProposalVO.RfpIssueVO i : allIssues) {
                if (issueSet.contains(i.getIssueId())) filteredIssues.add(i);
            }
        }
        List<ProposalVO.RequirementVO> filteredReqs = new java.util.ArrayList<>();
        if (allReqs != null) {
            for (ProposalVO.RequirementVO r : allReqs) {
                if (reqSet.contains(r.getRequirementId())) filteredReqs.add(r);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("당신은 제안서 전략 분석가입니다. 아래 문제정의 1건을 사용자 보완 요청에 맞게 수정하세요.\n");
        sb.append("다른 문제정의는 만들지 말고, 반드시 JSON 객체 1개만 반환하세요.\n");
        sb.append("필드: currentProblem, rootCause, riskIfIgnored, goal, requiredCapability, strategySummary, kpi, problemTypeCd\n");
        sb.append("\n## 사업 기본 정보\n- 사업명: ").append(CommonUtil.nullToBlank(project.getProjectNm()));
        sb.append("\n\n## 수정 대상 문제정의 (JSON)\n");
        java.util.Map<String, Object> pdMap = new java.util.LinkedHashMap<>();
        pdMap.put("problemId", existing.getProblemId());
        pdMap.put("problemTypeCd", existing.getProblemTypeCd());
        pdMap.put("currentProblem", existing.getCurrentProblem());
        pdMap.put("rootCause", existing.getRootCause());
        pdMap.put("riskIfIgnored", existing.getRiskIfIgnored());
        pdMap.put("goal", existing.getGoal());
        pdMap.put("requiredCapability", existing.getRequiredCapability());
        pdMap.put("strategySummary", existing.getStrategySummary());
        pdMap.put("kpi", existing.getKpi());
        sb.append(GSON.toJson(pdMap));

        if (!filteredIssues.isEmpty()) {
            sb.append("\n\n## 관련 RFP 이슈 (이 문제정의의 근거만)\n");
            for (ProposalVO.RfpIssueVO issue : filteredIssues) {
                sb.append(String.format("- [%s][%s] %s%n",
                        issue.getIssueId(), issueTypeLabel(issue.getIssueTypeCd()), issue.getIssueContent()));
            }
        }
        if (!filteredReqs.isEmpty()) {
            sb.append("\n\n## 관련 요구사항 (이 문제정의의 근거만, JSON)\n");
            sb.append(GSON.toJson(filteredReqs.stream().map(this::toRequirementLite).collect(java.util.stream.Collectors.toList())));
        }
        if (CommonUtil.isNotEmpty(vo.getUserFeedback())) {
            sb.append("\n\n## 사용자 보완 요청\n").append(vo.getUserFeedback());
        }

        logger.info("[PT Stage2 refine] 시작 problemId={}, issues={}, reqs={}, promptLen={}",
                problemId, filteredIssues.size(), filteredReqs.size(), sb.length());
        String aiResp = riskDiagnosisAgentService.callLlmQuerySync(sb.toString(), vo.getModelId(), "", vo.getAgentId());
        if (CommonUtil.isEmpty(aiResp)) {
            throw new RuntimeException("LLM 응답이 비어 있습니다. 문제정의 보완을 완료할 수 없습니다.");
        }

        JsonObject obj = extractFirstJsonObject(aiResp);
        ProposalVO.ProblemDefinitionUpdateVO upd = new ProposalVO.ProblemDefinitionUpdateVO();
        if (obj.has("problemTypeCd") && !obj.get("problemTypeCd").isJsonNull())
            upd.setProblemTypeCd(obj.get("problemTypeCd").getAsString());
        if (obj.has("currentProblem") && !obj.get("currentProblem").isJsonNull())
            upd.setCurrentProblem(obj.get("currentProblem").getAsString());
        if (obj.has("rootCause") && !obj.get("rootCause").isJsonNull())
            upd.setRootCause(obj.get("rootCause").getAsString());
        if (obj.has("riskIfIgnored") && !obj.get("riskIfIgnored").isJsonNull())
            upd.setRiskIfIgnored(obj.get("riskIfIgnored").getAsString());
        if (obj.has("goal") && !obj.get("goal").isJsonNull())
            upd.setGoal(obj.get("goal").getAsString());
        if (obj.has("requiredCapability") && !obj.get("requiredCapability").isJsonNull())
            upd.setRequiredCapability(obj.get("requiredCapability").getAsString());
        if (obj.has("strategySummary") && !obj.get("strategySummary").isJsonNull())
            upd.setStrategySummary(obj.get("strategySummary").getAsString());
        if (obj.has("kpi") && !obj.get("kpi").isJsonNull())
            upd.setKpi(obj.get("kpi").getAsString());

        return updateStage2ProblemDefinition(ptProjectId, problemId, upd);
    }

    // ── Stage1 단건 CRUD ──────────────────────────────────────────────────

    public ProposalVO.RequirementVO insertRequirementManual(ProposalVO.RequirementVO vo) throws Exception {
        String userId = SessionUtil.getUserId();
        List<ProposalVO.RequirementVO> existing = proposalDAO.selectRequirements(vo.getPtProjectId());
        vo.setRequirementId(keyGenerate.generateTableKey("PTQ", "TB_PT_REQUIREMENT", "REQUIREMENT_ID", 6));
        vo.setSourceTypeCd("999");
        if (vo.getMandatoryYn() == null) vo.setMandatoryYn("Y");
        if (vo.getSortOrd() == null) vo.setSortOrd(existing != null ? existing.size() : 0);
        vo.setCreateUserId(userId != null ? userId : "system");
        proposalDAO.insertRequirement(vo);
        return vo;
    }

    public void deleteRequirement(String requirementId) {
        proposalDAO.deleteRequirement(requirementId);
    }

    public ProposalVO.EvalCriteriaVO insertEvalCriteriaManual(ProposalVO.EvalCriteriaVO vo) throws Exception {
        String userId = SessionUtil.getUserId();
        List<ProposalVO.EvalCriteriaVO> existing = proposalDAO.selectEvalCriteria(vo.getPtProjectId());
        vo.setEvalCriteriaId(keyGenerate.generateTableKey("PTE", "TB_PT_EVAL_CRITERIA", "EVAL_CRITERIA_ID", 6));
        if (vo.getSortOrd() == null) vo.setSortOrd(existing != null ? existing.size() : 0);
        vo.setCreateUserId(userId != null ? userId : "system");
        proposalDAO.insertEvalCriteria(vo);
        return vo;
    }

    public void deleteEvalCriteria(String evalCriteriaId) {
        proposalDAO.deleteEvalCriteria(evalCriteriaId);
    }

    public ProposalVO.RfpIssueVO insertRfpIssueManual(ProposalVO.RfpIssueVO vo) throws Exception {
        String userId = SessionUtil.getUserId();
        List<ProposalVO.RfpIssueVO> existing = proposalDAO.selectRfpIssues(vo.getPtProjectId());
        vo.setIssueId(keyGenerate.generateTableKey("PTI", "tb_pt_rfp_issue", "ISSUE_ID", 6));
        if (CommonUtil.isEmpty(vo.getIssueTypeCd())) vo.setIssueTypeCd("003");
        vo.setSortOrd(existing != null ? existing.size() : 0);
        vo.setCreateUserId(userId != null ? userId : "system");
        proposalDAO.insertRfpIssue(vo);
        return vo;
    }

    public void updateRfpIssue(ProposalVO.RfpIssueVO vo) {
        proposalDAO.updateRfpIssue(vo);
    }

    public void deleteRfpIssue(String issueId) {
        proposalDAO.deleteRfpIssue(issueId);
    }

    // ── Stage2 전략검토 헬퍼 ──────────────────────────────────────────────

    private List<String> parseIdList(String jsonArrayStr) {
        if (CommonUtil.isEmpty(jsonArrayStr)) return new java.util.ArrayList<>();
        try {
            List<String> ids = new java.util.ArrayList<>();
            for (JsonElement el : JsonParser.parseString(jsonArrayStr).getAsJsonArray()) {
                if (!el.isJsonNull() && CommonUtil.isNotEmpty(el.getAsString())) ids.add(el.getAsString());
            }
            return ids;
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    private List<ProposalVO.ProblemDefinitionResponseVO> toProblemDefinitionResponses(
            List<ProposalVO.ProblemDefinitionVO> list) {
        List<ProposalVO.ProblemDefinitionResponseVO> result = new java.util.ArrayList<>();
        if (list == null) return result;
        for (ProposalVO.ProblemDefinitionVO pd : list) result.add(toProblemDefinitionResponse(pd));
        return result;
    }

    private ProposalVO.ProblemDefinitionResponseVO toProblemDefinitionResponse(ProposalVO.ProblemDefinitionVO pd) {
        ProposalVO.ProblemDefinitionResponseVO r = new ProposalVO.ProblemDefinitionResponseVO();
        if (pd == null) return r;
        r.setProblemId(pd.getProblemId());
        r.setPtProjectId(pd.getPtProjectId());
        r.setProblemTypeCd(pd.getProblemTypeCd());
        r.setCurrentProblem(pd.getCurrentProblem());
        r.setRootCause(pd.getRootCause());
        r.setRiskIfIgnored(pd.getRiskIfIgnored());
        r.setGoal(pd.getGoal());
        r.setRequiredCapability(pd.getRequiredCapability());
        r.setStrategySummary(pd.getStrategySummary());
        r.setKpi(pd.getKpi());
        r.setSourceTypeCd(pd.getSourceTypeCd());
        r.setSourceIssueIds(parseIdList(pd.getSourceIssueIdsJson()));
        r.setSourceRequirementIds(parseIdList(pd.getSourceRequirementIdsJson()));
        r.setGeneratedDt(pd.getCreateDt());
        r.setModifyDt(pd.getModifyDt());
        r.setManualYn("999".equals(pd.getSourceTypeCd()) ? "Y" : "N");
        return r;
    }

    private List<ProposalVO.WinThemeResponseVO> toWinThemeResponses(
            List<ProposalVO.WinThemeVO> wts, List<ProposalVO.ProblemDefinitionVO> pds) {
        List<ProposalVO.WinThemeResponseVO> result = new java.util.ArrayList<>();
        if (wts == null) return result;
        for (ProposalVO.WinThemeVO wt : wts) result.add(toWinThemeResponse(wt, pds));
        return result;
    }

    private ProposalVO.WinThemeResponseVO toWinThemeResponse(
            ProposalVO.WinThemeVO wt, List<ProposalVO.ProblemDefinitionVO> pds) {
        ProposalVO.WinThemeResponseVO r = new ProposalVO.WinThemeResponseVO();
        if (wt == null) return r;
        r.setWinThemeId(wt.getWinThemeId());
        r.setPtProjectId(wt.getPtProjectId());
        r.setCoreMessage(wt.getCoreMessage());
        r.setCustomerProblem(wt.getCustomerProblem());
        r.setProposalStrategy(wt.getProposalStrategy());
        r.setEvidence(wt.getEvidence());
        r.setExpectedEffect(wt.getExpectedEffect());
        r.setDifferentiation(wt.getDifferentiation());
        List<String> sourceIds = parseIdList(wt.getSourceProblemIdsJson());
        r.setSourceProblemDefinitionIds(sourceIds);
        r.setGeneratedDt(wt.getCreateDt());
        r.setModifyDt(wt.getModifyDt());

        java.util.Map<String, ProposalVO.ProblemDefinitionVO> pdMap = new java.util.HashMap<>();
        if (pds != null) {
            for (ProposalVO.ProblemDefinitionVO pd : pds) pdMap.put(pd.getProblemId(), pd);
        }
        List<ProposalVO.WinThemeStaleDetailVO> details = new java.util.ArrayList<>();
        String wtBaseline = CommonUtil.isNotEmpty(wt.getModifyDt()) ? wt.getModifyDt() : wt.getCreateDt();
        List<ProposalVO.ProblemDefinitionVO> referenced = new java.util.ArrayList<>();
        for (String pid : sourceIds) {
            ProposalVO.ProblemDefinitionVO pd = pdMap.get(pid);
            if (pd == null) {
                ProposalVO.WinThemeStaleDetailVO d = new ProposalVO.WinThemeStaleDetailVO();
                d.setProblemId(pid);
                d.setReason("DELETED");
                details.add(d);
            } else {
                referenced.add(pd);
                if (CommonUtil.isNotEmpty(pd.getModifyDt())
                        && (CommonUtil.isEmpty(wtBaseline) || pd.getModifyDt().compareTo(wtBaseline) > 0)) {
                    ProposalVO.WinThemeStaleDetailVO d = new ProposalVO.WinThemeStaleDetailVO();
                    d.setProblemId(pid);
                    d.setReason("MODIFIED");
                    d.setProblemModifyDt(pd.getModifyDt());
                    details.add(d);
                }
            }
        }
        if (referenced.isEmpty()) {
            r.setStale(true);
            if (details.isEmpty()) {
                ProposalVO.WinThemeStaleDetailVO d = new ProposalVO.WinThemeStaleDetailVO();
                d.setReason("DELETED");
                details.add(d);
            }
        } else {
            r.setStale(!details.isEmpty());
        }
        r.setStaleDetails(details);
        return r;
    }

    private JsonObject extractFirstJsonObject(String aiResp) {
        String json = aiResp.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            if (firstNewline != -1) json = json.substring(firstNewline + 1);
            if (json.endsWith("```")) json = json.substring(0, json.lastIndexOf("```"));
            json = json.trim();
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) throw new RuntimeException("문제정의 보완 응답 JSON을 파싱할 수 없습니다.");
        return JsonParser.parseString(json.substring(start, end + 1)).getAsJsonObject();
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
                // sourceTypeCd — LLM 판단값 그대로 읽기 (001=rfpIssues 근거, 002=요구사항 추론). 미응답 시 002 fallback.
                String sourceTypeCd = getStrOrNull(obj, "sourceTypeCd");
                pd.setSourceTypeCd(CommonUtil.isNotEmpty(sourceTypeCd) ? sourceTypeCd : "002");
                // sourceIssueIds / sourceRequirementIds — JSON 배열 그대로 문자열 직렬화 (유효성 검증은 호출부에서 수행)
                if (obj.has("sourceIssueIds") && obj.get("sourceIssueIds").isJsonArray()
                        && obj.getAsJsonArray("sourceIssueIds").size() > 0) {
                    pd.setSourceIssueIdsJson(obj.getAsJsonArray("sourceIssueIds").toString());
                }
                if (obj.has("sourceRequirementIds") && obj.get("sourceRequirementIds").isJsonArray()
                        && obj.getAsJsonArray("sourceRequirementIds").size() > 0) {
                    pd.setSourceRequirementIdsJson(obj.getAsJsonArray("sourceRequirementIds").toString());
                }
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
                // linkedEvalCriteriaId (ID 우선) / linkedEvalCriteriaNm (이름 fallback)
                toc.setLinkedEvalCriteriaId(getStrOrNull(obj, "linkedEvalCriteriaId"));
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
     * JSON 배열 문자열(예: ["PTI000001","PTI000003"])을 파싱해 validSet과의 교집합만 남기고
     * 다시 JSON 배열 문자열로 직렬화. LLM이 존재하지 않거나(프롬프트에 보여주지 않은) ID를
     * 지어냈을 경우 걸러내기 위한 방어 로직. 교집합이 비면 null 반환 (DB에 빈 배열 대신 null 저장).
     */
    private String filterValidIds(String jsonArrayStr, java.util.Set<String> validSet) {
        if (CommonUtil.isEmpty(jsonArrayStr) || validSet == null || validSet.isEmpty()) return null;
        try {
            JsonArray arr = JsonParser.parseString(jsonArrayStr).getAsJsonArray();
            List<String> filtered = new java.util.ArrayList<>();
            for (JsonElement el : arr) {
                if (el.isJsonNull()) continue;
                String id = el.getAsString();
                if (validSet.contains(id)) {
                    filtered.add(id);
                } else {
                    logger.warn("[PT Stage2] 할루시네이션 — 존재하지 않거나 미노출된 ID 제거: {}", id);
                }
            }
            return filtered.isEmpty() ? null : GSON.toJson(filtered);
        } catch (Exception e) {
            logger.warn("[PT Stage2] source*Ids JSON 파싱 실패, null 처리: {}", e.getMessage());
            return null;
        }
    }

    /**
     * COVERED_REQ_IDS_JSON 동일성 — null/빈배열 동치, 원소 순서 무시.
     * 002 재개 시 LLM이 같은 ID를 다른 순서로 내도 MODIFY_DT를 건드리지 않기 위함.
     */
    private boolean sameCoveredReqIdsJson(String a, String b) {
        return java.util.Objects.equals(canonicalCoveredReqIdSet(a), canonicalCoveredReqIdSet(b));
    }

    private java.util.Set<String> canonicalCoveredReqIdSet(String json) {
        if (CommonUtil.isEmpty(json)) return java.util.Collections.emptySet();
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            java.util.Set<String> set = new java.util.TreeSet<>();
            for (JsonElement el : arr) {
                if (el.isJsonNull()) continue;
                String id = el.getAsString();
                if (CommonUtil.isNotEmpty(id)) set.add(id);
            }
            return set;
        } catch (Exception e) {
            // 파싱 실패 시 원문 기준으로라도 비교되도록 싱글톤 세트에 담음
            return java.util.Collections.singleton(json);
        }
    }

    /**
     * Call 2(S2B) 응답 JSON 파싱 → winThemes 목록
     * - winThemes: coreMessage 필수
     * - sourceProblemDefinitionIds → sourceProblemIdsJson (유효성 검증은 호출부에서 수행)
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
                // sourceProblemDefinitionIds — JSON 배열 그대로 문자열 직렬화 (유효성 검증은 호출부에서 수행)
                if (obj.has("sourceProblemDefinitionIds") && obj.get("sourceProblemDefinitionIds").isJsonArray()
                        && obj.getAsJsonArray("sourceProblemDefinitionIds").size() > 0) {
                    wt.setSourceProblemIdsJson(obj.getAsJsonArray("sourceProblemDefinitionIds").toString());
                }
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

        // evalItemNm → score 맵 (S2A 일반경로 호환용)
        java.util.Map<String, Double> evalNmToScore = new java.util.HashMap<>();
        // evalCriteriaId → score 맵 (S2C mandatedToc 경로)
        java.util.Map<String, Double> evalIdToScore = new java.util.HashMap<>();
        if (evalCriteria != null) {
            for (ProposalVO.EvalCriteriaVO ec : evalCriteria) {
                if (CommonUtil.isNotEmpty(ec.getEvalItemNm())) evalNmToScore.put(ec.getEvalItemNm(), ec.getScore());
                if (CommonUtil.isNotEmpty(ec.getEvalCriteriaId())) evalIdToScore.put(ec.getEvalCriteriaId(), ec.getScore());
            }
        }

        // 리프 탐색: parentTocIds에 자신의 tocId가 없는 노드 = 리프
        // (level 필드는 DB 로드 시 transient라 항상 0 → tocId/parentTocId 기반으로 판별)
        java.util.Set<String> parentTocIds = new java.util.HashSet<>();
        for (ProposalVO.TocVO toc : tocList) {
            if (CommonUtil.isNotEmpty(toc.getParentTocId())) parentTocIds.add(toc.getParentTocId());
        }
        List<ProposalVO.TocVO> leaves = new java.util.ArrayList<>();
        for (ProposalVO.TocVO toc : tocList) {
            if (!parentTocIds.contains(toc.getTocId())) leaves.add(toc);
        }
        // tocId가 없는 신규 subToc(INSERT 예정)도 리프로 포함
        for (ProposalVO.TocVO toc : tocList) {
            if (CommonUtil.isEmpty(toc.getTocId()) && !leaves.contains(toc)) leaves.add(toc);
        }

        if (leaves.isEmpty()) return;

        // S2C 세부목차(ORIGIN_TYPE_CD=002)가 있는 RFP 소분류(parentTocId를 가진 ORIGIN_TYPE_CD=002의 parent):
        // 해당 소분류의 예산은 자식 세부목차들에 분배 → 소분류 자체는 자식 합계를 받음 (후처리에서)
        // 우선 모든 리프에 대해 평가기준 배점 기반 예산 배분 진행

        // evalCriteriaId → 해당 소분류(parent) 예산 풀 구성:
        // 세부목차가 있는 소분류는 예산을 자신이 아닌 자식들에게 줘야 함
        // → 리프(leaves)가 세부목차이면 부모 소분류의 evalScore로 그룹 예산 산정 후 자식 간 coveredReqIds 비례로 분배

        // parentTocId → subToc 목록 맵 (세부목차 그룹화)
        java.util.Map<String, List<ProposalVO.TocVO>> subTocsByParent = new java.util.LinkedHashMap<>();
        for (ProposalVO.TocVO leaf : leaves) {
            if (CommonUtil.isNotEmpty(leaf.getParentTocId()) && "002".equals(leaf.getOriginTypeCd())) {
                subTocsByParent.computeIfAbsent(leaf.getParentTocId(), k -> new java.util.ArrayList<>()).add(leaf);
            }
        }

        // 세부목차(002)를 가진 소분류 tocId 집합
        java.util.Set<String> parentsWithSubToc = subTocsByParent.keySet();

        // 순수 리프(세부목차 없는 소분류 or 단층 목차): 직접 예산 배분
        List<ProposalVO.TocVO> directLeaves = new java.util.ArrayList<>();
        for (ProposalVO.TocVO leaf : leaves) {
            if (!"002".equals(leaf.getOriginTypeCd())) {
                // RFP 원본 노드: 세부목차를 가진 소분류는 직접 배분 대상에서 제외
                if (!parentsWithSubToc.contains(leaf.getTocId())) {
                    directLeaves.add(leaf);
                }
            }
        }

        // 소분류 그룹(세부목차 있는 소분류) tocVO 조회용 맵
        java.util.Map<String, ProposalVO.TocVO> tocById = new java.util.LinkedHashMap<>();
        for (ProposalVO.TocVO toc : tocList) {
            if (CommonUtil.isNotEmpty(toc.getTocId())) tocById.put(toc.getTocId(), toc);
        }

        // 전체 예산 배분: directLeaves + 세부목차 그룹을 하나의 단위로 묶어 배점 비례 배분
        // 세부목차 그룹의 score = 부모 소분류의 evalScore
        // 배분 단위 목록 구성
        List<Object> budgetUnits = new java.util.ArrayList<>(); // TocVO (direct) or String (parentTocId for subToc group)
        List<Double> budgetScores = new java.util.ArrayList<>();

        for (ProposalVO.TocVO leaf : directLeaves) {
            budgetUnits.add(leaf);
            budgetScores.add(resolveEvalScore(leaf, evalIdToScore, evalNmToScore));
        }
        for (String parentTocId : parentsWithSubToc) {
            ProposalVO.TocVO parentToc = tocById.get(parentTocId);
            double parentScore = parentToc != null ? resolveEvalScore(parentToc, evalIdToScore, evalNmToScore) : 0;
            budgetUnits.add(parentTocId);
            budgetScores.add(parentScore);
        }

        if (budgetUnits.isEmpty()) return;

        // MIN_SLIDES 예약 (score=0인 directLeaf)
        int reservedBudget = 0;
        List<Integer> unitBudgets = new java.util.ArrayList<>();
        for (int i = 0; i < budgetUnits.size(); i++) unitBudgets.add(0);

        List<Integer> linkedIdx = new java.util.ArrayList<>();
        for (int i = 0; i < budgetUnits.size(); i++) {
            if (budgetScores.get(i) <= 0 && budgetUnits.get(i) instanceof ProposalVO.TocVO) {
                unitBudgets.set(i, MIN_SLIDES);
                reservedBudget += MIN_SLIDES;
            } else if (budgetScores.get(i) > 0) {
                linkedIdx.add(i);
            } else {
                // subToc group with no score: distribute equally among subTocs
                int subTocCnt = subTocsByParent.getOrDefault(budgetUnits.get(i), java.util.Collections.emptyList()).size();
                int fallback = Math.max(MIN_SLIDES, subTocCnt);
                unitBudgets.set(i, fallback);
                reservedBudget += fallback;
            }
        }

        int remainBudget = totalSlideBudget - reservedBudget;
        if (remainBudget < linkedIdx.size()) remainBudget = linkedIdx.size();

        if (!linkedIdx.isEmpty()) {
            double totalScore = 0;
            for (int idx : linkedIdx) totalScore += budgetScores.get(idx);
            if (totalScore <= 0) totalScore = linkedIdx.size();

            int allocated = 0;
            for (int i = 0; i < linkedIdx.size() - 1; i++) {
                int idx = linkedIdx.get(i);
                double score = budgetScores.get(idx);
                int cnt = Math.max(1, (int) Math.round((score / totalScore) * remainBudget));
                unitBudgets.set(idx, cnt);
                allocated += cnt;
            }
            int lastIdx = linkedIdx.get(linkedIdx.size() - 1);
            unitBudgets.set(lastIdx, Math.max(1, remainBudget - allocated));
        }

        // direct leaf에 예산 직접 적용
        for (int i = 0; i < budgetUnits.size(); i++) {
            if (budgetUnits.get(i) instanceof ProposalVO.TocVO) {
                ((ProposalVO.TocVO) budgetUnits.get(i)).setPlannedSlideCnt(unitBudgets.get(i));
            }
        }

        // 세부목차 그룹: 그룹 예산을 coveredReqIds.size() 비례로 자식에 분배
        for (int i = 0; i < budgetUnits.size(); i++) {
            if (!(budgetUnits.get(i) instanceof String)) continue;
            String parentTocId = (String) budgetUnits.get(i);
            List<ProposalVO.TocVO> subTocs = subTocsByParent.get(parentTocId);
            if (subTocs == null || subTocs.isEmpty()) continue;
            int groupBudget = unitBudgets.get(i);

            int totalReqCount = 0;
            for (ProposalVO.TocVO st : subTocs)
                totalReqCount += (st.getCoveredReqIds() != null ? st.getCoveredReqIds().size() : 1);
            if (totalReqCount == 0) totalReqCount = subTocs.size();

            int subAllocated = 0;
            for (int j = 0; j < subTocs.size() - 1; j++) {
                ProposalVO.TocVO st = subTocs.get(j);
                int reqCnt = (st.getCoveredReqIds() != null && !st.getCoveredReqIds().isEmpty())
                        ? st.getCoveredReqIds().size() : 1;
                int cnt = Math.max(1, (int) Math.round((double) reqCnt / totalReqCount * groupBudget));
                st.setPlannedSlideCnt(cnt);
                subAllocated += cnt;
            }
            ProposalVO.TocVO lastSt = subTocs.get(subTocs.size() - 1);
            lastSt.setPlannedSlideCnt(Math.max(1, groupBudget - subAllocated));
        }

        // 비리프(대분류·세부목차 있는 소분류) plannedSlideCnt = children 합계 (post-order 재귀)
        java.util.Map<String, Integer> parentSumMap = new java.util.HashMap<>();
        for (ProposalVO.TocVO toc : tocList) {
            if (CommonUtil.isNotEmpty(toc.getParentTocId()) && toc.getPlannedSlideCnt() > 0) {
                parentSumMap.merge(toc.getParentTocId(), toc.getPlannedSlideCnt(), Integer::sum);
            }
        }
        for (ProposalVO.TocVO toc : tocList) {
            if (CommonUtil.isNotEmpty(toc.getTocId()) && parentSumMap.containsKey(toc.getTocId())) {
                toc.setPlannedSlideCnt(parentSumMap.get(toc.getTocId()));
            }
        }
    }

    /**
     * TocVO에 연결된 평가기준 배점을 반환.
     * linkedEvalCriteriaId(S2C 경로) 우선, 없으면 linkedEvalCriteriaNm(S2A 경로) 사용.
     * 매칭되는 항목이 없으면 0 반환.
     */
    private double resolveEvalScore(ProposalVO.TocVO leaf,
            java.util.Map<String, Double> evalIdToScore,
            java.util.Map<String, Double> evalNmToScore) {
        if (CommonUtil.isNotEmpty(leaf.getLinkedEvalCriteriaId())) {
            Double score = evalIdToScore.get(leaf.getLinkedEvalCriteriaId());
            if (score != null) return score;
        }
        if (CommonUtil.isNotEmpty(leaf.getLinkedEvalCriteriaNm())) {
            Double score = evalNmToScore.get(leaf.getLinkedEvalCriteriaNm());
            if (score != null) return score;
        }
        return 0.0;
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
     * Call 1(S2A) 프롬프트 조합 — problemDefinitions 전용 (toc는 Stage1 산출물 사용)
     * - S2A는 problemDefinitions만 반환 (toc는 Stage1 INSERT, coveredReqIds는 S2C에서 UPDATE)
     * - 평가기준은 문제정의 프롬프트에서 사용하지 않으므로 포함하지 않음
     */
    /**
     * buildStage2aPromptSlim 반환값 — 프롬프트 문자열 + 실제로 프롬프트에 포함된 요구사항 샘플.
     * sampledReqs는 이후 sourceRequirementIds 유효성 검증 시 "실제로 LLM에게 보여준 subset" 기준으로
     * 사용하기 위해 노출한다 (전체 요구사항 우주 기준으로 검증하면 할루시네이션이 새어나갈 수 있음).
     */
    private static final class Stage2aPromptContext {
        private final String promptText;
        private final List<ProposalVO.RequirementLiteVO> sampledReqs;

        private Stage2aPromptContext(String promptText, List<ProposalVO.RequirementLiteVO> sampledReqs) {
            this.promptText = promptText;
            this.sampledReqs = sampledReqs;
        }

        private String getPromptText() { return promptText; }
        private List<ProposalVO.RequirementLiteVO> getSampledReqs() { return sampledReqs; }
    }

    /** RFP 이슈 유형 코드 → 라벨 (프롬프트 표기용) */
    private String issueTypeLabel(String issueTypeCd) {
        if (issueTypeCd == null) return "추진배경";
        switch (issueTypeCd) {
            case "001": return "문제점";
            case "002": return "개선방향";
            case "003": return "추진배경";
            default:    return "추진배경";
        }
    }

    private Stage2aPromptContext buildStage2aPromptSlim(String promptContent,
            ProposalVO.ProjectVO project,
            List<ProposalVO.RequirementVO> requirements,
            List<ProposalVO.RfpIssueVO> rfpIssues,
            int totalSlideBudget) {

        StringBuilder sb = new StringBuilder();
        sb.append(promptContent);
        sb.append("\n\n## 사업 기본 정보");
        sb.append("\n- 사업명: ").append(CommonUtil.nullToBlank(project.getProjectNm()));
        sb.append("\n- 목표 슬라이드 수: ").append(totalSlideBudget).append("장");

        if (CommonUtil.isNotEmpty(project.getWritingGuidelineJson())) {
            sb.append("\n\n## 제안서 작성지침\n").append(project.getWritingGuidelineJson());
        }

        // ① 공통 원칙 — 이슈 유무 분기(if/else) 진입 전에 한 번만 append. 두 분기 모두 적용됨.
        sb.append("\n\n## 문제 분석 공통 원칙\n");
        sb.append("- 여러 카테고리에 걸쳐 있어도 같은 근본 원인이면 하나의 문제로 묶으세요.\n");
        sb.append("- 카테고리 단위로 순회하며 기계적으로 요약하지 마세요.\n");
        sb.append("- 근거로 사용한 ISSUE_ID(sourceIssueIds)와 REQUIREMENT_ID(sourceRequirementIds)를 반드시 출력하세요.\n");

        // ② 문제 근거 분기 — rfpIssues 유무에 따라 1순위 근거를 다르게 지정
        boolean hasIssues = rfpIssues != null && !rfpIssues.isEmpty();
        if (hasIssues) {
            sb.append("\n## 문제 근거 (1순위 — RFP 원문)\n");
            sb.append("아래 이슈를 최우선 근거로 삼아 문제를 정의하세요. ");
            sb.append("요구사항은 requiredCapability/strategySummary 보강 재료로만 사용하세요.\n");
            List<ProposalVO.RfpIssueVO> sorted = new java.util.ArrayList<>(rfpIssues);
            sorted.sort(java.util.Comparator.comparing(
                    (ProposalVO.RfpIssueVO i) -> CommonUtil.nullToBlank(i.getIssueTypeCd())));
            for (ProposalVO.RfpIssueVO issue : sorted) {
                sb.append(String.format("- [%s][%s] %s%n",
                        issue.getIssueId(),
                        issueTypeLabel(issue.getIssueTypeCd()),
                        issue.getIssueContent()));
            }
        } else {
            sb.append("\n## 문제 근거 (RFP에 명시적 문제점 섹션 없음)\n");
            sb.append("이 RFP에는 현황/문제점 서술이 없습니다. ");
            sb.append("아래 요구사항에서 현황성 표현(미흡/한계/필요/부족 등)이 포함된 항목만 선별해 문제를 재구성하세요. ");
            sb.append("순수 스펙 나열 항목은 근거로 사용하지 마세요.\n");
        }

        // ③ 요구사항 샘플 — 항상 포함 (requiredCapability/strategySummary 재료).
        //    이슈가 있으면 요구사항은 보강 재료로만 쓰이므로 샘플 리밋을 축소해 토큰 절감.
        List<ProposalVO.RequirementLiteVO> sampledReqs = new java.util.ArrayList<>();
        if (requirements != null && !requirements.isEmpty()) {
            List<ProposalVO.RequirementLiteVO> reqLite = requirements.stream()
                    .map(this::toRequirementLite)
                    .collect(java.util.stream.Collectors.toList());
            int totalLimit = hasIssues ? 10 : PROBLEM_DEF_REQ_LIMIT;
            sampledReqs = sampleRequirementsForProblemDef(reqLite, totalLimit);
            logger.info("[PT Stage2-A] 문제정의용 샘플링: 전체 {}건 → {}건 (hasIssues={})",
                    reqLite.size(), sampledReqs.size(), hasIssues);
            sb.append("\n\n## 문제정의용 요구사항 샘플 (JSON)");
            sb.append("\n※ 이 목록은 발주기관 문제 유형 파악(problemDefinitions)에만 사용하세요. 필수 요구사항 우선 샘플만 포함합니다.\n");
            sb.append(GSON.toJson(sampledReqs));
        }

        logger.info("[PT Stage2-A] 프롬프트 구성 완료 — 합계: {}자", sb.length());
        return new Stage2aPromptContext(sb.toString(), sampledReqs);
    }

    /**
     * Call 1B(S2C) 프롬프트 조합 — coveredReqIds + linkedEvalCriteriaId 매핑 + 세부목차 생성
     * - 입력: mandatedToc 기반 소분류 목록 + S2CLite 전체 요구사항 + 평가기준 목록 + Win Theme 목록
     * - 소분류 = parentTocIds에 자신의 tocId가 참조되는 노드를 제외한 나머지 (DB 로드 시 level 필드 무효)
     * - LLM에 소분류별 세부목차 subTocs 배열을 응답하도록 요청
     */
    private String buildStage2cCoveredReqIdsPrompt(String promptContent,
            List<ProposalVO.TocVO> tocList,
            List<ProposalVO.RequirementVO> requirements,
            List<ProposalVO.EvalCriteriaVO> evalCriteria,
            List<ProposalVO.WinThemeVO> winThemes) {

        // 리프(소분류) 노드 탐색: 다른 항목의 parentTocId로 참조되는 tocId = 대분류 → 제외
        // (no/parentNo는 transient 필드라 DB 로드 시 null → tocId/parentTocId 사용)
        java.util.Set<String> parentTocIds = new java.util.HashSet<>();
        if (tocList != null) {
            for (ProposalVO.TocVO t : tocList) {
                if (CommonUtil.isNotEmpty(t.getParentTocId())) parentTocIds.add(t.getParentTocId());
            }
        }

        List<java.util.Map<String, Object>> leafList = new java.util.ArrayList<>();
        for (ProposalVO.TocVO t : (tocList != null ? tocList : java.util.Collections.<ProposalVO.TocVO>emptyList())) {
            // parentTocIds에 포함된 tocId = 부모 노드(대분류) → 제외, 나머지만 소분류
            if (CommonUtil.isNotEmpty(t.getTocId()) && parentTocIds.contains(t.getTocId())) continue;
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("title", t.getSectionNm());
            if (CommonUtil.isNotEmpty(t.getParentTocId())) m.put("parentTitle",
                    tocList.stream().filter(p -> t.getParentTocId().equals(p.getTocId()))
                            .map(ProposalVO.TocVO::getSectionNm).findFirst().orElse(null));
            leafList.add(m);
        }

        List<java.util.Map<String, Object>> reqS2cLite =
                (requirements != null ? requirements : java.util.Collections.<ProposalVO.RequirementVO>emptyList())
                .stream()
                .map(this::toRequirementS2CLite)
                .collect(java.util.stream.Collectors.toList());

        List<ProposalVO.EvalCriteriaLiteVO> evalLite =
                (evalCriteria != null ? evalCriteria : java.util.Collections.<ProposalVO.EvalCriteriaVO>emptyList())
                .stream()
                .map(this::toEvalCriteriaLite)
                .collect(java.util.stream.Collectors.toList());

        List<java.util.Map<String, Object>> winThemeLite =
                (winThemes != null ? winThemes : java.util.Collections.<ProposalVO.WinThemeVO>emptyList())
                .stream()
                .map(this::toWinThemeUltraLite)
                .collect(java.util.stream.Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append(promptContent);
        sb.append("\n\n## 목차 소분류 목록 (JSON)\n");
        sb.append(GSON.toJson(leafList));
        sb.append("\n\n## 평가기준 목록 (JSON)");
        sb.append("\n※ linkedEvalCriteriaId에는 아래 목록의 evalCriteriaId 값을 그대로 사용하세요. 없으면 null로 두세요.\n");
        sb.append(GSON.toJson(evalLite));
        sb.append("\n\n## 요구사항 전체 목록 (JSON)");
        sb.append("\n※ requirementId 값을 coveredReqIds에 그대로 사용하세요. reqNo가 null인 항목(전략적해석)도 포함되며 동일하게 배정 대상입니다.\n");
        sb.append(GSON.toJson(reqS2cLite));
        if (!winThemeLite.isEmpty()) {
            sb.append("\n\n## Win Theme 목록 (JSON)");
            sb.append("\n※ 세부목차 생성 시 Win Theme의 핵심 메시지와 전략을 반영하세요.\n");
            sb.append(GSON.toJson(winThemeLite));
        }

        logger.info("[PT Stage2-C] 프롬프트 구성 완료 — 소분류: {}개, 평가기준: {}건, 요구사항: {}건, WinTheme: {}건, 합계: {}자",
                leafList.size(), evalLite.size(), reqS2cLite.size(), winThemeLite.size(), sb.length());
        return sb.toString();
    }

    // ── S2C 배치 분할 관련 헬퍼 ──────────────────────────────────────────────────

    /**
     * 배치 병렬 호출로 인한 동일 requirementId 중복 배정을 제거한다.
     * - 002(세부목차) 항목을 tocList 순서대로 순회 (대목차 SORT_ORD 순 보장 — selectTocList 반환순 기준)
     * - 동일 requirementId가 먼저 등장한 세부목차에서만 유지, 이후 세부목차의 coveredReqIds에서 제거
     * - coveredReqIds가 완전히 비어도 해당 세부목차 자체는 삭제하지 않음 (목차 구조 유지)
     */
    private void deduplicateS2cCoveredReqIds(List<ProposalVO.TocVO> tocList, String ptProjectId) {
        java.util.Set<String> seenReqIds = new java.util.LinkedHashSet<>();
        int removedCount = 0;
        for (ProposalVO.TocVO t : (tocList != null ? tocList : java.util.Collections.<ProposalVO.TocVO>emptyList())) {
            if (!"002".equals(t.getOriginTypeCd())) continue;
            if (t.getCoveredReqIds() == null || t.getCoveredReqIds().isEmpty()) continue;

            List<String> deduped = new java.util.ArrayList<>();
            for (String reqId : t.getCoveredReqIds()) {
                if (seenReqIds.add(reqId)) {
                    deduped.add(reqId);
                } else {
                    logger.info("[PT Stage2-C] 중복 배정 제거: reqId={} — sectionNm='{}' (parentTocId={}) 에서 제거",
                            reqId, t.getSectionNm(), t.getParentTocId());
                    removedCount++;
                }
            }
            t.setCoveredReqIds(deduped.isEmpty() ? null : deduped);
        }
        if (removedCount > 0)
            logger.info("[PT Stage2-C] 중복 배정 제거 완료 — 총 {}건 제거 (ptProjectId={})", removedCount, ptProjectId);
    }

    /**
     * 요구사항을 대목차 단위 배치로 라우팅한다.
     * - TOC 소분류 SECTION_NM 정규화(끝 "요구사항" 제거 + 공백 제거) → 대목차 그룹 키 생성
     *   (대목차 자신의 SECTION_NM도 키로 포함 — "프로젝트지원" 등 직접 매칭 지원)
     * - REQ_CATEGORY_TXT를 alias 조회 후 공백 제거 정규화 → 매칭 대목차 결정
     * - 어느 대목차에도 매칭 안 된 요구사항은 unmatchedReqsOut에 수집
     *
     * @param unmatchedReqsOut (out) 미매칭 요구사항 수집 리스트 (호출 전 비어있어야 함)
     * @return parentTocId → 매칭된 RequirementVO 리스트 (대목차 순서 유지)
     */
    private java.util.Map<String, List<ProposalVO.RequirementVO>> routeRequirementsToTocGroups(
            List<ProposalVO.TocVO> tocList,
            List<ProposalVO.RequirementVO> requirements,
            List<ProposalVO.RequirementVO> unmatchedReqsOut) {

        // 1. 대목차 식별 (PARENT_TOC_ID 없는 노드)
        List<ProposalVO.TocVO> parentTocs = new java.util.ArrayList<>();
        java.util.Map<String, List<ProposalVO.TocVO>> childrenByParent = new java.util.LinkedHashMap<>();
        for (ProposalVO.TocVO t : (tocList != null ? tocList : java.util.Collections.<ProposalVO.TocVO>emptyList())) {
            if (CommonUtil.isEmpty(t.getParentTocId())) {
                parentTocs.add(t);
                childrenByParent.put(t.getTocId(), new java.util.ArrayList<>());
            }
        }
        for (ProposalVO.TocVO t : (tocList != null ? tocList : java.util.Collections.<ProposalVO.TocVO>emptyList())) {
            if (CommonUtil.isNotEmpty(t.getParentTocId()) && childrenByParent.containsKey(t.getParentTocId()))
                childrenByParent.get(t.getParentTocId()).add(t);
        }

        // 2. 정규화 키 → 대목차ID 역색인 구성
        //    대목차 자신의 SECTION_NM + 직계 소분류 SECTION_NM 모두 포함
        java.util.Map<String, String> normalizedKeyToParentId = new java.util.LinkedHashMap<>();
        for (ProposalVO.TocVO parent : parentTocs) {
            String parentKey = normalizeTocSectionNm(parent.getSectionNm());
            if (CommonUtil.isNotEmpty(parentKey))
                normalizedKeyToParentId.putIfAbsent(parentKey, parent.getTocId());
            for (ProposalVO.TocVO child : childrenByParent.getOrDefault(parent.getTocId(), java.util.Collections.emptyList())) {
                String childKey = normalizeTocSectionNm(child.getSectionNm());
                if (CommonUtil.isNotEmpty(childKey))
                    normalizedKeyToParentId.putIfAbsent(childKey, parent.getTocId());
            }
        }

        // 3. alias 테이블 (key: 정규화 후 REQ_CATEGORY_TXT, value: 매칭에 쓸 정규화 TOC 키)
        // REQ_CATEGORY_TXT도 normalizeTocSectionNm과 동일 정규화("요구사항" 제거 + 공백 제거) 적용하므로
        // 대부분의 케이스는 정규화 후 직접 매칭됨. alias는 TOC 오타 등 진짜 불일치만 처리.
        java.util.Map<String, String> categoryAlias = new java.util.HashMap<>();
        categoryAlias.put("제약사항", "제약사향");  // TOC 데이터 오타("제약사향") 흡수

        // 4. 결과 맵 초기화 (대목차 순서 유지)
        java.util.Map<String, List<ProposalVO.RequirementVO>> result = new java.util.LinkedHashMap<>();
        for (ProposalVO.TocVO parent : parentTocs)
            result.put(parent.getTocId(), new java.util.ArrayList<>());

        // 5. 요구사항별 라우팅
        // REQ_CATEGORY_TXT에 TOC 쪽과 동일한 정규화("요구사항" 접미사 제거 + 공백 제거)를 적용해야
        // "성능 요구사항" → "성능", "프로젝트 관리 요구사항" → "프로젝트관리" 등이 올바르게 매칭됨
        if (requirements != null) {
            for (ProposalVO.RequirementVO req : requirements) {
                String rawCategory = req.getReqCategoryTxt() != null ? req.getReqCategoryTxt().trim() : "";
                String normalizedCategory = normalizeTocSectionNm(rawCategory); // TOC 쪽과 동일 정규화
                String tocKey = categoryAlias.getOrDefault(normalizedCategory, normalizedCategory);
                String parentTocId = normalizedKeyToParentId.get(tocKey);
                if (parentTocId != null) {
                    result.get(parentTocId).add(req);
                } else {
                    unmatchedReqsOut.add(req);
                    logger.debug("[PT Stage2-C] 요구사항 라우팅 미매칭 — reqNo={}, category='{}' (normalized='{}', tocKey='{}')",
                            req.getReqNo(), rawCategory, normalizedCategory, tocKey);
                }
            }
        }

        // 6. 로깅
        int totalMatched = result.values().stream().mapToInt(List::size).sum();
        logger.info("[PT Stage2-C] 요구사항 라우팅 완료 — 매칭:{}건, 미매칭:{}건 (대목차:{}개)",
                totalMatched, unmatchedReqsOut.size(), parentTocs.size());
        for (ProposalVO.TocVO parent : parentTocs) {
            logger.info("[PT Stage2-C]   대목차 '{}' ({}): 매칭 {}건",
                    parent.getSectionNm(), parent.getTocId(),
                    result.getOrDefault(parent.getTocId(), java.util.Collections.emptyList()).size());
        }
        if (!unmatchedReqsOut.isEmpty()) {
            logger.info("[PT Stage2-C] 미매칭 요구사항: {}",
                    unmatchedReqsOut.stream().map(ProposalVO.RequirementVO::getReqNo)
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        return result;
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * TOC SECTION_NM 정규화: 끝 "요구사항" 접미사(앞 공백 포함) 제거 후 공백 전체 제거.
     */
    private String normalizeTocSectionNm(String sectionNm) {
        if (sectionNm == null) return "";
        String s = sectionNm.trim();
        if (s.endsWith("요구사항")) s = s.substring(0, s.length() - 4).trim();
        return s.replaceAll("\\s+", "");
    }

    /**
     * S2C 고정 프리픽스 조립 — 캐싱 구조 대비 분리.
     * promptContent(원칙·출력형식) + 평가기준 전체 목록. 모든 배치 호출에서 동일.
     */
    private String buildS2cFixedPrefix(String promptContent, List<ProposalVO.EvalCriteriaVO> evalCriteria) {
        List<ProposalVO.EvalCriteriaLiteVO> evalLite =
                (evalCriteria != null ? evalCriteria : java.util.Collections.<ProposalVO.EvalCriteriaVO>emptyList())
                .stream().map(this::toEvalCriteriaLite)
                .collect(java.util.stream.Collectors.toList());
        StringBuilder sb = new StringBuilder();
        sb.append(promptContent);
        sb.append("\n\n## 평가기준 목록 (JSON)");
        sb.append("\n※ linkedEvalCriteriaId에는 아래 목록의 evalCriteriaId 값을 그대로 사용하세요.");
        sb.append(" 애매하더라도 가장 근접한 평가항목을 선택하는 것을 우선하세요 — 정말 관련 평가항목이 없는 경우에만 null로 두세요.\n");
        sb.append(GSON.toJson(evalLite));
        return sb.toString();
    }

    /**
     * S2C 배치용 프롬프트 조합 (대목차 단위 분할 호출).
     *
     * 구조:
     * [고정] fixedPrefix (promptContent + 평가기준 전체)
     * [가변] 배치 목차 소분류 + 매칭 요구사항(전문, truncate 없음)
     *        + 미매칭 요구사항(경량, 모든 배치 공통) + WinTheme(조건부, 공통)
     */
    private String buildStage2cBatchPrompt(
            String fixedPrefix,
            List<ProposalVO.TocVO> batchNodes,
            List<ProposalVO.RequirementVO> matchedRequirements,
            List<java.util.Map<String, Object>> unmatchedReqsLite,
            List<java.util.Map<String, Object>> winThemeLite,
            String batchParentSectionNm) {

        // 배치 내 소분류만 추출 (대목차 자신은 leaf 아님)
        java.util.Set<String> batchParentTocIds = new java.util.HashSet<>();
        for (ProposalVO.TocVO t : batchNodes)
            if (CommonUtil.isNotEmpty(t.getParentTocId())) batchParentTocIds.add(t.getParentTocId());

        List<java.util.Map<String, Object>> batchLeafList = new java.util.ArrayList<>();
        for (ProposalVO.TocVO t : batchNodes) {
            if (CommonUtil.isNotEmpty(t.getTocId()) && batchParentTocIds.contains(t.getTocId())) continue;
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("title", t.getSectionNm());
            if (CommonUtil.isNotEmpty(t.getParentTocId())) {
                batchNodes.stream().filter(p -> t.getParentTocId().equals(p.getTocId()))
                        .map(ProposalVO.TocVO::getSectionNm).findFirst()
                        .ifPresent(pnm -> m.put("parentTitle", pnm));
            }
            batchLeafList.add(m);
        }

        // 매칭 요구사항 전문 (truncate 없음)
        List<java.util.Map<String, Object>> matchedReqFull =
                (matchedRequirements != null ? matchedRequirements : java.util.Collections.<ProposalVO.RequirementVO>emptyList())
                .stream().map(this::toRequirementS2CFullDetail)
                .collect(java.util.stream.Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append(fixedPrefix);
        sb.append("\n\n## [배치] 목차 소분류 목록 (JSON) — 대목차: ").append(batchParentSectionNm).append("\n");
        sb.append(GSON.toJson(batchLeafList));
        sb.append("\n\n## [배치] 이번 배치 요구사항 전체 목록 (JSON)");
        sb.append("\n※ requirementId 값을 coveredReqIds에 그대로 사용하세요. reqNo가 null인 항목도 동일하게 배정 대상입니다.\n");
        sb.append(GSON.toJson(matchedReqFull));
        if (!unmatchedReqsLite.isEmpty()) {
            sb.append("\n\n## [공통] 미매칭 요구사항 경량 목록 (JSON) — 참고용");
            sb.append("\n※ 특정 대목차에 자동 배정되지 않은 요구사항입니다. 소분류 내용과 관련 있으면 coveredReqIds에 포함하세요.\n");
            sb.append(GSON.toJson(unmatchedReqsLite));
        }
        if (!winThemeLite.isEmpty()) {
            sb.append("\n\n## Win Theme 목록 (JSON)");
            sb.append("\n※ 세부목차 생성 시 Win Theme의 핵심 메시지와 전략을 반영하세요.\n");
            sb.append(GSON.toJson(winThemeLite));
        }

        logger.info("[PT Stage2-C][배치={}] 프롬프트 구성 — 소분류:{}개, 매칭요구사항:{}건, 미매칭:{}건, WinTheme:{}건, 합계:{}자",
                batchParentSectionNm, batchLeafList.size(), matchedReqFull.size(),
                unmatchedReqsLite.size(), winThemeLite.size(), sb.length());
        return sb.toString();
    }

    /**
     * RequirementVO → S2C 배치용 전문 맵 변환. truncate 없이 전체 내용 포함.
     */
    private java.util.Map<String, Object> toRequirementS2CFullDetail(ProposalVO.RequirementVO src) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("requirementId", src.getRequirementId());
        m.put("reqNo", src.getReqNo());
        m.put("reqCategoryTxt", src.getReqCategoryTxt());
        m.put("reqContent", src.getReqContent());
        if (CommonUtil.isNotEmpty(src.getReqDetailTxt()))
            m.put("reqDetailTxt", src.getReqDetailTxt());
        m.put("mandatoryYn", src.getMandatoryYn());
        return m;
    }

    /**
     * RequirementVO → 미매칭 공통 경량 맵 변환. requirementId + reqNo + reqContent(80자)만.
     */
    private java.util.Map<String, Object> toRequirementMinimalLite(ProposalVO.RequirementVO src) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("requirementId", src.getRequirementId());
        m.put("reqNo", src.getReqNo());
        m.put("reqContent", truncateAtWordBoundary(src.getReqContent(), 80));
        return m;
    }

    /**
     * Call 1B(S2C) 응답 JSON 파싱 후 tocList에 적용.
     * 응답 형식:
     * [
     *   {
     *     "title": "소분류제목",
     *     "linkedEvalCriteriaId": "PTE000001",
     *     "subTocs": [
     *       { "sectionNm": "세부목차명", "coveredReqIds": ["PTQ000001",...], "linkedEvalCriteriaId": "PTE000001" },
     *       ...
     *     ]
     *   },
     *   ...
     * ]
     * - RFP 소분류(title)는 coveredReqIds=null, linkedEvalCriteriaId만 적용
     * - subTocs 항목은 ORIGIN_TYPE_CD=002 신규 TocVO로 tocList에 append
     * - 파싱 실패는 non-fatal (경고 로그만)
     */
    private void parseAndApplyStage2cResponse(List<ProposalVO.TocVO> tocList, String s2cResponse,
            String ptProjectId, java.util.Set<String> validEvalCriteriaIds,
            String batchParentTocId, String batchParentSectionNm) {
        String json = stripJsonCodeBlock(s2cResponse);
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();

            // sectionNm → TocVO 맵 구성 (이번 배치의 001 소분류만 포함 — 대목차·002·다른 배치 항목 제외)
            // 키: sectionNm 공백 정규화 값, 값: TocVO
            java.util.Map<String, ProposalVO.TocVO> titleMap = new java.util.LinkedHashMap<>();
            if (tocList != null) {
                for (ProposalVO.TocVO t : tocList) {
                    // 이번 배치 대목차(batchParentTocId)의 직접 자식 001 소분류만 포함
                    if (!"001".equals(t.getOriginTypeCd())) continue;
                    if (!batchParentTocId.equals(t.getParentTocId())) continue;
                    if (CommonUtil.isNotEmpty(t.getSectionNm()))
                        titleMap.put(normalizeTocSectionNm(t.getSectionNm()), t);
                }
            }
            logger.info("[PT Stage2-C][배치={}] 001 소분류 titleMap 구성: {}개 항목 (ptProjectId={})",
                    batchParentSectionNm, titleMap.size(), ptProjectId);

            int appliedParent = 0, appendedSubToc = 0, matchFail = 0, evalNullCount = 0;
            List<ProposalVO.TocVO> newSubTocs = new java.util.ArrayList<>();

            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String title = getStrOrNull(obj, "title");
                // 공백 정규화 후 매칭
                String normalizedTitle = normalizeTocSectionNm(title);
                ProposalVO.TocVO parentToc = titleMap.get(normalizedTitle);
                if (parentToc == null) {
                    logger.warn("[PT Stage2-C][배치={}] 001 업데이트 스킵: title='{}' 매칭 실패 (정규화='{}', ptProjectId={})",
                            batchParentSectionNm, title, normalizedTitle, ptProjectId);
                    matchFail++;
                    continue;
                }

                // RFP 소분류(parent): coveredReqIds는 null (세부목차가 가짐), evalCriteriaId만 적용
                String parentEvalId = getStrOrNull(obj, "linkedEvalCriteriaId");
                if (CommonUtil.isNotEmpty(parentEvalId) && validEvalCriteriaIds != null
                        && !validEvalCriteriaIds.contains(parentEvalId)) {
                    logger.warn("[PT Stage2-C][배치={}] 할루시네이션 — 존재하지 않는 evalCriteriaId 제거: evalCriteriaId={}, title={}, ptProjectId={}",
                            batchParentSectionNm, parentEvalId, title, ptProjectId);
                    parentEvalId = null;
                }
                if (CommonUtil.isNotEmpty(parentEvalId)) {
                    logger.info("[PT Stage2-C][배치={}] 001 업데이트: title='{}' 매칭성공, evalCriteriaId={}로 설정 (ptProjectId={})",
                            batchParentSectionNm, title, parentEvalId, ptProjectId);
                } else {
                    logger.info("[PT Stage2-C][배치={}] 001 업데이트: title='{}' 매칭성공, evalCriteriaId=null (LLM 미지정) (ptProjectId={})",
                            batchParentSectionNm, title, ptProjectId);
                    evalNullCount++;
                }
                parentToc.setLinkedEvalCriteriaId(parentEvalId);
                parentToc.setCoveredReqIds(null); // 세부목차가 요구사항을 가짐
                appliedParent++;

                // subTocs: AI 생성 세부목차 (ORIGIN_TYPE_CD=002)
                if (obj.has("subTocs") && obj.get("subTocs").isJsonArray()) {
                    JsonArray subArr = obj.getAsJsonArray("subTocs");
                    int subSortOrd = 1;
                    for (JsonElement subEl : subArr) {
                        if (!subEl.isJsonObject()) continue;
                        JsonObject subObj = subEl.getAsJsonObject();
                        String subNm = getStrOrNull(subObj, "sectionNm");
                        if (CommonUtil.isEmpty(subNm)) continue;

                        List<String> reqIds = new java.util.ArrayList<>();
                        if (subObj.has("coveredReqIds") && subObj.get("coveredReqIds").isJsonArray()) {
                            for (JsonElement rn : subObj.getAsJsonArray("coveredReqIds")) {
                                if (!rn.isJsonNull() && CommonUtil.isNotEmpty(rn.getAsString())) {
                                    reqIds.add(rn.getAsString());
                                }
                            }
                        }

                        String subEvalId = getStrOrNull(subObj, "linkedEvalCriteriaId");
                        if (CommonUtil.isEmpty(subEvalId)) subEvalId = parentEvalId; // 부모 평가기준 상속
                        if (CommonUtil.isNotEmpty(subEvalId) && validEvalCriteriaIds != null
                                && !validEvalCriteriaIds.contains(subEvalId)) {
                            subEvalId = parentEvalId; // 할루시네이션 → 부모 값으로 폴백
                        }

                        ProposalVO.TocVO subToc = new ProposalVO.TocVO();
                        // tocId는 saveStage2TocMappingInternal에서 채번
                        subToc.setPtProjectId(ptProjectId);
                        subToc.setParentTocId(parentToc.getTocId());
                        subToc.setSectionNm(subNm);
                        subToc.setLinkedEvalCriteriaId(subEvalId);
                        subToc.setCoveredReqIds(reqIds.isEmpty() ? null : reqIds);
                        subToc.setOriginTypeCd("002");
                        subToc.setSortOrd(subSortOrd++);
                        newSubTocs.add(subToc);
                        appendedSubToc++;
                    }
                }
            }

            if (tocList != null) tocList.addAll(newSubTocs);
            logger.info("[PT Stage2-C][배치={}] 배정 완료 — 001 매칭성공:{}개(evalNull:{}개), 매칭실패:{}개, 세부목차:{}개 추가 (ptProjectId={})",
                    batchParentSectionNm, appliedParent, evalNullCount, matchFail, appendedSubToc, ptProjectId);
        } catch (Exception e) {
            logger.warn("[PT Stage2-C][배치={}] S2C 응답 파싱 실패, 배정 생략 (ptProjectId={}): {}",
                    batchParentSectionNm, ptProjectId, e.getMessage());
        }
    }

    /**
     * Call 2(S2B) 프롬프트 조합 — winThemes 생성용
     * - 입력: Call 1 problemDefinitions(problemId 선채번 완료) + 자사/경쟁사/기타 참고자료 원문
     * - requirements/evalCriteria 원문은 포함하지 않음 (문제 정의는 problemDefinitions 로 요약 전달됨)
     */
    private String buildStage2bWinThemePrompt(String promptContent,
            ProposalVO.ProjectVO project,
            List<ProposalVO.ProblemDefinitionVO> problemDefinitions,
            String ownContext,
            String competitorContext,
            String etcRefContext,
            String feedback) {

        StringBuilder sb = new StringBuilder();
        sb.append(promptContent);
        sb.append("\n\n## 사업 기본 정보");
        sb.append("\n- 사업명: ").append(CommonUtil.nullToBlank(project.getProjectNm()));

        sb.append("\n\n## 발주기관 문제 정의 목록 (Call 1 결과, JSON)");
        sb.append("\n각 항목의 problemId(PTP…)는 문제를 식별하는 고유값입니다.");
        sb.append("\nWin Theme의 sourceProblemDefinitionIds에는 아래 problemId를 그대로 사용하세요");
        sb.append(" — 새로 만들거나 변형하지 마세요.\n");
        sb.append(GSON.toJson(toStage2bProblemDefVOs(problemDefinitions)));

        sb.append("\n\n## 자사 정보\n").append(ownContext);
        sb.append("\n\n## 경쟁사 정보\n").append(competitorContext);
        sb.append("\n\n## 기타 참고자료\n").append(etcRefContext);

        if (CommonUtil.isNotEmpty(feedback)) {
            sb.append("\n\n## 사용자 보완 요청\n");
            sb.append("아래 요청을 반영해 Win Theme를 재작성하세요.\n");
            sb.append(feedback);
        }

        return sb.toString();
    }

    /**
     * Stage2-B 프롬프트 전용 경량 문제정의 목록.
     * LLM이 sourceProblemDefinitionIds에 참조할 problemId를 명확히 노출하고 토큰을 절감한다.
     */
    private List<java.util.Map<String, Object>> toStage2bProblemDefVOs(List<ProposalVO.ProblemDefinitionVO> src) {
        List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        if (src == null) return result;
        for (ProposalVO.ProblemDefinitionVO p : src) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("problemId", p.getProblemId());
            m.put("problemTypeCd", p.getProblemTypeCd());
            m.put("currentProblem", p.getCurrentProblem());
            m.put("rootCause", p.getRootCause());
            m.put("goal", p.getGoal());
            m.put("requiredCapability", p.getRequiredCapability());
            m.put("strategySummary", p.getStrategySummary());
            m.put("kpi", p.getKpi());
            result.add(m);
        }
        return result;
    }

    /**
     * RequirementVO → RequirementLiteVO 변환 (Stage2 프롬프트 전용-입력 토큰 절약을 위해 경량화.)
     * reqContent 가 300자 초과이면 문장 경계에서 절삭
     */
    private ProposalVO.RequirementLiteVO toRequirementLite(ProposalVO.RequirementVO src) {
        ProposalVO.RequirementLiteVO lite = new ProposalVO.RequirementLiteVO();
        lite.setRequirementId(src.getRequirementId()); // LLM이 문제정의 sourceRequirementIds에 그대로 반환
        lite.setReqNo(src.getReqNo());
        lite.setReqCategoryTxt(src.getReqCategoryTxt());
        lite.setReqContent(truncateAtSentenceBoundary(src.getReqContent(), 300));
        lite.setMandatoryYn(src.getMandatoryYn());
        return lite;
    }

    /**
     * RequirementVO → RequirementLiteVO 변환 (toc 매핑용 초경량 버전)
     * reqContent를 80자로 제한. 문장 경계 우선, 없으면 단어(공백) 경계까지만 절삭.
     * reqNo·reqCategoryTxt는 절삭하지 않음 (toc 매핑 식별자).
     */
    private ProposalVO.RequirementLiteVO toRequirementUltraLite(ProposalVO.RequirementVO src) {
        ProposalVO.RequirementLiteVO lite = new ProposalVO.RequirementLiteVO();
        lite.setRequirementId(src.getRequirementId()); // LLM이 coveredReqIds에 그대로 반환
        lite.setReqNo(src.getReqNo());
        lite.setReqCategoryTxt(src.getReqCategoryTxt());
        lite.setReqContent(truncateAtWordBoundary(src.getReqContent(), 80));
        lite.setMandatoryYn(src.getMandatoryYn());
        return lite;
    }

    /**
     * RequirementVO → S2C용 경량 맵 변환.
     * reqDetailTxt(RFP 상세설명 원문)를 포함해 세부목차 생성 품질을 향상.
     * reqContent는 120자, reqDetailTxt는 200자로 절삭.
     */
    private java.util.Map<String, Object> toRequirementS2CLite(ProposalVO.RequirementVO src) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("requirementId", src.getRequirementId());
        m.put("reqNo", src.getReqNo());
        m.put("reqCategoryTxt", src.getReqCategoryTxt());
        m.put("reqContent", truncateAtWordBoundary(src.getReqContent(), 120));
        if (CommonUtil.isNotEmpty(src.getReqDetailTxt()))
            m.put("reqDetailTxt", truncateAtWordBoundary(src.getReqDetailTxt(), 200));
        m.put("mandatoryYn", src.getMandatoryYn());
        return m;
    }

    /**
     * WinThemeVO → S2C 프롬프트 전용 초경량 맵 변환.
     * coreMessage + proposalStrategy 두 필드만 포함 (토큰 절감).
     */
    private java.util.Map<String, Object> toWinThemeUltraLite(ProposalVO.WinThemeVO src) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("coreMessage", src.getCoreMessage());
        m.put("proposalStrategy", src.getProposalStrategy());
        return m;
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
     * Stage2-A 문제정의용 요구사항 샘플링 (reqLite 전체 → mandatoryYn='Y' 우선, 최대 limit 건)
     * - 카테고리 그룹핑 없이 전체 리스트에서 mandatoryYn='Y' 우선 선택
     * - 입력 리스트 순서(sortOrd 기준으로 이미 정렬된 상태) 그대로 유지
     * - rfpIssues가 있어 요구사항이 보강 재료로만 쓰일 때는 호출부에서 리밋을 축소해 토큰을 절감한다.
     */
    private List<ProposalVO.RequirementLiteVO> sampleRequirementsForProblemDef(
            List<ProposalVO.RequirementLiteVO> reqLite, int limit) {
        return pickWithMandatoryFirst(reqLite, limit);
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
        lite.setEvalCriteriaId(src.getEvalCriteriaId());
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
     * Stage 2 세부목차 실행 — 오케스트레이터 (S2C만).
     * 반드시 전략(STAGE2_STATUS_CD=005)이 완료된 후 호출해야 한다.
     * @param progressCallback step 코드 콜백 (null 허용).
     * STAGE2_STATUS_CD: 005전략완료(S2C 실행 대상) | 003완료(skip) | 004실패
     */
    public List<ProposalVO.TocVO> executeStage2Toc(String ptProjectId, int totalSlideBudget,
            String modelId, String agentId, java.util.function.Consumer<String> progressCallback) throws Exception {

        if (progressCallback != null) progressCallback.accept("load");
        ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
        if (project == null) throw new RuntimeException("프로젝트를 찾을 수 없습니다. ptProjectId=" + ptProjectId);

        String stage2StatusCd = CommonUtil.isNotEmpty(project.getStage2StatusCd())
                ? project.getStage2StatusCd() : STAGE2_STATUS_NOT_STARTED;
        if (STAGE2_STATUS_DONE.equals(stage2StatusCd)) {
            logger.info("[PT Stage2-Toc] 이미 완료(STAGE2_STATUS_CD=003), 저장 결과 반환 (ptProjectId={})", ptProjectId);
            return proposalDAO.selectTocList(ptProjectId);
        }
        if (!STAGE2_STATUS_STRATEGY_DONE.equals(stage2StatusCd)) {
            throw new RuntimeException("전략(문제정의/WinTheme)이 아직 완료되지 않았습니다. "
                    + "먼저 전략검토를 완료하세요. ptProjectId=" + ptProjectId);
        }

        try {
            List<ProposalVO.TocVO> result = runS2c(ptProjectId, totalSlideBudget, modelId, agentId, progressCallback);
            updateStage2StatusCd(ptProjectId, STAGE2_STATUS_DONE);
            return result;
        } catch (Exception e) {
            // 세부목차 실패해도 전략(005) 상태는 유지 — 재시도 가능해야 하므로 004로 내리지 않음
            logger.warn("[PT Stage2-Toc] 실패, STAGE2_STATUS_CD=005 유지 (재시도 가능) (ptProjectId={}): {}",
                    ptProjectId, e.getMessage());
            throw e;
        }
    }

    /**
     * D-0: Stage2 전략 분석 SSE 스트림
     * - STAGE2_STATUS_CD=005(전략완료) 또는 003(전체완료)이면 skip하고 done 이벤트 즉시 전송
     * - 002면 S2B(WinTheme)부터 재개, 001/004면 처음부터 — executeStage2()가 분기
     * - progress step: load | prompt | problem_def | parse | win_theme | save
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
                // Stage2 완료 여부 — 문제정의 건수가 아니라 STAGE2_STATUS_CD=003 기준
                ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
                String stage2StatusCd = (project != null && CommonUtil.isNotEmpty(project.getStage2StatusCd()))
                        ? project.getStage2StatusCd() : STAGE2_STATUS_NOT_STARTED;
                if (STAGE2_STATUS_STRATEGY_DONE.equals(stage2StatusCd) || STAGE2_STATUS_DONE.equals(stage2StatusCd)) {
                    logger.info("[PT D-0] 전략 이미 완료, skip (ptProjectId={}, STAGE2_STATUS_CD={})", ptProjectId, stage2StatusCd);
                    sendSseEvent(emitter, "done", "{\"ptProjectId\":\"" + ptProjectId + "\",\"skipped\":true}");
                    emitter.complete();
                    return;
                }

                // Stage2 실행 (동기 호출, 별도 스레드에서 실행 중이므로 OK)
                // progressCallback step 코드를 그대로 SSE progress 이벤트로 전달 (Stage1 패턴과 동일)
                ProposalVO.Stage2ResultVO result = executeStage2(ptProjectId, totalSlideBudget, modelId, agentId,
                        step -> sendSseEvent(emitter, "progress", "{\"step\":\"" + step + "\"}"));

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

    /**
     * D-0T: Stage2 세부목차 생성 SSE 스트림
     * - STAGE2_STATUS_CD=003(완료)이면 skip하고 done 이벤트 즉시 전송
     * - STAGE2_STATUS_CD=005(전략완료) 상태에서만 S2C 실행
     * - 그 외 상태이면 에러 이벤트 전송
     *
     * @param ptProjectId      프로젝트 ID
     * @param totalSlideBudget 목표 슬라이드 수 (기본 20)
     * @param modelId          LLM 모델 ID
     * @param agentId          에이전트 ID
     */
    public SseEmitter streamAnalyzeStage2Toc(String ptProjectId, int totalSlideBudget, String modelId, String agentId) {
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onTimeout(() -> {
            logger.warn("[PT D-0T] SSE timeout - ptProjectId={}", ptProjectId);
            emitter.complete();
        });
        emitter.onError(e -> logger.warn("[PT D-0T] SSE error - ptProjectId={}, msg={}", ptProjectId, e.getMessage()));

        sendSseEvent(emitter, "connected", "{\"ptProjectId\":\"" + ptProjectId + "\"}");

        final String userId = SessionUtil.getUserId();

        STAGE_D_EXECUTOR.execute(() -> {
            try {
                List<ProposalVO.TocVO> result = executeStage2Toc(ptProjectId, totalSlideBudget, modelId, agentId,
                        step -> sendSseEvent(emitter, "progress", "{\"step\":\"" + step + "\"}"));

                int tocCount = result != null ? result.size() : 0;

                sendSseEvent(emitter, "done",
                        "{\"ptProjectId\":\"" + ptProjectId + "\""
                        + ",\"skipped\":false"
                        + ",\"tocCount\":" + tocCount + "}");

            } catch (Exception e) {
                logger.error("[PT D-0T] Stage2-Toc 처리 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
                sendSseEvent(emitter, "error", "{\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step D-1: 소목차 슬라이드 생성 (Stage3)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * D-1: 소목차 슬라이드 생성 SSE 스트림 (Stage3)
     * - 한 번의 LLM 호출로 PLANNED_SLIDE_CNT 장 배열 생성
     * - 이미 슬라이드가 있으면 삭제 후 재생성
     * - 각 슬라이드 insert → RENDER_STATUS_CD='003' (본문 생성 완료)
     * - IMAGE_GEN_HINT는 이후 온디맨드 이미지 생성 버튼 클릭 시점에 조립될 예정
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

                sendSseEvent(emitter, "progress", "{\"step\":\"load\"}");

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
                // Stage2C 미실행(null) vs Stage2C 실행 후 0건("[]")을 구분해 폴백 범위를 최소화
                List<ProposalVO.RequirementVO> filteredReqs;
                String coveredReqIdsJsonRaw = tocVO.getCoveredReqIdsJson();
                if (CommonUtil.isEmpty(coveredReqIdsJsonRaw)) {
                    // Stage2C 미실행: COVERED_REQ_IDS_JSON 없음 → 전체 폴백 (Stage2 완료 후 재생성 권장)
                    filteredReqs = allRequirements;
                    logger.warn("[PT D-1] COVERED_REQ_IDS_JSON 미설정 — 전체 요구사항 {}건 사용 (tocId={}). Stage2 완료 후 재생성 권장.",
                            allRequirements.size(), tocId);
                } else {
                    Set<String> coveredReqIds = parseCoveredReqIds(coveredReqIdsJsonRaw);
                    if (coveredReqIds.isEmpty()) {
                        // Stage2C 실행 결과 0건("[]") → 빈 목록 (전체 폴백 금지)
                        filteredReqs = java.util.Collections.emptyList();
                        logger.info("[PT D-1] coveredReqIds 0건 (Stage2 결과 '[]') — 요구사항 없이 슬라이드 생성 (tocId={})", tocId);
                    } else {
                        filteredReqs = allRequirements.stream()
                                .filter(r -> coveredReqIds.contains(r.getRequirementId()))
                                .collect(java.util.stream.Collectors.toList());
                        logger.info("[PT D-1] 요구사항 필터링: 전체 {} → 관련 {} (tocId={})",
                                allRequirements.size(), filteredReqs.size(), tocId);
                    }
                }
                List<ProposalVO.RequirementStage3VO> requirements = toStage3RequirementVOs(filteredReqs);
                List<ProposalVO.WinThemeStage3VO> winThemes = toStage3WinThemeVOs(proposalDAO.selectWinThemes(ptProjectId));
                List<ProposalVO.ProblemDefinitionStage3VO> problemDefs = toStage3ProblemDefVOs(proposalDAO.selectProblemDefinitions(ptProjectId));

                // 4. PROJECT_CONFIG_JSON 로드
                String configJson = proposalDAO.selectProjectConfigJson(ptProjectId);
                ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);

                // 형제 소목차 완료 슬라이드 조회 (중복 방지 컨텍스트)
                List<ProposalVO.SiblingSlideVO> siblingSlides = java.util.Collections.emptyList();
                if (CommonUtil.isNotEmpty(tocVO.getParentTocId())) {
                    java.util.Map<String, Object> siblingParams = new java.util.HashMap<>();
                    siblingParams.put("parentTocId", tocVO.getParentTocId());
                    siblingParams.put("currentTocId", tocId);
                    siblingSlides = proposalDAO.selectSiblingSlides(siblingParams);
                    logger.info("[PT D-1] 형제 완료 슬라이드 {}건 로드 (parentTocId={}, tocId={})",
                            siblingSlides.size(), tocVO.getParentTocId(), tocId);
                }

                // 5. Stage3 프롬프트 조합 + LLM 호출
                sendSseEvent(emitter, "progress", "{\"step\":\"llm\"}");
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
                        requirements, winThemes, problemDefs, project, configJson, siblingSlides);

                String aiResponse = callLlmWithRetry(fullPrompt, modelId, agentId, "[PT D-1]");
                if (CommonUtil.isEmpty(aiResponse)) {
                    sendSseEvent(emitter, "error", "{\"message\":\"AI 응답이 비어 있습니다. 잠시 후 다시 시도해 주세요.\"}");
                    emitter.complete();
                    return;
                }

                // 6. Stage3 응답 파싱 → slides 배열
                sendSseEvent(emitter, "progress", "{\"step\":\"parse\"}");
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
                sendSseEvent(emitter, "progress", "{\"step\":\"save\"}");
                proposalDAO.deleteSlidesByToc(tocId);

                // 기존 최대 SLIDE_NO 이후로 시작 (다른 소목차 슬라이드 포함 누적)
                // 단, 이 소목차에 속한 슬라이드는 방금 삭제했으므로 현재 전체 max를 기준으로 함
                int maxSlideNo = proposalDAO.selectMaxSlideNo(ptProjectId);

                // 8. 슬라이드 insert (RENDER_STATUS_CD='003' 본문 생성 완료)
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

                    // Stage3(본문 생성) 완료. IMAGE_GEN_HINT는 이미지 생성 버튼 클릭 시점에 조립.
                    // 기존에는 Stage3.5(D-2) 스타일 조립 성공 후 완료로 올렸으나,
                    // Stage3.5 제거로 인해 INSERT 직후 바로 완료 상태로 설정한다.
                    slide.setRenderStatusCd(SLIDE_RENDER_DONE);
                    slide.setCreateUserId(userId);

                    proposalDAO.insertSlide(slide);
                    insertedSlides.add(slide);
                }

                int successCount = insertedSlides.size();
                int failCount = 0;
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
     * 이미지 생성 API에 전달할 시각 스타일 앵커 문자열을 반환한다.
     * <p>
     * 동일 프로젝트의 모든 슬라이드에 이 prefix를 동일하게 붙임으로써,
     * 소목차별 독립 호출에서도 AI 이미지 생성기가 일관된 시각 언어를 유지하도록 한다.
     * hex 코드 단독 전달보다 구체적인 시각 설명(flat vector, outline icon 등)이
     * 이미지 생성 모델의 스타일 일관성에 더 강하게 작용한다.
     *
     * @param baseColor    기본색 hex (e.g. "#5B4FE9")
     * @param accentColor  강조색 hex (e.g. "#E08A2C")
     * @param docSize      문서 크기 코드 ("a4" / "169" / "43")
     * @param writingStyle 문체 코드 ("formal" / "plain" / "persuasive")
     * @return "[STYLE: ...]" 형식의 스타일 앵커 문자열
     */
    private String buildStyleManifest(String baseColor, String accentColor, String docSize, String writingStyle) {
        String docSizeDesc;
        if ("169".equals(docSize))      docSizeDesc = "16:9 widescreen landscape";
        else if ("43".equals(docSize))  docSizeDesc = "4:3 standard landscape";
        else                            docSizeDesc = "A4 portrait";

        String toneDesc;
        if ("persuasive".equals(writingStyle))  toneDesc = "bold and impactful";
        else if ("plain".equals(writingStyle))  toneDesc = "clear and concise";
        else                                    toneDesc = "professional and formal";

        return String.format(
                "[STYLE: flat vector infographic illustration, minimalist clean corporate design, " +
                "white or very light background, outline icons with 2px stroke and rounded corners, " +
                "primary color %s for main headers and panel borders, " +
                "accent color %s for highlights callouts and emphasis, " +
                "dark navy text #1A1A2E, no photographic elements, no gradients, " +
                "consistent icon family throughout, clean sans-serif typography, " +
                "Korean corporate presentation, %s layout, %s tone]",
                baseColor, accentColor, docSizeDesc, toneDesc);
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
        // allRequirements : 사용자가 요구사항을 언급할 경우 슬라이드별 reqIdsJson으로 필터링하여 프롬프트에 포함
        List<ProposalVO.RequirementVO> allRequirements = proposalDAO.selectRequirements(ptProjectId);

        // 형제 소목차 완료 슬라이드 조회 (중복 방지 컨텍스트)
        ProposalVO.TocVO chatTocVO = proposalDAO.selectTocById(tocId);
        List<ProposalVO.SiblingSlideVO> siblingSlides = java.util.Collections.emptyList();
        if (chatTocVO != null && CommonUtil.isNotEmpty(chatTocVO.getParentTocId())) {
            java.util.Map<String, Object> siblingParams = new java.util.HashMap<>();
            siblingParams.put("parentTocId", chatTocVO.getParentTocId());
            siblingParams.put("currentTocId", tocId);
            siblingSlides = proposalDAO.selectSiblingSlides(siblingParams);
        }

        // 4. 대상 슬라이드 보완
        List<ProposalVO.SlideVO> updatedSlides = new java.util.ArrayList<>();
        Set<String> targetSet = new java.util.HashSet<>(targetSlideIds);

        for (ProposalVO.SlideVO existingSlide : currentSlides) {
            if (!targetSet.contains(existingSlide.getSlideId())) continue;

            // 해당 슬라이드에 연결된 요구사항만 필터링 (reqIdsJson의 reqNo 기준)
            List<ProposalVO.RequirementStage3VO> slideReqs =
                    filterReqsBySlide(allRequirements, existingSlide.getReqIdsJson());

            String chatFullPrompt = buildSectionChatPrompt(existingSlide, slideReqs, userMessage, siblingSlides);

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
                        updateVO.setModifyUserId(SessionUtil.getUserId());
                        proposalDAO.updateSlide(updateVO);

                        // 본문이 갱신됐으므로 '001'(이미지 생성 대기)로 리셋
                        // — IMAGE_GEN_HINT는 이미지 생성 버튼 클릭 시점에 새로 조립될 예정
                        ProposalVO.SlideVO resetVO = new ProposalVO.SlideVO();
                        resetVO.setSlideId(existingSlide.getSlideId());
                        resetVO.setRenderStatusCd("001");
                        proposalDAO.updateSlide(resetVO);
                        existingSlide.setRenderStatusCd("001");

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
            String userMessage,
            List<ProposalVO.SiblingSlideVO> siblingSlides) {
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

        appendSiblingContext(sb, siblingSlides);

        sb.append("\n\n## 보완 요청\n").append(userMessage)
          .append("\n\n슬라이드 1장을 수정하여 {\"slides\":[{...}]} JSON으로만 출력하세요.");

        return sb.toString();
    }

    /**
     * 형제 슬라이드 컨텍스트 append 공통 헬퍼.
     * siblingSlides가 비어있으면 아무것도 추가하지 않음.
     */
    private void appendSiblingContext(StringBuilder sb, List<ProposalVO.SiblingSlideVO> siblingSlides) {
        if (siblingSlides == null || siblingSlides.isEmpty()) return;
        sb.append("\n\n## 이미 생성된 인접 슬라이드 (제목·강조배너·부제 등 중복 금지)");
        for (ProposalVO.SiblingSlideVO s : siblingSlides) {
            String title = s.getTitleTxt();
            if (title != null && title.length() > 30) {
                title = title.substring(0, 30) + "...";
            }
            sb.append("\n- ").append(s.getSectionNm()).append(": ");
            if (CommonUtil.isNotEmpty(s.getEyebrowTxt())) {
                sb.append("eyebrow \"").append(s.getEyebrowTxt()).append("\", ");
            }
            sb.append("제목 \"").append(title).append("\"");
            if (CommonUtil.isNotEmpty(s.getHighlightBannerTxt())) {
                String banner = s.getHighlightBannerTxt();
                if (banner.length() > 40) {
                    banner = banner.substring(0, 40) + "...";
                }
                sb.append(", 강조배너 \"").append(banner).append("\"");
            }
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
            if (!SLIDE_RENDER_DONE.equals(s.getRenderStatusCd())) {
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

        // 다음 소목차 조회 — 3단계 계층 대응: selectTocList 기반 리프 판별 후 Java에서 탐색
        List<ProposalVO.TocVO> allToc = proposalDAO.selectTocList(ptProjectId);
        // tocId → TocVO 맵 (O(1) 조회용)
        java.util.Map<String, ProposalVO.TocVO> tocById = new java.util.HashMap<>();
        java.util.Set<String> parentTocIdSet = new java.util.HashSet<>();
        for (ProposalVO.TocVO t : allToc) {
            tocById.put(t.getTocId(), t);
            if (CommonUtil.isNotEmpty(t.getParentTocId())) parentTocIdSet.add(t.getParentTocId());
        }
        List<String> leafTocIds = new java.util.ArrayList<>();
        for (ProposalVO.TocVO t : allToc) {
            if (CommonUtil.isNotEmpty(t.getParentTocId()) && !parentTocIdSet.contains(t.getTocId())) {
                leafTocIds.add(t.getTocId());
            }
        }
        int currentIdx = leafTocIds.indexOf(tocId);
        ProposalVO.TocVO nextToc = null;
        if (currentIdx >= 0 && currentIdx + 1 < leafTocIds.size()) {
            nextToc = tocById.get(leafTocIds.get(currentIdx + 1));
        }

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
     * LLM 동기 호출 + 1회 재시도 헬퍼.
     * 첫 번째 호출 응답이 비어있으면 경고 로그 후 1회 재시도하고 결과를 반환한다.
     * 호출부에서 반환값이 empty인 경우의 처리(throw / SSE error 전송 등)를 직접 담당한다.
     *
     * @param prompt    LLM에 전달할 완성 프롬프트
     * @param modelId   사용할 LLM 모델 ID
     * @param agentId   에이전트 ID
     * @param logPrefix 워닝 로그 접두사 (예: "[PT Stage2-A]")
     * @return LLM 응답 문자열, 재시도 후에도 없으면 빈 문자열 또는 null
     */
    private String callLlmWithRetry(String prompt, String modelId, String agentId, String logPrefix) {
        String resp = riskDiagnosisAgentService.callLlmQuerySync(prompt, modelId, "", agentId);
        if (CommonUtil.isEmpty(resp)) {
            logger.warn("{} LLM 응답 없음, 1회 재시도", logPrefix);
            resp = riskDiagnosisAgentService.callLlmQuerySync(prompt, modelId, "", agentId);
        }
        return resp;
    }

    /**
     * TOC 트리 메타데이터 재귀 설정 — 3단계 계층 대응
     * - level: 루트=0(대목차), 자식=1(소목차), 손자=2(세부목차)
     * - no: DB의 sectionNo 값을 transient no 필드에도 동기화
     *
     * @param nodes 현재 레벨의 노드 목록
     * @param depth 현재 깊이 (최초 호출: 0)
     */
    private void setTocTreeMeta(List<ProposalVO.TocVO> nodes, int depth) {
        if (nodes == null) return;
        for (ProposalVO.TocVO node : nodes) {
            node.setLevel(depth);
            if (CommonUtil.isEmpty(node.getNo()) && CommonUtil.isNotEmpty(node.getSectionNo())) {
                node.setNo(node.getSectionNo());
            }
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                setTocTreeMeta(node.getChildren(), depth + 1);
            }
        }
    }

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
     * progress step: load | render
     * 슬라이드별 완료 progress(slideId, renderStatusCd, renderedImagePath, current, total) 후 done 이벤트.
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

        // 연결 확정 (Stage1 패턴과 동일)
        sendSseEvent(emitter, "connected",
                "{\"ptProjectId\":\"" + ptProjectId + "\",\"tocId\":\"" + tocId + "\"}");

        EXPORT_EXECUTOR.submit(() -> {
            try {
                // Step 1: 슬라이드 목록 로드
                sendSseEvent(emitter, "progress", "{\"step\":\"load\"}");
                List<ProposalVO.SlideVO> slides = proposalDAO.selectSlidesByToc(tocId);

                if (slides == null || slides.isEmpty()) {
                    logger.warn("[PT Image SSE] 렌더링할 슬라이드 없음 (tocId={})", tocId);
                    sendSseEvent(emitter, "done", GSON.toJson(buildPtImageDoneData(0, 0)));
                    return;
                }

                // Step 2: 슬라이드별 이미지 생성
                sendSseEvent(emitter, "progress", "{\"step\":\"render\"}");
                int total = slides.size();
                int successCount = 0;
                int current = 0;
                for (ProposalVO.SlideVO slide : slides) {
                    current++;
                    // 이미 완료된 슬라이드는 건너뜀
                    if (SLIDE_RENDER_DONE.equals(slide.getRenderStatusCd()) && CommonUtil.isNotEmpty(slide.getRenderedImagePath())) {
                        successCount++;
                        continue;
                    }
                    try {
                        String renderedPath = doImageRender(slide);

                        Map<String, Object> progressData = new HashMap<>();
                        progressData.put("step", "render");
                        progressData.put("slideId", slide.getSlideId());
                        progressData.put("current", current);
                        progressData.put("total", total);
                        if (renderedPath != null) {
                            progressData.put("renderStatusCd", SLIDE_RENDER_DONE);
                            progressData.put("renderedImagePath", renderedPath);
                            successCount++;
                        } else {
                            progressData.put("renderStatusCd", SLIDE_RENDER_FAIL);
                        }
                        sendSseEvent(emitter, "progress", GSON.toJson(progressData));
                    } catch (Exception e) {
                        logger.error("[PT Image SSE] 슬라이드 이미지 생성 오류 (slideId={}): {}", slide.getSlideId(), e.getMessage());
                        Map<String, Object> failData = new HashMap<>();
                        failData.put("step", "render");
                        failData.put("slideId", slide.getSlideId());
                        failData.put("renderStatusCd", SLIDE_RENDER_FAIL);
                        failData.put("current", current);
                        failData.put("total", total);
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
     * 슬라이드 단건 인포그래픽 이미지 생성 SSE (온디맨드, 버튼 클릭 시 호출)
     * <p>
     * S3_IMAGE 프롬프트(LLM)로 {@code imageQuery}를 생성하고,
     * {@link #buildStyleManifest}와 styleParams를 조합한 IMAGE_GEN_HINT로
     * 이미지 생성 API를 호출한다.
     * <p>
     * SSE 단계: connected → progress(llm) → progress(parse) → progress(image_gen) → done
     *
     * @param slideId 생성 대상 슬라이드 ID (TB_PT_SLIDE.SLIDE_ID)
     * @param modelId LLM 모델 ID
     * @param agentId 에이전트 ID
     */
    public SseEmitter streamGenerateSlideImage(String slideId, String modelId, String agentId) {
        SseEmitter emitter = new SseEmitter(0L);

        if (CommonUtil.isEmpty(slideId)) {
            sendSseEvent(emitter, "error", "{\"message\":\"slideId가 없습니다.\"}");
            emitter.complete();
            return emitter;
        }

        emitter.onTimeout(() -> {
            logger.warn("[PT Img-Gen SSE] timeout - slideId={}", slideId);
            emitter.complete();
        });
        emitter.onError(e -> logger.warn("[PT Img-Gen SSE] error - slideId={}, msg={}", slideId, e.getMessage()));
        emitter.onCompletion(() -> logger.info("[PT Img-Gen SSE] complete - slideId={}", slideId));

        sendSseEvent(emitter, "connected", "{\"slideId\":\"" + slideId + "\"}");

        STAGE_D_EXECUTOR.execute(() -> {
            try {
                // ── 1. 슬라이드 조회 ──────────────────────────────────────────
                ProposalVO.SlideVO query = new ProposalVO.SlideVO();
                query.setSlideId(slideId);
                ProposalVO.SlideVO slide = proposalDAO.selectSlideById(query);
                if (slide == null) {
                    Map<String, Object> notFound = new HashMap<>();
                    notFound.put("success", false);
                    notFound.put("renderStatusCd", SLIDE_RENDER_FAIL);
                    notFound.put("errorMessage", "슬라이드를 찾을 수 없습니다.");
                    sendSseEvent(emitter, "done", GSON.toJson(notFound));
                    emitter.complete();
                    return;
                }
                String ptProjectId = slide.getPtProjectId();

                // ── 2. PROJECT_CONFIG_JSON → docSize, colors, writingStyle ───
                String configJson   = proposalDAO.selectProjectConfigJson(ptProjectId);
                String docSize      = "169";
                List<String> baseColorList   = new ArrayList<>();
                List<String> accentColorList = new ArrayList<>();
                String writingStyle = "formal";

                if (CommonUtil.isNotEmpty(configJson)) {
                    try {
                        JsonObject cfgRoot = JsonParser.parseString(configJson).getAsJsonObject();
                        if (cfgRoot.has("template") && !cfgRoot.get("template").isJsonNull()) {
                            JsonObject tmpl = cfgRoot.getAsJsonObject("template");
                            if (tmpl.has("docSize") && !tmpl.get("docSize").isJsonNull()) {
                                docSize = tmpl.get("docSize").getAsString();
                            }
                        }
                        if (cfgRoot.has("settings") && !cfgRoot.get("settings").isJsonNull()) {
                            JsonObject settings = cfgRoot.getAsJsonObject("settings");
                            if (settings.has("colors") && !settings.get("colors").isJsonNull()) {
                                JsonObject colors = settings.getAsJsonObject("colors");
                                if (colors.has("base")   && !colors.get("base").isJsonNull())   baseColorList   = jsonArrayToList(colors.getAsJsonArray("base"));
                                if (colors.has("accent") && !colors.get("accent").isJsonNull()) accentColorList = jsonArrayToList(colors.getAsJsonArray("accent"));
                            }
                            String ws = getStrOrNull(settings, "writingStyle");
                            if (CommonUtil.isNotEmpty(ws)) writingStyle = ws;
                        }
                    } catch (Exception e) {
                        logger.warn("[PT Img-Gen SSE] configJson 파싱 실패 (slideId={}): {}", slideId, e.getMessage());
                    }
                }

                // COLOR_INDEX % baseColorList.size() → baseColor 선택
                String baseColor   = baseColorList.isEmpty()   ? "#5B4FE9" : baseColorList.get(slide.getColorIndex() % baseColorList.size());
                String accentColor = accentColorList.isEmpty() ? "#E08A2C" : accentColorList.get(0);

                // ── 3. RENDER_STATUS_CD = '002' (생성중) 선행 업데이트 ───────
                ProposalVO.SlideVO startVO = new ProposalVO.SlideVO();
                startVO.setSlideId(slideId);
                startVO.setRenderStatusCd("002");
                proposalDAO.updateSlide(startVO);

                // ── 4. S3_IMAGE 프롬프트 조회 ─────────────────────────────────
                sendSseEvent(emitter, "progress", "{\"step\":\"llm\"}");
                String promptContent = null;
                try {
                    promptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S3_IMAGE");
                } catch (Exception e) {
                    logger.warn("[PT Img-Gen SSE] S3_IMAGE 프롬프트 조회 실패: {}", e.getMessage());
                }
                if (CommonUtil.isEmpty(promptContent)) {
                    ProposalVO.SlideVO failVO = new ProposalVO.SlideVO();
                    failVO.setSlideId(slideId);
                    failVO.setRenderStatusCd(SLIDE_RENDER_FAIL);
                    proposalDAO.updateSlide(failVO);
                    Map<String, Object> failDone = new HashMap<>();
                    failDone.put("success", false);
                    failDone.put("renderStatusCd", SLIDE_RENDER_FAIL);
                    failDone.put("errorMessage", "S3_IMAGE 프롬프트가 등록되어 있지 않습니다.");
                    sendSseEvent(emitter, "done", GSON.toJson(failDone));
                    emitter.complete();
                    return;
                }

                // ── 5. 슬라이드 데이터 → LLM 컨텍스트 조립 ──────────────────
                // S3_SLIDE가 소목차 데이터를 JSON으로 넘기는 것과 같은 패턴
                JsonObject slideCtx = new JsonObject();
                slideCtx.addProperty("title",            CommonUtil.nullToBlank(slide.getTitleTxt()));
                slideCtx.addProperty("subtitle",         CommonUtil.nullToBlank(slide.getSubtitleTxt()));
                slideCtx.addProperty("highlightBanner",  CommonUtil.nullToBlank(slide.getHighlightBannerTxt()));
                slideCtx.addProperty("conclusionRibbon", CommonUtil.nullToBlank(slide.getConclusionRibbonTxt()));
                if (CommonUtil.isNotEmpty(slide.getComponentsJson())) {
                    try {
                        slideCtx.add("components", JsonParser.parseString(slide.getComponentsJson()));
                    } catch (Exception e) {
                        slideCtx.addProperty("components", slide.getComponentsJson());
                    }
                }
                String fullPrompt = promptContent + "\n\n## 슬라이드 데이터\n" + GSON.toJson(slideCtx);

                // ── 6. LLM 호출 (1회 재시도) ──────────────────────────────────
                String aiResponse = callLlmWithRetry(fullPrompt, modelId, agentId, "[PT Img-Gen SSE]");

                // ── 7. imageQuery 파싱 (1회 재시도) ───────────────────────────
                sendSseEvent(emitter, "progress", "{\"step\":\"parse\"}");
                String imageQuery = null;
                try {
                    imageQuery = parseImageQueryFromLlmResponse(aiResponse);
                } catch (Exception e) {
                    logger.warn("[PT Img-Gen SSE] imageQuery 파싱 실패, 1회 재시도 (slideId={}): {}", slideId, e.getMessage());
                    aiResponse = riskDiagnosisAgentService.callLlmQuerySync(fullPrompt, modelId, "", agentId);
                    try {
                        imageQuery = parseImageQueryFromLlmResponse(aiResponse);
                    } catch (Exception e2) {
                        logger.error("[PT Img-Gen SSE] imageQuery 파싱 재시도 실패 (slideId={}): {}", slideId, e2.getMessage());
                    }
                }
                if (imageQuery == null) {
                    ProposalVO.SlideVO failVO = new ProposalVO.SlideVO();
                    failVO.setSlideId(slideId);
                    failVO.setRenderStatusCd(SLIDE_RENDER_FAIL);
                    proposalDAO.updateSlide(failVO);
                    Map<String, Object> failDone = new HashMap<>();
                    failDone.put("success", false);
                    failDone.put("renderStatusCd", SLIDE_RENDER_FAIL);
                    failDone.put("errorMessage", "imageQuery 파싱에 실패했습니다.");
                    sendSseEvent(emitter, "done", GSON.toJson(failDone));
                    emitter.complete();
                    return;
                }

                // ── 8. IMAGE_GEN_HINT 조립 ────────────────────────────────────
                sendSseEvent(emitter, "progress", "{\"step\":\"image_gen\"}");
                String styleManifest = buildStyleManifest(baseColor, accentColor, docSize, writingStyle);
                String styleParams   = String.format("docSize=%s baseColor=%s accentColor=%s writingStyle=%s colorIndex=%d",
                        docSize, baseColor, accentColor, writingStyle, slide.getColorIndex());
                String imageGenHint  = styleManifest + " " + imageQuery + " | " + styleParams;

                // ── 9. 이미지 생성 API 호출 ───────────────────────────────────
                String base64Image = callPtImageApi(imageGenHint);
                if (base64Image == null || base64Image.isEmpty()) {
                    logger.warn("[PT Img-Gen SSE] 이미지 API 응답 없음 (slideId={})", slideId);
                    ProposalVO.SlideVO failVO = new ProposalVO.SlideVO();
                    failVO.setSlideId(slideId);
                    failVO.setRenderStatusCd(SLIDE_RENDER_FAIL);
                    proposalDAO.updateSlide(failVO);
                    Map<String, Object> failDone = new HashMap<>();
                    failDone.put("success", false);
                    failDone.put("renderStatusCd", SLIDE_RENDER_FAIL);
                    failDone.put("errorMessage", "이미지 생성 API 응답이 없습니다.");
                    sendSseEvent(emitter, "done", GSON.toJson(failDone));
                    emitter.complete();
                    return;
                }

                // ── 10. NCP 업로드 ────────────────────────────────────────────
                byte[] imageBytes   = Base64.getDecoder().decode(base64Image);
                String renderedPath = uploadSlideImageToNcp(ptProjectId, slideId, imageBytes);

                // ── 11. DB 저장 (IMAGE_GEN_HINT, RENDERED_IMAGE_PATH, 완료) ───
                ProposalVO.SlideVO doneVO = new ProposalVO.SlideVO();
                doneVO.setSlideId(slideId);
                doneVO.setImageGenHint(imageGenHint);
                doneVO.setRenderedImagePath(renderedPath);
                doneVO.setRenderStatusCd(SLIDE_RENDER_DONE);

                // 템플릿 프레임 합성 이미지 (Step D 미리보기용) — doImageRender와 동일 패턴
                try {
                    ProposalVO.PtTemplateVO tmpl = proposalDAO.selectPtTemplate(ptProjectId);
                    if (tmpl != null && tmpl.getFrameImagePath() != null) {
                        byte[] frameBytes   = downloadNcpObject(tmpl.getFrameImagePath());
                        byte[] composite    = stackFrameWithContent(frameBytes, imageBytes);
                        String compositeKey = "proposal/" + ptProjectId + "/slide-images/" + slideId + "_composite.png";
                        uploadNcpObject(compositeKey, composite);
                        doneVO.setCompositeImagePath(compositeKey);
                        logger.info("[PT Img-Gen SSE] 합성 이미지 저장 완료 (slideId={}, key={})", slideId, compositeKey);
                    }
                } catch (Exception ex) {
                    logger.warn("[PT Img-Gen SSE] 합성 이미지 생성 실패 — 원본만 저장 (slideId={}): {}", slideId, ex.getMessage());
                }

                proposalDAO.updateSlide(doneVO);
                logger.info("[PT Img-Gen SSE] 이미지 생성 완료 (slideId={}, path={})", slideId, renderedPath);

                // ── 12. done (성공) ───────────────────────────────────────────
                Map<String, Object> successDone = new HashMap<>();
                successDone.put("success", true);
                successDone.put("renderStatusCd", SLIDE_RENDER_DONE);
                successDone.put("renderedImagePath", renderedPath);
                sendSseEvent(emitter, "done", GSON.toJson(successDone));

            } catch (Exception e) {
                logger.error("[PT Img-Gen SSE] 이미지 생성 오류 (slideId={}): {}", slideId, e.getMessage(), e);
                try {
                    ProposalVO.SlideVO failVO = new ProposalVO.SlideVO();
                    failVO.setSlideId(slideId);
                    failVO.setRenderStatusCd(SLIDE_RENDER_FAIL);
                    proposalDAO.updateSlide(failVO);
                } catch (Exception ex) {
                    logger.warn("[PT Img-Gen SSE] 실패 상태 업데이트 오류 (slideId={}): {}", slideId, ex.getMessage());
                }
                Map<String, Object> failDone = new HashMap<>();
                failDone.put("success", false);
                failDone.put("renderStatusCd", SLIDE_RENDER_FAIL);
                failDone.put("errorMessage", "이미지 생성 중 오류가 발생했습니다.");
                sendSseEvent(emitter, "done", GSON.toJson(failDone));
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * LLM 응답에서 {@code imageQuery} 값을 파싱한다.
     * 마크다운 코드 블록(```json ... ```)을 자동으로 제거한다.
     *
     * @param aiResponse LLM 응답 원문
     * @return imageQuery 문자열
     * @throws RuntimeException 파싱 실패 또는 키 없음
     */
    private String parseImageQueryFromLlmResponse(String aiResponse) {
        if (CommonUtil.isEmpty(aiResponse)) throw new RuntimeException("aiResponse 가 비어 있음");
        String cleaned = aiResponse.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence    = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        JsonObject obj = JsonParser.parseString(cleaned).getAsJsonObject();
        if (!obj.has("imageQuery") || obj.get("imageQuery").isJsonNull()) {
            throw new RuntimeException("imageQuery 키 없음");
        }
        return obj.get("imageQuery").getAsString();
    }

    /**
     * 슬라이드 단건 이미지 생성 (D-5)
     * IMAGE_GEN_HINT를 image API에 전달해 base64 이미지를 받고, NCP에 업로드 후 URL을 반환한다.
     * IMAGE_GEN_HINT는 온디맨드 이미지 생성 시점에 조립되어 저장된다.
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
                        String compositeKey = "proposal/" + slide.getPtProjectId() + "/slide-images/" + slide.getSlideId() + "_composite.png";
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
     * @param imageGenHint 이미지 생성 힌트 문자열
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
        params.put("quality", "high");
        params.put("room_id", "");
        params.put("aspect_ratio", aspectRatio);

        String reqParamJson = GSON.toJson(params);
        long startMs = System.currentTimeMillis();

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(200, TimeUnit.SECONDS)
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
     * 표지 배경 이미지 presigned URL 조회 (미리보기용).
     * TB_PT_TEMPLATE.COVER_IMAGE_PATH(objectKey)를 읽어 FileService로 presigned URL 생성.
     */
    public Map<String, Object> viewCoverImage(String ptProjectId) throws Exception {
        ProposalVO.PtTemplateVO template = proposalDAO.selectPtTemplate(ptProjectId);
        Map<String, Object> notFound = new HashMap<>();
        notFound.put("viewType", "DOWNLOAD");
        notFound.put("reason", "FILE_NOT_FOUND");
        notFound.put("url", "");

        if (template == null || CommonUtil.isEmpty(template.getCoverImagePath())) {
            return notFound;
        }

        FileVO fileVo = new FileVO();
        fileVo.setFilePath(template.getCoverImagePath());
        fileVo.setFileName("cover.png");
        fileVo.setFileType("image/png");
        return fileService.createViewPresignedUrlForStorageObject(fileVo);
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
     * 저장 경로: proposal/{ptProjectId}/slide-images/{slideId}.png
     */
    private String uploadSlideImageToNcp(String ptProjectId, String slideId, byte[] imageBytes) {
        String bucket = PropertyUtil.getProperty("ncp.storage.bucket");
        String objectKey = "proposal/" + ptProjectId + "/slide-images/" + slideId + ".png";

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
        String objectKey  = "proposal/" + ptProjectId + "/template-images/frame.png";
        uploadNcpObject(objectKey, imageBytes);

        // 4. FRAME_IMAGE_PATH DB 저장
        ProposalVO.PtTemplateVO patch = new ProposalVO.PtTemplateVO();
        patch.setPtProjectId(ptProjectId);
        patch.setFrameImagePath(objectKey);
        patch.setModifyUserId(template.getModifyUserId() != null ? template.getModifyUserId() : SessionUtil.getUserId());
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

    // ── 표지 이미지 생성 ──────────────────────────────────────────────────────────

    /**
     * TB_PROMPT(STAGE_CD='S3_COVER_TEMPLATE') 텍스트를 조회하고,
     * TB_PT_PROJECT + PROJECT_CONFIG_JSON 값으로 {{}} 플레이스홀더를 치환해 최종 프롬프트를 반환한다.
     *
     * <p>LLM 호출 없음 — 치환된 텍스트를 callPtImageApi에 직접 전달하기 위한 용도.
     */
    private String buildCoverPrompt(String ptProjectId, String agentId) {
        String projectNm      = "";
        String orgNm          = "";
        String submissionDate = "";
        String companyNm      = "";
        String writingStyle   = "formal";
        String baseColor      = "\"#5B4FE9\",\"#8B7FFF\",\"#EFECFE\"";
        String accentColor    = "\"#E08A2C\",\"#22A06B\"";
        String docSize        = "169";

        try {
            ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
            if (project != null) {
                projectNm      = CommonUtil.nullToBlank(project.getProjectNm());
                orgNm          = CommonUtil.nullToBlank(project.getOrgNm());
                submissionDate = CommonUtil.nullToBlank(project.getDueDt());

                String configJson = project.getProjectConfigJson();
                if (CommonUtil.isNotEmpty(configJson)) {
                    JsonObject cfgRoot = JsonParser.parseString(configJson).getAsJsonObject();

                    if (cfgRoot.has("template") && !cfgRoot.get("template").isJsonNull()) {
                        JsonObject tmpl = cfgRoot.getAsJsonObject("template");
                        String ds = getStrOrNull(tmpl, "docSize");
                        if (CommonUtil.isNotEmpty(ds)) docSize = ds;
                    }

                    if (cfgRoot.has("settings") && !cfgRoot.get("settings").isJsonNull()) {
                        JsonObject settings = cfgRoot.getAsJsonObject("settings");

                        String sn = getStrOrNull(settings, "submitterNm");
                        if (CommonUtil.isNotEmpty(sn)) companyNm = sn;

                        String ws = getStrOrNull(settings, "writingStyle");
                        if (CommonUtil.isNotEmpty(ws)) writingStyle = ws;

                        if (settings.has("colors") && !settings.get("colors").isJsonNull()) {
                            JsonObject colors = settings.getAsJsonObject("colors");
                            List<String> bases   = colors.has("base")   && !colors.get("base").isJsonNull()   ? jsonArrayToList(colors.getAsJsonArray("base"))   : java.util.Collections.emptyList();
                            List<String> accents = colors.has("accent") && !colors.get("accent").isJsonNull() ? jsonArrayToList(colors.getAsJsonArray("accent")) : java.util.Collections.emptyList();
                            if (!bases.isEmpty())   baseColor   = bases.stream().map(c -> "\"" + c + "\"").collect(java.util.stream.Collectors.joining(","));
                            if (!accents.isEmpty()) accentColor = accents.stream().map(c -> "\"" + c + "\"").collect(java.util.stream.Collectors.joining(","));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("[PT Cover] buildCoverPrompt 파싱 실패, 기본값 사용 (ptProjectId={}): {}", ptProjectId, e.getMessage());
        }

        // safe fallback
        String safeProject  = projectNm.isEmpty()      ? "제안서"   : projectNm;
        String safeOrg      = orgNm.isEmpty()           ? "발주기관" : orgNm;
        String safeCompany  = companyNm.isEmpty()       ? "제안사"   : companyNm;
        String safeDate     = submissionDate.isEmpty()  ? ""         : submissionDate;
        String safeStyle    = writingStyle.isEmpty()    ? "formal"   : writingStyle;

        // TB_PROMPT에서 STAGE_CD='S3_COVER_TEMPLATE' 텍스트 조회
        String promptTemplate = null;
        try {
            promptTemplate = promptService.getPromptsByAgentIdAndStageCd(agentId, "S3_COVER_TEMPLATE");
        } catch (Exception e) {
            logger.warn("[PT Cover] TB_PROMPT 'S3_COVER_TEMPLATE' 조회 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
        }
        if (CommonUtil.isEmpty(promptTemplate)) {
            throw new RuntimeException("표지 이미지 프롬프트가 등록되어 있지 않습니다. TB_PROMPT에 STAGE_CD='S3_COVER_TEMPLATE'인 프롬프트를 등록해 주세요.");
        }

        // {{}} 플레이스홀더 치환 (String.replace — LLM 지시문 방식 사용 금지)
        String prompt = promptTemplate
                .replace("{{projectNm}}",      safeProject)
                .replace("{{orgNm}}",          safeOrg)
                .replace("{{companyNm}}",      safeCompany)
                .replace("{{submissionDate}}", safeDate)
                .replace("{{docSize}}",        docSize)
                .replace("{{baseColor}}",      baseColor)
                .replace("{{accentColor}}",    accentColor)
                .replace("{{writingStyle}}",   safeStyle);

        // callPtImageApi 내부 정규식(docSize=(\S+))과 호환되는 마커 append
        return prompt + " | docSize=" + docSize;
    }

    /**
     * 간지 재사용 배경 이미지 프롬프트를 빌드한다.
     *
     * <p>본문형 프레임과 동일하게 프로젝트 공통 배경 1장용이다.
     * TB_PROMPT STAGE_CD='S3_DIVIDER_TEMPLATE'를 조회하고,
     * 사업정보·색상·docSize만 치환한다.
     * 대목차번호/명/하위목차는 문서 출력 시 오버레이하므로 이미지에 넣지 않는다.
     */
    private String buildDividerPrompt(String ptProjectId, String agentId) {
        String projectNm   = "제안서";
        String orgNm       = "발주기관";
        String baseColor   = "\"#5B4FE9\",\"#8B7FFF\",\"#EFECFE\"";
        String accentColor = "\"#E08A2C\",\"#22A06B\"";
        String docSize     = "169";

        try {
            ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
            if (project != null) {
                if (CommonUtil.isNotEmpty(project.getProjectNm())) projectNm = project.getProjectNm();
                if (CommonUtil.isNotEmpty(project.getOrgNm()))     orgNm     = project.getOrgNm();

                String configJson = project.getProjectConfigJson();
                if (CommonUtil.isNotEmpty(configJson)) {
                    JsonObject cfgRoot = JsonParser.parseString(configJson).getAsJsonObject();

                    if (cfgRoot.has("template") && !cfgRoot.get("template").isJsonNull()) {
                        JsonObject tmpl = cfgRoot.getAsJsonObject("template");
                        String ds = getStrOrNull(tmpl, "docSize");
                        if (CommonUtil.isNotEmpty(ds)) docSize = ds;
                    }

                    if (cfgRoot.has("settings") && !cfgRoot.get("settings").isJsonNull()) {
                        JsonObject settings = cfgRoot.getAsJsonObject("settings");
                        if (settings.has("colors") && !settings.get("colors").isJsonNull()) {
                            JsonObject colors = settings.getAsJsonObject("colors");
                            List<String> bases   = colors.has("base")   && !colors.get("base").isJsonNull()
                                    ? jsonArrayToList(colors.getAsJsonArray("base")) : java.util.Collections.emptyList();
                            List<String> accents = colors.has("accent") && !colors.get("accent").isJsonNull()
                                    ? jsonArrayToList(colors.getAsJsonArray("accent")) : java.util.Collections.emptyList();
                            if (!bases.isEmpty()) {
                                baseColor = bases.stream().map(c -> "\"" + c + "\"")
                                        .collect(java.util.stream.Collectors.joining(","));
                            }
                            if (!accents.isEmpty()) {
                                accentColor = accents.stream().map(c -> "\"" + c + "\"")
                                        .collect(java.util.stream.Collectors.joining(","));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("[PT Divider] buildDividerPrompt 파싱 실패, 기본값 사용 (ptProjectId={}): {}", ptProjectId, e.getMessage());
        }

        String promptTemplate = null;
        try {
            promptTemplate = promptService.getPromptsByAgentIdAndStageCd(agentId, "S3_DIVIDER_TEMPLATE");
        } catch (Exception e) {
            logger.warn("[PT Divider] TB_PROMPT 'S3_DIVIDER_TEMPLATE' 조회 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
        }
        if (CommonUtil.isEmpty(promptTemplate)) {
            throw new RuntimeException("간지 이미지 프롬프트가 등록되어 있지 않습니다. TB_PROMPT에 STAGE_CD='S3_DIVIDER_TEMPLATE'인 프롬프트를 등록해 주세요.");
        }

        // 본문형 프레임과 동일: 이미지는 배경·여백만. 텍스트는 export 시 PPTX 오버레이로 치환.
        // {{chapter*}} 자리에 {chapter_no} 문자열을 넣으면 이미지 모델이 그대로 그려 export와 겹친다.
        String prompt = promptTemplate
                .replace("{{projectNm}}",  projectNm)
                .replace("{{orgNm}}",      orgNm)
                .replace("{{docSize}}",    docSize)
                .replace("{{baseColor}}",  baseColor)
                .replace("{{accentColor}}", accentColor)
                .replace("{{chapterNo}}",  "")
                .replace("{{chapterNm}}",  "")
                .replace("{{subTocList}}", "");

        prompt += "\n\n## 재사용 배경 제약 (본문형 프레임과 동일 — 텍스트는 출력 단계에서 오버레이)"
                + "\n- 이 이미지는 대목차마다 동일하게 재사용되는 배경이다."
                + "\n- 이미지에 글자를 절대 그리지 마세요. 한글·영문·숫자·기호 모두 금지."
                + "\n- {chapter_no}, {chapter_title}, {sub_toc_list}, {{chapterNo}} 같은 플레이스홀더 문자열도 그리지 마세요."
                + "\n- 01~06 번호 배지, 점선 리스트 항목, 예시 목차 문구도 그리지 마세요."
                + "\n- 좌측 중앙에 대목차번호·대목차명용 빈 여백, 우측에 하위목차 리스트용 빈 여백만 확보하세요."
                + "\n- 그래픽 모티브·색상 체계만 표현하고, 텍스트는 비워 두세요."
                + "\n- 로고·인장·실존 기관 마크를 생성하지 마세요.";

        return prompt + " | docSize=" + docSize;
    }

    /**
     * 표지 배경 이미지를 생성하고 NCP에 업로드한 뒤 DB를 갱신한다.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>COVER_GEN_STATUS_CD = '002' (생성중) 설정</li>
     *   <li>buildCoverPrompt → callPtImageApi 호출</li>
     *   <li>실패: COVER_GEN_STATUS_CD = '004', 기존 이미지 경로 보존, return</li>
     *   <li>성공: NCP 업로드 → COVER_IMAGE_PATH + COVER_GEN_STATUS_CD = '003' 갱신</li>
     * </ol>
     */
    private void generateCoverImage(String ptProjectId, String agentId, String requestType, String message) {
        logger.info("[PT Cover] 표지 이미지 생성 시작 (ptProjectId={})", ptProjectId);
        // 1. 생성중(002) 상태 설정
        ProposalVO.PtTemplateVO statusVO = new ProposalVO.PtTemplateVO();
        statusVO.setPtProjectId(ptProjectId);
        statusVO.setCoverGenStatusCd("002");
        statusVO.setModifyUserId(SessionUtil.getUserId());
        proposalDAO.updateTemplateCoverStatus(statusVO);

        // 2. 프롬프트 빌드
        String prompt;
        try {
            prompt = buildCoverPrompt(ptProjectId, agentId);
            if("complement_request".equals(requestType)) {
                prompt += "\n\n## 추가 반영 요청사항";
                prompt += "\n" + message;
            }
        } catch (Exception e) {
            logger.warn("[PT Cover] 프롬프트 빌드 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
            statusVO.setCoverGenStatusCd("004");
            proposalDAO.updateTemplateCoverStatus(statusVO);
            return;
        }

        // 3. 이미지 API 호출 (기존 callPtImageApi 재사용 — 시그니처 변경 없음)
        String base64Image = callPtImageApi(prompt);
        if (base64Image == null || base64Image.isEmpty()) {
            logger.warn("[PT Cover] 이미지 API 응답 없음 (ptProjectId={})", ptProjectId);
            statusVO.setCoverGenStatusCd("004");
            proposalDAO.updateTemplateCoverStatus(statusVO);
            return;
        }

        // 4. Base64 디코딩 → NCP 업로드
        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
        String objectKey  = "proposal/" + ptProjectId + "/cover-images/cover.png";
        uploadNcpObject(objectKey, imageBytes);

        // 5. COVER_IMAGE_PATH + 완료(003) 상태 갱신
        ProposalVO.PtTemplateVO pathVO = new ProposalVO.PtTemplateVO();
        pathVO.setPtProjectId(ptProjectId);
        pathVO.setCoverImagePath(objectKey);
        pathVO.setCoverGenStatusCd("003");
        pathVO.setModifyUserId(statusVO.getModifyUserId());
        proposalDAO.updateTemplateCoverPath(pathVO);

        logger.info("[PT Cover] 표지 이미지 저장 완료 (ptProjectId={}, key={})", ptProjectId, objectKey);
    }

    /**
     * 간지 재사용 배경 이미지를 생성하고 NCP에 업로드한 뒤 TB_PT_TEMPLATE을 갱신한다.
     *
     * <p>본문형 FRAME_IMAGE_PATH와 동일하게 프로젝트당 1장을 저장한다.
     * 대목차 텍스트는 문서 빌드 시 플레이스홀더 치환으로 오버레이한다.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>DIVIDER_GEN_STATUS_CD = '002' (생성중) 설정</li>
     *   <li>buildDividerPrompt → callPtImageApi 호출</li>
     *   <li>실패: DIVIDER_GEN_STATUS_CD = '004', return</li>
     *   <li>성공: NCP 업로드 → DIVIDER_IMAGE_PATH + DIVIDER_GEN_STATUS_CD = '003' 갱신</li>
     * </ol>
     */
    private void generateDividerImage(String ptProjectId, String agentId, String requestType, String message) {
        logger.info("[PT Divider] 간지 이미지 생성 시작 (ptProjectId={})", ptProjectId);
        // 1. 생성중(002) 상태 설정
        ProposalVO.PtTemplateVO statusVO = new ProposalVO.PtTemplateVO();
        statusVO.setPtProjectId(ptProjectId);
        statusVO.setDividerGenStatusCd("002");
        statusVO.setModifyUserId(SessionUtil.getUserId());
        proposalDAO.updateTemplateDividerStatus(statusVO);

        // 2. 프롬프트 빌드
        String prompt;
        try {
            prompt = buildDividerPrompt(ptProjectId, agentId);
            if ("complement_request".equals(requestType)) {
                prompt += "\n\n## 추가 반영 요청사항";
                prompt += "\n" + message;
            }
        } catch (Exception e) {
            logger.warn("[PT Divider] 프롬프트 빌드 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
            statusVO.setDividerGenStatusCd("004");
            proposalDAO.updateTemplateDividerStatus(statusVO);
            return;
        }

        // 3. 이미지 API 호출 (표지와 동일한 callPtImageApi 재사용)
        String base64Image = callPtImageApi(prompt);
        if (base64Image == null || base64Image.isEmpty()) {
            logger.warn("[PT Divider] 이미지 API 응답 없음 (ptProjectId={})", ptProjectId);
            statusVO.setDividerGenStatusCd("004");
            proposalDAO.updateTemplateDividerStatus(statusVO);
            return;
        }

        // 4. Base64 디코딩 → NCP 업로드
        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
        String objectKey  = "proposal/" + ptProjectId + "/divider-images/divider.png";
        uploadNcpObject(objectKey, imageBytes);

        // 5. TB_PT_TEMPLATE.DIVIDER_IMAGE_PATH + 완료(003) 상태 갱신
        ProposalVO.PtTemplateVO pathVO = new ProposalVO.PtTemplateVO();
        pathVO.setPtProjectId(ptProjectId);
        pathVO.setDividerImagePath(objectKey);
        pathVO.setDividerGenStatusCd("003");
        pathVO.setModifyUserId(statusVO.getModifyUserId());
        proposalDAO.updateTemplateDividerPath(pathVO);

        logger.info("[PT Divider] 간지 이미지 저장 완료 (ptProjectId={}, key={})", ptProjectId, objectKey);
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

        // 최종 캔버스는 프레임 크기로 고정 (캔버스 크기 변경 금지)
        BufferedImage canvas = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);

        // 1) 흰 배경
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, fw, fh);

        // 2) 프레임 전체를 캔버스에 배치
        g.drawImage(frame, 0, 0, fw, fh, null);

        // 3) 콘텐츠 영역 계산 (헤더 9%, 푸터 5% 제외)
        int headerH  = Math.max(1, (int) (fh * 0.09));
        int footerH  = Math.max(1, (int) (fh * 0.05));
        int contentY = headerH;
        int contentH = fh - headerH - footerH;

        // 4) 콘텐츠를 비율 유지(letterbox)로 배치
        int cw = content.getWidth();
        int ch = content.getHeight();
        double zoneAspect = (double) fw / contentH;
        double imgAspect  = (double) cw / ch;
        int destW, destH, destX, destY;
        if (imgAspect >= zoneAspect) {
            destW = fw;
            destH = (int) Math.round((double) fw / imgAspect);
            destX = 0;
            destY = contentY + (contentH - destH) / 2;
        } else {
            destH = contentH;
            destW = (int) Math.round((double) contentH * imgAspect);
            destX = (fw - destW) / 2;
            destY = contentY;
        }

        // 콘텐츠 영역 밖으로 넘치지 않도록 클립 적용
        java.awt.Shape oldClip = g.getClip();
        g.setClip(new java.awt.Rectangle(0, contentY, fw, contentH));
        g.drawImage(content, destX, destY, destW, destH, null);
        g.setClip(oldClip);

        // 5) 프레임 재오버레이: 헤더·푸터가 콘텐츠 위에 항상 보이도록
        g.drawImage(frame, 0, 0,           fw, headerH,         0, 0,           fw, headerH,         null);
        g.drawImage(frame, 0, fh - footerH, fw, fh,             0, fh - footerH, fw, fh,             null);

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
            vo.setModifyUserId(SessionUtil.getUserId());
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
            String configJson,
            List<ProposalVO.SiblingSlideVO> siblingSlides) {

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
        if (CommonUtil.isNotEmpty(tocVO.getGuideContent())) {
            sb.append("\n- RFP 세부 작성 지침: ").append(tocVO.getGuideContent());
        }

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

        appendSiblingContext(sb, siblingSlides);

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
            v.setReqCategoryTxt(r.getReqCategoryTxt());
            v.setReqContent(r.getReqContent());
            // reqDetailTxt: RFP 상세 원문 — Stage3 호출당 요구사항 1~4건 수준이므로 truncate 없이 원문 전달
            // (최대 4건 × 1,200자 ≈ 4,800자, 다른 섹션 포함해도 토큰 예산 내 관리 가능)
            v.setReqDetailTxt(r.getReqDetailTxt());
            v.setMandatoryYn(r.getMandatoryYn());
            v.setSourceTypeCd(r.getSourceTypeCd());
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
            v.setGoal(p.getGoal());
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
     * 1. forceRebuild=true 이면 캐시 무시하고 신규 빌드
     * 2. 캐시 재사용 판단: 최근 완료(004) 빌드의 INPUT_FINGERPRINT vs 현재 빌드 입력 지문
     *    → 일치하고 NCP 파일이 있으면 presigned URL 발급 후 즉시 반환 (cacheReused=true)
     * 3. 신규 빌드: TB_PT_EXPORT row 생성 → 비동기 빌드 시작 → exportId 즉시 반환
     *
     * @param vo ptProjectId, agentId, forceRebuild
     * @return ExportVO (캐시 재사용 시 즉시 완료, 신규 빌드 시 BUILD_STATUS_CD='003')
     */
    public ProposalVO.ExportVO startExport(ProposalVO.ExportRequestVO vo) throws Exception {
        String ptProjectId = vo.getPtProjectId();

        // 1. docSize 기반 내보내기 형식 코드 결정 (a4 → "002"/PDF, 그 외 → "001"/PPTX)
        String exportTypeCd = resolveExportTypeCd(ptProjectId);

        // 2. 캐시 재사용 (forceRebuild가 아닐 때만)
        if (!Boolean.TRUE.equals(vo.getForceRebuild())) {
            ProposalVO.ExportVO cached = tryReuseCachedExport(ptProjectId, exportTypeCd);
            if (cached != null) {
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
        exportVO.setCacheReused(Boolean.FALSE);
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
     * F — 지문이 일치하는 최근 완료(004) 출력 조회 (이전 파일 받기용).
     * 없으면 null.
     */
    public ProposalVO.ExportVO selectReusableExport(String ptProjectId) {
        String exportTypeCd = resolveExportTypeCd(ptProjectId);
        return tryReuseCachedExport(ptProjectId, exportTypeCd);
    }

    /**
     * 최근 완료 export의 INPUT_FINGERPRINT가 현재 빌드 입력과 같고 NCP 파일이 있으면
     * presigned URL을 붙여 반환. 아니면 null.
     */
    private ProposalVO.ExportVO tryReuseCachedExport(String ptProjectId, String exportTypeCd) {
        ProposalVO.ExportVO cached = proposalDAO.selectLatestCompletedExport(ptProjectId, exportTypeCd);
        if (cached == null
                || CommonUtil.isEmpty(cached.getFilePath())
                || CommonUtil.isEmpty(cached.getInputFingerprint())) {
            return null;
        }

        String currentFp = buildExportInputFingerprint(ptProjectId, exportTypeCd);
        if (CommonUtil.isEmpty(currentFp) || !currentFp.equals(cached.getInputFingerprint())) {
            return null;
        }

        try {
            String bucket = PropertyUtil.getProperty("ncp.storage.bucket");
            if (!amazonS3.doesObjectExist(bucket, cached.getFilePath())) {
                logger.warn("[PT F] 캐시 파일 NCP 없음 (key={})", cached.getFilePath());
                return null;
            }
        } catch (Exception e) {
            logger.warn("[PT F] 캐시 NCP 존재 확인 실패: {}", e.getMessage());
            return null;
        }

        logger.info("[PT F] 캐시 재사용 (ptProjectId={}, exportTypeCd={}, exportId={})",
                ptProjectId, exportTypeCd, cached.getExportId());
        try {
            String downloadUrl = fileService.createDownloadPresignedUrlStr(
                    cached.getFilePath(), cached.getFileNm());
            cached.setDownloadUrl(downloadUrl);
        } catch (Exception e) {
            logger.warn("[PT F] 캐시 presigned URL 발급 실패: {}", e.getMessage());
        }
        cached.setCacheReused(Boolean.TRUE);
        return cached;
    }

    /**
     * 출력 빌드 입력 지문(SHA-256 hex) 생성.
     * runExportBuild가 읽는 값만 포함 (프레임 이미지·maxStepNo·Stage2 상태 제외).
     */
    private String buildExportInputFingerprint(String ptProjectId, String exportTypeCd) {
        try {
            StringBuilder sb = new StringBuilder(4096);
            sb.append("exportTypeCd=").append(nullToEmpty(exportTypeCd)).append('\n');

            ProposalVO.ProjectVO project = proposalDAO.selectProject(ptProjectId);
            sb.append("projectNm=").append(project != null ? nullToEmpty(project.getProjectNm()) : "").append('\n');
            sb.append("orgNm=").append(project != null ? nullToEmpty(project.getOrgNm()) : "").append('\n');
            sb.append("config=").append(nullToEmpty(proposalDAO.selectProjectConfigJson(ptProjectId))).append('\n');

            ProposalVO.PtTemplateVO tmpl = proposalDAO.selectPtTemplate(ptProjectId);
            if (tmpl != null) {
                sb.append("header=").append(nullToEmpty(tmpl.getHeaderComponentsJson())).append('\n');
                sb.append("footer=").append(nullToEmpty(tmpl.getFooterComponentsJson())).append('\n');
                sb.append("color=").append(nullToEmpty(tmpl.getColorJson())).append('\n');
                sb.append("cover=").append(nullToEmpty(tmpl.getCoverImagePath())).append('\n');
            } else {
                sb.append("header=\nfooter=\ncolor=\ncover=\n");
            }

            List<ProposalVO.TocVO> tocList = proposalDAO.selectTocList(ptProjectId);
            if (tocList != null) {
                for (ProposalVO.TocVO toc : tocList) {
                    sb.append("toc=")
                            .append(nullToEmpty(toc.getTocId())).append('|')
                            .append(nullToEmpty(toc.getParentTocId())).append('|')
                            .append(nullToEmpty(toc.getSectionNm())).append('|')
                            .append(toc.getSortOrd() != null ? toc.getSortOrd() : 0)
                            .append('\n');
                }
            }

            List<ProposalVO.SlideVO> slides = proposalDAO.selectAllSlidesByProject(ptProjectId);
            if (slides != null) {
                for (ProposalVO.SlideVO s : slides) {
                    sb.append("slide=")
                            .append(nullToEmpty(s.getSlideId())).append('|')
                            .append(s.getSlideNo()).append('|')
                            .append(nullToEmpty(s.getModifyDt())).append('|')
                            .append(nullToEmpty(s.getRenderedImagePath()))
                            .append('\n');
                }
            }

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            logger.warn("[PT F] 지문 계산 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
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
                            if (!accents.isEmpty()) accentColor = accents.get(0);
                        }
                        String sn = getStrOrNull(settings, "submitterNm");
                        if (CommonUtil.isNotEmpty(sn)) submitterNm = sn;
                    }
                } catch (Exception e) {
                    logger.warn("[PT F] configJson 파싱 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage());
                }
            }

            final String submitterNmFinal = submitterNm;
            String projectNm = project != null ? CommonUtil.nullToBlank(project.getProjectNm()) : ptProjectId;
            String orgNm     = project != null ? CommonUtil.nullToBlank(project.getOrgNm())     : "";

            // ── 3. TOC 계층 조회 → 챕터 로마숫자 · 소목차 제목 · 하위목차 맵 ──
            List<ProposalVO.TocVO> tocList = proposalDAO.selectTocList(ptProjectId);
            Map<String, String> tocRomanMap      = new java.util.LinkedHashMap<>();
            Map<String, String> tocTitleMap      = new HashMap<>();
            Map<String, String> tocSectionNoMap  = new HashMap<>();
            Map<String, String> tocParentMap     = new HashMap<>();
            List<String> chapterOrder           = new java.util.ArrayList<>();
            int chapterIdx = 0;
            for (ProposalVO.TocVO toc : tocList) {
                tocTitleMap.put(toc.getTocId(), CommonUtil.nullToBlank(toc.getSectionNm()));
                tocSectionNoMap.put(toc.getTocId(), CommonUtil.nullToBlank(toc.getSectionNo()));
                if (CommonUtil.isEmpty(toc.getParentTocId())) {
                    chapterIdx++;
                    tocRomanMap.put(toc.getTocId(),
                            kr.teamagent.common.util.ProposalPptxUtil.toRomanNumeral(chapterIdx));
                    chapterOrder.add(toc.getTocId());
                } else {
                    tocParentMap.put(toc.getTocId(), toc.getParentTocId());
                }
            }
            // tocId → 대목차 tocId (부모 체인 walk-up)
            Map<String, String> tocToChapterMap = new HashMap<>();
            for (ProposalVO.TocVO toc : tocList) {
                String cur = toc.getTocId();
                while (tocParentMap.containsKey(cur)) {
                    cur = tocParentMap.get(cur);
                }
                tocToChapterMap.put(toc.getTocId(), cur);
            }
            // 대목차 → ORIGIN_TYPE_CD=001 직계 소목차 리스트 문자열
            Map<String, String> chapterSubTocMap = new HashMap<>();
            for (String chapterId : chapterOrder) {
                StringBuilder sb = new StringBuilder();
                for (ProposalVO.TocVO toc : tocList) {
                    if (!chapterId.equals(toc.getParentTocId())) continue;
                    if (!"001".equals(toc.getOriginTypeCd())) continue;
                    String no = CommonUtil.nullToBlank(toc.getSectionNo());
                    String nm = CommonUtil.nullToBlank(toc.getSectionNm());
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(CommonUtil.isNotEmpty(no) ? no : "").append(". ").append(nm);
                }
                chapterSubTocMap.put(chapterId, sb.toString());
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

                // 표지 이미지 완료(003) + 경로 있으면 맨 앞 장에 cover(001)로 삽입 (헤더/푸터 제외·전체 채움)
                if ("003".equals(ptTemplate.getCoverGenStatusCd())
                        && CommonUtil.isNotEmpty(ptTemplate.getCoverImagePath())) {
                    try {
                        byte[] coverBytes = downloadNcpObject(ptTemplate.getCoverImagePath());
                        if (coverBytes != null && coverBytes.length > 0) {
                            pages.add(new kr.teamagent.common.util.ProposalPptxUtil.PageInfo(
                                    coverBytes, "", "", "", projectNm, orgNm, submitterNmFinal, "001"));
                            logger.info("[PT F] 표지 이미지 맨 앞 장 추가 (path={})", ptTemplate.getCoverImagePath());
                        }
                    } catch (Exception e) {
                        logger.warn("[PT F] 표지 이미지 다운로드 실패 (path={}): {}",
                                ptTemplate.getCoverImagePath(), e.getMessage());
                    }
                }

                // 간지 재사용 배경 (본문형 FRAME과 동일 — TEMPLATE 1장)
                byte[] dividerBytes = null;
                if ("003".equals(ptTemplate.getDividerGenStatusCd())
                        && CommonUtil.isNotEmpty(ptTemplate.getDividerImagePath())) {
                    try {
                        dividerBytes = downloadNcpObject(ptTemplate.getDividerImagePath());
                        if (dividerBytes != null && dividerBytes.length > 0) {
                            logger.info("[PT F] 간지 재사용 배경 로드 (path={})", ptTemplate.getDividerImagePath());
                        } else {
                            dividerBytes = null;
                        }
                    } catch (Exception e) {
                        logger.warn("[PT F] 간지 이미지 다운로드 실패 (path={}): {}",
                                ptTemplate.getDividerImagePath(), e.getMessage());
                        dividerBytes = null;
                    }
                }

                // 대목차별 본문 슬라이드 그룹 (간지 주입 순서용) — layoutType=002는 TEMPLATE 간지로 대체하므로 스킵
                Map<String, List<ProposalVO.SlideVO>> slidesByChapter = new java.util.LinkedHashMap<>();
                for (String chapterId : chapterOrder) {
                    slidesByChapter.put(chapterId, new java.util.ArrayList<>());
                }
                List<ProposalVO.SlideVO> orphanSlides = new java.util.ArrayList<>();
                for (ProposalVO.SlideVO s : allSlides) {
                    if ("002".equals(s.getLayoutType()) && dividerBytes != null) {
                        // TEMPLATE 간지 재사용 시 기존 section_divider 슬라이드는 스킵 (중복 방지)
                        continue;
                    }
                    String chapterId = tocToChapterMap.getOrDefault(s.getTocId(), s.getTocId());
                    if (slidesByChapter.containsKey(chapterId)) {
                        slidesByChapter.get(chapterId).add(s);
                    } else {
                        orphanSlides.add(s);
                    }
                }

                java.util.function.Function<ProposalVO.SlideVO, kr.teamagent.common.util.ProposalPptxUtil.PageInfo> toPage =
                        s -> {
                            String tocId     = s.getTocId();
                            String chapterId = tocToChapterMap.getOrDefault(tocId, tocId);
                            String roman     = tocRomanMap.getOrDefault(chapterId, "Ⅰ");
                            String secTitle  = tocTitleMap.getOrDefault(tocId, "");
                            int slideNoInChapter = chapterSlideCount.merge(chapterId, 1, Integer::sum);
                            String pageLabel = roman + "-" + slideNoInChapter;

                            byte[] imageBytes = null;
                            if (CommonUtil.isNotEmpty(s.getRenderedImagePath())) {
                                try {
                                    imageBytes = downloadNcpObject(s.getRenderedImagePath());
                                } catch (Exception e) {
                                    logger.warn("[PT F] 렌더링 이미지 다운로드 실패 (slideId={}, path={}): {}",
                                            s.getSlideId(), s.getRenderedImagePath(), e.getMessage());
                                }
                            }
                            return new kr.teamagent.common.util.ProposalPptxUtil.PageInfo(
                                    imageBytes, roman, secTitle, pageLabel, projectNm, orgNm, submitterNmFinal,
                                    s.getLayoutType());
                        };

                for (String chapterId : chapterOrder) {
                    List<ProposalVO.SlideVO> chapterSlides = slidesByChapter.get(chapterId);
                    if (chapterSlides == null || chapterSlides.isEmpty()) continue;

                    // 대목차 간지 삽입 (TEMPLATE 배경 재사용 + 목차 텍스트 오버레이)
                    if (dividerBytes != null) {
                        String chapterNo = CommonUtil.isNotEmpty(tocSectionNoMap.get(chapterId))
                                ? tocSectionNoMap.get(chapterId)
                                : tocRomanMap.getOrDefault(chapterId, "Ⅰ");
                        String chapterNm = tocTitleMap.getOrDefault(chapterId, "");
                        String subTocList = chapterSubTocMap.getOrDefault(chapterId, "");
                        pages.add(new kr.teamagent.common.util.ProposalPptxUtil.PageInfo(
                                dividerBytes, chapterNo, chapterNm, chapterNo,
                                projectNm, orgNm, submitterNmFinal, "002", subTocList));
                    }

                    for (ProposalVO.SlideVO s : chapterSlides) {
                        pages.add(toPage.apply(s));
                    }
                }
                for (ProposalVO.SlideVO s : orphanSlides) {
                    pages.add(toPage.apply(s));
                }

                // 항상 코드 기반 헤더/푸터 렌더링 사용 (frameImageBytes=null → useFrameImage=false)
                // 프레임 이미지(FRAME_IMAGE_PATH)는 Step D 미리보기 전용이며 실제 출력에는 사용하지 않음
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
                objectKey   = "proposal/" + ptProjectId + "/exports/" + exportId + ".pdf";
                uploadBytes = pdfBytes;
                contentType = "application/pdf";
            } else {
                // PPTX (001)
                fileNm      = ptProjectId + ".pptx";
                objectKey   = "proposal/" + ptProjectId + "/exports/" + exportId + ".pptx";
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
            doneVO.setInputFingerprint(buildExportInputFingerprint(ptProjectId, exportTypeCd));
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
        vo.setModifyUserId(SessionUtil.getUserId());
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

    /**
     * 표지 이미지 생성 · 재생성 (동기, 사용자 명시적 트리거).
     *
     * <p>이미지 생성 완료 후 현재 템플릿 레코드를 반환한다.
     * 프론트에서는 응답의 {@code coverImagePath}로 NCP 이미지를 렌더링하면 된다.
     *
     * @param ptProjectId 프로젝트 ID
     * @param agentId     TB_PROMPT_APPLY_AGT 조회 키 (STAGE_CD='S3_COVER_TEMPLATE')
     * @return 갱신된 PtTemplateVO (coverImagePath, coverGenStatusCd 포함)
     */
    public ProposalVO.PtTemplateVO generatePtCoverImage(String ptProjectId, String agentId, String requestType, String message) {
        generateCoverImage(ptProjectId, agentId, requestType, message);
        ProposalVO.PtTemplateVO result = proposalDAO.selectPtTemplate(ptProjectId);
        if (result != null && "complement_request".equals(requestType)) {
            result.setAiMessage("003".equals(result.getCoverGenStatusCd())
                    ? "보완 요청에 따라 표지가 수정되었습니다."
                    : "보완 요청을 반영하지 못했습니다. 다시 시도해 주세요.");
        }
        return result;
    }

    public ProposalVO.PtTemplateVO generatePtDividerImage(String ptProjectId, String agentId) {
        generateDividerImage(ptProjectId, agentId, null, null);
        return proposalDAO.selectPtTemplate(ptProjectId);
    }

    public ProposalVO.PtTemplateVO generatePtDividerImage(String ptProjectId, String agentId, String requestType, String message) {
        generateDividerImage(ptProjectId, agentId, requestType, message);
        ProposalVO.PtTemplateVO result = proposalDAO.selectPtTemplate(ptProjectId);
        if (result != null && "complement_request".equals(requestType)) {
            result.setAiMessage("003".equals(result.getDividerGenStatusCd())
                    ? "보완 요청에 따라 간지가 수정되었습니다."
                    : "보완 요청을 반영하지 못했습니다. 다시 시도해 주세요.");
        }
        return result;
    }

    /**
     * 간지 배경 이미지 presigned URL 조회 (미리보기용).
     * TB_PT_TEMPLATE.DIVIDER_IMAGE_PATH(objectKey)를 읽어 FileService로 presigned URL 생성.
     */
    public Map<String, Object> viewDividerImage(String ptProjectId) throws Exception {
        ProposalVO.PtTemplateVO template = proposalDAO.selectPtTemplate(ptProjectId);
        Map<String, Object> notFound = new HashMap<>();
        notFound.put("viewType", "DOWNLOAD");
        notFound.put("reason", "FILE_NOT_FOUND");
        notFound.put("url", "");

        if (template == null || CommonUtil.isEmpty(template.getDividerImagePath())) {
            return notFound;
        }

        FileVO fileVo = new FileVO();
        fileVo.setFilePath(template.getDividerImagePath());
        fileVo.setFileName("divider.png");
        fileVo.setFileType("image/png");
        return fileService.createViewPresignedUrlForStorageObject(fileVo);
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
            String baseColor    = "#5B4FE9";
            String accentColor  = "#E08A2C";
            String docSize      = "a4";
            String writingStyle = "formal";

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
                        String ws = getStrOrNull(settings, "writingStyle");
                        if (CommonUtil.isNotEmpty(ws)) writingStyle = ws;
                    }
                    if (cfgRoot.has("template") && !cfgRoot.get("template").isJsonNull()) {
                        JsonObject tmpl = cfgRoot.getAsJsonObject("template");
                        String ds = getStrOrNull(tmpl, "docSize");
                        if (CommonUtil.isNotEmpty(ds)) docSize = ds;
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

            // 변수 치환 — 프롬프트 빌드타임 변수: {{camelCase}} (PPTX 런타임 변수 {snake_case}와 구분)
            String prompt = promptTemplate
                    .replace("{{projectNm}}", projectNm)
                    .replace("{{orgNm}}", orgNm)
                    .replace("{{docSize}}", docSize)
                    .replace("{{baseColor}}", baseColor)
                    .replace("{{accentColor}}", accentColor)
                    .replace("{{writingStyle}}", writingStyle)
                    .replace("{{topLevelTocList}}", tocSb.toString().trim());

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
            // 레이아웃 검증 (width/height 누락, 겹침)
            kr.teamagent.common.util.ProposalPptxUtil.TemplateValidationResult headerVal =
                    kr.teamagent.common.util.ProposalPptxUtil.validateTemplateJson(headerJson, ptProjectId);
            kr.teamagent.common.util.ProposalPptxUtil.TemplateValidationResult footerVal =
                    kr.teamagent.common.util.ProposalPptxUtil.validateTemplateJson(footerJson, ptProjectId);
            if (headerVal.hasInvalidSlot || footerVal.hasInvalidSlot) {
                List<String> valMsgs = new ArrayList<>();
                if (headerVal.hasInvalidSlot) valMsgs.add("header: " + headerVal.msg);
                if (footerVal.hasInvalidSlot) valMsgs.add("footer: " + footerVal.msg);
                throw new RuntimeException("[레이아웃 검증 실패] " + String.join(" / ", valMsgs));
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

    // ── 스텝 프롬프트 조회/수정 ────────────────────────────────────────────────

    /**
     * 스텝 프롬프트 목록 조회 (stageCd 목록 기준)
     */
    public List<ProposalVO.PromptEditVO> selectStepPrompts(List<String> stageCds) throws Exception {
        return proposalDAO.selectStepPrompts(stageCds);
    }

    /**
     * 스텝 프롬프트 내용 수정
     */
    public void updatePromptContent(ProposalVO.PromptEditVO vo) throws Exception {
        proposalDAO.updatePromptContent(vo);
    }

    /**
     * 스텝 프롬프트 원본 복구 (CONTENT = ORIGINAL_CONTENT)
     */
    public void restorePromptContent(String promptId) throws Exception {
        proposalDAO.restorePromptContent(promptId);
    }

    // ============================================================
    // Step5 콘텐츠 개요 API
    // ============================================================

    /**
     * 콘텐츠 개요 텍스트 단건 조회 (노드 클릭 시 지연 로딩)
     */
    public ProposalVO.TocVO selectTocOutline(String tocId) throws Exception {
        return proposalDAO.selectTocOutline(tocId);
    }

    /**
     * 콘텐츠 개요 생성 (S3_OUTLINE 프롬프트 호출)
     * - 동기 REST: SSE 불필요
     */
    public ProposalVO.TocVO generateTocOutline(String tocId, String modelId, String agentId) throws Exception {
        // 1. 해당 TOC 정보 로드
        ProposalVO.TocVO tocVO = proposalDAO.selectTocById(tocId);
        if (tocVO == null) throw new RuntimeException("목차를 찾을 수 없습니다. tocId=" + tocId);
        String ptProjectId = tocVO.getPtProjectId();

        // 2. 상위 경로 조회 (대목차 > 소분류 경로 구성)
        List<ProposalVO.TocVO> allToc = proposalDAO.selectTocList(ptProjectId);
        String sectionPath = buildSectionPath(tocVO, allToc);

        // 3. 매핑된 요구사항 조회
        List<ProposalVO.RequirementVO> allRequirements = proposalDAO.selectRequirements(ptProjectId);
        List<ProposalVO.RequirementVO> filteredReqs = filterRequirementsByToc(tocVO, allRequirements);

        // 4. Win Theme 조회
        List<ProposalVO.WinThemeVO> winThemes = null;
        try { winThemes = proposalDAO.selectWinThemes(ptProjectId); }
        catch (Exception e) { logger.warn("[PT Outline] Win Theme 조회 실패, 프롬프트에서 제외 (tocId={})", tocId); }

        // 5. 인접 세부목차 제목 목록 (같은 부모 아래)
        List<String> siblingTitles = buildSiblingTitles(tocVO, allToc);

        // 6. 프롬프트 조회
        String promptContent = null;
        try { promptContent = promptService.getPromptsByAgentIdAndStageCd(agentId, "S3_OUTLINE"); }
        catch (Exception e) { logger.warn("[PT Outline] S3_OUTLINE 프롬프트 조회 실패: {}", e.getMessage()); }

        // 7. 전체 프롬프트 조합
        String fullPrompt = buildOutlineFullPrompt(promptContent, tocVO, sectionPath, filteredReqs, winThemes, siblingTitles);

        // 8. LLM 호출
        String aiResponse = callLlmWithRetry(fullPrompt, modelId, agentId, "[PT Outline]");
        if (CommonUtil.isEmpty(aiResponse)) throw new RuntimeException("AI 응답이 비어 있습니다. 잠시 후 다시 시도해 주세요.");

        // 9. 저장
        ProposalVO.TocVO upd = new ProposalVO.TocVO();
        upd.setTocId(tocId);
        upd.setContentOutlineTxt(aiResponse.trim());
        upd.setOutlineStatusCd("002"); // 초안
        upd.setModifyUserId(SessionUtil.getUserId());
        proposalDAO.updateTocOutline(upd);

        upd.setContentOutlineTxt(aiResponse.trim());
        return upd;
    }

    /**
     * 콘텐츠 개요 보완 채팅 (현재 개요 + 사용자 메시지 → 새 개요)
     */
    public ProposalVO.TocVO chatTocOutline(String tocId, String message, String modelId, String agentId) throws Exception {
        // 1. 현재 개요 텍스트 조회
        ProposalVO.TocVO current = proposalDAO.selectTocOutline(tocId);
        if (current == null) throw new RuntimeException("목차를 찾을 수 없습니다. tocId=" + tocId);
        String currentOutline = current.getContentOutlineTxt();
        if (CommonUtil.isEmpty(currentOutline)) throw new RuntimeException("개요가 없습니다. 먼저 개요를 생성해주세요.");

        // 2. 채팅 프롬프트 조합
        String fullPrompt = buildOutlineChatPrompt(currentOutline, message);

        // 3. LLM 호출
        String aiResponse = callLlmWithRetry(fullPrompt, modelId, agentId, "[PT Outline Chat]");
        if (CommonUtil.isEmpty(aiResponse)) throw new RuntimeException("AI 응답이 비어 있습니다. 잠시 후 다시 시도해 주세요.");

        // 4. 저장 (확정→초안 강등)
        ProposalVO.TocVO upd = new ProposalVO.TocVO();
        upd.setTocId(tocId);
        upd.setContentOutlineTxt(aiResponse.trim());
        upd.setOutlineStatusCd("002"); // 채팅으로 수정 시 항상 초안
        upd.setModifyUserId(SessionUtil.getUserId());
        proposalDAO.updateTocOutline(upd);

        upd.setContentOutlineTxt(aiResponse.trim());
        return upd;
    }

    /**
     * 콘텐츠 개요 확정 (OUTLINE_STATUS_CD = '003')
     */
    public void confirmTocOutline(String tocId, String outlineTxt) throws Exception {
        ProposalVO.TocVO upd = new ProposalVO.TocVO();
        upd.setTocId(tocId);
        upd.setContentOutlineTxt(outlineTxt);
        upd.setOutlineStatusCd("003");
        upd.setModifyUserId(SessionUtil.getUserId());
        proposalDAO.updateTocOutline(upd);
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // [Outline 내부 헬퍼]
    // ──────────────────────────────────────────────────────────────────────────────

    /** 세부목차 경로 문자열 조합 (대목차 > 소분류 > 세부목차) */
    private String buildSectionPath(ProposalVO.TocVO leaf, List<ProposalVO.TocVO> allToc) {
        if (leaf.getParentTocId() == null) return leaf.getSectionNm();
        ProposalVO.TocVO parent = allToc.stream()
                .filter(t -> t.getTocId().equals(leaf.getParentTocId()))
                .findFirst().orElse(null);
        if (parent == null) return leaf.getSectionNm();
        if (parent.getParentTocId() == null) {
            return parent.getSectionNm() + " > " + leaf.getSectionNm();
        }
        ProposalVO.TocVO grandParent = allToc.stream()
                .filter(t -> t.getTocId().equals(parent.getParentTocId()))
                .findFirst().orElse(null);
        String gp = grandParent != null ? grandParent.getSectionNm() + " > " : "";
        return gp + parent.getSectionNm() + " > " + leaf.getSectionNm();
    }

    /** TOC에 매핑된 요구사항 필터링 */
    private List<ProposalVO.RequirementVO> filterRequirementsByToc(
            ProposalVO.TocVO tocVO, List<ProposalVO.RequirementVO> allReqs) {
        String coveredJson = tocVO.getCoveredReqIdsJson();
        if (CommonUtil.isEmpty(coveredJson) || "[]".equals(coveredJson.trim())) return allReqs;
        try {
            java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<List<String>>(){}.getType();
            List<String> ids = GSON.fromJson(coveredJson, listType);
            if (ids == null || ids.isEmpty()) return allReqs;
            java.util.Set<String> idSet = new java.util.HashSet<>(ids);
            return allReqs.stream().filter(r -> idSet.contains(r.getRequirementId())).collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            return allReqs;
        }
    }

    /** 같은 부모 아래 형제 세부목차 제목 목록 */
    private List<String> buildSiblingTitles(ProposalVO.TocVO leaf, List<ProposalVO.TocVO> allToc) {
        if (leaf.getParentTocId() == null) return java.util.Collections.emptyList();
        return allToc.stream()
                .filter(t -> leaf.getParentTocId().equals(t.getParentTocId())
                        && !t.getTocId().equals(leaf.getTocId()))
                .map(ProposalVO.TocVO::getSectionNm)
                .collect(java.util.stream.Collectors.toList());
    }

    /** 콘텐츠 개요 생성용 전체 프롬프트 조합 */
    private String buildOutlineFullPrompt(String promptContent,
            ProposalVO.TocVO tocVO,
            String sectionPath,
            List<ProposalVO.RequirementVO> reqs,
            List<ProposalVO.WinThemeVO> winThemes,
            List<String> siblingTitles) {
        StringBuilder sb = new StringBuilder();
        if (!CommonUtil.isEmpty(promptContent)) {
            sb.append(promptContent).append("\n\n");
        } else {
            sb.append("당신은 PT 제안서 콘텐츠 개요 작성 전문가입니다.\n")
              .append("아래 정보를 바탕으로 해당 세부목차 슬라이드에 담을 수 있는 아이디어를 5~8개 번호 목록으로 제시하세요.\n")
              .append("각 아이디어는 2~3줄 설명을 포함하세요.\n\n");
        }
        sb.append("## 세부목차 정보\n");
        sb.append("- 경로: ").append(sectionPath).append("\n");
        sb.append("- 제목: ").append(tocVO.getSectionNm()).append("\n\n");
        if (reqs != null && !reqs.isEmpty()) {
            sb.append("## 관련 요구사항\n");
            reqs.forEach(r -> sb.append("- [").append(r.getRequirementId()).append("] ")
                    .append(r.getReqNo() != null ? r.getReqNo() + " " : "")
                    .append(r.getReqContent()).append("\n"));
            sb.append("\n");
        }
        if (winThemes != null && !winThemes.isEmpty()) {
            sb.append("## Win Theme\n");
            winThemes.forEach(w -> sb.append("- ").append(w.getCoreMessage())
                    .append(w.getProposalStrategy() != null ? ": " + w.getProposalStrategy() : "").append("\n"));
            sb.append("\n");
        }
        if (!siblingTitles.isEmpty()) {
            sb.append("## 인접 세부목차 (중복 제외)\n");
            siblingTitles.forEach(t -> sb.append("- ").append(t).append("\n"));
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 콘텐츠 개요 채팅 프롬프트 조합 */
    private String buildOutlineChatPrompt(String currentOutline, String userMessage) {
        return "당신은 PT 제안서 콘텐츠 개요 보완 전문가입니다.\n"
            + "아래 현재 개요를 사용자의 요청에 맞게 수정하여 전체 개요를 다시 작성해 주세요.\n"
            + "형식은 기존 번호 목록 형식을 유지하세요.\n\n"
            + "## 현재 개요\n" + currentOutline + "\n\n"
            + "## 사용자 요청\n" + userMessage + "\n\n"
            + "## 지시\n수정된 전체 개요를 번호 목록으로 다시 작성해 주세요.";
    }

}