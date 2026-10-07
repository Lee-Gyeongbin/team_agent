package kr.teamagent.marketing.service.impl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.HtmlUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import kr.teamagent.agent.service.AgentVO;
import kr.teamagent.agent.service.impl.AgentDAO;
import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.chat.service.impl.ChatbotAgentSupport;
import kr.teamagent.chat.service.impl.ChatbotDAO;
import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.apilog.service.impl.ApiCallLogServiceImpl;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.marketing.service.MarketingVO;
import kr.teamagent.prompt.service.impl.PromptServiceImpl;
import kr.teamagent.tmpl.service.TmplVO;
import kr.teamagent.tmpl.service.impl.TmplServiceImpl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

@Service
public class MarketingServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(MarketingServiceImpl.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** DB 컬럼 길이 (TB_MKT_CONTENT.TITLE) */
    private static final int TITLE_MAX_LENGTH = 200;
    private static final int VARIANT_COUNT_MAX = 5;
    /** 콘텐츠 생성 참고파일 최대 개수 */
    private static final int REFERENCE_FILE_MAX = 5;
    private static final long VARIANT_AI_TIMEOUT_SEC = 150L;
    private static final long AI_HTTP_TIMEOUT_SEC = 120L;
    private static final long GENERATION_WAIT_TIMEOUT_MIN = 15L;
    private static final String PART_TEXT = "TEXT";
    private static final String PART_IMAGE = "IMAGE";
    private static final String IMAGE_DATA_URI_PREFIX = "data:image/png;base64,";
    /** /file_query 임시 첨부 브릿지용 ROOM_ID */
    private static final long FILE_ROOM_ID = 0L;
    /** 마케팅 콘텐츠 내보내기 문서템플릿 */
    private static final String MARKETING_EXPORT_TMPL_ID = "TM000009";
    /** MK000001: 001작성중 002검수중(AI) 003승인필요 004승인완료 005예약 006발행완료 007발행실패 */
    private static final String STATUS_WRITING = "001";
    private static final String STATUS_REVIEWING = "002";
    private static final String STATUS_APPROVAL_REQUIRED = "003";
    private static final String STATUS_APPROVED = "004";
    private static final String STATUS_SCHEDULED = "005";
    private static final String STATUS_PUBLISHED = "006";
    private static final Set<String> PROJECT_STATUS_CDS = Set.of("001", "002", "003", "004", "005", "006", "007");
    /** AI 검수 판정/체크 상태/이슈 심각도 공통 3단계 — PASS: 발행 가능 REVIEW: 확인 후 승인 가능 FAIL: 승인·발행 차단 */
    private static final String VERDICT_PASS = "PASS";
    private static final String VERDICT_REVIEW = "REVIEW";
    private static final String VERDICT_FAIL = "FAIL";
    /** MK000002: 001대기 002생성중 003완료 004실패 — AI 생성 처리 상태(워크플로 STATUS_CD와 별개) */
    private static final String AI_STATUS_WAITING = "001";
    private static final String AI_STATUS_GENERATING = "002";
    private static final String AI_STATUS_DONE = "003";
    private static final String AI_STATUS_FAILED = "004";
    /** TB_PROMPT.PROMPT_ID */
    private static final String PROMPT_ID_PLAN = "PI000038";
    private static final String PROMPT_ID_IMAGE = "PI000039";
    private static final String PROMPT_ID_REVIEW = "PI000040";
    private static final String PROMPT_ID_TITLE = "PI000041";
    private static final String PROMPT_ID_LABEL = "PI000042";
    private static final String PROMPT_ID_TEXT_REFINE = "PI000043";
    private static final String PROMPT_ID_REF = "PI000044";
    private static final String PROMPT_ID_IMAGE_REFINE = "PI000045";
    private static final String PROMPT_ID_TEXT = "PI000046";
    private static final String PROMPT_ID_PLAN_REFINE = "PI000047";
    /** MK000003: 001콘텐츠 002브랜드 003이미지 */
    private static final String FILE_PURPOSE_CONTENT = "001";
    private static final String FILE_PURPOSE_BRAND = "002";
    private static final String FILE_PURPOSE_IMAGE = "003";
    private static final Set<String> FILE_PURPOSE_CDS = Set.of(
            FILE_PURPOSE_CONTENT, FILE_PURPOSE_BRAND, FILE_PURPOSE_IMAGE);
    private static final DateTimeFormatter PUBLISH_DT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    /** 검수 체크 항목 key → 한글 라벨 */
    private static final Map<String, String> CHECK_LABELS = Map.ofEntries(
            Map.entry("fact", "사실·근거"),
            Map.entry("brand", "브랜드 가이드"),
            Map.entry("goal", "캠페인 목적·메시지"),
            Map.entry("channel", "채널·발행 규격"),
            Map.entry("legal", "법률·정책"),
            Map.entry("complete", "콘텐츠 완결성"),
            Map.entry("visual", "이미지·비주얼"));

    /** 에이전트 ADDITIONAL_CONFIG.channelsByContentType → 채널 코드→라벨. 조회 실패 시 빈 맵 */
    @SuppressWarnings("unchecked")
    private Map<String, String> resolveChannelLabels(String agentId) {
        Map<String, String> channelLabels = new LinkedHashMap<>();
        Map<String, Object> additionalConfig = null;
        try {
            ChatbotVO.AgtSubCfgVO subCfg = agentSupport.getAgentSubCfg(agentId);
            additionalConfig = subCfg != null ? subCfg.getAdditionalConfigMap() : null;
        } catch (Exception e) {
            logger.warn("[MKT] 에이전트 라벨 설정 조회 실패 - agentId={}: {}", agentId, e.getMessage());
        }
        Object channelsByContentType = additionalConfig == null ? null : additionalConfig.get("channelsByContentType");
        if (!(channelsByContentType instanceof Map)) {
            return channelLabels;
        }
        for (Object options : ((Map<String, Object>) channelsByContentType).values()) {
            if (!(options instanceof List)) {
                continue;
            }
            for (Object item : (List<?>) options) {
                if (!(item instanceof Map)) {
                    continue;
                }
                String value = stringValue(((Map<String, Object>) item).get("value"));
                String label = stringValue(((Map<String, Object>) item).get("label"));
                if (CommonUtil.isNotEmpty(value) && CommonUtil.isNotEmpty(label)) {
                    channelLabels.put(value, label);
                }
            }
        }
        return channelLabels;
    }

    /** 상한이 있으면 fixed pool, 없으면 cachedThreadPool */
    private static ExecutorService daemonPool(String name, int fixedSize) {
        ThreadFactory factory = r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        };
        if (fixedSize <= 0) {
            return Executors.newCachedThreadPool(factory);
        }
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                fixedSize, fixedSize, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** 시안 TEXT/IMAGE AI 호출 풀 */
    private static final ExecutorService MARKETING_AI_EXECUTOR = daemonPool("marketing-ai", VARIANT_COUNT_MAX * 2 * 4);
    /** SSE 진행 이벤트 전송 풀 */
    private static final ExecutorService MARKETING_STREAM_EXECUTOR = daemonPool("marketing-sse", 0);
    private static final ConcurrentHashMap<String, CompletableFuture<Void>> ACTIVE_GENERATIONS =
            new ConcurrentHashMap<>();
    /** /file_query, 이미지 API 동기 호출 */
    private static final OkHttpClient AI_HTTP_CLIENT = new OkHttpClient.Builder()
            .readTimeout(AI_HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build();

    @Autowired
    private MarketingDAO marketingDAO;

    @Autowired
    private TmplServiceImpl tmplService;

    @Autowired
    private KeyGenerate keyGenerate;

    @Autowired
    @Lazy
    private ChatbotServiceImpl chatbotService;

    @Autowired
    private ChatbotDAO chatbotDAO;

    @Autowired
    private AgentDAO agentDAO;

    @Autowired
    private ChatbotAgentSupport agentSupport;

    @Autowired
    private FileServiceImpl fileService;

    @Autowired
    private PromptServiceImpl promptService;

    @Autowired
    private ApiCallLogServiceImpl apiCallLogService;

    // ── 공통 헬퍼 ────────────────────────────────────────────────────────────────

    /** Map/JSON Object → String (null 안전). CommonUtil.nullToBlank(Object)는 String 캐스트라 Number 등에서 깨짐. */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** AI 생성 TITLE 등 DB 컬럼 길이 방어 */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRequest(String json) {
        if (CommonUtil.isEmpty(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> stored = GSON.fromJson(json, Map.class);
            return stored != null ? new LinkedHashMap<>(stored) : new LinkedHashMap<>();
        } catch (Exception e) {
            logger.warn("마케팅 REQUEST_JSON 파싱 실패", e);
            return new LinkedHashMap<>();
        }
    }

    /** REQUEST_JSON 저장값 — 별도 컬럼에 있는 값은 뺀다 */
    private Map<String, Object> storedRequest(Map<String, Object> request) {
        Map<String, Object> stored = new LinkedHashMap<>(request);
        for (String key : List.of("outputs", "contentType", "variantCount", "marketingProjectId",
                "textPrompts", "imagePrompts", "previousTextPrompts", "previousImagePrompts")) {
            stored.remove(key);
        }
        return stored;
    }

    /** REQUEST_JSON + 콘텐츠 컬럼 → 생성 조건 */
    private Map<String, Object> contentRequest(MarketingVO row) {
        Map<String, Object> request = parseRequest(row.getRequestJson());
        request.put("contentType", row.getContentType());
        request.put("variantCount", row.getVariantCount());
        request.put("outputs", "BOTH".equals(row.getOutputMode()) ? List.of(PART_TEXT, PART_IMAGE)
                : List.of(row.getOutputMode()));
        return request;
    }

    /** MKT_CONTENT_ID 조회용 VO */
    private MarketingVO contentSearch(String mktContentId) {
        MarketingVO searchVO = new MarketingVO();
        searchVO.setMktContentId(mktContentId);
        return searchVO;
    }

    /** MKT_ID 조회용 VO */
    private MarketingVO.ProjectVO projectSearch(String marketingProjectId) {
        MarketingVO.ProjectVO searchVO = new MarketingVO.ProjectVO();
        searchVO.setMarketingProjectId(marketingProjectId);
        return searchVO;
    }

    /** MKT_FILE_ID 조회용 VO */
    private MarketingVO.FileVO fileSearch(String marketingFileId) {
        MarketingVO.FileVO searchVO = new MarketingVO.FileVO();
        searchVO.setMarketingFileId(marketingFileId);
        return searchVO;
    }

    /** MKT_ID 기획서 조회용 VO */
    private MarketingVO.PlanVO planSearch(String marketingProjectId) {
        MarketingVO.PlanVO searchVO = new MarketingVO.PlanVO();
        searchVO.setMarketingProjectId(marketingProjectId);
        return searchVO;
    }

    private Map<String, Object> successResult() {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        return resultMap;
    }

    private Map<String, Object> failResult(String message) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", false);
        resultMap.put("returnMsg", message);
        return resultMap;
    }

    // ── 권한 / 승인 상태 ─────────────────────────────────────────────────────────

    /** 프로젝트 존재 + 멤버 여부 확인 (해당 안 되면 찾을 수 없음과 동일한 메시지로 예외) */
    private MarketingVO.ProjectVO requireProjectMember(String marketingProjectId, String userId) throws Exception {
        String projectId = stringValue(marketingProjectId);
        if (CommonUtil.isEmpty(projectId)) {
            throw new RuntimeException("marketingProjectId는 필수입니다.");
        }
        MarketingVO.ProjectVO data = marketingDAO.selectMarketingProject(projectSearch(projectId));
        if (data == null) {
            throw new RuntimeException("마케팅 프로젝트를 찾을 수 없습니다.");
        }
        MarketingVO.ProjectVO memberSearchVO = projectSearch(projectId);
        memberSearchVO.setUserId(userId);
        if (marketingDAO.countMarketingProjectMember(memberSearchVO) <= 0) {
            throw new RuntimeException("마케팅 프로젝트를 찾을 수 없습니다.");
        }
        return data;
    }

    /** 마케팅 프로젝트 존재 + 멤버 확인 */
    private void requireProject(String marketingProjectId) throws Exception {
        requireProjectMember(marketingProjectId, SessionUtil.getUserId());
    }

    /** 콘텐츠 존재 + 프로젝트 멤버 확인 */
    private MarketingVO requireMarketing(String mktContentId, String userId) throws Exception {
        MarketingVO row = marketingDAO.selectMarketing(contentSearch(mktContentId));
        if (row == null) {
            throw new RuntimeException("마케팅 콘텐츠를 찾을 수 없습니다.");
        }
        requireProjectMember(row.getMarketingProjectId(), userId);
        return row;
    }

    /** 검수 결과가 현재 선택 시안·버전 대상인지 */
    private boolean isCurrentReview(MarketingVO row, MarketingVO.ReviewVO review) {
        return review != null && Objects.equals(row.getSelectedVariantNo(), review.getVariantNo())
                && Objects.equals(row.getContentVersion(), review.getContentVersion());
    }

    /** 승인 이력이 현재 선택 시안·버전에 대한 지정 승인자 처리인지 */
    private boolean isCurrentApproval(MarketingVO row, MarketingVO.ApprovalVO approval, String approverId) {
        return approval != null && Objects.equals(row.getSelectedVariantNo(), approval.getVariantNo())
                && Objects.equals(row.getContentVersion(), approval.getContentVersion())
                && Objects.equals(approverId, approval.getCreateUserId());
    }

    /** 발행 설정 전 현재 선택 시안의 승인 완료 확인 */
    private void requireApproved(MarketingVO row) throws Exception {
        MarketingVO.ProjectVO project = marketingDAO.selectMarketingProject(projectSearch(row.getMarketingProjectId()));
        MarketingVO.ApprovalVO approval = marketingDAO.selectLatestApproval(row.getMktContentId());
        if (!Set.of(STATUS_APPROVED, STATUS_SCHEDULED, STATUS_PUBLISHED).contains(row.getStatusCd())
                || !isCurrentApproval(row, approval, project.getApproverUserId())
                || !"Y".equals(approval.getApprovedYn())) {
            throw new RuntimeException("선택한 시안의 승인 완료 후 발행 설정을 저장해 주세요.");
        }
    }

    /** 예약·발행완료 콘텐츠는 승인 해제 대상에서 막는다 */
    private void requireEditable(MarketingVO row) {
        if (STATUS_SCHEDULED.equals(row.getStatusCd()) || STATUS_PUBLISHED.equals(row.getStatusCd())) {
            throw new RuntimeException("예약·발행완료 콘텐츠는 수정할 수 없습니다. 발행 설정을 미발행 유지로 바꾼 후 다시 시도해 주세요.");
        }
    }

    /** 콘텐츠 버전을 올려 기존 검수·승인과 발행 설정을 해제한다 */
    private void invalidateApproval(MarketingVO row, Map<String, Object> request, String userId) throws Exception {
        requireEditable(row);
        row.setRequestJson(GSON.toJson(storedRequest(request)));
        row.setModifyUserId(userId);
        if (marketingDAO.invalidateMarketingApproval(row) != 1) {
            throw new RuntimeException("마케팅 콘텐츠를 찾을 수 없습니다.");
        }
        row.setContentVersion(row.getContentVersion() + 1);
        row.setStatusCd(STATUS_REVIEWING);
        row.setPublishTypeCd("HOLD");
        row.setPublishedYn("N");
        row.setPublishScheduledDt(null);
    }

    // ── 프로젝트 / 파일 ────────────────────────────────────────────────────────────

    /** 마케팅 프로젝트 생성/수정 */
    @Transactional(rollbackFor = Exception.class)
    public String saveMarketingProject(MarketingVO.ProjectVO vo) throws Exception {
        if (vo == null || CommonUtil.isEmpty(vo.getProjectNm())) {
            throw new RuntimeException("프로젝트명은 필수입니다.");
        }
        String userId = SessionUtil.getUserId();
        if (CommonUtil.isEmpty(vo.getOrgNm())) {
            vo.setOrgNm(null);
        }
        if (CommonUtil.isEmpty(vo.getDueDt())) {
            vo.setDueDt(null);
        }
        if (CommonUtil.isNotEmpty(vo.getStatusCd()) && !PROJECT_STATUS_CDS.contains(vo.getStatusCd())) {
            throw new RuntimeException("프로젝트 상태를 확인해 주세요.");
        }

        String projectId = stringValue(vo.getMarketingProjectId());
        boolean creating = projectId.isEmpty();
        MarketingVO.ProjectVO existing = null;
        if (!creating) {
            existing = requireProjectMember(projectId, userId);
            if (vo.getMemberUserIds() == null) {
                List<String> members = new ArrayList<>();
                for (MarketingVO.MemberVO member : marketingDAO.selectMarketingProjectMemberList(existing)) {
                    members.add(member.getUserId());
                }
                vo.setMemberUserIds(members);
            }
            if (vo.getApproverUserId() == null) {
                vo.setApproverUserId(existing.getApproverUserId());
            }
            boolean owner = userId.equals(existing.getCreateUserId());
            if (!owner && !Objects.equals(vo.getApproverUserId(), existing.getApproverUserId())) {
                throw new RuntimeException("프로젝트 작성자만 승인자를 변경할 수 있습니다.");
            }
            if (!owner && vo.getManagerUserId() != null) {
                throw new RuntimeException("프로젝트 작성자만 관리자를 변경할 수 있습니다.");
            }
            if (!owner && !userId.equals(existing.getManagerUserId())
                    && CommonUtil.isNotEmpty(vo.getStatusCd()) && !Objects.equals(vo.getStatusCd(), existing.getStatusCd())) {
                throw new RuntimeException("프로젝트 작성자와 관리자만 상태를 변경할 수 있습니다.");
            }
        }

        String ownerId = creating ? userId : existing.getCreateUserId();
        List<String> memberUserIds = vo.getMemberUserIds() == null ? Collections.emptyList() : vo.getMemberUserIds();
        String approverId = stringValue(vo.getApproverUserId());
        if (approverId.isEmpty()) {
            throw new RuntimeException("프로젝트 승인자를 선택해 주세요.");
        }
        if (!approverId.equals(ownerId) && !memberUserIds.contains(approverId)) {
            throw new RuntimeException("승인자는 프로젝트 멤버 중에서 선택해 주세요.");
        }
        vo.setApproverUserId(approverId);
        String managerId = vo.getManagerUserId();
        if (CommonUtil.isNotEmpty(managerId) && !managerId.equals(ownerId) && !memberUserIds.contains(managerId)) {
            throw new RuntimeException("관리자는 프로젝트 멤버 중에서 선택해 주세요.");
        }

        vo.setCreateUserId(userId);
        vo.setModifyUserId(userId);
        if (creating) {
            projectId = keyGenerate.generateTableKey("MP", "TB_MKT", "MKT_ID");
            vo.setMarketingProjectId(projectId);
            vo.setStatusCd(STATUS_WRITING);
            marketingDAO.insertMarketingProject(vo);
        } else {
            if (marketingDAO.updateMarketingProject(vo) != 1) {
                throw new RuntimeException("마케팅 프로젝트를 찾을 수 없습니다.");
            }
            // 기존 관리자를 멤버에서 빼는 요청이면 관리자 변경을 먼저 반영한다.
            if (managerId != null) {
                marketingDAO.updateMarketingManager(vo);
            }
        }
        saveProjectMembers(projectId, userId, ownerId, vo.getMemberUserIds());
        if (managerId != null) {
            marketingDAO.updateMarketingManager(vo);
        }

        if (!creating && !Objects.equals(existing.getApproverUserId(), approverId)) {
            MarketingVO search = new MarketingVO();
            search.setMarketingProjectId(projectId);
            for (MarketingVO content : marketingDAO.selectMarketingList(search)) {
                if (!STATUS_SCHEDULED.equals(content.getStatusCd()) && !STATUS_PUBLISHED.equals(content.getStatusCd())) {
                    invalidateApproval(content, contentRequest(content), userId);
                }
            }
        }
        return projectId;
    }

    /** 프로젝트 멤버 차이 반영 */
    private void saveProjectMembers(String projectId, String userId, String ownerId, List<String> requested) throws Exception {
        Set<String> members = new LinkedHashSet<>();
        members.add(ownerId);
        if (requested != null) {
            for (String id : requested) {
                if (CommonUtil.isEmpty(id)) {
                    throw new RuntimeException("멤버 ID를 확인해 주세요.");
                }
                members.add(id);
            }
        }
        for (MarketingVO.MemberVO member : marketingDAO.selectMarketingProjectMemberList(projectSearch(projectId))) {
            if (members.remove(member.getUserId())) {
                continue;
            }
            if ("Y".equals(member.getManageYn())) {
                throw new RuntimeException("관리자를 해제한 후 멤버에서 제외해 주세요.");
            }
            marketingDAO.deleteMarketingProjectMember(member);
        }
        if (!members.isEmpty() && marketingDAO.countExistingUsers(new ArrayList<>(members)) != members.size()) {
            throw new RuntimeException("존재하지 않는 프로젝트 멤버가 포함되어 있습니다.");
        }
        for (String id : members) {
            MarketingVO.MemberVO member = new MarketingVO.MemberVO();
            member.setMarketingMemberId(keyGenerate.generateTableKey("MM", "TB_MKT_MEMBER", "MKT_MEMBER_ID"));
            member.setMarketingProjectId(projectId);
            member.setUserId(id);
            member.setCreateUserId(userId);
            marketingDAO.insertMarketingProjectMember(member);
        }
    }

    /** 마케팅 프로젝트 삭제 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMarketingProject(String marketingProjectId) throws Exception {
        String userId = SessionUtil.getUserId();
        if (!userId.equals(requireProjectMember(marketingProjectId, userId).getCreateUserId())) {
            throw new RuntimeException("프로젝트 작성자만 삭제할 수 있습니다.");
        }
        MarketingVO.ProjectVO searchVO = projectSearch(marketingProjectId);
        MarketingVO contentSearchVO = new MarketingVO();
        contentSearchVO.setMarketingProjectId(marketingProjectId);
        for (MarketingVO row : marketingDAO.selectMarketingList(contentSearchVO)) {
            marketingDAO.deleteMarketingHistories(row);
        }
        MarketingVO.FileVO fileSearchVO = new MarketingVO.FileVO();
        fileSearchVO.setMarketingProjectId(marketingProjectId);
        List<MarketingVO.FileVO> files = marketingDAO.selectMarketingFileList(fileSearchVO);
        marketingDAO.deleteMarketingContentsByProject(searchVO);
        marketingDAO.deleteMarketingByProject(searchVO);
        marketingDAO.deleteMarketingFilesByProject(searchVO);
        marketingDAO.deleteMarketingPlanByProject(searchVO);
        marketingDAO.deleteMarketingProjectMembersByProject(searchVO);
        if (marketingDAO.deleteMarketingProject(searchVO) != 1) {
            throw new RuntimeException("마케팅 프로젝트를 찾을 수 없습니다.");
        }
        for (MarketingVO.FileVO file : files) {
            deleteFileStorageObject(file);
        }
    }

    /** 마케팅 프로젝트 목록 조회 — 로그인 사용자가 멤버인 프로젝트만 */
    public List<MarketingVO.ProjectVO> selectMarketingProjectList(MarketingVO.ProjectVO searchVO) throws Exception {
        if (searchVO == null) {
            searchVO = new MarketingVO.ProjectVO();
        }
        searchVO.setUserId(SessionUtil.getUserId());
        return marketingDAO.selectMarketingProjectList(searchVO);
    }

    /** 마케팅 프로젝트 단건 조회 (멤버가 아니면 찾을 수 없음과 동일하게 처리) */
    public MarketingVO.ProjectVO selectMarketingProject(String marketingProjectId) throws Exception {
        return requireProjectMember(marketingProjectId, SessionUtil.getUserId());
    }

    /** 프로젝트 멤버(공개범위) 목록 조회 */
    public List<MarketingVO.MemberVO> selectMarketingProjectMemberList(String marketingProjectId) throws Exception {
        requireProject(marketingProjectId);
        return marketingDAO.selectMarketingProjectMemberList(projectSearch(marketingProjectId));
    }

    /** 마케팅 파일 업로드 presigned URL */
    public Map<String, Object> saveMarketingFileUploadUrl(MarketingVO.FileVO fileVO) throws Exception {
        if (CommonUtil.isNotEmpty(fileVO.getMarketingProjectId()) && !"draft".equals(fileVO.getMarketingProjectId())) {
            requireProject(fileVO.getMarketingProjectId());
        }
        requireMarketingUploadPath(fileVO.getFilePath(), fileVO.getMarketingProjectId());
        FileVO req = new FileVO();
        req.setFileName(fileVO.getFileNm());
        req.setFileType(fileVO.getFileType());
        if (fileVO.getFileSize() != null) {
            req.setFileSize(String.valueOf(fileVO.getFileSize()));
        }
        if (CommonUtil.isNotEmpty(fileVO.getFilePath())) {
            req.setKey(fileVO.getFilePath());
        }
        return fileService.createUploadPresignedUrl(req);
    }

    /** 업로드는 로그인 사용자와 대상 프로젝트의 저장 경로로 제한한다. */
    private void requireMarketingUploadPath(String path, String projectId) {
        String prefix = "marketing/" + SessionUtil.getUserId() + "/" + (CommonUtil.isEmpty(projectId) ? "draft" : projectId) + "/";
        if (path == null || !path.startsWith(prefix) || path.length() <= prefix.length()
                || path.contains("..") || path.contains("\\")) {
            throw new RuntimeException("마케팅 파일 저장 경로를 확인해 주세요.");
        }
    }

    /** 마케팅 파일 메타 저장 */
    public Map<String, Object> saveMarketingFile(MarketingVO.FileVO vo) throws Exception {
        if (CommonUtil.isEmpty(vo.getFilePath())) {
            throw new RuntimeException("filePath는 필수입니다.");
        }
        if (CommonUtil.isEmpty(vo.getFileNm())) {
            throw new RuntimeException("fileName은 필수입니다.");
        }

        String marketingProjectId = stringValue(vo.getMarketingProjectId());
        boolean draft = marketingProjectId.isEmpty() || "draft".equals(marketingProjectId);
        if (!draft) {
            requireProject(marketingProjectId);
        }
        requireMarketingUploadPath(vo.getFilePath(), marketingProjectId);
        String filePurposeCd = requireFilePurposeCd(vo.getFilePurposeCd());
        String fileType = CommonUtil.nvl(vo.getMimeType(), CommonUtil.nullToBlank(vo.getFileType()));
        if (vo.getFileSize() == null || CommonUtil.isEmpty(fileType)) {
            throw new RuntimeException("파일 크기와 MIME 타입은 필수입니다.");
        }

        MarketingVO.FileVO fileVO = new MarketingVO.FileVO();
        fileVO.setMarketingFileId(keyGenerate.generateTableKey("MF", "TB_MKT_FILE", "MKT_FILE_ID"));
        fileVO.setMarketingProjectId(draft ? null : marketingProjectId);
        fileVO.setFilePath(vo.getFilePath());
        fileVO.setFileNm(vo.getFileNm());
        fileVO.setFileSize(vo.getFileSize());
        fileVO.setFileType(fileType);
        fileVO.setFilePurposeCd(filePurposeCd);
        fileVO.setCreateUserId(SessionUtil.getUserId());
        marketingDAO.insertMarketingFile(fileVO);

        Map<String, Object> resultMap = successResult();
        resultMap.put("marketingFileId", fileVO.getMarketingFileId());
        resultMap.put("filePath", fileVO.getFilePath());
        resultMap.put("fileName", fileVO.getFileNm());
        return resultMap;
    }

    /** 마케팅 프로젝트 첨부파일 목록 */
    public List<MarketingVO.FileVO> selectMarketingFileList(String marketingProjectId, String filePurposeCd)
            throws Exception {
        requireProject(marketingProjectId);
        MarketingVO.FileVO searchVO = new MarketingVO.FileVO();
        searchVO.setMarketingProjectId(marketingProjectId);
        if (CommonUtil.isNotEmpty(stringValue(filePurposeCd))) {
            searchVO.setFilePurposeCd(requireFilePurposeCd(filePurposeCd));
        }
        return marketingDAO.selectMarketingFileList(searchVO);
    }

    /** 마케팅 프로젝트 첨부파일명 수정 */
    public void updateMarketingFileName(String marketingFileId, String fileName) throws Exception {
        String fileId = stringValue(marketingFileId);
        String trimmedName = stringValue(fileName);
        if (CommonUtil.isEmpty(fileId)) {
            throw new RuntimeException("marketingFileId는 필수입니다.");
        }
        if (CommonUtil.isEmpty(trimmedName)) {
            throw new RuntimeException("파일명은 필수입니다.");
        }
        requireFileAccess(marketingDAO.selectMarketingFileById(fileSearch(fileId)), SessionUtil.getUserId());
        MarketingVO.FileVO dataVO = fileSearch(fileId);
        dataVO.setFileNm(trimmedName);
        dataVO.setModifyUserId(SessionUtil.getUserId());
        if (marketingDAO.updateMarketingFile(dataVO) != 1) {
            throw new RuntimeException("첨부파일을 찾을 수 없습니다.");
        }
    }

    /** 마케팅 첨부파일 삭제 — 참고파일로 쓰는 콘텐츠는 참조를 빼고 승인을 해제한다 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMarketingFile(String marketingFileId) throws Exception {
        String userId = SessionUtil.getUserId();
        MarketingVO.FileVO row = marketingDAO.selectMarketingFileById(fileSearch(marketingFileId));
        requireFileAccess(row, userId);
        if (row.getMarketingProjectId() != null) {
            MarketingVO search = new MarketingVO();
            search.setMarketingProjectId(row.getMarketingProjectId());
            for (MarketingVO content : marketingDAO.selectMarketingList(search)) {
                Map<String, Object> request = contentRequest(content);
                Object refs = request.get("referenceMarketingFileIds");
                if (!(refs instanceof List) || !((List<?>) refs).contains(marketingFileId)) {
                    continue;
                }
                if (STATUS_SCHEDULED.equals(content.getStatusCd()) || STATUS_PUBLISHED.equals(content.getStatusCd())) {
                    throw new RuntimeException("예약·발행완료 콘텐츠에서 사용하는 파일은 삭제할 수 없습니다.");
                }
                List<Object> updated = new ArrayList<>((List<?>) refs);
                updated.removeIf(marketingFileId::equals);
                request.put("referenceMarketingFileIds", updated);
                invalidateApproval(content, request, userId);
            }
        }
        if (marketingDAO.deleteMarketingFile(fileSearch(marketingFileId)) != 1) {
            throw new RuntimeException("첨부파일을 찾을 수 없습니다.");
        }
        deleteFileStorageObject(row);
    }

    /** 임시 파일은 등록자, 프로젝트 파일은 멤버만 접근 */
    private void requireFileAccess(MarketingVO.FileVO row, String userId) throws Exception {
        if (row == null) {
            throw new RuntimeException("첨부파일을 찾을 수 없습니다.");
        }
        if (row.getMarketingProjectId() != null) {
            requireProjectMember(row.getMarketingProjectId(), userId);
        } else if (!Objects.equals(userId, row.getCreateUserId())) {
            throw new RuntimeException("첨부파일을 찾을 수 없습니다.");
        }
    }

    /** 참고파일 확인 — 임시 파일은 대상 프로젝트로 연결한다 */
    private void validateReferenceFiles(String projectId, Object raw, String userId) throws Exception {
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List)) {
            throw new RuntimeException("참고파일 목록을 확인해 주세요.");
        }
        for (Object id : (List<?>) raw) {
            MarketingVO.FileVO file = marketingDAO.selectMarketingFileById(fileSearch(stringValue(id)));
            requireFileAccess(file, userId);
            if (file.getMarketingProjectId() == null) {
                file.setMarketingProjectId(projectId);
                file.setModifyUserId(userId);
                if (marketingDAO.attachMarketingFile(file) != 1) {
                    throw new RuntimeException("임시 파일 연결에 실패했습니다.");
                }
            } else if (!projectId.equals(file.getMarketingProjectId())) {
                throw new RuntimeException("다른 프로젝트의 파일입니다.");
            }
        }
    }

    /** S3 스토리지 객체 삭제 */
    private void deleteFileStorageObject(MarketingVO.FileVO row) {
        if (row == null || CommonUtil.isEmpty(row.getFilePath())) {
            return;
        }
        Map<String, Object> result = fileService.deleteStorageObjectByKey(row.getFilePath());
        if (result != null && Boolean.FALSE.equals(result.get("successYn"))) {
            logger.warn("[MKT] 스토리지 객체 정리 실패 - marketingFileId={}, filePath={}: {}",
                    row.getMarketingFileId(), row.getFilePath(), result.get("returnMsg"));
        }
    }

    /** 마케팅 첨부파일 미리보기/다운로드 URL 발급 */
    public Map<String, Object> viewMarketingFile(String marketingFileId) throws Exception {
        String fileId = stringValue(marketingFileId);
        if (CommonUtil.isEmpty(fileId)) {
            return downloadFallback("MISSING_MARKETING_FILE_ID");
        }
        MarketingVO.FileVO row = marketingDAO.selectMarketingFileById(fileSearch(fileId));
        if (row == null || CommonUtil.isEmpty(row.getFilePath())) {
            return downloadFallback("FILE_NOT_FOUND");
        }
        requireFileAccess(row, SessionUtil.getUserId());
        FileVO fileVo = new FileVO();
        fileVo.setFilePath(row.getFilePath());
        fileVo.setFileName(row.getFileNm());
        fileVo.setFileType(row.getFileType());
        return fileService.createViewPresignedUrlForStorageObject(fileVo);
    }

    /** viewMarketingFile 실패 응답 */
    private Map<String, Object> downloadFallback(String reason) {
        Map<String, Object> result = new HashMap<>();
        result.put("viewType", "DOWNLOAD");
        result.put("reason", reason);
        result.put("fileName", "");
        result.put("downloadUrl", "");
        return result;
    }

    // ── 기획서 ─────────────────────────────────────────────────────────────────

    /** 마케팅 기획서 조회 — 없으면 null. 프로젝트 목적은 포함하지 않는다 */
    public MarketingVO.PlanVO selectMarketingPlan(String marketingProjectId) throws Exception {
        requireProject(marketingProjectId);
        return toPlanResponse(marketingDAO.selectMarketingPlan(planSearch(marketingProjectId)));
    }

    /** 마케팅 기획서 생성/재생성 (동기). 이미 있으면 덮어쓴다 */
    public MarketingVO.PlanVO generateMarketingPlan(MarketingVO.PlanVO req) throws Exception {
        if (req == null) {
            throw new RuntimeException("요청 본문이 없습니다.");
        }
        String projectId = stringValue(req.getMarketingProjectId());
        MarketingVO.ProjectVO project = requireProjectMember(projectId, SessionUtil.getUserId());
        String goal = stringValue(req.getGoal());
        String productNm = stringValue(req.getProductNm());
        if (CommonUtil.isEmpty(goal)) {
            throw new RuntimeException("goal은 필수입니다.");
        }
        if (CommonUtil.isEmpty(productNm)) {
            throw new RuntimeException("productNm은 필수입니다.");
        }
        String userId = SessionUtil.getUserId();
        List<MarketingVO.FileVO> contentFiles = loadPlanFiles(projectId, req.getContentFileIds(), FILE_PURPOSE_CONTENT, userId);
        List<MarketingVO.FileVO> brandFiles = loadPlanFiles(projectId, req.getBrandFileIds(), FILE_PURPOSE_BRAND, userId);
        List<MarketingVO.FileVO> imageFiles = loadPlanFiles(projectId, req.getImageFileIds(), FILE_PURPOSE_IMAGE, userId);

        StringBuilder referenceContext = new StringBuilder();
        appendPlanSlot(referenceContext, "[콘텐츠 참고자료]", contentFiles);
        appendPlanSlot(referenceContext, "[브랜드 참고자료]", brandFiles);
        appendPlanSlot(referenceContext, "[이미지 참고자료]", imageFiles);
        Map<String, String> markers = new LinkedHashMap<>();
        markers.put("TODAY", LocalDate.now().toString());
        markers.put("PROJECT_NM", stringValue(project.getProjectNm()));
        markers.put("ORG_NM", stringValue(project.getOrgNm()));
        markers.put("PROJECT_OVERVIEW", stringValue(project.getProjectOverview()));
        markers.put("DUE_DT", stringValue(project.getDueDt()));
        markers.put("GOAL", goal);
        markers.put("PRODUCT_NM", productNm);
        markers.put("REQUEST_TXT", stringValue(req.getRequestTxt()));
        markers.put("TARGET_NM", stringValue(req.getTargetNm()));
        markers.put("REFERENCE_CONTEXT", referenceContext.toString().trim());
        MarketingVO.PlanVO parsed = generateMarketingPlanJson(PROMPT_ID_PLAN, markers);

        MarketingVO.PlanVO saveVO = new MarketingVO.PlanVO();
        saveVO.setMarketingProjectId(projectId);
        saveVO.setGoal(goal);
        saveVO.setProductNm(productNm);
        saveVO.setRequestTxt(stringValue(req.getRequestTxt()));
        saveVO.setTargetNm(stringValue(req.getTargetNm()));
        saveVO.setKeyMessage(parsed.getKeyMessage());
        saveVO.setRecommendChannels(parsed.getRecommendChannels());
        saveVO.setVisualTxt(parsed.getVisualTxt());
        saveVO.setSections(parsed.getSections());
        saveMarketingPlan(saveVO);
        return toPlanResponse(marketingDAO.selectMarketingPlan(planSearch(projectId)));
    }

    /** 마케팅 기획서 대화/프롬프트 수정 (동기) */
    public MarketingVO.PlanVO refineMarketingPlan(MarketingVO.PlanVO req) throws Exception {
        if (req == null) {
            throw new RuntimeException("요청 본문이 없습니다.");
        }
        String projectId = stringValue(req.getMarketingProjectId());
        requireProject(projectId);
        String message = stringValue(req.getMessage());
        if (CommonUtil.isEmpty(message)) {
            throw new RuntimeException("message는 필수입니다.");
        }
        MarketingVO.PlanVO current = toPlanResponse(marketingDAO.selectMarketingPlan(planSearch(projectId)));
        if (current == null) {
            throw new RuntimeException("기획서가 없습니다.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("goal", current.getGoal());
        payload.put("productNm", current.getProductNm());
        payload.put("requestTxt", current.getRequestTxt());
        payload.put("targetNm", current.getTargetNm());
        payload.put("keyMessage", current.getKeyMessage());
        payload.put("recommendChannels", current.getRecommendChannels());
        payload.put("visualTxt", current.getVisualTxt());
        payload.put("sections", current.getSections());
        Map<String, String> markers = new LinkedHashMap<>();
        markers.put("Q_CONTENT", message);
        markers.put("R_CONTENT", GSON.toJson(payload));
        markers.put("REFERENCE_CONTEXT", "");
        MarketingVO.PlanVO parsed = generateMarketingPlanJson(PROMPT_ID_PLAN_REFINE, markers);

        MarketingVO.PlanVO saveVO = new MarketingVO.PlanVO();
        saveVO.setMarketingProjectId(projectId);
        saveVO.setGoal(firstNonEmpty(parsed.getGoal(), current.getGoal()));
        saveVO.setProductNm(firstNonEmpty(parsed.getProductNm(), current.getProductNm()));
        saveVO.setRequestTxt(firstNonEmpty(parsed.getRequestTxt(), current.getRequestTxt()));
        saveVO.setTargetNm(firstNonEmpty(parsed.getTargetNm(), current.getTargetNm()));
        saveVO.setKeyMessage(firstNonEmpty(parsed.getKeyMessage(), current.getKeyMessage()));
        saveVO.setRecommendChannels(firstNonEmpty(parsed.getRecommendChannels(), current.getRecommendChannels()));
        saveVO.setVisualTxt(firstNonEmpty(parsed.getVisualTxt(), current.getVisualTxt()));
        saveVO.setSections(parsed.getSections() != null ? parsed.getSections() : current.getSections());
        saveMarketingPlan(saveVO);
        return toPlanResponse(marketingDAO.selectMarketingPlan(planSearch(projectId)));
    }

    /** 프로젝트 기획서 등록/수정 */
    private void saveMarketingPlan(MarketingVO.PlanVO saveVO) throws Exception {
        saveVO.setSectionsJson(GSON.toJson(saveVO.getSections()));
        if (marketingDAO.selectMarketingPlan(planSearch(saveVO.getMarketingProjectId())) == null) {
            saveVO.setMarketingPlanId(keyGenerate.generateTableKey("ML", "TB_MKT_PLAN", "MKT_PLAN_ID"));
            saveVO.setCreateUserId(SessionUtil.getUserId());
            marketingDAO.insertMarketingPlan(saveVO);
        } else {
            saveVO.setModifyUserId(SessionUtil.getUserId());
            if (marketingDAO.updateMarketingPlan(saveVO) != 1) {
                throw new RuntimeException("기획서를 찾을 수 없습니다.");
            }
        }
    }

    private String requireFilePurposeCd(String filePurposeCd) {
        String value = stringValue(filePurposeCd);
        if (CommonUtil.isEmpty(value)) {
            throw new RuntimeException("filePurposeCd는 필수입니다.");
        }
        if (!FILE_PURPOSE_CDS.contains(value)) {
            throw new RuntimeException("filePurposeCd는 001, 002, 003만 허용됩니다.");
        }
        return value;
    }

    /** 기획서 칸별 참고파일 확인 — 접근·용도 확인 후 임시 파일은 프로젝트로 연결한다 */
    private List<MarketingVO.FileVO> loadPlanFiles(String projectId, List<String> ids, String purposeCd, String userId)
            throws Exception {
        List<MarketingVO.FileVO> files = new ArrayList<>();
        if (ids == null) {
            return files;
        }
        for (String id : ids) {
            MarketingVO.FileVO file = marketingDAO.selectMarketingFileById(fileSearch(stringValue(id)));
            requireFileAccess(file, userId);
            if (!purposeCd.equals(file.getFilePurposeCd())
                    || (file.getMarketingProjectId() != null && !projectId.equals(file.getMarketingProjectId()))) {
                throw new RuntimeException("참고파일이 올바르지 않습니다.");
            }
            if (file.getMarketingProjectId() == null) {
                file.setMarketingProjectId(projectId);
                file.setModifyUserId(userId);
                if (marketingDAO.attachMarketingFile(file) != 1) {
                    throw new RuntimeException("임시 파일 연결에 실패했습니다.");
                }
            }
            files.add(file);
        }
        return files;
    }

    private void appendPlanSlot(StringBuilder labeled, String label, List<MarketingVO.FileVO> files) throws Exception {
        if (files == null || files.isEmpty()) {
            return;
        }
        String extracted = queryReferenceFiles(files, "", resolveMarketingPrompt(PROMPT_ID_REF), SessionUtil.getUserId());
        if (CommonUtil.isEmpty(extracted)) {
            return;
        }
        if (labeled.length() > 0) {
            labeled.append("\n\n");
        }
        labeled.append(label).append("\n").append(extracted);
    }

    /** DB 프롬프트로 기획서를 생성하고 JSON 계약 검증 */
    private MarketingVO.PlanVO generateMarketingPlanJson(String promptId, Map<String, String> markers) throws Exception {
        String prompt = replacePromptMarkers(resolveMarketingPrompt(promptId), markers);
        JsonObject json = JsonParser.parseString(stripJsonFence(chatbotService.callAiSummary(prompt, "marketing_plan", null))).getAsJsonObject();
        for (String key : List.of("goal", "productNm", "requestTxt", "targetNm", "keyMessage", "recommendChannels", "visualTxt")) {
            if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.get(key).getAsJsonPrimitive().isString()) {
                throw new RuntimeException("기획서 응답 항목을 확인해 주세요: " + key);
            }
        }
        if (!json.has("sections") || !json.get("sections").isJsonArray() || json.getAsJsonArray("sections").size() != 7) {
            throw new RuntimeException("기획서 본문 7개 항목이 필요합니다.");
        }
        MarketingVO.PlanVO plan = GSON.fromJson(json, MarketingVO.PlanVO.class);
        for (MarketingVO.PlanSectionVO section : plan.getSections()) {
            if (section == null || CommonUtil.isEmpty(section.getTitle()) || CommonUtil.isEmpty(section.getBody())) {
                throw new RuntimeException("기획서 본문이 비어 있습니다.");
            }
        }
        return plan;
    }

    /** 프롬프트의 {{MARKER}}를 값으로 치환한다. 값이 없는 마커는 예외 */
    private String replacePromptMarkers(String template, Map<String, String> values) {
        Matcher matcher = Pattern.compile("\\{\\{([A-Z_]+)\\}\\}").matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            if (!values.containsKey(matcher.group(1))) {
                throw new RuntimeException("프롬프트 마커를 확인해 주세요: " + matcher.group(1));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(CommonUtil.nullToBlank(values.get(matcher.group(1)))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private MarketingVO.PlanVO toPlanResponse(MarketingVO.PlanVO raw) {
        if (raw == null) {
            return null;
        }
        raw.setSections(parseStoredPlanSections(raw.getSectionsJson()));
        return raw;
    }

    private List<MarketingVO.PlanSectionVO> parseStoredPlanSections(String sectionsJson) {
        if (CommonUtil.isEmpty(sectionsJson)) {
            return new ArrayList<>();
        }
        try {
            List<MarketingVO.PlanSectionVO> sections = GSON.fromJson(
                    sectionsJson, new TypeToken<List<MarketingVO.PlanSectionVO>>() { }.getType());
            return sections != null ? sections : new ArrayList<>();
        } catch (Exception e) {
            logger.warn("[MKT] 기획서 SECTIONS_JSON 파싱 실패", e);
            return new ArrayList<>();
        }
    }

    private String firstNonEmpty(String primary, String fallback) {
        return CommonUtil.isNotEmpty(stringValue(primary)) ? stringValue(primary) : stringValue(fallback);
    }

    // ── 조회 ───────────────────────────────────────────────────────────────────

    /** 마케팅 에이전트 목록 조회 (SVC_TY = 'K') */
    public Map<String, Object> selectMarketingAgents() throws Exception {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (ChatbotVO agent : chatbotService.selectAgentListForChat(new ChatbotVO())) {
            if (!"K".equals(agent.getSvcTy())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agentId", agent.getAgentId());
            item.put("agentNm", agent.getAgentNm());
            item.put("colorHex", agent.getColorHex());
            item.put("iconClassNm", agent.getIconClassNm());
            item.put("config", agent.getSubCfg() == null ? new HashMap<String, Object>()
                    : agent.getSubCfg().getAdditionalConfigMap());
            agents.add(item);
        }
        Map<String, Object> resultMap = successResult();
        resultMap.put("list", agents);
        return resultMap;
    }

    /** 마케팅 콘텐츠 목록 조회 */
    public Map<String, Object> selectMarketingList(MarketingVO searchVO) throws Exception {
        Map<String, Object> resultMap = successResult();
        List<Map<String, Object>> list = new ArrayList<>();
        if (searchVO == null || CommonUtil.isEmpty(searchVO.getMarketingProjectId())) {
            resultMap.put("list", list);
            return resultMap;
        }
        requireProject(searchVO.getMarketingProjectId());
        for (MarketingVO row : marketingDAO.selectMarketingList(searchVO)) {
            list.add(toSummary(row, contentRequest(row)));
        }
        resultMap.put("list", list);
        return resultMap;
    }

    /** 마케팅 콘텐츠 상세 조회 */
    public Map<String, Object> selectMarketing(String mktContentId) throws Exception {
        String userId = SessionUtil.getUserId();
        MarketingVO marketing = requireMarketing(mktContentId, userId);
        MarketingVO.ProjectVO project = marketingDAO.selectMarketingProject(projectSearch(marketing.getMarketingProjectId()));
        MarketingVO.ReviewVO review = marketingDAO.selectLatestReview(mktContentId);
        MarketingVO.ApprovalVO approval = marketingDAO.selectLatestApproval(mktContentId);
        boolean currentReview = isCurrentReview(marketing, review);

        Map<String, Object> request = contentRequest(marketing);
        Map<String, Object> detail = toSummary(marketing, request);
        detail.putAll(successResult());
        detail.put("request", request);
        detail.put("selectedVariantId", marketing.getSelectedVariantNo());
        detail.put("contentVersion", marketing.getContentVersion());
        detail.put("review", currentReview ? toReviewResponse(review) : null);
        detail.put("approval", isCurrentApproval(marketing, approval, project.getApproverUserId()) ? approval : null);
        detail.put("approverUserId", project.getApproverUserId());
        detail.put("approverUserNm", project.getApproverUserNm());
        detail.put("canApprove", userId.equals(project.getApproverUserId())
                && currentReview && !VERDICT_FAIL.equals(review.getVerdict())
                && STATUS_APPROVAL_REQUIRED.equals(marketing.getStatusCd()));
        detail.put("schedule", toSchedule(marketing));
        detail.put("result", toResult(marketing, marketingDAO.selectMarketingContents(contentSearch(mktContentId))));
        return detail;
    }

    /** 목록/상세 공통 요약 */
    private Map<String, Object> toSummary(MarketingVO row, Map<String, Object> request) {
        List<String> summaryLabels = new ArrayList<>();
        if (CommonUtil.isNotEmpty(row.getContentType())) {
            summaryLabels.add(row.getContentType());
        }
        String channel = resolveChannelValue(request, null);
        if (CommonUtil.isNotEmpty(channel)) {
            summaryLabels.add(channel);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contentId", row.getMktContentId());
        result.put("agentId", row.getAgentId());
        result.put("marketingProjectId", row.getMarketingProjectId());
        result.put("title", row.getTitle());
        result.put("outputMode", row.getOutputMode());
        result.put("statusCd", row.getStatusCd());
        result.put("statusNm", row.getStatusNm());
        result.put("aiStatusCd", row.getAiStatusCd());
        result.put("aiStatusNm", row.getAiStatusNm());
        result.put("publishScheduledDt", CommonUtil.nullToBlank(row.getPublishScheduledDt()));
        result.put("publishedYn", CommonUtil.nvl(row.getPublishedYn(), "N"));
        result.put("summaryLabels", summaryLabels);
        result.put("createUserNm", row.getCreateUserNm());
        result.put("createDt", row.getCreateDt());
        return result;
    }

    private Map<String, Object> toResult(MarketingVO marketing, List<MarketingVO> contents) {
        List<Map<String, Object>> variants = new ArrayList<>();
        List<Map<String, Object>> images = new ArrayList<>();
        boolean imageOnly = PART_IMAGE.equals(marketing.getOutputMode());
        for (MarketingVO content : contents) {
            boolean recommended = "Y".equals(content.getRecommendYn());
            boolean canRestore = "Y".equals(content.getHasPreviousYn());
            String label = CommonUtil.nullToBlank(content.getContentLabel());
            int variantNo = content.getVariantNo();
            if (!imageOnly) {
                String text = CommonUtil.nullToBlank(content.getTextContent());
                Map<String, Object> variant = new LinkedHashMap<>();
                variant.put("id", variantNo);
                variant.put("label", label);
                variant.put("recommended", recommended);
                variant.put("content", text);
                variant.put("canRestore", canRestore);
                String prompt = stringValue(parseRequest(content.getTextPromptJson()).get("prompt"));
                if (CommonUtil.isNotEmpty(prompt)) {
                    variant.put("prompt", prompt);
                }
                variants.add(variant);
            }
            if (CommonUtil.isNotEmpty(content.getImageFile())) {
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("id", variantNo);
                image.put("url", content.getImageFile());
                image.put("label", label);
                image.put("recommended", recommended);
                image.put("canRestore", canRestore);
                String prompt = stringValue(parseRequest(content.getImagePromptJson()).get("prompt"));
                if (CommonUtil.isNotEmpty(prompt)) {
                    image.put("prompt", prompt);
                }
                images.add(image);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", marketing.getTitle());
        result.put("mode", marketing.getOutputMode());
        result.put("variants", variants);
        result.put("images", images);
        return result;
    }

    // ── 내보내기 ───────────────────────────────────────────────────────────────

    /** 마케팅 콘텐츠 내보내기 HTML 조회 */
    public String exportMarketingContentHtml(String mktContentId) throws Exception {
        MarketingVO marketing = requireMarketing(mktContentId, SessionUtil.getUserId());
        List<MarketingVO> contents = marketingDAO.selectMarketingContents(contentSearch(mktContentId));
        boolean hasText = contents.stream().anyMatch(c -> CommonUtil.isNotEmpty(c.getTextContent()));
        boolean hasImage = contents.stream().anyMatch(c -> CommonUtil.isNotEmpty(c.getImageFile()));
        if (!hasText && !hasImage) {
            throw new RuntimeException("내보낼 시안이 없습니다.");
        }
        return buildMarketingExportHtml(marketing, contentRequest(marketing), contents);
    }

    /** 저장된 템플릿에 콘텐츠 결과를 직접 채운다 */
    private String buildMarketingExportHtml(MarketingVO marketing, Map<String, Object> request,
            List<MarketingVO> contents) throws Exception {
        TmplVO template = tmplService.selectTmplList().stream()
                .filter(t -> MARKETING_EXPORT_TMPL_ID.equals(t.getTmplId())).findFirst()
                .orElseThrow(() -> new RuntimeException("내보내기 템플릿을 찾을 수 없습니다."));
        if (CommonUtil.isEmpty(template.getTmplHtml()) || template.getFields() == null) {
            throw new RuntimeException("내보내기 템플릿 설정을 확인해 주세요.");
        }
        Set<String> fields = new LinkedHashSet<>();
        for (TmplVO.TmplFieldVO field : template.getFields()) {
            fields.add(field.getJsonKey());
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", HtmlUtils.htmlEscape(CommonUtil.nullToBlank(marketing.getTitle())));
        values.put("metaLine", HtmlUtils.htmlEscape(buildExportMetaLine(marketing, request)));
        values.put("conditionTableHtml", buildConditionTableHtml(request));
        StringBuilder variants = new StringBuilder();
        for (MarketingVO content : contents) {
            variants.append("<section><h3>시안 ").append(content.getVariantNo()).append(" · ")
                    .append(HtmlUtils.htmlEscape(CommonUtil.nullToBlank(content.getContentLabel()))).append("</h3><p>")
                    .append(HtmlUtils.htmlEscape(CommonUtil.nullToBlank(content.getTextContent())).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>"))
                    .append("</p>");
            if (CommonUtil.isNotEmpty(content.getImageFile())) {
                variants.append("<img src=\"").append(HtmlUtils.htmlEscape(content.getImageFile())).append("\" alt=\"시안 이미지\" />");
            }
            variants.append("</section>");
        }
        values.put("variants", variants.toString());
        for (String key : values.keySet()) {
            if (!fields.contains(key) || !template.getTmplHtml().contains("{{" + key + "}}")) {
                throw new RuntimeException("내보내기 템플릿 항목이 없습니다: " + key);
            }
        }
        Matcher matcher = Pattern.compile("\\{\\{(title|metaLine|conditionTableHtml|variants)\\}\\}").matcher(template.getTmplHtml());
        StringBuffer html = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(html, Matcher.quoteReplacement(values.get(matcher.group(1))));
        }
        matcher.appendTail(html);
        return html.toString();
    }

    /** 생성 조건 요약 표 */
    private String buildConditionTableHtml(Map<String, Object> request) {
        Map<String, String> conditionLabels = Map.ofEntries(
                Map.entry("contentType", "콘텐츠 유형"), Map.entry("channel", "게시 채널"), Map.entry("customChannel", "직접 입력 채널"),
                Map.entry("purpose", "목적"), Map.entry("audience", "대상 고객"), Map.entry("keyMessage", "핵심 메시지"),
                Map.entry("additionalRequirements", "추가 요청사항"), Map.entry("variantCount", "시안 수"), Map.entry("tones", "톤앤매너"),
                Map.entry("length", "분량"), Map.entry("customCallToAction", "유도할 행동"), Map.entry("visualStyle", "비주얼 방향"),
                Map.entry("aspectRatio", "화면 비율"), Map.entry("brandColors", "브랜드 컬러"), Map.entry("promotionInformation", "홍보 정보"),
                Map.entry("customPurpose", "직접 입력 목적"), Map.entry("customAudience", "직접 입력 대상 고객"),
                Map.entry("customTone", "직접 입력 어조"), Map.entry("customLength", "직접 입력 분량"),
                Map.entry("outputSections", "문안 구성"), Map.entry("includeHashtags", "해시태그 포함"), Map.entry("allowEmoji", "이모지 허용"),
                Map.entry("imageUsage", "이미지 용도"), Map.entry("snsPlatform", "SNS 채널"), Map.entry("imageType", "이미지 스타일"),
                Map.entry("customAspectRatio", "직접 입력 화면 비율"), Map.entry("imageText", "이미지 문구"), Map.entry("outputs", "출력 유형"));
        StringBuilder conditions = new StringBuilder("<table><tbody>");
        for (Map.Entry<String, Object> entry : request.entrySet()) {
            if (!conditionLabels.containsKey(entry.getKey())) {
                continue;
            }
            conditions.append("<tr><th>").append(HtmlUtils.htmlEscape(conditionLabels.get(entry.getKey()))).append("</th><td>")
                    .append(HtmlUtils.htmlEscape(stringValue(entry.getValue()))).append("</td></tr>");
        }
        conditions.append("</tbody></table>");
        return conditions.toString();
    }

    /** 표지 메타줄 */
    private String buildExportMetaLine(MarketingVO marketing, Map<String, Object> request) {
        List<String> metaParts = new ArrayList<>();
        if (CommonUtil.isNotEmpty(marketing.getCreateDt())) {
            metaParts.add("생성일시 " + marketing.getCreateDt());
        }
        String channelLabel = resolveChannelValue(request, resolveChannelLabels(marketing.getAgentId()));
        if (CommonUtil.isNotEmpty(channelLabel)) {
            metaParts.add("게시 채널 " + channelLabel);
        }
        String outputMode = marketing.getOutputMode();
        metaParts.add("콘텐츠 유형 " + (PART_TEXT.equals(outputMode) ? "문구" : PART_IMAGE.equals(outputMode) ? "이미지" : "통합"));
        return String.join("   ·   ", metaParts);
    }

    /** IMAGE_FILE data URI를 디코딩한다 */
    private byte[] decodeImageDataUri(String imageFile) {
        if (CommonUtil.isEmpty(imageFile)) {
            return null;
        }
        String base64 = imageFile.trim();
        int marker = base64.indexOf("base64,");
        if (marker >= 0) {
            base64 = base64.substring(marker + "base64,".length()).trim();
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            logger.warn("[MKT] 이미지 base64 디코딩 실패: {}", e.getMessage());
            return null;
        }
    }

    // ── 생성 · 수정 · 삭제 ─────────────────────────────────────────────────────────

    /** 마케팅 콘텐츠 생성 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createMarketing(Map<String, Object> input) throws Exception {
        if (input == null) {
            throw new RuntimeException("요청 본문이 없습니다.");
        }
        Map<String, Object> request = new LinkedHashMap<>(input);
        String projectId = stringValue(request.remove("marketingProjectId"));
        String userId = SessionUtil.getUserId();
        requireProject(projectId);

        MarketingVO marketing = new MarketingVO();
        marketing.setMktContentId(keyGenerate.generateTableKey("MK", "TB_MKT_CONTENT", "MKT_CONTENT_ID"));
        marketing.setMarketingProjectId(projectId);
        marketing.setCreateUserId(userId);
        marketing.setModifyUserId(userId);
        marketing.setAgentId(stringValue(request.remove("agentId")));
        if (CommonUtil.isEmpty(marketing.getAgentId())) {
            throw new RuntimeException("에이전트 ID는 필수입니다.");
        }
        AgentVO agentSearch = new AgentVO();
        agentSearch.setAgentId(marketing.getAgentId());
        AgentVO agent = agentDAO.selectAgent(agentSearch);
        if (agent == null || !"K".equals(agent.getSvcTy())) {
            throw new RuntimeException("마케팅 에이전트를 확인해 주세요.");
        }
        marketing.setContentType(stringValue(request.get("contentType")));
        if (CommonUtil.isEmpty(marketing.getContentType())) {
            throw new RuntimeException("콘텐츠 유형은 필수입니다.");
        }
        marketing.setTitle(buildFallbackTitle(request));
        marketing.setOutputMode(resolveOutputMode(request.get("outputs")));
        marketing.setVariantCount(parseVariantCount(request.get("variantCount")));
        marketing.setStatusCd(STATUS_WRITING);
        marketing.setAiStatusCd(AI_STATUS_WAITING);
        Object referenceFileIds = request.get("referenceMarketingFileIds");
        if (referenceFileIds instanceof List && ((List<?>) referenceFileIds).size() > REFERENCE_FILE_MAX) {
            throw new RuntimeException("참고파일은 최대 " + REFERENCE_FILE_MAX + "개까지 선택할 수 있습니다.");
        }
        validateReferenceFiles(projectId, request.get("referenceMarketingFileIds"), userId);
        marketing.setRequestJson(GSON.toJson(storedRequest(request)));
        marketingDAO.insertMarketing(marketing);

        Map<String, Object> resultMap = successResult();
        resultMap.put("contentId", marketing.getMktContentId());
        return resultMap;
    }

    /** outputs[] → OUTPUT_MODE (TEXT | IMAGE | BOTH) */
    @SuppressWarnings("unchecked")
    private String resolveOutputMode(Object value) {
        List<String> outputs = (value instanceof List)
                ? (List<String>) value
                : Collections.<String>emptyList();
        boolean hasImage = outputs.contains(PART_IMAGE);
        if (outputs.contains(PART_TEXT) && hasImage) {
            return "BOTH";
        }
        return hasImage ? PART_IMAGE : PART_TEXT;
    }

    /** 마케팅 콘텐츠 제목 수정 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateTitle(String mktContentId, String title) throws Exception {
        String userId = SessionUtil.getUserId();
        MarketingVO row = requireMarketing(mktContentId, userId);
        String trimmed = truncate(stringValue(title), TITLE_MAX_LENGTH);
        if (CommonUtil.isEmpty(trimmed)) {
            return failResult("제목을 입력해 주세요");
        }
        if (saveTitle(mktContentId, userId, trimmed) != 1) {
            return failResult("제목 저장에 실패했습니다");
        }
        invalidateApproval(row, contentRequest(row), userId);
        return successResult();
    }

    /** TITLE 저장 */
    private int saveTitle(String mktContentId, String userId, String title) throws Exception {
        MarketingVO dataVO = contentSearch(mktContentId);
        dataVO.setTitle(title);
        dataVO.setModifyUserId(userId);
        return marketingDAO.updateMarketingTitle(dataVO);
    }

    /** 발행 설정 응답 */
    private Map<String, Object> toSchedule(MarketingVO row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contentId", row.getMktContentId());
        result.put("publishType", row.getPublishTypeCd());
        result.put("publishScheduledDt", CommonUtil.nullToBlank(row.getPublishScheduledDt()));
        result.put("alertHour", row.getAlertHour());
        result.put("scheduleStateCd", STATUS_PUBLISHED.equals(row.getStatusCd()) ? "DONE"
                : STATUS_SCHEDULED.equals(row.getStatusCd()) ? "QUEUED" : "007".equals(row.getStatusCd()) ? "FAILED" : "WAITING");
        return result;
    }

    /**
     * 발행 설정 저장 — publishScheduledDt/publishType(NOW|SCHEDULE|HOLD)/alertHour.
     * 현재 선택 시안의 승인 상태와 예약 일시를 검증한다.
     */
    public Map<String, Object> updateSchedule(String mktContentId, Map<String, Object> request) throws Exception {
        MarketingVO row = requireMarketing(mktContentId, SessionUtil.getUserId());
        String type = stringValue(request.get("publishType"));
        if (!Set.of("NOW", "SCHEDULE", "HOLD").contains(type)) {
            throw new RuntimeException("발행 방식을 확인해 주세요.");
        }
        String date = stringValue(request.get("publishScheduledDt"));
        if ("SCHEDULE".equals(type)) {
            LocalDateTime at = parsePublishScheduledDt(date);
            if (at == null || !at.isAfter(LocalDateTime.now())) {
                throw new RuntimeException("예약 일시는 현재보다 이후로 지정해 주세요.");
            }
            if (at.getMinute() != 0 || at.getSecond() != 0) {
                throw new RuntimeException("예약은 시간 단위로만 가능합니다. 분·초는 00으로 지정해 주세요.");
            }
        }
        Object alert = request.get("alertHour");
        if (!(alert instanceof Number) || ((Number) alert).intValue() < 0 || ((Number) alert).intValue() > 168) {
            throw new RuntimeException("알림 시간을 0~168 사이의 정수로 입력해 주세요.");
        }
        if (!"HOLD".equals(type)) {
            requireApproved(row);
        }

        String currentStatusCd = row.getStatusCd();
        row.setPublishTypeCd(type);
        row.setPublishScheduledDt("SCHEDULE".equals(type) ? date : null);
        row.setAlertHour(((Number) alert).intValue());
        row.setPublishedYn("NOW".equals(type) ? "Y" : "N");
        if ("NOW".equals(type)) {
            row.setStatusCd(STATUS_PUBLISHED);
        } else if ("SCHEDULE".equals(type)) {
            row.setStatusCd(STATUS_SCHEDULED);
        } else if (Set.of(STATUS_APPROVED, STATUS_SCHEDULED, STATUS_PUBLISHED).contains(currentStatusCd)) {
            row.setStatusCd(STATUS_APPROVED);
        }
        row.setModifyUserId(SessionUtil.getUserId());
        if (marketingDAO.updateMarketingSchedule(row) != 1) {
            return failResult("발행 설정 저장에 실패했습니다");
        }
        Map<String, Object> result = successResult();
        result.put("data", toSchedule(row));
        return result;
    }

    /** 발행 완료 표시/해제 */
    public Map<String, Object> updatePublished(String mktContentId, String publishedYn) throws Exception {
        if (!"Y".equals(publishedYn) && !"N".equals(publishedYn)) {
            return failResult("publishedYn은 Y 또는 N이어야 합니다");
        }
        MarketingVO row = requireMarketing(mktContentId, SessionUtil.getUserId());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("publishType", "Y".equals(publishedYn) ? "NOW" : "HOLD");
        request.put("publishScheduledDt", "");
        request.put("alertHour", row.getAlertHour() == null ? 0 : row.getAlertHour());
        return updateSchedule(mktContentId, request);
    }

    /** 마케팅 콘텐츠 삭제 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteMarketing(String mktContentId) throws Exception {
        MarketingVO row = requireMarketing(mktContentId, SessionUtil.getUserId());
        marketingDAO.deleteMarketingHistories(row);
        marketingDAO.deleteMarketingContents(row);
        if (marketingDAO.deleteMarketing(row) != 1) {
            return failResult("삭제에 실패했습니다");
        }
        return successResult();
    }

    /** 사용할 시안 선택 — 선택 변경도 새 검수·승인 대상으로 처리한다 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> selectVariant(String mktContentId, int variantNo) throws Exception {
        MarketingVO row = requireMarketing(mktContentId, SessionUtil.getUserId());
        if (findVariant(mktContentId, variantNo) == null) {
            return failResult("시안을 찾을 수 없습니다");
        }
        if (!Objects.equals(row.getSelectedVariantNo(), variantNo)) {
            row.setSelectedVariantNo(variantNo);
            invalidateApproval(row, contentRequest(row), SessionUtil.getUserId());
        }
        return successResult();
    }

    /** 마케팅 시안 문안 직접 수정 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateVariantText(String mktContentId, int variantNo, Map<String, Object> request) throws Exception {
        MarketingVO row = requireMarketing(mktContentId, SessionUtil.getUserId());
        MarketingVO previous = findVariant(mktContentId, variantNo);
        if (previous == null) {
            return failResult("수정할 시안을 찾을 수 없습니다");
        }
        String textContent = stringValue(request.get("textContent"));
        if (CommonUtil.isEmpty(textContent)) {
            return failResult("저장할 문안이 없습니다");
        }
        return saveVariant(row, previous, textContent, null, null);
    }

    /** 마케팅 시안 보완 */
    public Map<String, Object> refineMarketing(String mktContentId, int variantNo, Map<String, Object> request) throws Exception {
        String userId = SessionUtil.getUserId();
        MarketingVO row = requireMarketing(mktContentId, userId);
        MarketingVO previous = findVariant(mktContentId, variantNo);
        if (previous == null) {
            return failResult("수정할 시안을 찾을 수 없습니다");
        }
        String instruction = stringValue(request.get("request"));
        if (CommonUtil.isEmpty(instruction)) {
            return failResult("수정 요청사항을 입력해 주세요");
        }
        requireEditable(row);
        boolean image = PART_IMAGE.equals(stringValue(request.get("type")));
        if (CommonUtil.isEmpty(image ? previous.getImageFile() : previous.getTextContent())) {
            return failResult("보완할 시안이 없습니다");
        }

        Map<String, Object> stored = contentRequest(row);
        String reference = resolveReferenceContext(row.getMarketingProjectId(), row.getAgentId(),
                stored.get("referenceMarketingFileIds"), userId);
        if (image) {
            String prompt = buildGenerationPrompt(PROMPT_ID_IMAGE_REFINE, row.getAgentId(), stored, reference,
                    previous.getTextContent(), instruction);
            String refined = refineVariantImage(prompt, row.getAgentId(), previous.getImageFile(), imageAspectRatio(stored), userId);
            if (CommonUtil.isEmpty(refined)) {
                return failResult("이미지 재생성에 실패했습니다");
            }
            return saveVariant(row, previous, null, refined, prompt);
        }
        String prompt = buildGenerationPrompt(PROMPT_ID_TEXT_REFINE, row.getAgentId(), stored, reference,
                previous.getTextContent(), instruction);
        String refined = generateVariantText(prompt, "marketing_refine");
        if (CommonUtil.isEmpty(refined)) {
            return failResult("글 수정에 실패했습니다");
        }
        return saveVariant(row, previous, refined, null, prompt);
    }

    private MarketingVO findVariant(String mktContentId, int variantNo) throws Exception {
        MarketingVO searchVO = new MarketingVO();
        searchVO.setMktContentId(mktContentId);
        searchVO.setVariantNo(variantNo);
        return marketingDAO.selectMarketingContent(searchVO);
    }

    /** 시안 부분 갱신 후 승인 해제 */
    private Map<String, Object> saveVariant(MarketingVO row, MarketingVO previous,
            String textContent, String imageFile, String prompt) throws Exception {
        MarketingVO content = new MarketingVO();
        content.setMktContentVariantId(previous.getMktContentVariantId());
        content.setMktContentId(row.getMktContentId());
        content.setTextContent(textContent);
        content.setImageFile(imageFile);
        if (textContent != null && prompt != null) {
            content.setTextPromptJson(GSON.toJson(Map.of("promptId", PROMPT_ID_TEXT_REFINE, "prompt", prompt)));
        }
        if (imageFile != null && prompt != null) {
            content.setImagePromptJson(GSON.toJson(Map.of("promptId", PROMPT_ID_IMAGE_REFINE, "prompt", prompt)));
        }
        content.setModifyUserId(SessionUtil.getUserId());
        if (marketingDAO.updateMarketingContent(content) != 1) {
            return failResult("시안 저장에 실패했습니다");
        }
        invalidateApproval(row, contentRequest(row), SessionUtil.getUserId());
        return successResult();
    }

    /** 시안 직전 버전으로 되돌리기 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> restoreVariant(String mktContentId, int variantNo) throws Exception {
        String userId = SessionUtil.getUserId();
        MarketingVO row = requireMarketing(mktContentId, userId);
        MarketingVO previous = findVariant(mktContentId, variantNo);
        if (previous == null) {
            return failResult("시안을 찾을 수 없습니다");
        }
        MarketingVO restoreVO = new MarketingVO();
        restoreVO.setMktContentVariantId(previous.getMktContentVariantId());
        restoreVO.setMktContentId(mktContentId);
        restoreVO.setModifyUserId(userId);
        if (marketingDAO.restoreMarketingContentPrevious(restoreVO) != 1) {
            return failResult("되돌릴 이전 버전이 없습니다");
        }
        invalidateApproval(row, contentRequest(row), userId);
        return successResult();
    }

    // ── AI 검수 / 승인 / 캘린더 ─────────────────────────────────────────────────

    /** 선택 시안의 실제 문구·이미지 검수 */
    public Map<String, Object> runReview(String mktContentId) throws Exception {
        String userId = SessionUtil.getUserId();
        MarketingVO row = requireMarketing(mktContentId, userId);
        if (!AI_STATUS_DONE.equals(row.getAiStatusCd()) || row.getSelectedVariantNo() == null) {
            return failResult("시안 생성·선택 후 검수해 주세요");
        }
        requireEditable(row);
        MarketingVO target = findVariant(mktContentId, row.getSelectedVariantNo());
        boolean needText = !PART_IMAGE.equals(row.getOutputMode());
        boolean needImage = !PART_TEXT.equals(row.getOutputMode());
        if (target == null || (needText && CommonUtil.isEmpty(target.getTextContent()))
                || (needImage && CommonUtil.isEmpty(target.getImageFile()))) {
            return failResult("검수할 시안이 완성되지 않았습니다");
        }

        Map<String, Object> request = contentRequest(row);
        String reference = resolveReferenceContext(row.getMarketingProjectId(), row.getAgentId(),
                request.get("referenceMarketingFileIds"), userId);
        String prompt = buildGenerationPrompt(PROMPT_ID_REVIEW, row.getAgentId(), request, reference, target.getTextContent(), null);
        String response = needImage ? reviewMarketingImage(row, target, prompt, userId)
                : chatbotService.callAiSummary(prompt, "marketing_review", null);
        ParsedReview parsed = parseReviewResponse(mktContentId, response, row.getOutputMode());

        // 새 검수는 이전 승인을 무효화하고 올라간 버전 기준으로 저장한다.
        invalidateApproval(row, request, userId);
        MarketingVO.ReviewVO review = new MarketingVO.ReviewVO();
        review.setReviewId(keyGenerate.generateTableKey("MH", "TB_MKT_WORKFLOW_HIST", "MKT_HIST_ID"));
        review.setContentId(mktContentId);
        review.setVariantNo(row.getSelectedVariantNo());
        review.setContentVersion(row.getContentVersion());
        review.setOutputMode(row.getOutputMode());
        review.setScore(parsed.score);
        review.setVerdict(parsed.verdict);
        review.setChecksJson(GSON.toJson(parsed.checks));
        review.setIssuesJson(GSON.toJson(parsed.issues));
        review.setCreateUserId(userId);
        marketingDAO.insertReviewHistory(review);
        marketingDAO.pruneReviewHistory(mktContentId);

        MarketingVO statusVO = contentSearch(mktContentId);
        statusVO.setStatusCd(VERDICT_FAIL.equals(parsed.verdict) ? STATUS_REVIEWING : STATUS_APPROVAL_REQUIRED);
        statusVO.setModifyUserId(userId);
        marketingDAO.updateMarketingStatus(statusVO);

        Map<String, Object> result = successResult();
        result.put("data", toReviewResponse(marketingDAO.selectLatestReview(mktContentId)));
        return result;
    }

    /** 이미지 시안은 임시 스토리지 파일로 올려 /file_query로 검수한다 */
    private String reviewMarketingImage(MarketingVO row, MarketingVO target, String prompt, String userId) {
        byte[] bytes = decodeImageDataUri(target.getImageFile());
        if (bytes == null || bytes.length == 0) {
            throw new RuntimeException("검수할 이미지를 확인해 주세요.");
        }
        String mime = target.getImageFile().startsWith("data:") && target.getImageFile().contains(";")
                ? target.getImageFile().substring(5, target.getImageFile().indexOf(';')) : "image/png";
        String extension = mime.substring(mime.indexOf('/') + 1);
        MarketingVO.FileVO file = new MarketingVO.FileVO();
        file.setFilePath("marketing/" + userId + "/review/" + UUID.randomUUID() + "." + extension);
        file.setFileNm("review." + extension);
        file.setFileType(mime);
        file.setFileSize((long) bytes.length);
        try {
            fileService.uploadBytes(file.getFilePath(), bytes, mime);
            return queryReferenceFiles(List.of(file), row.getAgentId(), prompt, userId);
        } finally {
            deleteFileStorageObject(file);
        }
    }

    /** 검수 이슈 수정안 적용 — issueId 또는 "ALL". 새로 적용되는 건만 실제 문안에 반영한다 */
    public Map<String, Object> applyFix(String mktContentId, String issueId) throws Exception {
        String userId = SessionUtil.getUserId();
        MarketingVO row = requireMarketing(mktContentId, userId);
        MarketingVO.ReviewVO review = marketingDAO.selectLatestReview(mktContentId);
        if (!isCurrentReview(row, review)) {
            return failResult("현재 시안을 먼저 검수해 주세요");
        }
        requireEditable(row);
        List<MarketingVO.IssueVO> issues = parseStoredIssues(review.getIssuesJson());
        List<String> textFixes = new ArrayList<>();
        List<String> imageFixes = new ArrayList<>();
        for (MarketingVO.IssueVO issue : issues) {
            if ("Y".equals(issue.getFixAppliedYn()) || !("ALL".equals(issueId) || Objects.equals(issueId, issue.getIssueId()))) {
                continue;
            }
            if (PART_IMAGE.equals(issue.getTargetType())) {
                imageFixes.add(issue.getFixSuggestion());
            } else {
                textFixes.add(issue.getFixSuggestion());
            }
            issue.setFixAppliedYn("Y");
        }
        if (textFixes.isEmpty() && imageFixes.isEmpty()) {
            return failResult("적용할 수정안이 없습니다");
        }
        MarketingVO target = findVariant(mktContentId, row.getSelectedVariantNo());
        if (target == null) {
            return failResult("시안을 찾을 수 없습니다");
        }

        Map<String, Object> request = contentRequest(row);
        String reference = resolveReferenceContext(row.getMarketingProjectId(), row.getAgentId(),
                request.get("referenceMarketingFileIds"), userId);
        MarketingVO content = new MarketingVO();
        content.setMktContentId(mktContentId);
        content.setMktContentVariantId(target.getMktContentVariantId());
        content.setModifyUserId(userId);
        if (!textFixes.isEmpty()) {
            String prompt = buildGenerationPrompt(PROMPT_ID_TEXT_REFINE, row.getAgentId(), request, reference,
                    target.getTextContent(), String.join("\n", textFixes));
            String text = generateVariantText(prompt, "marketing_refine");
            if (CommonUtil.isEmpty(text)) {
                return failResult("문구 수정안 적용에 실패했습니다");
            }
            content.setTextContent(text);
            content.setTextPromptJson(GSON.toJson(Map.of("promptId", PROMPT_ID_TEXT_REFINE, "prompt", prompt)));
        }
        if (!imageFixes.isEmpty()) {
            String prompt = buildGenerationPrompt(PROMPT_ID_IMAGE_REFINE, row.getAgentId(), request, reference,
                    target.getTextContent(), String.join("\n", imageFixes));
            String image = refineVariantImage(prompt, row.getAgentId(), target.getImageFile(), imageAspectRatio(request), userId);
            if (CommonUtil.isEmpty(image)) {
                return failResult("이미지 수정안 적용에 실패했습니다");
            }
            content.setImageFile(image);
            content.setImagePromptJson(GSON.toJson(Map.of("promptId", PROMPT_ID_IMAGE_REFINE, "prompt", prompt)));
        }

        if (marketingDAO.updateMarketingContent(content) != 1) {
            return failResult("수정안 저장에 실패했습니다");
        }
        invalidateApproval(row, request, userId);
        review.setIssuesJson(GSON.toJson(issues));
        marketingDAO.updateReviewIssues(review);
        Map<String, Object> result = successResult();
        result.put("data", toReviewResponse(review));
        return result;
    }

    private static final class ParsedReview {
        List<MarketingVO.CheckItemVO> checks;
        List<MarketingVO.IssueVO> issues;
        int score;
        String verdict;
    }

    /** 검수 응답이 계약을 지키지 않으면 저장하지 않는다. */
    private ParsedReview parseReviewResponse(String mktContentId, String raw, String outputMode) {
        try {
            JsonObject root = JsonParser.parseString(stripJsonFence(raw)).getAsJsonObject();
            List<MarketingVO.CheckItemVO> checks = new ArrayList<>();
            List<MarketingVO.IssueVO> issues = new ArrayList<>();
            Set<String> keys = new LinkedHashSet<>();
            Set<String> requiredKeys = new LinkedHashSet<>(Set.of("fact", "brand", "goal", "channel", "legal", "complete"));
            if (!PART_TEXT.equals(outputMode)) {
                requiredKeys.add("visual");
            }
            for (JsonElement element : root.getAsJsonArray("checks")) {
                JsonObject item = element.getAsJsonObject();
                String key = jsonString(item, "key");
                String status = jsonString(item, "status");
                double score = item.get("score").getAsDouble();
                if (!requiredKeys.contains(key) || !keys.add(key) || !Set.of(VERDICT_PASS, VERDICT_REVIEW, VERDICT_FAIL).contains(status)
                        || score < 0 || score > 100 || score != Math.floor(score)) {
                    throw new IllegalArgumentException("잘못된 검수 항목");
                }
                MarketingVO.CheckItemVO check = new MarketingVO.CheckItemVO();
                check.setKey(key);
                check.setLabel(CHECK_LABELS.get(key));
                check.setScore((int) score);
                check.setStatus(status);
                checks.add(check);
            }
            if (!keys.equals(requiredKeys)) {
                throw new IllegalArgumentException("검수 항목 누락");
            }
            int index = 0;
            for (JsonElement element : root.getAsJsonArray("issues")) {
                JsonObject item = element.getAsJsonObject();
                String severity = jsonString(item, "severity");
                String targetType = jsonString(item, "targetType");
                String title = jsonString(item, "title");
                String suggestion = jsonString(item, "fixSuggestion");
                if (!Set.of(VERDICT_REVIEW, VERDICT_FAIL).contains(severity) || title.isEmpty() || suggestion.isEmpty()
                        || !Set.of(PART_TEXT, PART_IMAGE).contains(targetType)
                        || (PART_TEXT.equals(outputMode) && PART_IMAGE.equals(targetType))
                        || (PART_IMAGE.equals(outputMode) && PART_TEXT.equals(targetType))) {
                    throw new IllegalArgumentException("잘못된 검수 이슈");
                }
                MarketingVO.IssueVO issue = new MarketingVO.IssueVO();
                issue.setTargetType(targetType);
                issue.setIssueId(mktContentId + "-issue-" + (++index));
                issue.setSeverity(severity);
                issue.setTitle(title);
                issue.setDescription(jsonString(item, "description"));
                issue.setFixSuggestion(suggestion);
                issue.setFixAppliedYn("N");
                issues.add(issue);
            }
            ParsedReview result = new ParsedReview();
            result.checks = checks;
            result.issues = issues;
            result.score = Math.round((float) checks.stream().mapToInt(MarketingVO.CheckItemVO::getScore).sum() / checks.size());
            boolean fail = checks.stream().anyMatch(c -> VERDICT_FAIL.equals(c.getStatus()))
                    || issues.stream().anyMatch(i -> VERDICT_FAIL.equals(i.getSeverity()));
            boolean review = checks.stream().anyMatch(c -> VERDICT_REVIEW.equals(c.getStatus()))
                    || issues.stream().anyMatch(i -> VERDICT_REVIEW.equals(i.getSeverity()));
            result.verdict = fail ? VERDICT_FAIL : review ? VERDICT_REVIEW : VERDICT_PASS;
            return result;
        } catch (Exception e) {
            throw new RuntimeException("AI 검수 응답 형식이 올바르지 않습니다. 다시 검수해 주세요.", e);
        }
    }

    private String jsonString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** ```json 코드펜스 등 LLM이 덧붙이는 장식을 걷어내고 {...} 본문만 남긴다 */
    private String stripJsonFence(String raw) {
        String text = CommonUtil.nullToBlank(raw).trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }

    private List<MarketingVO.CheckItemVO> parseStoredChecks(String checksJson) {
        if (CommonUtil.isEmpty(checksJson)) {
            return new ArrayList<>();
        }
        List<MarketingVO.CheckItemVO> checks = GSON.fromJson(
                checksJson, new TypeToken<List<MarketingVO.CheckItemVO>>() { }.getType());
        return checks != null ? checks : new ArrayList<>();
    }

    private List<MarketingVO.IssueVO> parseStoredIssues(String issuesJson) {
        if (CommonUtil.isEmpty(issuesJson)) {
            return new ArrayList<>();
        }
        List<MarketingVO.IssueVO> issues = GSON.fromJson(
                issuesJson, new TypeToken<List<MarketingVO.IssueVO>>() { }.getType());
        return issues != null ? issues : new ArrayList<>();
    }

    private MarketingVO.ReviewVO toReviewResponse(MarketingVO.ReviewVO raw) {
        if (raw == null) {
            return null;
        }
        raw.setChecks(parseStoredChecks(raw.getChecksJson()));
        raw.setIssues(parseStoredIssues(raw.getIssuesJson()));
        raw.setVerdictLabel(resolveVerdictLabel(raw.getVerdict()));
        return raw;
    }

    private String resolveVerdictLabel(String verdict) {
        if (VERDICT_PASS.equals(verdict)) {
            return "PASS";
        }
        return VERDICT_FAIL.equals(verdict) ? "수정 필요" : "검토 필요";
    }

    /** 콘텐츠 승인/반려 저장. 처리자는 CREATE_USER_ID에 세션 사용자 ID를 넣는다. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveApproval(String mktContentId, String memo, String approvedYn) throws Exception {
        if (!"Y".equals(approvedYn) && !"N".equals(approvedYn)) {
            return failResult("approvedYn은 Y 또는 N이어야 합니다");
        }
        String userId = SessionUtil.getUserId();
        MarketingVO row = requireMarketing(mktContentId, userId);
        MarketingVO.ProjectVO project = marketingDAO.selectMarketingProject(projectSearch(row.getMarketingProjectId()));
        if (!userId.equals(project.getApproverUserId())) {
            return failResult("프로젝트의 지정 승인자만 승인·반려할 수 있습니다");
        }
        MarketingVO.ReviewVO review = marketingDAO.selectLatestReview(mktContentId);
        if (!STATUS_APPROVAL_REQUIRED.equals(row.getStatusCd()) || !isCurrentReview(row, review)) {
            return failResult("현재 시안의 검수 완료 후 승인·반려해 주세요");
        }
        if ("Y".equals(approvedYn) && VERDICT_FAIL.equals(review.getVerdict())) {
            return failResult("검수 결과가 FAIL입니다. 수정 후 다시 검수해 주세요");
        }

        MarketingVO.ApprovalVO approval = new MarketingVO.ApprovalVO();
        approval.setApprovalId(keyGenerate.generateTableKey("MH", "TB_MKT_WORKFLOW_HIST", "MKT_HIST_ID"));
        approval.setContentId(mktContentId);
        approval.setVariantNo(row.getSelectedVariantNo());
        approval.setContentVersion(row.getContentVersion());
        approval.setMemo(memo);
        approval.setReviewHistId(review.getReviewId());
        approval.setOutputMode(row.getOutputMode());
        approval.setScore(review.getScore());
        approval.setVerdict(review.getVerdict());
        approval.setResultJson(GSON.toJson(Map.of("checks", JsonParser.parseString(review.getChecksJson()),
                "issues", JsonParser.parseString(review.getIssuesJson()))));
        approval.setApprovedYn(approvedYn);
        approval.setCreateUserId(userId);
        marketingDAO.insertApprovalHistory(approval);
        marketingDAO.pruneApprovalHistory(mktContentId);

        MarketingVO statusVO = contentSearch(mktContentId);
        statusVO.setStatusCd("Y".equals(approvedYn) ? STATUS_APPROVED : STATUS_REVIEWING);
        statusVO.setModifyUserId(userId);
        marketingDAO.updateMarketingStatus(statusVO);

        Map<String, Object> result = successResult();
        result.put("data", marketingDAO.selectLatestApproval(mktContentId));
        return result;
    }

    /** 로그인 사용자가 멤버로 속한 프로젝트의 콘텐츠 발행 일정 전체 */
    public Map<String, Object> selectCalendarEvents() throws Exception {
        Map<String, Object> resultMap = successResult();
        resultMap.put("list", marketingDAO.selectCalendarEvents(SessionUtil.getUserId()));
        return resultMap;
    }

    /** 예약 시각이 지난 콘텐츠를 발행완료(006)로 일괄 전환 */
    public int advanceScheduledMarketingToPublished() throws Exception {
        return marketingDAO.advanceScheduledMarketingToPublished();
    }

    private LocalDateTime parsePublishScheduledDt(String publishScheduledDt) {
        if (CommonUtil.isEmpty(publishScheduledDt)) {
            return null;
        }
        try {
            return LocalDateTime.parse(publishScheduledDt.trim(), PUBLISH_DT_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ── 생성 SSE ───────────────────────────────────────────────────────────────

    /** 마케팅 생성 SSE */
    public SseEmitter streamMarketingEvents(String contentId) {
        SseEmitter emitter = new SseEmitter(0L);
        String mktContentId = stringValue(contentId);
        if (CommonUtil.isEmpty(mktContentId)) {
            sendSseError(emitter, "contentId가 없습니다.");
            completeMarketingEmitter(emitter);
            return emitter;
        }

        emitter.onTimeout(() -> {
            logger.warn("마케팅 SSE timeout - contentId={}", mktContentId);
            completeMarketingEmitter(emitter);
        });
        emitter.onError(e -> logger.warn("마케팅 SSE error - contentId={}, message={}", mktContentId, e.getMessage()));
        emitter.onCompletion(() -> logger.info("마케팅 SSE complete - contentId={}", mktContentId));

        String userId;
        try {
            userId = SessionUtil.getUserId();
            requireMarketing(mktContentId, userId);
        } catch (Exception e) {
            sendSseError(emitter, "로그인 정보를 확인할 수 없습니다.");
            completeMarketingEmitter(emitter);
            return emitter;
        }

        MARKETING_STREAM_EXECUTOR.execute(() -> runMarketingGenerationStream(emitter, mktContentId, userId));
        return emitter;
    }

    /** 마케팅 시안 생성 스트림 — SSE 백그라운드 스레드라 SessionUtil 대신 인자로 받은 userId를 쓴다 */
    private void runMarketingGenerationStream(SseEmitter emitter, String mktContentId, String userId) {
        CompletableFuture<Void> generationFuture = new CompletableFuture<>();
        CompletableFuture<Void> existing = ACTIVE_GENERATIONS.putIfAbsent(mktContentId, generationFuture);
        if (existing != null) {
            waitAndSendExistingResult(emitter, mktContentId, existing, userId);
            return;
        }
        MarketingVO generating = null;
        try {
            MarketingVO marketing = requireMarketing(mktContentId, userId);
            if (AI_STATUS_DONE.equals(marketing.getAiStatusCd())) {
                sendMarketingDone(emitter, marketing, marketingDAO.selectMarketingContents(contentSearch(mktContentId)));
                return;
            }
            if (!AI_STATUS_WAITING.equals(marketing.getAiStatusCd())) {
                invalidateApproval(marketing, contentRequest(marketing), userId);
            }
            marketing.setAiStatusCd(AI_STATUS_GENERATING);
            marketing.setModifyUserId(userId);
            marketingDAO.updateMarketingAiStatus(marketing);
            generating = marketing;

            Map<String, Object> request = contentRequest(marketing);
            String reference = resolveReferenceContext(marketing.getMarketingProjectId(), marketing.getAgentId(),
                    request.get("referenceMarketingFileIds"), userId);
            String title = buildMarketingTitle(request, marketing.getAgentId());
            marketing.setTitle(title);
            sendProgress(emitter, "title", "title", title);
            List<String> labels = buildVariantLabels(marketing.getVariantCount(), request, marketing.getAgentId(), reference);
            List<MarketingVO> contents = buildVariantShells(mktContentId, marketing.getVariantCount(), labels);
            boolean needText = !PART_IMAGE.equals(marketing.getOutputMode());
            boolean needImage = !PART_TEXT.equals(marketing.getOutputMode());
            String aspectRatio = imageAspectRatio(request);
            List<CompletableFuture<Void>> jobs = new ArrayList<>();
            for (MarketingVO content : contents) {
                Map<String, Object> variantRequest = new LinkedHashMap<>(request);
                variantRequest.put("_variantLabel", content.getContentLabel());
                if (needText) {
                    String prompt = buildGenerationPrompt(PROMPT_ID_TEXT, marketing.getAgentId(), variantRequest, reference, null, null);
                    content.setTextPromptJson(GSON.toJson(Map.of("promptId", PROMPT_ID_TEXT, "prompt", prompt)));
                    jobs.add(submitVariantPart(emitter, content, () -> generateVariantText(prompt, "marketing"), PART_TEXT));
                }
                if (needImage) {
                    String prompt = buildGenerationPrompt(PROMPT_ID_IMAGE, marketing.getAgentId(), variantRequest, reference, null, null);
                    content.setImagePromptJson(GSON.toJson(Map.of("promptId", PROMPT_ID_IMAGE, "prompt", prompt)));
                    jobs.add(submitVariantPart(emitter, content,
                            () -> callImageApiSync(prompt, marketing.getAgentId(), aspectRatio, null, userId), PART_IMAGE));
                }
            }
            CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).join();
            boolean complete = contents.stream().allMatch(c -> (!needText || CommonUtil.isNotEmpty(c.getTextContent()))
                    && (!needImage || CommonUtil.isNotEmpty(c.getImageFile())));

            marketingDAO.deleteMarketingContents(contentSearch(mktContentId));
            for (MarketingVO content : contents) {
                content.setMktContentVariantId(keyGenerate.generateTableKey("MC", "TB_MKT_CONTENT_VARIANT", "MKT_CONTENT_VARIANT_ID"));
                content.setCreateUserId(userId);
                marketingDAO.insertMarketingContent(content);
            }
            marketing.setRequestJson(GSON.toJson(storedRequest(request)));
            marketing.setGenerationSnapshotJson(GSON.toJson(Map.of("request", storedRequest(request),
                    "referenceContext", reference, "labels", labels)));
            marketingDAO.updateMarketingRequestJson(marketing);
            saveTitle(mktContentId, userId, title);
            marketing.setAiStatusCd(complete ? AI_STATUS_DONE : AI_STATUS_FAILED);
            marketing.setStatusCd(complete ? STATUS_REVIEWING : STATUS_WRITING);
            marketing.setSelectedVariantNo(complete ? 1 : null);
            marketingDAO.completeMarketingGeneration(marketing);

            if (complete) {
                sendMarketingDone(emitter, marketing, contents);
            } else {
                sendSseError(emitter, "일부 시안을 생성하지 못했습니다. 다시 시도해 주세요.");
            }
        } catch (Exception e) {
            logger.error("마케팅 생성 실패 - contentId={}", mktContentId, e);
            if (generating != null) {
                markGenerationFailed(generating, userId);
            }
            sendSseError(emitter, e.getMessage());
        } finally {
            generationFuture.complete(null);
            ACTIVE_GENERATIONS.remove(mktContentId, generationFuture);
            completeMarketingEmitter(emitter);
        }
    }

    /** 생성 중 예외 시 생성중 상태가 남지 않도록 실패로 되돌린다 */
    private void markGenerationFailed(MarketingVO marketing, String userId) {
        try {
            marketing.setAiStatusCd(AI_STATUS_FAILED);
            marketing.setStatusCd(STATUS_WRITING);
            marketing.setModifyUserId(userId);
            marketingDAO.completeMarketingGeneration(marketing);
        } catch (Exception e) {
            logger.warn("마케팅 생성 실패 상태 저장 실패 - contentId={}: {}", marketing.getMktContentId(), e.getMessage());
        }
    }

    /** 진행 중인 생성 완료 후 결과만 전송 */
    private void waitAndSendExistingResult(
            SseEmitter emitter, String mktContentId, CompletableFuture<Void> existing, String userId) {
        try {
            existing.get(GENERATION_WAIT_TIMEOUT_MIN, TimeUnit.MINUTES);
            MarketingVO marketing = requireMarketing(mktContentId, userId);
            List<MarketingVO> contents = marketingDAO.selectMarketingContents(contentSearch(mktContentId));
            if (!AI_STATUS_DONE.equals(marketing.getAiStatusCd()) || CommonUtil.isEmpty(contents)) {
                sendSseError(emitter, "생성 결과를 찾을 수 없습니다.");
                return;
            }
            sendMarketingDone(emitter, marketing, contents);
        } catch (Exception e) {
            logger.warn("마케팅 SSE 대기 실패 - contentId: {}, msg: {}", mktContentId, e.getMessage());
            sendSseError(emitter, "콘텐츠 생성 대기 중 오류가 발생했습니다.");
        } finally {
            completeMarketingEmitter(emitter);
        }
    }

    private List<MarketingVO> buildVariantShells(String mktContentId, int variantCount, List<String> labels) {
        List<MarketingVO> contents = new ArrayList<>();
        for (int no = 1; no <= variantCount; no++) {
            MarketingVO content = new MarketingVO();
            content.setMktContentId(mktContentId);
            content.setVariantNo(no);
            content.setRecommendYn(no == 1 ? "Y" : "N");
            content.setContentLabel(labels.get(no - 1));
            contents.add(content);
        }
        return contents;
    }

    /** 시안 TEXT/IMAGE AI 호출 후 progress 전송 */
    private CompletableFuture<Void> submitVariantPart(
            SseEmitter emitter, MarketingVO content, Supplier<String> generator, String part) {
        boolean isText = PART_TEXT.equals(part);
        int variantNo = content.getVariantNo();
        CompletableFuture<String> aiFuture = CompletableFuture.supplyAsync(generator, MARKETING_AI_EXECUTOR);
        BiFunction<String, Throwable, Void> handleResult = (raw, error) -> {
            if (error != null) {
                if (error instanceof TimeoutException) {
                    logger.warn("마케팅 시안 {} AI 타임아웃 - variantNo: {}", part, variantNo);
                } else {
                    logger.warn("마케팅 시안 {} AI 실패 - variantNo: {}, msg: {}", part, variantNo, error.getMessage());
                }
                return null;
            }

            String value = isText ? raw : (CommonUtil.isNotEmpty(raw) ? IMAGE_DATA_URI_PREFIX + raw : null);
            if (isText) {
                content.setTextContent(value);
            } else {
                content.setImageFile(value);
            }
            if (CommonUtil.isEmpty(value)) {
                return null;
            }
            sendProgress(emitter, "variant",
                    "variantNo", variantNo,
                    "label", CommonUtil.nullToBlank(content.getContentLabel()),
                    "recommended", "Y".equals(content.getRecommendYn()),
                    "part", part,
                    isText ? "text" : "imageUrl", value);
            return null;
        };
        return aiFuture.orTimeout(VARIANT_AI_TIMEOUT_SEC, TimeUnit.SECONDS)
                .handleAsync(handleResult, MARKETING_STREAM_EXECUTOR);
    }

    private void sendMarketingDone(SseEmitter emitter, MarketingVO marketing, List<MarketingVO> contents) {
        sendSseEvent(emitter, "done", Collections.singletonMap("result", toResult(marketing, contents)));
    }

    private void sendSseError(SseEmitter emitter, String message) {
        sendSseEvent(emitter, "error", Collections.singletonMap("message", message));
    }

    /** progress 이벤트 — step 뒤에 key, value 순으로 전달한다 */
    private void sendProgress(SseEmitter emitter, String step, Object... keyValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("step", step);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            data.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        sendSseEvent(emitter, "progress", data);
    }

    private void sendSseEvent(SseEmitter emitter, String eventName, Object payload) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                if (isSseAlreadyCompleted(e)) {
                    logger.debug("마케팅 SSE 이미 종료 — eventName={}", eventName);
                    return;
                }
                logger.warn("마케팅 SSE 전송 실패 - eventName={}, message={}", eventName, e.getMessage());
            }
        }
    }

    private void completeMarketingEmitter(SseEmitter emitter) {
        synchronized (emitter) {
            try {
                emitter.complete();
            } catch (Exception e) {
                if (!isSseAlreadyCompleted(e)) {
                    logger.warn("마케팅 SSE complete 실패 - message={}", e.getMessage());
                }
            }
        }
    }

    private boolean isSseAlreadyCompleted(Throwable e) {
        return e != null && String.valueOf(e.getMessage()).contains("already completed");
    }

    // ── AI 호출 ────────────────────────────────────────────────────────────────

    /** TEXT 생성 */
    private String generateVariantText(String prompt, String aiType) {
        return CommonUtil.nullToBlank(chatbotService.callAiSummary(prompt, aiType, null)).trim();
    }

    /**
     * 기존 이미지 기준 IMAGE 보완 — 이미지 API가 room_id의 최신 이미지를 참고하므로
     * 임시 채팅방에 기존 이미지를 넣고 호출 후 정리한다.
     */
    private String refineVariantImage(String prompt, String agentId, String imageFile, String aspectRatio, String userId)
            throws Exception {
        AgentVO agentSearch = new AgentVO();
        agentSearch.setAgentId(agentId);
        AgentVO agent = agentDAO.selectAgent(agentSearch);
        if (agent == null) {
            throw new RuntimeException("에이전트를 찾을 수 없습니다.");
        }
        ChatbotVO room = new ChatbotVO();
        room.setUserId(userId);
        room.setRoomTitle("마케팅 이미지 보완");
        chatbotDAO.insertChatRoom(room);
        try {
            room.setAgentId(agentId);
            room.setSvcTy(agent.getSvcTy());
            room.setModelId(resolveFileQueryModelId());
            room.setQContent("");
            room.setRContent(imageFile);
            chatbotDAO.insertChatLog(room);
            String image = callImageApiSync(prompt, agentId, aspectRatio, room.getRoomId(), userId);
            return CommonUtil.isEmpty(image) ? null : IMAGE_DATA_URI_PREFIX + image;
        } finally {
            try {
                chatbotDAO.deleteChatRef(room);
                chatbotDAO.deleteChatLog(room);
                chatbotDAO.deleteChatRoom(room);
            } catch (Exception e) {
                logger.warn("[MKT] 이미지 보완 임시 채팅방 정리 실패 - roomId={}: {}", room.getRoomId(), e.getMessage());
            }
        }
    }

    /** 이미지 API 동기 호출. 성공 시 data URI 접두사 없는 base64, 실패 시 null */
    private String callImageApiSync(String query, String agentId, String aspectRatio, Long roomId, String userId) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.image.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("[MKT] 이미지 API URL 미설정");
            return null;
        }
        String modelId = "gpt";
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("room_id", roomId == null ? "" : String.valueOf(roomId));
        params.put("model", modelId);
        params.put("aspect_ratio", aspectRatio);
        String reqJson = GSON.toJson(params);
        long startMs = System.currentTimeMillis();

        try {
            RequestBody body = RequestBody.create(reqJson, okhttp3.MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build();

            try (okhttp3.Response response = AI_HTTP_CLIENT.newCall(request).execute()) {
                int respTimeMs = (int) (System.currentTimeMillis() - startMs);
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("[MKT] 이미지 API 응답 오류: {}", response.code());
                    apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "marketing_image", reqJson,
                            0, 0, respTimeMs, "N", "HTTP " + response.code(), userId);
                    return null;
                }
                String jsonStr = CommonUtil.nullToBlank(response.body().string()).trim();
                if (jsonStr.startsWith("data: ")) {
                    jsonStr = jsonStr.substring(6).trim();
                    int nl = jsonStr.indexOf('\n');
                    if (nl >= 0) {
                        jsonStr = jsonStr.substring(0, nl).trim();
                    }
                }
                JsonObject data = JsonParser.parseString(jsonStr).getAsJsonObject();
                String errorCode = jsonString(data, "errorCode");
                if (!errorCode.isEmpty() && !"None".equalsIgnoreCase(errorCode)) {
                    logger.warn("[MKT] 이미지 API 오류: {} - {}", errorCode, jsonString(data, "errorContent"));
                    apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "marketing_image", reqJson,
                            0, 0, respTimeMs, "N", errorCode, userId);
                    return null;
                }
                String image = jsonString(data, "image").trim();
                int marker = image.indexOf("base64,");
                if (image.startsWith("data:") && marker >= 0) {
                    image = image.substring(marker + "base64,".length());
                }
                apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "marketing_image", reqJson,
                        0, 0, respTimeMs, image.isEmpty() ? "N" : "Y", image.isEmpty() ? "이미지 값 없음" : null, userId);
                return image.isEmpty() ? null : image;
            }
        } catch (Exception e) {
            int respTimeMs = (int) (System.currentTimeMillis() - startMs);
            logger.warn("[MKT] 이미지 API 호출 실패 ({}ms 경과): {}", respTimeMs, e.getMessage());
            apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "marketing_image", reqJson,
                    0, 0, respTimeMs, "N", e.getMessage(), userId);
            return null;
        }
    }

    // ── 참고 자료 ────────────────────────────────────────────────────────────────

    /** 선택 첨부파일을 /file_query로 정리한다 */
    private String resolveReferenceContext(String projectId, String agentId, Object selectedFileIds, String userId) throws Exception {
        MarketingVO.FileVO fileSearchVO = new MarketingVO.FileVO();
        fileSearchVO.setMarketingProjectId(projectId);
        List<MarketingVO.FileVO> files = filterReferenceFiles(marketingDAO.selectMarketingFileList(fileSearchVO), selectedFileIds);
        if (selectedFileIds instanceof List && files.size() != new LinkedHashSet<>((List<?>) selectedFileIds).size()) {
            throw new RuntimeException("선택한 참고파일을 찾을 수 없습니다. 파일 목록을 확인해 주세요.");
        }
        if (files.isEmpty()) {
            return "";
        }
        return queryReferenceFiles(files, agentId, resolveMarketingPrompt(PROMPT_ID_REF), userId);
    }

    /** 임시 첨부 브릿지로 기존 파일 분석 API 호출 */
    private String queryReferenceFiles(List<MarketingVO.FileVO> files, String agentId, String query, String userId) {
        List<ChatbotVO> tempChatFiles = new ArrayList<>();
        try {
            List<String> attachmentFileIds = new ArrayList<>();
            for (MarketingVO.FileVO fileVO : files) {
                ChatbotVO tempChatFile = new ChatbotVO();
                tempChatFile.setRoomId(FILE_ROOM_ID);
                tempChatFile.setFileName(fileVO.getFileNm());
                tempChatFile.setStoreFileName(fileVO.getFileNm());
                tempChatFile.setFilePath(fileVO.getFilePath());
                tempChatFile.setFileSize(fileVO.getFileSize());
                tempChatFile.setFileType(fileVO.getFileType());
                tempChatFile.setUserId(userId);
                chatbotDAO.saveChatFile(tempChatFile);
                tempChatFiles.add(tempChatFile);
                attachmentFileIds.add(String.valueOf(tempChatFile.getChatFileId()));
            }
            return callFileQuerySync(query, attachmentFileIds, agentId, userId);
        } catch (Exception e) {
            throw new RuntimeException("참고자료를 읽지 못했습니다. 파일을 확인하고 다시 시도해 주세요.", e);
        } finally {
            for (ChatbotVO tempChatFile : tempChatFiles) {
                try {
                    chatbotDAO.deleteChatFile(tempChatFile);
                } catch (Exception e) {
                    logger.warn("[MKT] 임시 참고파일 브릿지 정리 실패 - chatFileId={}: {}", tempChatFile.getChatFileId(), e.getMessage());
                }
            }
        }
    }

    /** file_query model_id — TB_LLM_MDL SORT_ORDER 1순위 */
    private String resolveFileQueryModelId() {
        try {
            List<AgentVO.ModelVO> models = agentDAO.selectModelList();
            if (models != null) {
                for (AgentVO.ModelVO model : models) {
                    if (model != null && CommonUtil.isNotEmpty(model.getModelId())) {
                        return model.getModelId();
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("[MKT] file_query 모델 조회 실패: {}", e.getMessage());
        }
        return "";
    }

    /** 선택된 MARKETING_FILE_ID만 남긴다 */
    private List<MarketingVO.FileVO> filterReferenceFiles(List<MarketingVO.FileVO> files, Object selectedFileIds) {
        Set<String> selected = new LinkedHashSet<>();
        if (selectedFileIds instanceof List) {
            for (Object id : (List<?>) selectedFileIds) {
                String value = stringValue(id);
                if (CommonUtil.isNotEmpty(value)) {
                    selected.add(value);
                }
            }
        }
        List<MarketingVO.FileVO> filtered = new ArrayList<>();
        for (MarketingVO.FileVO fileVO : files) {
            if (selected.contains(fileVO.getMarketingFileId())) {
                filtered.add(fileVO);
            }
        }
        return filtered;
    }

    /** /file_query 동기 호출. 실패 시 예외 */
    private String callFileQuerySync(String query, List<String> attachmentFileIds, String agentId, String userId) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.gpt.apiFileUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("[MKT] file_query API URL 미설정");
            throw new RuntimeException("참고자료 추출에 실패했습니다.");
        }

        String modelId = resolveFileQueryModelId();
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("user_id", userId);
        params.put("threadId", "string");
        params.put("dataset_id", "");
        params.put("room_id", "string");
        params.put("model_id", modelId);
        params.put("agent_id", CommonUtil.nvl(agentId, ""));
        params.put("attachment_file_ids", attachmentFileIds);
        String reqJson = GSON.toJson(params);
        long startMs = System.currentTimeMillis();

        try {
            RequestBody body = RequestBody.create(reqJson, okhttp3.MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .build();

            logger.info("[MKT] 참고파일 file_query 호출 시작 - url={}, 첨부={}건", apiUrl, attachmentFileIds.size());

            try (okhttp3.Response response = AI_HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("[MKT] file_query 응답 오류: {}", response.code());
                    apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "marketing_file_query", reqJson,
                            0, 0, (int) (System.currentTimeMillis() - startMs), "N",
                            "HTTP " + response.code(), userId);
                    throw new RuntimeException("참고자료 추출에 실패했습니다.");
                }
                try (okhttp3.ResponseBody responseBody = response.body()) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8));
                    StringBuilder answerBuilder = new StringBuilder();
                    String doneAnswer = "";
                    String streamError = "";
                    String line;
                    while ((line = reader.readLine()) != null) {
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
                            JsonObject data = JsonParser.parseString(jsonStr).getAsJsonObject();
                            String errorCode = jsonString(data, "errorCode");
                            if (!errorCode.isEmpty() && !"None".equalsIgnoreCase(errorCode)) {
                                streamError = errorCode;
                            }
                            if (data.has("text") && !data.get("text").isJsonNull()) {
                                answerBuilder.append(data.get("text").getAsString());
                            }
                            if (data.has("answer") && !data.get("answer").isJsonNull()) {
                                doneAnswer = data.get("answer").getAsString();
                            } else if (data.has("답변") && !data.get("답변").isJsonNull()) {
                                doneAnswer = data.get("답변").getAsString();
                            }
                        } catch (Exception ignore) {
                            // SSE keep-alive/비-JSON 라인 무시
                        }
                    }
                    String result = CommonUtil.isNotEmpty(doneAnswer) ? doneAnswer : answerBuilder.toString();
                    if (!streamError.isEmpty() || result.trim().isEmpty()) {
                        throw new RuntimeException("참고자료 응답이 비어 있거나 오류가 발생했습니다.");
                    }
                    int respTimeMs = (int) (System.currentTimeMillis() - startMs);
                    logger.info("[MKT] 참고파일 file_query 완료 - 응답 길이={}자, 소요={}ms", result.length(), respTimeMs);
                    apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "marketing_file_query", reqJson,
                            0, result.length(), respTimeMs, "Y", null, userId);
                    return result.trim();
                }
            }
        } catch (Exception e) {
            int respTimeMs = (int) (System.currentTimeMillis() - startMs);
            logger.warn("[MKT] file_query 호출 실패 ({}ms 경과): {}", respTimeMs, e.getMessage());
            apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "marketing_file_query", reqJson,
                    0, 0, respTimeMs, "N", e.getMessage(), userId);
            throw new RuntimeException("참고자료 추출에 실패했습니다.");
        }
    }

    // ── 프롬프트 구성 ──────────────────────────────────────────────────────────────

    /** TB_PROMPT 본문 조회. 없으면 예외 — 폴백 상수는 두지 않는다 */
    private String resolveMarketingPrompt(String promptId) throws Exception {
        String content = promptService.getPrompt(promptId, null);
        if (CommonUtil.isEmpty(content)) {
            throw new RuntimeException("사용 가능한 마케팅 프롬프트가 없습니다. PROMPT_ID=" + promptId);
        }
        return content;
    }

    private String buildMarketingTitle(Map<String, Object> request, String agentId) throws Exception {
        String prompt = buildGenerationPrompt(PROMPT_ID_TITLE, agentId, request, null, null, null);
        String title = generateVariantText(prompt, "marketing_title");
        if (CommonUtil.isEmpty(title)) {
            throw new RuntimeException("제목 생성에 실패했습니다.");
        }
        return truncate(title.replaceAll("^[\"'`]+|[\"'`]+$", "").trim(), TITLE_MAX_LENGTH);
    }

    private String buildFallbackTitle(Map<String, Object> request) {
        String keyMessage = stringValue(request.get("keyMessage"));
        return CommonUtil.isNotEmpty(keyMessage) ? truncate(keyMessage, TITLE_MAX_LENGTH) : "마케팅 콘텐츠";
    }

    private List<String> buildVariantLabels(int variantCount, Map<String, Object> request, String agentId, String reference)
            throws Exception {
        String prompt = buildGenerationPrompt(PROMPT_ID_LABEL, agentId, request, reference, null, null);
        String answer = generateVariantText(prompt, "marketing_label");
        List<String> labels = new ArrayList<>();
        for (String line : answer.split("\\R")) {
            String label = line.trim();
            if (label.isEmpty()) {
                continue;
            }
            if (label.length() > 10 || !label.endsWith("형") || labels.contains(label)) {
                throw new RuntimeException("시안 라벨 형식을 확인해 주세요.");
            }
            labels.add(label);
        }
        if (labels.size() != variantCount) {
            throw new RuntimeException("시안 라벨 개수가 맞지 않습니다.");
        }
        return labels;
    }

    /** DB 지시문에 요청값을 붙인다. 생성·보완·검수가 같은 요청 조건을 사용한다. */
    private String buildGenerationPrompt(String promptId, String agentId, Map<String, Object> request,
            String reference, String original, String instruction) throws Exception {
        Map<String, Object> conditions = new LinkedHashMap<>(request);
        conditions.remove("_variantLabel");
        conditions.put("channelLabel", resolveChannelValue(request, resolveChannelLabels(agentId)));
        Map<String, String> markers = new LinkedHashMap<>();
        markers.put("REQUEST_JSON", GSON.toJson(conditions));
        markers.put("REFERENCE_CONTEXT", CommonUtil.nullToBlank(reference));
        markers.put("ORIGINAL_TEXT", CommonUtil.nullToBlank(original));
        markers.put("INSTRUCTION", CommonUtil.nullToBlank(instruction));
        markers.put("VARIANT_COUNT", stringValue(request.get("variantCount")));
        markers.put("VARIANT_LABEL", stringValue(request.get("_variantLabel")));
        markers.put("OUTPUT_MODE", resolveOutputMode(request.get("outputs")));
        markers.put("VISUAL_TXT", stringValue(request.get("visualStyle")));
        return replacePromptMarkers(resolveMarketingPrompt(promptId), markers);
    }

    /** 요청 화면 비율 — 이미지 API 지원 비율만 허용, 없으면 16:9 */
    private String imageAspectRatio(Map<String, Object> request) {
        String ratio = stringValue(request.get("aspectRatio"));
        if ("OTHER".equals(ratio) || "CUSTOM".equals(ratio)) {
            ratio = stringValue(request.get("customAspectRatio"));
        }
        if (ratio.isEmpty()) {
            return "16:9";
        }
        if (!Arrays.asList("1:1", "2:3", "3:4", "4:5", "9:16", "3:2", "4:3", "16:9", "21:9",
                "1:4", "4:1", "1:8", "8:1").contains(ratio)) {
            throw new RuntimeException("이미지 API에서 지원하는 비율을 선택해 주세요.");
        }
        return ratio;
    }

    /** 채널 코드/직접입력/SNS 플랫폼 순으로 해석한다 */
    private String resolveChannelValue(Map<String, Object> request, Map<String, String> labels) {
        String channel = stringValue(request.get("channel"));
        if ("OTHER".equals(channel)) {
            String customChannel = stringValue(request.get("customChannel"));
            if (CommonUtil.isNotEmpty(customChannel)) {
                return customChannel;
            }
        } else if (CommonUtil.isNotEmpty(channel)) {
            return labels == null ? channel : labels.getOrDefault(channel, channel);
        }
        Object platforms = request.get("snsPlatform");
        Set<String> items = new LinkedHashSet<>();
        for (Object item : (platforms instanceof List) ? (List<?>) platforms : Collections.singletonList(platforms)) {
            String code = stringValue(item);
            if (CommonUtil.isNotEmpty(code) && !"OTHER".equals(code)) {
                items.add(labels == null ? code : labels.getOrDefault(code, code));
            }
        }
        return String.join(", ", items);
    }

    /** 요청 variantCount — Gson이 Map으로 역직렬화하면 JSON 숫자는 Double이다 */
    private int parseVariantCount(Object raw) {
        if (!(raw instanceof Number)) {
            throw new RuntimeException("시안 수는 필수입니다.");
        }
        double count = ((Number) raw).doubleValue();
        if (count != Math.rint(count) || count < 1 || count > VARIANT_COUNT_MAX) {
            throw new RuntimeException("시안 수는 1~" + VARIANT_COUNT_MAX + " 사이의 정수여야 합니다.");
        }
        return (int) count;
    }
}
