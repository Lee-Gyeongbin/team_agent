package kr.teamagent.marketing.service.impl;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import javax.imageio.ImageIO;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import kr.teamagent.agent.service.AgentVO;
import kr.teamagent.agent.service.impl.AgentDAO;
import kr.teamagent.common.apilog.service.impl.ApiCallLogServiceImpl;
import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.chat.service.impl.ChatbotAgentSupport;
import kr.teamagent.chat.service.impl.ChatbotDAO;
import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.library.service.impl.LibraryDAO;
import kr.teamagent.marketing.service.MarketingVO;
import kr.teamagent.tmpl.service.impl.TmplHtmlRenderService;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

@Service
public class MarketingServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(MarketingServiceImpl.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** DB 컬럼 길이 (TB_MKT.TITLE, TB_MKT.CONTENT_TYPE, TB_MKT_CONTENT.CONTENT_LABEL) */
    private static final int TITLE_MAX_LENGTH = 100;
    private static final int CONTENT_TYPE_MAX_LENGTH = 20;
    private static final int VARIANT_LABEL_MAX_LENGTH = 20;
    private static final int VARIANT_COUNT_MAX = 5;
    private static final long VARIANT_AI_TIMEOUT_SEC = 150L;
    private static final long FILE_QUERY_TIMEOUT_SEC = 120L;
    private static final long GENERATION_WAIT_TIMEOUT_MIN = 15L;
    private static final String IMAGE_DATA_URI_PREFIX = "data:image/png;base64,";
    /** /file_query 임시 TB_CHAT_FILE ROOM_ID */
    private static final long FILE_ROOM_ID = 0L;
    /** PT000007: 001=대기, 002=생성중, 003=완료, 004=실패 */
    private static final String STATUS_WAIT = "001";
    private static final String STATUS_GENERATING = "002";
    private static final String STATUS_COMPLETE = "003";
    private static final String STATUS_FAILED = "004";
    private static final String PART_TEXT = "TEXT";
    private static final String PART_IMAGE = "IMAGE";
    /** 마케팅 콘텐츠 내보내기 문서템플릿 */
    private static final String MARKETING_EXPORT_TMPL_ID = "TM000009";
    /** 렌더러가 버리는 빈 줄 대체 토큰 */
    private static final String EXPORT_BLANK_LINE_TOKEN = "[[MKT_BLANK]]";
    /** PT000002: 001작성중 002검수중 003완료 004보류 */
    private static final Set<String> PROJECT_STATUS_CDS = Set.of("001", "002", "003", "004");

    private static final String MARKETING_TITLE_PROMPT = String.join("\n",
            "## 작업",
            "다음 마케팅 콘텐츠 요청의 핵심을 20자 이내의 한 줄 제목으로 만들어 주세요.",
            "",
            "## 응답 형식",
            "- 항목명, 따옴표, 부연 설명 없이 제목만 출력하세요.");
    private static final String MARKETING_LABEL_PROMPT = String.join("\n",
            "## 작업",
            "다음 마케팅 콘텐츠 요청으로 서로 다른 각도의 시안 %d개를 만들 예정입니다.",
            "각 시안의 성격을 나타내는 형식 라벨을 %d개 만들어 주세요.",
            "",
            "## 작성 조건",
            "- 라벨은 '감성형', '혜택강조형'처럼 10자 이내의 '~형' 한국어 명사로 작성하세요.",
            "- 라벨은 서로 중복되지 않아야 합니다.",
            "",
            "## 응답 형식",
            "- 설명, 번호, 글머리 없이 라벨만 한 줄에 하나씩 출력하세요.");
    private static final String MARKETING_TEXT_PROMPT = String.join("\n",
            "## 작업",
            "다음 요청에 맞는 마케팅 문안을 작성해 주세요.",
            "",
            "## 응답 형식",
            "- 완성 문안 본문 한 편만 출력하세요.",
            "- '제목:', '도입부:', '소제목:', '본문:', '핵심 메시지:', '행동 유도 문구:', '키워드:' 등 구성 요소 라벨·항목명은 절대 출력하지 마세요.",
            "- 각 구성 내용은 라벨 없이 자연스러운 문안 흐름으로만 작성하세요.",
            "- 각 문장이 끝날 때 마다 줄바꿈을 사용하고, 문단 구분을 정확하게 하세요.");
    private static final String MARKETING_IMAGE_RESPONSE_RULE =
            String.join("\n",
                    "## 응답 형식",
                    "- 설명이나 안내 문구 없이 이미지만 생성하세요.");
    private static final String MARKETING_IMAGE_PROMPT = String.join("\n",
            "## 작업",
            "다음 요청에 맞는 마케팅용 이미지를 생성해 주세요.",
            "",
            MARKETING_IMAGE_RESPONSE_RULE);
    private static final String MARKETING_TEXT_REFINE_PROMPT = String.join("\n",
            "## 작업",
            "다음 마케팅 문안을 요청사항에 맞게 수정해 주세요.",
            "- 기존 문안의 구조와 문체를 유지하되, 요청사항상 필요한 경우에만 바꾸세요.",
            "",
            "## 응답 형식",
            "- 설명, 인사말, 변경 요약 없이 수정된 완성 문안만 출력하세요.",
            "- '제목:', '도입부:', '소제목:' 등 구성 요소 라벨·항목명은 절대 출력하지 마세요.",
            "- 각 문장이 끝날 때 마다 줄바꿈을 사용하고, 문단 구분을 정확하게 하세요.");
    private static final String MARKETING_IMAGE_REFINE_PROMPT = String.join("\n",
            "## 작업",
            "다음 마케팅용 이미지를 수정 요청에 맞게 새로 생성해 주세요.",
            "",
            MARKETING_IMAGE_RESPONSE_RULE);
    private static final String IMAGE_NO_TEXT_RULE =
            "\n\n## 이미지 작성 조건\n- 글자, 문구, 숫자 등 텍스트를 넣지 말고 텍스트가 없는 이미지만 생성하세요.";
    /** 이미지 사용처·유형·분위기 코드 라벨 */
    private static final Map<String, String> IMAGE_USAGE_LABELS = Map.of(
            "BANNER", "배너 이미지",
            "THUMBNAIL", "썸네일",
            "PRODUCT_DETAIL", "상품 상세 이미지",
            "SNS_VISUAL", "SNS 게시물 이미지");
    private static final Map<String, String> IMAGE_TYPE_LABELS = Map.of(
            "REAL_PHOTO", "실사 사진",
            "CHARACTER_ILLUST", "캐릭터 일러스트",
            "GENERAL_ILLUST", "일반 일러스트",
            "GRAPHIC_3D", "3D 그래픽",
            "GRAPHIC_DESIGN", "그래픽 디자인",
            "TYPOGRAPHY", "타이포그래피 중심");
    private static final Map<String, String> IMAGE_ATMOSPHERE_LABELS = Map.ofEntries(
            Map.entry("BRIGHT_CHEERFUL", "밝고 경쾌한"),
            Map.entry("PROFESSIONAL", "전문적인"),
            Map.entry("TRUSTWORTHY", "신뢰감 있는"),
            Map.entry("LUXURIOUS", "고급스러운"),
            Map.entry("EMOTIONAL", "감성적인"),
            Map.entry("DYNAMIC", "역동적인"),
            Map.entry("MINIMAL", "미니멀한"),
            Map.entry("WARM", "따뜻한"));

    /** 에이전트 ADDITIONAL_CONFIG 코드→라벨 */
    private static final class AgentLabelSet {
        final Map<String, String> contentType;
        final Map<String, String> channel;
        final Map<String, String> purpose;
        final Map<String, String> audience;
        final Map<String, String> tone;
        final Map<String, String> length;
        final Map<String, String> outputSection;

        AgentLabelSet(Map<String, String> contentType, Map<String, String> channel, Map<String, String> purpose,
                Map<String, String> audience, Map<String, String> tone, Map<String, String> length,
                Map<String, String> outputSection) {
            this.contentType = contentType;
            this.channel = channel;
            this.purpose = purpose;
            this.audience = audience;
            this.tone = tone;
            this.length = length;
            this.outputSection = outputSection;
        }
    }

    /** [{value,label}, ...] → 코드→라벨 맵 */
    @SuppressWarnings("unchecked")
    private Map<String, String> toOptionLabelMap(Object rawOptionList) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!(rawOptionList instanceof List)) {
            return map;
        }
        for (Object item : (List<?>) rawOptionList) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) item;
            String value = stringValue(row.get("value"));
            String label = stringValue(row.get("label"));
            if (CommonUtil.isNotEmpty(value) && CommonUtil.isNotEmpty(label)) {
                map.put(value, label);
            }
        }
        return map;
    }

    /** 에이전트 ADDITIONAL_CONFIG로 라벨 세트를 만든다. 조회 실패 시 빈 맵. */
    @SuppressWarnings("unchecked")
    private AgentLabelSet resolveAgentLabelSet(String agentId) {
        Map<String, Object> additionalConfig = null;
        try {
            ChatbotVO.AgtSubCfgVO subCfg = agentSupport.getAgentSubCfg(agentId);
            additionalConfig = subCfg != null ? subCfg.getAdditionalConfigMap() : null;
        } catch (Exception e) {
            logger.warn("[MKT] 에이전트 라벨 설정 조회 실패 - agentId={}: {}", agentId, e.getMessage());
        }
        if (additionalConfig == null) {
            Map<String, String> empty = Collections.emptyMap();
            return new AgentLabelSet(empty, empty, empty, empty, empty, empty, empty);
        }

        Object workflowRaw = additionalConfig.get("workflow");
        Map<String, Object> workflow = (workflowRaw instanceof Map)
                ? (Map<String, Object>) workflowRaw : Collections.<String, Object>emptyMap();

        // 콘텐츠 유형별 채널 옵션을 하나의 맵으로 합친다
        Map<String, String> channelLabels = new LinkedHashMap<>();
        Object channelsByContentTypeRaw = additionalConfig.get("channelsByContentType");
        if (channelsByContentTypeRaw instanceof Map) {
            for (Object options : ((Map<String, Object>) channelsByContentTypeRaw).values()) {
                channelLabels.putAll(toOptionLabelMap(options));
            }
        }

        return new AgentLabelSet(
                toOptionLabelMap(additionalConfig.get("contentTypes")),
                channelLabels,
                toOptionLabelMap(workflow.get("purposes")),
                toOptionLabelMap(workflow.get("audiences")),
                toOptionLabelMap(workflow.get("tones")),
                toOptionLabelMap(workflow.get("lengths")),
                toOptionLabelMap(workflow.get("outputSections")));
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
    /** 내보내기 HTML 캐시 (mktId, 프롬프트 해시) */
    private static final int EXPORT_HTML_CACHE_MAX_SIZE = 200;
    private static final Cache<String, ExportHtmlCacheEntry> EXPORT_HTML_CACHE =
            CacheBuilder.newBuilder().maximumSize(EXPORT_HTML_CACHE_MAX_SIZE).build();
    private static final OkHttpClient FILE_QUERY_HTTP_CLIENT = new OkHttpClient.Builder()
            .readTimeout(FILE_QUERY_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build();

    @Autowired
    private MarketingDAO marketingDAO;

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
    private LibraryDAO libraryDAO;

    @Autowired
    private ApiCallLogServiceImpl apiCallLogService;

    @Autowired
    private TmplHtmlRenderService tmplHtmlRenderService;

    // ── 공통 헬퍼 ────────────────────────────────────────────────────────────────

    /** Map/JSON Object → String (null 안전). CommonUtil.nullToBlank(Object)는 String 캐스트라 Number 등에서 깨짐. */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** AI 생성 TITLE/CONTENT_LABEL 등 DB 컬럼 길이 방어 */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** List/스칼라를 중복 없는 콤마 문자열로 만든다 */
    private String joinDistinct(Object raw, UnaryOperator<String> mapper) {
        Set<String> items = new LinkedHashSet<>();
        for (Object item : (raw instanceof List) ? (List<?>) raw : Collections.singletonList(raw)) {
            String text = mapper.apply(stringValue(item));
            if (CommonUtil.isNotEmpty(text)) {
                items.add(text);
            }
        }
        return String.join(", ", items);
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

    /** MKT_ID 조회용 VO */
    private MarketingVO contentSearch(String mktId) {
        MarketingVO searchVO = new MarketingVO();
        searchVO.setMktId(mktId);
        return searchVO;
    }

    /** MKT_PROJECT_ID 조회용 VO */
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

    // ── 프로젝트 / 파일 ────────────────────────────────────────────────────────────

    /** 마케팅 프로젝트 생성/수정 */
    @Transactional
    public String saveMarketingProject(MarketingVO.ProjectVO vo) throws Exception {
        if (vo == null || CommonUtil.isEmpty(vo.getProjectNm())) {
            throw new RuntimeException("프로젝트명은 필수입니다.");
        }
        String userId = SessionUtil.getUserId();
        if (CommonUtil.isEmpty(vo.getDueDt())) {
            vo.setDueDt(null);
        }

        String marketingProjectId = stringValue(vo.getMarketingProjectId());
        if (CommonUtil.isNotEmpty(marketingProjectId)) {
            vo.setCreateUserId(userId);
            vo.setModifyUserId(userId);
            vo.setStatusCd(vo.getStatusCd() != null && PROJECT_STATUS_CDS.contains(vo.getStatusCd())
                    ? vo.getStatusCd() : null);
            if (marketingDAO.updateMarketingProject(vo) != 1) {
                throw new RuntimeException("마케팅 프로젝트를 찾을 수 없습니다.");
            }
            return marketingProjectId;
        }

        marketingProjectId = keyGenerate.generateTableKey("MP", "TB_MKT_PROJECT", "MKT_PROJECT_ID");
        vo.setMarketingProjectId(marketingProjectId);
        vo.setStatusCd("001");
        vo.setCreateUserId(userId);
        marketingDAO.insertMarketingProject(vo);
        return marketingProjectId;
    }

    /** 마케팅 프로젝트 삭제 */
    @Transactional
    public void deleteMarketingProject(String marketingProjectId) throws Exception {
        String projectId = stringValue(marketingProjectId);
        if (CommonUtil.isEmpty(projectId)) {
            throw new RuntimeException("marketingProjectId는 필수입니다.");
        }
        MarketingVO.ProjectVO searchVO = projectSearch(projectId);
        MarketingVO.FileVO fileSearchVO = new MarketingVO.FileVO();
        fileSearchVO.setMarketingProjectId(projectId);
        for (MarketingVO.FileVO fileRow : marketingDAO.selectMarketingFileList(fileSearchVO)) {
            deleteFileStorageObject(fileRow);
        }
        marketingDAO.deleteMarketingContentsByProject(searchVO);
        marketingDAO.deleteMarketingByProject(searchVO);
        marketingDAO.deleteMarketingFilesByProject(searchVO);
        if (marketingDAO.deleteMarketingProject(searchVO) != 1) {
            throw new RuntimeException("마케팅 프로젝트를 찾을 수 없습니다.");
        }
    }

    /** 마케팅 프로젝트 목록 조회 */
    public List<MarketingVO.ProjectVO> selectMarketingProjectList(MarketingVO.ProjectVO searchVO) throws Exception {
        if (searchVO == null) {
            searchVO = new MarketingVO.ProjectVO();
        }
        return marketingDAO.selectMarketingProjectList(searchVO);
    }

    /** 마케팅 프로젝트 단건 조회 */
    public MarketingVO.ProjectVO selectMarketingProject(String marketingProjectId) throws Exception {
        MarketingVO.ProjectVO searchVO = projectSearch(stringValue(marketingProjectId));
        MarketingVO.ProjectVO data = marketingDAO.selectMarketingProject(searchVO);
        if (data == null) {
            throw new RuntimeException("마케팅 프로젝트를 찾을 수 없습니다.");
        }
        return data;
    }

    /** 마케팅 프로젝트 존재 확인 */
    private void requireProject(String marketingProjectId) throws Exception {
        String projectId = stringValue(marketingProjectId);
        if (CommonUtil.isEmpty(projectId)) {
            throw new RuntimeException("marketingProjectId는 필수입니다.");
        }
        selectMarketingProject(projectId);
    }

    /** 마케팅 파일 업로드 presigned URL */
    public Map<String, Object> saveMarketingFileUploadUrl(MarketingVO.FileVO fileVO) {
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

    /** 마케팅 파일 메타 저장 */
    @Transactional
    public Map<String, Object> saveMarketingFile(MarketingVO.FileVO vo) throws Exception {
        if (CommonUtil.isEmpty(vo.getFilePath())) {
            throw new RuntimeException("filePath는 필수입니다.");
        }
        if (CommonUtil.isEmpty(vo.getFileNm())) {
            throw new RuntimeException("fileName은 필수입니다.");
        }

        String userId = SessionUtil.getUserId();
        String marketingProjectId = stringValue(vo.getMarketingProjectId());
        if (CommonUtil.isNotEmpty(marketingProjectId)) {
            requireProject(marketingProjectId);
        }
        String fileType = CommonUtil.nvl(vo.getMimeType(), CommonUtil.nullToBlank(vo.getFileType()));

        String marketingFileId = keyGenerate.generateTableKey("MF", "TB_MKT_FILE", "MKT_FILE_ID");
        MarketingVO.FileVO fileVO = new MarketingVO.FileVO();
        fileVO.setMarketingFileId(marketingFileId);
        fileVO.setMarketingProjectId(CommonUtil.isNotEmpty(marketingProjectId) ? marketingProjectId : null);
        fileVO.setFilePath(vo.getFilePath());
        fileVO.setFileNm(vo.getFileNm());
        fileVO.setFileSize(vo.getFileSize());
        fileVO.setFileType(fileType);
        fileVO.setCreateUserId(userId);
        marketingDAO.insertMarketingFile(fileVO);

        Map<String, Object> resultMap = successResult();
        resultMap.put("marketingFileId", marketingFileId);
        resultMap.put("filePath", fileVO.getFilePath());
        resultMap.put("fileName", fileVO.getFileNm());
        return resultMap;
    }

    /** 마케팅 프로젝트 첨부파일 목록 */
    public List<MarketingVO.FileVO> selectMarketingFileList(String marketingProjectId) throws Exception {
        requireProject(marketingProjectId);
        MarketingVO.FileVO searchVO = new MarketingVO.FileVO();
        searchVO.setMarketingProjectId(marketingProjectId);
        return marketingDAO.selectMarketingFileList(searchVO);
    }

    /** 마케팅 프로젝트 첨부파일명 수정 */
    @Transactional
    public void updateMarketingFileName(String marketingFileId, String fileName) throws Exception {
        String fileId = stringValue(marketingFileId);
        String trimmedName = stringValue(fileName);
        if (CommonUtil.isEmpty(fileId)) {
            throw new RuntimeException("marketingFileId는 필수입니다.");
        }
        if (CommonUtil.isEmpty(trimmedName)) {
            throw new RuntimeException("파일명은 필수입니다.");
        }
        MarketingVO.FileVO dataVO = fileSearch(fileId);
        dataVO.setFileNm(trimmedName);
        if (marketingDAO.updateMarketingFile(dataVO) != 1) {
            throw new RuntimeException("첨부파일을 찾을 수 없습니다.");
        }
    }

    /** 마케팅 첨부파일 삭제 */
    public void deleteMarketingFile(String marketingFileId) throws Exception {
        String fileId = stringValue(marketingFileId);
        if (CommonUtil.isEmpty(fileId)) {
            throw new RuntimeException("marketingFileId는 필수입니다.");
        }
        MarketingVO.FileVO searchVO = fileSearch(fileId);
        MarketingVO.FileVO row = marketingDAO.selectMarketingFileById(searchVO);
        if (row == null) {
            throw new RuntimeException("첨부파일을 찾을 수 없습니다.");
        }
        deleteFileStorageObject(row);

        if (marketingDAO.deleteMarketingFile(fileSearch(fileId)) != 1) {
            throw new RuntimeException("첨부파일을 찾을 수 없습니다.");
        }
    }

    /** NCP 스토리지 객체 삭제 */
    private void deleteFileStorageObject(MarketingVO.FileVO row) {
        if (row == null || CommonUtil.isEmpty(row.getFilePath())) {
            return;
        }
        try {
            fileService.deleteStorageObjectByKey(row.getFilePath());
        } catch (Exception e) {
            logger.warn("[MKT] 스토리지 객체 정리 실패 - marketingFileId={}, filePath={}: {}",
                    row.getMarketingFileId(), row.getFilePath(), e.getMessage());
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
        if (CommonUtil.isNotEmpty(row.getMarketingProjectId())) {
            requireProject(row.getMarketingProjectId());
        }
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
        for (MarketingVO row : marketingDAO.selectMarketingList(searchVO)) {
            list.add(toSummary(row, parseRequest(row.getRequestJson())));
        }
        resultMap.put("list", list);
        return resultMap;
    }

    /** 마케팅 콘텐츠 상세 조회 */
    public Map<String, Object> selectMarketing(String mktId) throws Exception {
        MarketingVO searchVO = contentSearch(mktId);
        MarketingVO marketing = marketingDAO.selectMarketing(searchVO);
        if (marketing == null) {
            return failResult("마케팅 콘텐츠를 찾을 수 없습니다");
        }
        Map<String, Object> request = parseRequest(marketing.getRequestJson());
        Map<String, Object> detail = toSummary(marketing, request);
        detail.putAll(successResult());
        detail.put("request", request);
        detail.put("result", toResult(marketing, marketingDAO.selectMarketingContents(searchVO)));
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
        result.put("contentId", row.getMktId());
        result.put("agentId", row.getAgentId());
        result.put("marketingProjectId", row.getMarketingProjectId());
        result.put("title", row.getTitle());
        result.put("outputMode", row.getOutputMode());
        result.put("statusCd", row.getStatusCd());
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
            if (!imageOnly) {
                String text = CommonUtil.nullToBlank(content.getTextContent());
                Map<String, Object> variant = new LinkedHashMap<>();
                variant.put("id", content.getContentNo());
                variant.put("label", label);
                variant.put("recommended", recommended);
                variant.put("content", text);
                variant.put("canRestore", canRestore);
                variants.add(variant);
            }
            if (CommonUtil.isNotEmpty(content.getImageFile())) {
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("id", content.getContentNo());
                image.put("url", content.getImageFile());
                image.put("label", label);
                image.put("recommended", recommended);
                image.put("canRestore", canRestore);
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
    public String exportMarketingContentHtml(String mktId) throws Exception {
        MarketingVO searchVO = contentSearch(mktId);
        MarketingVO marketing = marketingDAO.selectMarketing(searchVO);
        if (marketing == null) {
            throw new RuntimeException("마케팅 콘텐츠를 찾을 수 없습니다.");
        }
        List<MarketingVO> contents = marketingDAO.selectMarketingContents(searchVO);
        if (CommonUtil.isEmpty(contents)) {
            throw new RuntimeException("내보낼 시안이 없습니다.");
        }
        boolean hasText = contents.stream().anyMatch(c -> CommonUtil.isNotEmpty(c.getTextContent()));
        boolean hasImage = contents.stream().anyMatch(c -> CommonUtil.isNotEmpty(c.getImageFile()));
        if (!hasText && !hasImage) {
            throw new RuntimeException("내보낼 시안이 없습니다.");
        }
        Map<String, Object> request = parseRequest(marketing.getRequestJson());
        return buildMarketingExportHtml(marketing, request, contents);
    }

    /** 문서템플릿 LLM 렌더링으로 내보내기 HTML을 만든다 */
    private String buildMarketingExportHtml(
            MarketingVO marketing, Map<String, Object> request, List<MarketingVO> contents)
            throws Exception {
        LibraryVO tmplSearchVO = new LibraryVO();
        tmplSearchVO.setTmplId(MARKETING_EXPORT_TMPL_ID);
        LibraryVO.TmplItem tmpl = libraryDAO.selectTmpl(tmplSearchVO);
        if (tmpl == null || CommonUtil.isEmpty(tmpl.getTmplHtml())) {
            throw new RuntimeException("내보내기 문서 템플릿을 찾을 수 없습니다.");
        }
        String promptTemplate = CommonUtil.nullToBlank(tmpl.getLlmPrompt());
        if (CommonUtil.isEmpty(promptTemplate)) {
            throw new RuntimeException("내보내기 문서 템플릿 프롬프트가 없습니다.");
        }
        List<LibraryVO.TmplFieldItem> tmplFieldList = libraryDAO.selectTmplFieldList(tmplSearchVO);

        Map<Integer, String> imageDataUrisByToken = new LinkedHashMap<>();
        AgentLabelSet labelSet = resolveAgentLabelSet(marketing.getAgentId());
        String qContent = buildExportPromptQuestion(marketing, request, labelSet);
        String rContent = buildExportPromptAnswer(contents, imageDataUrisByToken);
        String prompt = buildExportLlmPrompt(promptTemplate, tmplFieldList, qContent, rContent,
                imageDataUrisByToken.size());

        String fingerprint = sha256Hex(prompt);
        ExportHtmlCacheEntry cached = EXPORT_HTML_CACHE.getIfPresent(marketing.getMktId());
        if (cached != null && cached.fingerprint.equals(fingerprint)) {
            logger.info("[MKT] 내보내기 HTML 캐시 재사용 (mktId={}) — LLM 재호출 생략", marketing.getMktId());
            return cached.html;
        }

        logger.info("[MKT] 내보내기 LLM 호출 시작 (tmplId={})", MARKETING_EXPORT_TMPL_ID);
        String res = chatbotService.callAiSummary(prompt, "marketingExport", null);
        if (CommonUtil.isEmpty(res)) {
            throw new RuntimeException("내보내기 문서 생성에 실패했습니다.");
        }
        JSONObject aiJson = parseExportTemplateJson(res);
        if (aiJson == null) {
            throw new RuntimeException("내보내기 문서 생성 결과를 해석하지 못했습니다.");
        }
        if (!imageDataUrisByToken.isEmpty()) {
            tmplHtmlRenderService.dedupeCreateDocImageTokens(aiJson, tmplFieldList);
        }
        markExportBlankLines(aiJson);
        String html = tmplHtmlRenderService.renderTemplateHtml(tmpl.getTmplHtml(), aiJson, tmplFieldList)
                .replace(EXPORT_BLANK_LINE_TOKEN, "");
        String resolvedHtml = resolveMarketingExportImages(html, imageDataUrisByToken);
        EXPORT_HTML_CACHE.put(marketing.getMktId(), new ExportHtmlCacheEntry(fingerprint, resolvedHtml));
        return resolvedHtml;
    }

    /** 내보내기 HTML 캐시 엔트리 */
    private static final class ExportHtmlCacheEntry {
        private final String fingerprint;
        private final String html;

        private ExportHtmlCacheEntry(String fingerprint, String html) {
            this.fingerprint = fingerprint;
            this.html = html;
        }
    }

    /** 프롬프트 문자열 SHA-256 — 실패 시 hashCode로 대체 */
    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(value.hashCode());
        }
    }

    /** JSON 문자열 빈 줄을 토큰으로 치환한다 */
    @SuppressWarnings("unchecked")
    private void markExportBlankLines(Object node) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (Object key : new ArrayList<Object>(obj.keySet())) {
                Object value = obj.get(key);
                if (value instanceof String) {
                    obj.put(key, markExportBlankLinesInText((String) value));
                } else {
                    markExportBlankLines(value);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.size(); i++) {
                Object value = arr.get(i);
                if (value instanceof String) {
                    arr.set(i, markExportBlankLinesInText((String) value));
                } else {
                    markExportBlankLines(value);
                }
            }
        }
    }

    private String markExportBlankLinesInText(String text) {
        if (text == null || text.isEmpty() || text.toLowerCase().contains("<table")) {
            return text;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.indexOf('\n') < 0) {
            return text;
        }
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i].isEmpty() ? EXPORT_BLANK_LINE_TOKEN : lines[i]);
        }
        return sb.toString();
    }

    /** 문서템플릿 플레이스홀더를 치환한다 */
    private String buildExportLlmPrompt(
            String promptTemplate, List<LibraryVO.TmplFieldItem> tmplFieldList,
            String qContent, String rContent, int imageCount) {
        List<String> multilineJsonKeys = new ArrayList<>();
        StringBuilder fieldList = new StringBuilder();
        if (tmplFieldList != null) {
            for (LibraryVO.TmplFieldItem fieldItem : tmplFieldList) {
                if (fieldItem == null || CommonUtil.isEmpty(fieldItem.getJsonKey())) {
                    continue;
                }
                String jsonKey = fieldItem.getJsonKey();
                String fieldNm = CommonUtil.isEmpty(fieldItem.getFieldNm()) ? jsonKey : fieldItem.getFieldNm();
                fieldList.append("\nkey : ").append(jsonKey).append("_label (고정값: \"").append(fieldNm)
                        .append("\". 반드시 이 문자열만 사용. 내용 요약 금지)");
                if ("Y".equals(fieldItem.getMultilineYn())) {
                    multilineJsonKeys.add(jsonKey);
                    fieldList.append("\nkey : ").append(jsonKey).append(" (").append(fieldNm)
                            .append(") (JSON 문자열 배열로 응답. 예: [\"항목1\", \"항목2\"])");
                } else {
                    fieldList.append("\nkey : ").append(jsonKey).append(" (").append(fieldNm).append(")");
                }
            }
        }

        String htmlFieldInstruction = "";
        if (!multilineJsonKeys.isEmpty()) {
            htmlFieldInstruction = "key가 다음인 필드의 value는 HTML이 아닌 일반 텍스트 기반 JSON 문자열 배열이어야 합니다: "
                    + String.join(", ", multilineJsonKeys)
                    + ". 예: [\"항목1\", \"항목2\"]."
                    + " HTML 태그(<p>, <li> 등)는 포함하지 말 것."
                    + " 응답 JSON의 키(key) 순서는 본 프롬프트에 제시된 필드 목록에 나온 key 나열 순서와 동일하게 유지할 것."
                    + " 요청·명세에 정의된 필드 순서를 바꾸거나 뒤섞지 말고, 동일한 순서로 출력할 것.";
        }
        if (TmplHtmlRenderService.containsMarkdownPipeTable(qContent)
                || TmplHtmlRenderService.containsMarkdownPipeTable(rContent)) {
            htmlFieldInstruction += " 단, 원문에 마크다운 파이프 표가 있으면 표 구간만 HTML <table>로 변환해 넣을 것."
                    + " 그 외 HTML은 포함하지 말고 <table> 관련 태그만 예외로 허용.";
        }

        UserVO userVO = SessionUtil.getUserVO();
        String userNm = userVO != null ? CommonUtil.nullToBlank(userVO.getUserNm()) : "";
        String today = LocalDate.now().toString();
        String prompt = promptTemplate
                .replace("{{Q_CONTENT}}", qContent)
                .replace("{{R_CONTENT}}", rContent)
                .replace("{{TODAY}}", today)
                .replace("{{USER_NM}}", userNm)
                .replace("{{HTML_FIELD_INSTRUCTION}}", htmlFieldInstruction)
                .replace("{{FIELD_LIST}}", fieldList.toString());
        if (!promptTemplate.contains("{{Q_CONTENT}}") || !promptTemplate.contains("{{R_CONTENT}}")) {
            prompt = prompt + "\n\n## 요청(Q_CONTENT)\n" + qContent + "\n\n## 결과(R_CONTENT)\n" + rContent;
        }
        if (!promptTemplate.contains("{{FIELD_LIST}}")) {
            prompt = prompt + "\n\n## FIELD_LIST" + fieldList + "\n" + htmlFieldInstruction;
        }
        String imageInstruction = buildExportImageTokenInstruction(imageCount, multilineJsonKeys);
        if (CommonUtil.isNotEmpty(imageInstruction)) {
            prompt = prompt + "\n\n" + imageInstruction;
        }
        return prompt;
    }

    /** 이미지 토큰을 응답 JSON에 넣도록 지시한다 */
    private String buildExportImageTokenInstruction(int imageCount, List<String> multilineJsonKeys) {
        if (imageCount <= 0) {
            return "";
        }
        String token = TmplHtmlRenderService.CREATE_DOC_IMG_TOKEN;
        String fieldHint = multilineJsonKeys.isEmpty()
                ? "FIELD_LIST에 정의된 적절한 필드"
                : String.join(", ", multilineJsonKeys);
        StringBuilder requiredTokens = new StringBuilder();
        for (int i = 0; i < imageCount; i++) {
            if (i > 0) {
                requiredTokens.append(", ");
            }
            requiredTokens.append("[[").append(token).append(":").append(i).append("]]");
        }
        return "【필수·이미지 포함】 결과(R_CONTENT)에 인라인 이미지 " + imageCount + "개가 있습니다. "
                + "응답 JSON 전체에 아래 " + imageCount + "개 토큰을 반드시 모두 포함할 것. "
                + "필수 토큰(이미지 1개당 응답 전체에서 정확히 1회만): " + requiredTokens + ". "
                + "이미지 토큰은 " + fieldHint + " 필드의 관련 배열 항목에 원문 그대로 넣을 것. "
                + "data:image/...;base64,... 데이터를 응답에 직접 출력하지 말 것.";
    }

    /** 내보내기 요청 조건 */
    private String buildExportPromptQuestion(MarketingVO marketing, Map<String, Object> request, AgentLabelSet labelSet) {
        StringBuilder sb = new StringBuilder();
        sb.append("콘텐츠 제목: ").append(CommonUtil.nullToBlank(marketing.getTitle())).append("\n");
        String metaLine = buildExportMetaLine(marketing, request, labelSet);
        if (CommonUtil.isNotEmpty(metaLine)) {
            sb.append("생성 정보: ").append(metaLine).append("\n");
        }
        List<String[]> rows = collectRequestConditions(request, labelSet);
        if (!rows.isEmpty()) {
            sb.append("\n## 제작조건\n");
            for (String[] row : rows) {
                sb.append(row[0]).append(": ").append(row[1]).append("\n");
            }
            String tableHtml = buildConditionSummaryTableHtml(rows);
            if (CommonUtil.isNotEmpty(tableHtml)) {
                sb.append("\n## 제작조건 표 HTML (conditionTableHtml)\n").append(tableHtml).append("\n");
            }
        }
        return sb.toString();
    }

    /** 내보내기 시안 본문 */
    private String buildExportPromptAnswer(
            List<MarketingVO> contents, Map<Integer, String> imageDataUrisByToken) {
        StringBuilder sb = new StringBuilder();
        int tokenIdx = 0;
        for (MarketingVO content : contents) {
            boolean hasText = CommonUtil.isNotEmpty(content.getTextContent());
            boolean hasImage = CommonUtil.isNotEmpty(content.getImageFile());
            if (!hasText && !hasImage) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("시안 ").append(content.getContentNo());
            String label = CommonUtil.nullToBlank(content.getContentLabel());
            if (CommonUtil.isNotEmpty(label)) {
                sb.append(" · ").append(label);
            }
            if ("Y".equals(content.getRecommendYn())) {
                sb.append("  [추천]");
            }
            if (hasText) {
                sb.append("\n\n").append(content.getTextContent());
            }
            if (hasImage) {
                int token = tokenIdx++;
                imageDataUrisByToken.put(token, content.getImageFile());
                sb.append("\n\n[[").append(TmplHtmlRenderService.CREATE_DOC_IMG_TOKEN).append(':').append(token)
                        .append("]]");
            }
        }
        return sb.toString();
    }

    /** 내보내기 LLM 응답 JSON 추출 */
    private JSONObject parseExportTemplateJson(String answer) {
        if (CommonUtil.isEmpty(answer)) {
            return null;
        }
        String jsonStr = answer
                .replace("```json", "")
                .replace("```", "")
                .trim();
        if (jsonStr.isEmpty()) {
            return null;
        }
        int start = jsonStr.indexOf('{');
        int end = jsonStr.lastIndexOf('}');
        if (start >= 0 && end > start) {
            jsonStr = jsonStr.substring(start, end + 1);
        }
        try {
            Object parsed = new JSONParser().parse(jsonStr);
            if (!(parsed instanceof JSONObject)) {
                return null;
            }
            JSONObject json = (JSONObject) parsed;
            if (json.size() == 1) {
                Object onlyVal = json.values().iterator().next();
                if (onlyVal instanceof JSONObject) {
                    return (JSONObject) onlyVal;
                }
            }
            return json;
        } catch (Exception e) {
            logger.warn("[MKT] 내보내기 JSON 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    /** CDOC_IMG 토큰·플레이스홀더를 실제 base64 이미지로 되돌린다 */
    private String resolveMarketingExportImages(String html, Map<Integer, String> imageDataUrisByToken) {
        if (imageDataUrisByToken.isEmpty()) {
            return html;
        }
        String result = html;
        for (Map.Entry<Integer, String> entry : imageDataUrisByToken.entrySet()) {
            String imgTag = buildExportImageTag(entry.getValue());
            String placeholderTag = "<img " + TmplHtmlRenderService.CREATE_DOC_IMG_ATTR + "=\"" + entry.getKey() + "\">";
            String token = "[[" + TmplHtmlRenderService.CREATE_DOC_IMG_TOKEN + ":" + entry.getKey() + "]]";
            result = result.replace(placeholderTag, imgTag).replace(token, imgTag);
        }
        return result;
    }

    /** 내보내기 이미지 태그 — 원본 픽셀을 읽어 가로 폭을 제한한다 */
    private static final int EXPORT_IMAGE_MAX_WIDTH_PX = 640;

    private String buildExportImageTag(String dataUri) {
        int[] pixelSize = resolveImagePixelSize(dataUri);
        if (pixelSize == null || pixelSize[0] <= 0) {
            return "<img src=\"" + dataUri + "\">";
        }
        int nativeWidth = pixelSize[0];
        int nativeHeight = pixelSize[1];
        int targetWidth = Math.min(nativeWidth, EXPORT_IMAGE_MAX_WIDTH_PX);
        int targetHeight = Math.round((float) targetWidth * nativeHeight / nativeWidth);
        return "<img src=\"" + dataUri + "\" width=\"" + targetWidth + "\" height=\"" + targetHeight + "\">";
    }

    /** data URI를 디코딩해 실제 픽셀 가로/세로를 읽는다. 읽기 실패 시 null. */
    private int[] resolveImagePixelSize(String dataUri) {
        try {
            byte[] bytes = decodeImageDataUri(dataUri);
            if (bytes == null) {
                return null;
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return null;
            }
            return new int[]{image.getWidth(), image.getHeight()};
        } catch (Exception e) {
            logger.warn("[MKT] 내보내기 이미지 크기 확인 실패: {}", e.getMessage());
            return null;
        }
    }

    /** 표지 메타줄 */
    private String buildExportMetaLine(MarketingVO marketing, Map<String, Object> request, AgentLabelSet labelSet) {
        List<String> metaParts = new ArrayList<>();
        if (CommonUtil.isNotEmpty(marketing.getCreateDt())) {
            metaParts.add("생성일시 " + marketing.getCreateDt());
        }
        String channelLabel = resolveChannelValue(request, labelSet.channel);
        if (CommonUtil.isNotEmpty(channelLabel)) {
            metaParts.add("사용 채널 " + channelLabel);
        }
        metaParts.add("콘텐츠 유형 " + resolveOutputModeLabel(marketing.getOutputMode()));
        return String.join("   ·   ", metaParts);
    }

    private String resolveOutputModeLabel(String outputMode) {
        if (PART_TEXT.equals(outputMode)) {
            return "문구";
        }
        if (PART_IMAGE.equals(outputMode)) {
            return "이미지";
        }
        return "통합";
    }

    /** OTHER면 직접입력값, 아니면 라벨(없으면 코드 그대로) */
    private String resolveLabeledChoice(Map<String, String> labels, String code, String custom) {
        String value = resolveChoice(code, custom);
        return isPlaceholderCode(code) || CommonUtil.isEmpty(value)
                ? value
                : labels.getOrDefault(value, value);
    }

    /** 요청 조건을 (표시명, 값)으로 푼다 */
    private List<String[]> collectRequestConditions(Map<String, Object> request, AgentLabelSet labels) {
        if (request == null) {
            return Collections.emptyList();
        }
        boolean export = labels != null;
        List<String[]> items = new ArrayList<>();
        addConditionRow(items, export ? "콘텐츠 분류" : "콘텐츠 유형",
                codesOrLabels(request.get("contentType"), export ? labels.contentType : null));
        if (!export) {
            addConditionRow(items, "채널", resolveChannelValue(request, null));
        }
        addConditionRow(items, export ? "제작 목적" : "목적",
                choiceOrLabeled(request, "purpose", "customPurpose", export ? labels.purpose : null));
        addConditionRow(items, export ? "대상 고객" : "대상",
                choiceOrLabeled(request, "audience", "customAudience", export ? labels.audience : null));
        addConditionRow(items, export ? "홍보 상품·서비스" : "홍보할 상품·서비스",
                stringValue(request.get("promotionInformation")));
        addConditionRow(items, "핵심 메시지", stringValue(request.get("keyMessage")));
        addConditionRow(items, "유도할 행동", stringValue(request.get("customCallToAction")));
        String customTone = stringValue(request.get("customTone"));
        addConditionRow(items, export ? "톤앤매너" : "톤",
                joinDistinct(request.get("tones"),
                        code -> choiceOrLabeledValue(code, customTone, export ? labels.tone : null)));
        addConditionRow(items, "분량",
                choiceOrLabeled(request, "length", "customLength", export ? labels.length : null));
        addConditionRow(items, "추가 요청", stringValue(request.get("additionalRequirements")));
        addConditionRow(items, "이미지 사용처",
                codesOrLabels(request.get("imageUsage"), export ? IMAGE_USAGE_LABELS : null));
        if (!export) {
            addConditionRow(items, "SNS 플랫폼", joinCodes(request.get("snsPlatform")));
        }
        addConditionRow(items, "이미지 분위기",
                codesOrLabels(request.get("visualStyle"), export ? IMAGE_ATMOSPHERE_LABELS : null));
        addConditionRow(items, export ? "표현 방식" : "이미지 유형",
                codesOrLabels(request.get("imageType"), export ? IMAGE_TYPE_LABELS : null));
        addConditionRow(items, export ? "화면 비율" : "이미지 비율", resolveAspectRatio(request));
        addConditionRow(items, "이미지 문구", stringValue(request.get("imageText")));
        addConditionRow(items, "브랜드 컬러", stringValue(request.get("brandColors")));
        return items;
    }

    private String codesOrLabels(Object raw, Map<String, String> labels) {
        return labels == null ? joinCodes(raw) : joinDistinct(raw, code -> labels.getOrDefault(code, code));
    }

    private String choiceOrLabeled(Map<String, Object> request, String codeKey, String customKey,
            Map<String, String> labels) {
        return choiceOrLabeledValue(stringValue(request.get(codeKey)), stringValue(request.get(customKey)), labels);
    }

    private String choiceOrLabeledValue(String code, String custom, Map<String, String> labels) {
        return labels == null ? resolveChoice(code, custom) : resolveLabeledChoice(labels, code, custom);
    }

    private void addConditionRow(List<String[]> rows, String label, String value) {
        String trimmed = CommonUtil.nullToBlank(value).trim();
        if (CommonUtil.isNotEmpty(trimmed)) {
            rows.add(new String[] { label, trimmed });
        }
    }

    /** 제작조건 표 HTML */
    private String buildConditionSummaryTableHtml(List<String[]> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        html.append("<table><tbody>");
        for (String[] row : rows) {
            html.append("<tr><th>").append(escapeExportHtml(row[0])).append("</th><td>")
                    .append(escapeExportHtml(row[1])).append("</td></tr>");
        }
        html.append("</tbody></table>");
        return html.toString();
    }

    private String escapeExportHtml(String value) {
        return CommonUtil.nullToBlank(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
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
            logger.warn("[MKT] 내보내기 이미지 base64 디코딩 실패: {}", e.getMessage());
            return null;
        }
    }

    // ── 생성 · 수정 · 삭제 ─────────────────────────────────────────────────────────

    /** 마케팅 콘텐츠 생성 */
    @Transactional
    public Map<String, Object> createMarketing(Map<String, Object> request) throws Exception {
        String userId = SessionUtil.getUserId();
        if (request == null) {
            request = new HashMap<>();
        }

        String marketingProjectId = stringValue(request.remove("marketingProjectId"));
        if (CommonUtil.isEmpty(marketingProjectId)) {
            return failResult("marketingProjectId는 필수입니다");
        }
        try {
            requireProject(marketingProjectId);
        } catch (RuntimeException e) {
            return failResult(e.getMessage());
        }

        MarketingVO marketing = new MarketingVO();
        marketing.setMktId(keyGenerate.generateTableKey("MK", "TB_MKT", "MKT_ID"));
        marketing.setUserId(userId);
        marketing.setMarketingProjectId(marketingProjectId);
        marketing.setCreateUserId(userId);
        marketing.setModifyUserId(userId);
        marketing.setAgentId(stringValue(request.remove("agentId")));
        marketing.setTitle(buildFallbackTitle(request));
        marketing.setContentType(
                truncate(CommonUtil.nvl(stringValue(request.get("contentType")), "ETC"), CONTENT_TYPE_MAX_LENGTH));
        marketing.setOutputMode(resolveOutputMode(request.get("outputs")));
        marketing.setStatusCd(STATUS_WAIT);
        marketing.setRequestJson(GSON.toJson(request));

        marketingDAO.insertMarketing(marketing);

        Map<String, Object> resultMap = successResult();
        resultMap.put("contentId", marketing.getMktId());
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
    public Map<String, Object> updateTitle(String mktId, String title) throws Exception {
        String trimmed = truncate(stringValue(title), TITLE_MAX_LENGTH);
        if (CommonUtil.isEmpty(trimmed)) {
            return failResult("제목을 입력해 주세요");
        }
        if (saveTitle(mktId, SessionUtil.getUserId(), trimmed) != 1) {
            return failResult("제목 저장에 실패했습니다");
        }
        return successResult();
    }

    /** TITLE 저장 */
    private int saveTitle(String mktId, String userId, String title) throws Exception {
        MarketingVO dataVO = contentSearch(mktId);
        dataVO.setTitle(title);
        dataVO.setModifyUserId(userId);
        return marketingDAO.updateMarketingTitle(dataVO);
    }

    /** 발행 예정일 지정/변경 */
    public Map<String, Object> updateSchedule(String mktId, String publishScheduledDt) throws Exception {
        if (marketingDAO.selectMarketing(contentSearch(mktId)) == null) {
            return failResult("마케팅 콘텐츠를 찾을 수 없습니다");
        }
        MarketingVO dataVO = contentSearch(mktId);
        dataVO.setPublishScheduledDt(CommonUtil.isEmpty(publishScheduledDt) ? null : publishScheduledDt.trim());
        dataVO.setModifyUserId(SessionUtil.getUserId());
        if (marketingDAO.updateMarketingSchedule(dataVO) != 1) {
            return failResult("발행 예정일 저장에 실패했습니다");
        }
        return successResult();
    }

    /** 발행 완료 표시/해제 */
    public Map<String, Object> updatePublished(String mktId, String publishedYn) throws Exception {
        if (!"Y".equals(publishedYn) && !"N".equals(publishedYn)) {
            return failResult("publishedYn은 Y 또는 N이어야 합니다");
        }
        if (marketingDAO.selectMarketing(contentSearch(mktId)) == null) {
            return failResult("마케팅 콘텐츠를 찾을 수 없습니다");
        }
        MarketingVO dataVO = contentSearch(mktId);
        dataVO.setPublishedYn(publishedYn);
        dataVO.setModifyUserId(SessionUtil.getUserId());
        if (marketingDAO.updateMarketingPublished(dataVO) != 1) {
            return failResult("발행 완료 표시 저장에 실패했습니다");
        }
        return successResult();
    }

    /** 마케팅 콘텐츠 삭제 */
    @Transactional
    public Map<String, Object> deleteMarketing(String mktId) throws Exception {
        MarketingVO dataVO = contentSearch(mktId);
        if (marketingDAO.selectMarketing(dataVO) == null) {
            return failResult("마케팅 콘텐츠를 찾을 수 없습니다");
        }
        marketingDAO.deleteMarketingContents(dataVO);
        if (marketingDAO.deleteMarketing(dataVO) != 1) {
            return failResult("삭제에 실패했습니다");
        }
        return successResult();
    }

    /** 마케팅 시안 문안 직접 수정 */
    @Transactional
    public Map<String, Object> updateVariantText(String mktId, int contentNo, Map<String, Object> request)
            throws Exception {
        String userId = SessionUtil.getUserId();
        if (marketingDAO.selectMarketing(contentSearch(mktId)) == null) {
            return failResult("마케팅 콘텐츠를 찾을 수 없습니다");
        }
        MarketingVO previous = findVariant(mktId, contentNo);
        if (previous == null) {
            return failResult("수정할 시안을 찾을 수 없습니다");
        }
        String textContent = stringValue(request.get("textContent"));
        if (CommonUtil.isEmpty(textContent)) {
            return failResult("저장할 문안이 없습니다");
        }
        return saveVariant(mktId, userId, previous.getMktContentId(), textContent, null);
    }

    /** 마케팅 시안 보완 */
    public Map<String, Object> refineMarketing(String mktId, int contentNo, Map<String, Object> request)
            throws Exception {
        String userId = SessionUtil.getUserId();
        MarketingVO marketing = marketingDAO.selectMarketing(contentSearch(mktId));
        if (marketing == null) {
            return failResult("마케팅 콘텐츠를 찾을 수 없습니다");
        }
        MarketingVO previous = findVariant(mktId, contentNo);
        if (previous == null) {
            return failResult("수정할 시안을 찾을 수 없습니다");
        }

        String instruction = stringValue(request.get("request"));
        if (CommonUtil.isEmpty(instruction)) {
            return failResult("수정 요청사항을 입력해 주세요");
        }

        Map<String, Object> storedRequest = parseRequest(marketing.getRequestJson());
        String originalText = CommonUtil.nullToBlank(previous.getTextContent()).trim();
        String referenceContext = resolveReferenceContext(
                marketing.getMarketingProjectId(),
                marketing.getAgentId(),
                storedRequest.get("referenceMarketingFileIds"));

        if (PART_IMAGE.equals(stringValue(request.get("type")))) {
            String rawImage = CommonUtil.nullToBlank(generateVariantImage(
                    buildImagePrompt(MARKETING_IMAGE_REFINE_PROMPT, storedRequest, referenceContext, originalText, instruction),
                    marketing.getAgentId())).trim();
            if (CommonUtil.isEmpty(rawImage)) {
                return failResult("이미지 재생성에 실패했습니다");
            }
            return saveVariant(mktId, userId, previous.getMktContentId(), null, IMAGE_DATA_URI_PREFIX + rawImage);
        }

        if (CommonUtil.isEmpty(originalText)) {
            return failResult("수정할 시안 문안이 없습니다");
        }
        Map<String, String> outputSectionLabels = resolveAgentLabelSet(marketing.getAgentId()).outputSection;
        String refined = generateVariantText(
                buildTextPrompt(MARKETING_TEXT_REFINE_PROMPT, storedRequest, referenceContext, originalText, instruction, outputSectionLabels),
                "marketing_refine");
        if (CommonUtil.isEmpty(refined)) {
            return failResult("글 수정에 실패했습니다");
        }
        return saveVariant(mktId, userId, previous.getMktContentId(), refined, null);
    }

    private MarketingVO findVariant(String mktId, int contentNo) throws Exception {
        MarketingVO searchVO = new MarketingVO();
        searchVO.setMktId(mktId);
        searchVO.setContentNo(contentNo);
        return marketingDAO.selectMarketingContent(searchVO);
    }

    /** 시안 부분 갱신 */
    private Map<String, Object> saveVariant(
            String mktId, String userId, String mktContentId, String textContent, String imageFile) throws Exception {
        MarketingVO content = new MarketingVO();
        content.setMktContentId(mktContentId);
        content.setMktId(mktId);
        content.setTextContent(textContent);
        content.setImageFile(imageFile);
        content.setModifyUserId(userId);
        if (marketingDAO.updateMarketingContent(content) != 1) {
            return failResult("시안 저장에 실패했습니다");
        }
        touchMarketing(mktId, userId);
        return successResult();
    }

    /** 시안 직전 버전으로 되돌리기 */
    @Transactional
    public Map<String, Object> restoreVariant(String mktId, int contentNo) throws Exception {
        String userId = SessionUtil.getUserId();
        if (marketingDAO.selectMarketing(contentSearch(mktId)) == null) {
            return failResult("마케팅 콘텐츠를 찾을 수 없습니다");
        }
        MarketingVO previous = findVariant(mktId, contentNo);
        if (previous == null) {
            return failResult("시안을 찾을 수 없습니다");
        }

        MarketingVO restoreVO = new MarketingVO();
        restoreVO.setMktContentId(previous.getMktContentId());
        restoreVO.setMktId(mktId);
        restoreVO.setModifyUserId(userId);
        if (marketingDAO.restoreMarketingContentPrevious(restoreVO) != 1) {
            return failResult("되돌릴 이전 버전이 없습니다");
        }
        touchMarketing(mktId, userId);
        return successResult();
    }

    /** 콘텐츠 수정일 갱신 */
    private void touchMarketing(String mktId, String userId) throws Exception {
        MarketingVO touchVO = contentSearch(mktId);
        touchVO.setModifyUserId(userId);
        marketingDAO.touchMarketing(touchVO);
    }

    private void updateMarketingStatus(String mktId, String userId, String statusCd) {
        try {
            MarketingVO updateVO = contentSearch(mktId);
            updateVO.setStatusCd(statusCd);
            updateVO.setModifyUserId(userId);
            marketingDAO.updateMarketingStatus(updateVO);
        } catch (Exception e) {
            logger.warn("마케팅 STATUS 갱신 실패 - mktId={}, status={}: {}", mktId, statusCd, e.getMessage());
        }
    }

    /** 마감일이 지난 작성중(001) 프로젝트를 검수중(002)으로 일괄 전환 */
    public int advanceOverdueProjectsToReview() throws Exception {
        return marketingDAO.advanceOverdueProjectsToReview();
    }

    // ── 생성 SSE ───────────────────────────────────────────────────────────────

    /** 마케팅 생성 SSE */
    public SseEmitter streamMarketingEvents(String contentId) {
        SseEmitter emitter = new SseEmitter(0L);
        String mktId = stringValue(contentId);
        if (CommonUtil.isEmpty(mktId)) {
            sendSseError(emitter, "contentId가 없습니다.");
            completeMarketingEmitter(emitter);
            return emitter;
        }

        emitter.onTimeout(() -> {
            logger.warn("마케팅 SSE timeout - contentId={}", mktId);
            completeMarketingEmitter(emitter);
        });
        emitter.onError(e -> logger.warn("마케팅 SSE error - contentId={}, message={}", mktId, e.getMessage()));
        emitter.onCompletion(() -> logger.info("마케팅 SSE complete - contentId={}", mktId));

        String userId;
        try {
            userId = SessionUtil.getUserId();
        } catch (Exception e) {
            sendSseError(emitter, "로그인 정보를 확인할 수 없습니다.");
            completeMarketingEmitter(emitter);
            return emitter;
        }

        MARKETING_STREAM_EXECUTOR.execute(() -> runMarketingGenerationStream(emitter, mktId, userId));
        return emitter;
    }

    /** 마케팅 시안 생성 스트림 */
    private void runMarketingGenerationStream(SseEmitter emitter, String mktId, String userId) {
        CompletableFuture<Void> generationFuture = new CompletableFuture<>();
        CompletableFuture<Void> existing = ACTIVE_GENERATIONS.putIfAbsent(mktId, generationFuture);
        if (existing != null) {
            waitAndSendExistingResult(emitter, mktId, existing);
            return;
        }

        try {
            MarketingVO searchVO = contentSearch(mktId);
            MarketingVO marketing = marketingDAO.selectMarketing(searchVO);
            if (marketing == null) {
                sendSseError(emitter, "콘텐츠를 찾을 수 없습니다.");
                return;
            }

            List<MarketingVO> existingContents = marketingDAO.selectMarketingContents(searchVO);
            if (CommonUtil.isNotEmpty(existingContents)) {
                sendMarketingDone(emitter, marketing, existingContents);
                return;
            }

            updateMarketingStatus(mktId, userId, STATUS_GENERATING);

            Map<String, Object> request = parseRequest(marketing.getRequestJson());
            final String agentId = marketing.getAgentId();
            Future<String> referenceContextFuture = MARKETING_AI_EXECUTOR.submit(() -> resolveReferenceContext(
                    marketing.getMarketingProjectId(), agentId, request.get("referenceMarketingFileIds")));

            String title = buildMarketingTitle(request);
            marketing.setTitle(title);
            saveTitle(mktId, userId, title);
            sendProgress(emitter, "title", "title", title);

            int variantCount = parseVariantCount(request.get("variantCount"));
            List<String> variantLabels = buildVariantLabels(request, variantCount);
            sendProgress(emitter, "labels", "variantCount", variantCount);

            boolean needText = !PART_IMAGE.equals(marketing.getOutputMode());
            boolean needImage = !PART_TEXT.equals(marketing.getOutputMode());
            String referenceContext = referenceContextFuture.get();
            AgentLabelSet labelSet = resolveAgentLabelSet(agentId);
            final String textPrompt = needText
                    ? buildTextPrompt(MARKETING_TEXT_PROMPT, request, referenceContext, null, null, labelSet.outputSection) : "";
            final String imagePrompt = needImage
                    ? buildImagePrompt(MARKETING_IMAGE_PROMPT, request, referenceContext, null, null) : "";

            List<MarketingVO> contents = buildVariantShells(mktId, variantCount, variantLabels);
            List<CompletableFuture<Void>> partWaiters = new ArrayList<>();
            for (MarketingVO content : contents) {
                final int contentNo = content.getContentNo();
                final String label = CommonUtil.nullToBlank(content.getContentLabel());
                if (needText) {
                    final String prompt = appendVariantAngle(textPrompt, contentNo, label, PART_TEXT);
                    partWaiters.add(submitVariantPart(
                            emitter, content, () -> generateVariantText(prompt, "marketing"), PART_TEXT));
                }
                if (needImage) {
                    final String prompt = appendVariantAngle(imagePrompt, contentNo, label, PART_IMAGE);
                    partWaiters.add(submitVariantPart(
                            emitter, content, () -> generateVariantImage(prompt, agentId), PART_IMAGE));
                }
            }
            if (!partWaiters.isEmpty()) {
                CompletableFuture.allOf(partWaiters.toArray(new CompletableFuture[0])).join();
            }

            List<MarketingVO> savedContents = new ArrayList<>();
            for (MarketingVO content : contents) {
                if (CommonUtil.isNotEmpty(content.getTextContent())
                        || CommonUtil.isNotEmpty(content.getImageFile())) {
                    content.setCreateUserId(userId);
                    marketingDAO.insertMarketingContent(content);
                    savedContents.add(content);
                }
            }
            if (savedContents.isEmpty()) {
                updateMarketingStatus(mktId, userId, STATUS_FAILED);
                sendSseError(emitter, "생성된 시안이 없습니다.");
                return;
            }

            updateMarketingStatus(mktId, userId, STATUS_COMPLETE);
            sendMarketingDone(emitter, marketing, savedContents);
        } catch (Exception e) {
            logger.error("마케팅 생성 스트림 오류 - contentId: {}", mktId, e);
            updateMarketingStatus(mktId, userId, STATUS_FAILED);
            sendSseError(emitter, "콘텐츠 생성 중 오류가 발생했습니다.");
        } finally {
            generationFuture.complete(null);
            ACTIVE_GENERATIONS.remove(mktId, generationFuture);
            completeMarketingEmitter(emitter);
        }
    }

    /** 진행 중인 생성 완료 후 결과만 전송 */
    private void waitAndSendExistingResult(
            SseEmitter emitter, String mktId, CompletableFuture<Void> existing) {
        try {
            existing.get(GENERATION_WAIT_TIMEOUT_MIN, TimeUnit.MINUTES);
            MarketingVO searchVO = contentSearch(mktId);
            MarketingVO marketing = marketingDAO.selectMarketing(searchVO);
            List<MarketingVO> contents = marketing == null
                    ? null : marketingDAO.selectMarketingContents(searchVO);
            if (CommonUtil.isEmpty(contents)) {
                sendSseError(emitter, "생성 결과를 찾을 수 없습니다.");
                return;
            }
            sendMarketingDone(emitter, marketing, contents);
        } catch (Exception e) {
            logger.warn("마케팅 SSE 대기 실패 - contentId: {}, msg: {}", mktId, e.getMessage());
            sendSseError(emitter, "콘텐츠 생성 대기 중 오류가 발생했습니다.");
        } finally {
            completeMarketingEmitter(emitter);
        }
    }

    private List<MarketingVO> buildVariantShells(String mktId, int variantCount, List<String> variantLabels)
            throws Exception {
        List<MarketingVO> contents = new ArrayList<>();
        String contentId = keyGenerate.generateTableKey("MC", "TB_MKT_CONTENT", "MKT_CONTENT_ID");
        for (int contentNo = 1; contentNo <= variantCount; contentNo++) {
            if (contentNo > 1) {
                contentId = CommonUtil.generateTableKey("MC", contentId);
            }
            String label = (contentNo - 1 < variantLabels.size()) ? variantLabels.get(contentNo - 1) : "";
            MarketingVO content = new MarketingVO();
            content.setMktContentId(contentId);
            content.setMktId(mktId);
            content.setContentNo(contentNo);
            content.setRecommendYn(contentNo == 1 ? "Y" : "N");
            content.setContentLabel(CommonUtil.isEmpty(label) ? null : label);
            contents.add(content);
        }
        return contents;
    }

    /** 시안 TEXT/IMAGE AI 호출 후 progress 전송 */
    private CompletableFuture<Void> submitVariantPart(
            SseEmitter emitter, MarketingVO content, Supplier<String> generator, String part) {
        boolean isText = PART_TEXT.equals(part);
        int contentNo = content.getContentNo();
        CompletableFuture<String> aiFuture = CompletableFuture.supplyAsync(generator, MARKETING_AI_EXECUTOR);
        BiFunction<String, Throwable, Void> handleResult = (raw, error) -> {
            if (error != null) {
                if (error instanceof TimeoutException) {
                    logger.warn("마케팅 시안 {} AI 타임아웃 - contentNo: {}", part, contentNo);
                } else {
                    logger.warn("마케팅 시안 {} AI 실패 - contentNo: {}, msg: {}", part, contentNo, error.getMessage());
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
                    "contentNo", contentNo,
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

    /** IMAGE 생성 */
    private String generateVariantImage(String prompt, String agentId) {
        return CommonUtil.nullToBlank(chatbotService.callAiImageApi(prompt, agentId)).trim();
    }

    // ── 참고 자료 ────────────────────────────────────────────────────────────────

    /** 선택 첨부파일을 /file_query로 정리한다 */
    private String resolveReferenceContext(String marketingProjectId, String agentId, Object selectedFileIds) {
        List<MarketingVO.FileVO> files;
        try {
            MarketingVO.FileVO fileSearchVO = new MarketingVO.FileVO();
            fileSearchVO.setMarketingProjectId(marketingProjectId);
            files = marketingDAO.selectMarketingFileList(fileSearchVO);
        } catch (Exception e) {
            logger.warn("[MKT] 참고파일 목록 조회 실패 - marketingProjectId={}: {}", marketingProjectId, e.getMessage());
            return "";
        }
        files = filterReferenceFiles(files, selectedFileIds);
        if (files.isEmpty()) {
            return "";
        }

        List<Long> tempChatFileIds = new ArrayList<>();
        try {
            String userId = CommonUtil.nullToBlank(SessionUtil.getUserId());
            for (MarketingVO.FileVO fileVO : files) {
                if (CommonUtil.isEmpty(fileVO.getFilePath())) {
                    continue;
                }
                try {
                    ChatbotVO tempChatFile = new ChatbotVO();
                    tempChatFile.setRoomId(FILE_ROOM_ID);
                    tempChatFile.setFileName(fileVO.getFileNm());
                    tempChatFile.setStoreFileName(fileVO.getFileNm());
                    tempChatFile.setFilePath(fileVO.getFilePath());
                    tempChatFile.setFileSize(fileVO.getFileSize());
                    tempChatFile.setFileType(fileVO.getFileType());
                    tempChatFile.setUserId(userId);
                    chatbotDAO.saveChatFile(tempChatFile);
                    tempChatFileIds.add(tempChatFile.getChatFileId());
                } catch (Exception e) {
                    logger.warn("[MKT] 임시 참고파일 브릿지 생성 실패 - marketingFileId={}: {}",
                            fileVO.getMarketingFileId(), e.getMessage());
                }
            }
            if (tempChatFileIds.isEmpty()) {
                return "";
            }
            List<String> attachmentFileIds = new ArrayList<>();
            for (Long chatFileId : tempChatFileIds) {
                attachmentFileIds.add(String.valueOf(chatFileId));
            }
            String query = "첨부된 참고 자료(문서, 이미지 등)의 핵심 내용을 마케팅 콘텐츠 제작에 참고할 수 있도록 정리해 주세요. "
                    + "표·수치·브랜드 컬러 같은 텍스트 정보뿐 아니라, 이미지가 있다면 스타일·구도·분위기·색감 등 시각적 특징도 설명해 주세요.";
            return callFileQuerySync(query, attachmentFileIds, agentId);
        } finally {
            for (Long chatFileId : tempChatFileIds) {
                try {
                    ChatbotVO tempChatFile = new ChatbotVO();
                    tempChatFile.setChatFileId(chatFileId);
                    chatbotDAO.deleteChatFile(tempChatFile);
                } catch (Exception e) {
                    logger.warn("[MKT] 임시 참고파일 브릿지 정리 실패 - chatFileId={}: {}", chatFileId, e.getMessage());
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

    /** /file_query 동기 호출. 실패 시 "". */
    private String callFileQuerySync(String query, List<String> attachmentFileIds, String agentId) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.gpt.apiFileUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            logger.warn("[MKT] file_query API URL 미설정");
            return "";
        }

        String userId = CommonUtil.nullToBlank(SessionUtil.getUserId());
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

            try (okhttp3.Response response = FILE_QUERY_HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("[MKT] file_query 응답 오류: {}", response.code());
                    apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "marketing_file_query", reqJson,
                            0, 0, (int) (System.currentTimeMillis() - startMs), "N",
                            "HTTP " + response.code(), userId);
                    return "";
                }
                try (okhttp3.ResponseBody responseBody = response.body()) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8));
                    StringBuilder answerBuilder = new StringBuilder();
                    String doneAnswer = "";
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
                    int respTimeMs = (int) (System.currentTimeMillis() - startMs);
                    logger.info("[MKT] 참고파일 file_query 완료 - 응답 길이={}자, 소요={}ms", result.length(), respTimeMs);
                    apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "marketing_file_query", reqJson,
                            0, result.length(), respTimeMs, "Y", null, userId);
                    return CommonUtil.nullToBlank(result).trim();
                }
            }
        } catch (Exception e) {
            int respTimeMs = (int) (System.currentTimeMillis() - startMs);
            logger.warn("[MKT] file_query 호출 실패 ({}ms 경과): {}", respTimeMs, e.getMessage());
            apiCallLogService.insertSilently(agentId, null, apiUrl, modelId, "marketing_file_query", reqJson,
                    0, 0, respTimeMs, "N", e.getMessage(), userId);
            return "";
        }
    }

    // ── 프롬프트 구성 ──────────────────────────────────────────────────────────────

    private String buildMarketingTitle(Map<String, Object> request) {
        try {
            StringBuilder titlePrompt = new StringBuilder(MARKETING_TITLE_PROMPT);
            for (String[] item : collectRequestConditions(request, null)) {
                appendSection(titlePrompt, item[0], item[1]);
            }
            String result = CommonUtil.nullToBlank(chatbotService.callAiSummary(
                    titlePrompt.toString(), "marketing_title", null)).trim();
            result = result.replaceAll("^[\"'`]+|[\"'`]+$", "").trim();
            if (CommonUtil.isNotEmpty(result)) {
                return truncate(result, TITLE_MAX_LENGTH);
            }
        } catch (Exception e) {
            logger.warn("마케팅 제목 생성 실패 - fallback 사용", e);
        }
        return buildFallbackTitle(request);
    }

    private String buildFallbackTitle(Map<String, Object> request) {
        String keyMessage = stringValue(request.get("keyMessage"));
        return CommonUtil.isNotEmpty(keyMessage) ? truncate(keyMessage, TITLE_MAX_LENGTH) : "마케팅 콘텐츠";
    }

    private List<String> buildVariantLabels(Map<String, Object> request, int variantCount) {
        List<String> labels = new ArrayList<>();
        try {
            StringBuilder labelPrompt = new StringBuilder(
                    String.format(MARKETING_LABEL_PROMPT, variantCount, variantCount));
            for (String[] item : collectRequestConditions(request, null)) {
                appendSection(labelPrompt, item[0], item[1]);
            }
            String response = chatbotService.callAiSummary(labelPrompt.toString(), "marketing_label", null);
            if (CommonUtil.isEmpty(response)) {
                return labels;
            }
            for (String line : response.split("\\r?\\n")) {
                String label = line.replaceAll("^[\\s\\-*]*\\d*[.)]?\\s*", "").replaceAll("[\"'`]", "").trim();
                if (CommonUtil.isEmpty(label) || labels.contains(label)) {
                    continue;
                }
                labels.add(truncate(label, VARIANT_LABEL_MAX_LENGTH));
                if (labels.size() >= variantCount) {
                    break;
                }
            }
        } catch (Exception e) {
            logger.warn("마케팅 시안 라벨 생성 실패 - 기본 표기로 대체", e);
        }
        return labels;
    }

    /** 문안 생성/보완 프롬프트 */
    private String buildTextPrompt(
            String basePrompt, Map<String, Object> request, String referenceContext,
            String originalText, String instruction, Map<String, String> outputSectionLabels) {
        StringBuilder prompt = new StringBuilder(basePrompt);
        appendTextStyleRules(prompt, request, outputSectionLabels);
        for (String[] item : collectRequestConditions(request, null)) {
            appendSection(prompt, item[0], item[1]);
        }
        appendContextSections(prompt, referenceContext, "원문", originalText, instruction);
        return prompt.toString();
    }

    /** 이모지·해시태그·출력 구성 */
    private void appendTextStyleRules(
            StringBuilder prompt, Map<String, Object> request, Map<String, String> outputSectionLabels) {
        if ("Y".equals(stringValue(request.get("allowEmoji")))) {
            prompt.append("\n- 채널과 톤에 맞게 이모지를 적절히 활용하세요.");
        } else {
            prompt.append("\n- 이모지는 사용하지 마세요.");
        }
        if ("Y".equals(stringValue(request.get("includeHashtags")))) {
            prompt.append("\n- 해시태그를 포함하세요.");
        } else {
            prompt.append("\n- 해시태그는 넣지 마세요.");
        }
        String sections = joinDistinct(request.get("outputSections"), code -> outputSectionLabels.getOrDefault(code, code));
        if (CommonUtil.isNotEmpty(sections)) {
            prompt.append("\n\n## 출력 구성\n다음 구성의 내용을 문안에 자연스럽게 반영하세요: ").append(sections)
                    .append("\n- 구성 이름(제목, 도입부, 소제목 등)을 라벨로 붙이지 말고, 해당 내용만 이어서 작성하세요.");
        }
    }

    /** 이미지 생성/보완 프롬프트 */
    private String buildImagePrompt(
            String basePrompt, Map<String, Object> request, String referenceContext,
            String originalText, String instruction) {
        StringBuilder prompt = new StringBuilder(basePrompt);
        for (String[] item : collectRequestConditions(request, null)) {
            appendSection(prompt, item[0], item[1]);
        }
        appendContextSections(prompt, referenceContext, "참고 시안 문안", originalText, instruction);
        return CommonUtil.isEmpty(stringValue(request.get("imageText")))
                ? prompt + IMAGE_NO_TEXT_RULE
                : prompt.toString();
    }

    private void appendContextSections(
            StringBuilder prompt, String referenceContext, String originalTitle, String originalText, String instruction) {
        appendSection(prompt, "참고 자료", referenceContext);
        appendSection(prompt, originalTitle, originalText);
        appendSection(prompt, "수정 요청사항", instruction);
    }

    /** 시안별 각도 지시 */
    private String appendVariantAngle(String basePrompt, int contentNo, String label, String part) {
        StringBuilder prompt = new StringBuilder(basePrompt);
        prompt.append("\n\n## 시안 작성 조건\n- 시안 ").append(contentNo).append("번");
        if (CommonUtil.isNotEmpty(label)) {
            prompt.append("(").append(label).append(" 각도)");
        }
        if (PART_TEXT.equals(part)) {
            prompt.append("의 완성 문안만 한 편 작성하세요.\n")
                    .append("- 다른 시안, 복수 버전, 대안 문구를 함께 출력하지 마세요.\n")
                    .append("- 설명, 인사, 작성 가이드는 넣지 마세요.\n")
                    .append("- '제목:', '도입부:', '소제목:' 등 구성 요소 라벨은 넣지 마세요.\n")
                    .append("- 다른 시안과 표현이 겹치지 않게 작성하세요.");
        } else {
            prompt.append("의 이미지 한 장만 생성하세요.\n")
                    .append("- 다른 시안과 구도·색감이 겹치지 않게 구성하세요.");
        }
        return prompt.toString();
    }

    private void appendSection(StringBuilder prompt, String title, String value) {
        if (CommonUtil.isNotEmpty(value)) {
            prompt.append("\n\n## ").append(title).append("\n").append(value);
        }
    }

    /** OTHER면 사용자 입력, 코드만 있으면 그대로 사용 */
    private String resolveChoice(String code, String custom) {
        return isPlaceholderCode(code) ? custom : code;
    }

    /** 채널 코드/직접입력/SNS 플랫폼 순으로 해석한다 */
    private String resolveChannelValue(Map<String, Object> request, Map<String, String> labels) {
        String channel = stringValue(request.get("channel"));
        if (isPlaceholderCode(channel)) {
            String customChannel = stringValue(request.get("customChannel"));
            if (CommonUtil.isNotEmpty(customChannel)) {
                return customChannel;
            }
        } else if (CommonUtil.isNotEmpty(channel)) {
            return mapCodeLabel(channel, labels);
        }
        return joinDistinct(request.get("snsPlatform"), code -> mapCodeLabel(skipPlaceholderCode(code), labels));
    }

    private String mapCodeLabel(String code, Map<String, String> labels) {
        if (CommonUtil.isEmpty(code)) {
            return "";
        }
        return labels == null ? code : labels.getOrDefault(code, code);
    }

    private String resolveAspectRatio(Map<String, Object> request) {
        String custom = stringValue(request.get("customAspectRatio"));
        return CommonUtil.isNotEmpty(custom) ? custom : skipPlaceholderCode(stringValue(request.get("aspectRatio")));
    }

    private boolean isPlaceholderCode(String value) {
        return "OTHER".equals(value);
    }

    private String skipPlaceholderCode(String value) {
        return isPlaceholderCode(value) ? "" : value;
    }

    /** List/스칼라 코드 → 콤마 문자열 (OTHER 제외) */
    private String joinCodes(Object value) {
        return joinDistinct(value, this::skipPlaceholderCode);
    }

    /** REQUEST_JSON의 variantCount — Gson이 Map으로 역직렬화하면 JSON 숫자는 항상 Double이다. 필드 없으면(null) 1 */
    private int parseVariantCount(Object raw) {
        if (!(raw instanceof Number)) {
            return 1;
        }
        double count = ((Number) raw).doubleValue();
        return Math.max(1, Math.min(VARIANT_COUNT_MAX, (int) Math.floor(count)));
    }
}
