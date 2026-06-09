package kr.teamagent.library.service.impl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;


import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import javax.imageio.ImageIO;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.CommonVO;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.impl.CommonServiceImpl;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.prompt.service.impl.PromptServiceImpl;
import kr.teamagent.tmpl.service.impl.TmplHtmlRenderService;

@Service
public class LibraryServiceImpl extends EgovAbstractServiceImpl {

    private static final String INSIGHT_PLACEMENT_NEW_SECTION = "NEW_SECTION";
    private static final String INSIGHT_PLACEMENT_REPLACE = "REPLACE";
    private static final String PROMPT_ID_INSIGHT_NEW_SECTION = "PI000019";
    private static final String PROMPT_ID_INSIGHT_REPLACE = "PI000020";
    private static final String INSIGHT_FIELD_ID = "insight";
    private static final Pattern INLINE_DATA_URL_PATTERN = Pattern.compile(
            "data:image/[a-zA-Z0-9+.-]+;base64,[A-Za-z0-9+/=\\r\\n]+",
            Pattern.CASE_INSENSITIVE);
    private static final Gson AGENT_SUB_CFG_GSON = new Gson();

    private static final Logger logger = LoggerFactory.getLogger(LibraryServiceImpl.class);

    @Autowired
    LibraryDAO libraryDAO;

    @Autowired
    KeyGenerate keyGenerate;

    @Autowired
    CommonServiceImpl commonService;

    @Autowired
    ChatbotServiceImpl chatbotService;

    @Autowired
    PromptServiceImpl promptService;

    @Autowired
    TmplHtmlRenderService tmplHtmlRenderService;

    /**
     * 라이브러리용 에이전트 목록 조회 (USE_YN 무관, 서브 설정 포함)
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<LibraryVO.AgentItem> selectAgentListForLibrary(LibraryVO searchVO) throws Exception {
        List<LibraryVO.AgentItem> agentList = libraryDAO.selectAgentListForLibrary(searchVO);
        if (agentList == null || agentList.isEmpty()) {
            return agentList;
        }

        List<String> agentIdList = agentList.stream()
                .map(LibraryVO.AgentItem::getAgentId)
                .filter(id -> !CommonUtil.isEmpty(id))
                .collect(Collectors.toList());
        if (agentIdList.isEmpty()) {
            return agentList;
        }

        LibraryVO subCfgParam = new LibraryVO();
        subCfgParam.setAgentIdList(agentIdList);
        List<LibraryVO.AgtSubCfgVO> subCfgList = libraryDAO.selectAgentSubCfgListByAgentIds(subCfgParam);

        Map<String, LibraryVO.AgtSubCfgVO> subCfgByAgentId = new HashMap<>();
        if (subCfgList != null) {
            for (LibraryVO.AgtSubCfgVO subCfg : subCfgList) {
                if (subCfg == null || CommonUtil.isEmpty(subCfg.getAgentId())) {
                    continue;
                }
                parseAgentSubAdditionalConfig(subCfg);
                subCfgByAgentId.put(subCfg.getAgentId(), subCfg);
            }
        }

        for (LibraryVO.AgentItem agent : agentList) {
            agent.setSubCfg(subCfgByAgentId.get(agent.getAgentId()));
        }
        return agentList;
    }

    /**
     * Agent 서브 설정 파싱
     * @param subCfg
     */
    private void parseAgentSubAdditionalConfig(LibraryVO.AgtSubCfgVO subCfg) {
        if (subCfg == null) {
            return;
        }
        String json = subCfg.getAdditionalConfig();
        if (CommonUtil.isEmpty(json)) {
            subCfg.setAdditionalConfigMap(null);
            return;
        }
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        subCfg.setAdditionalConfigMap(AGENT_SUB_CFG_GSON.fromJson(json, type));
    }

    /**
     * 카테고리 목록 조회 (세션 userId 자동 설정)
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<LibraryVO> selectCategoryList(LibraryVO searchVO) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId != null) {
            searchVO.setUserId(userId);
        }
        return libraryDAO.selectCategoryList(searchVO);
    }

    /**
     * 카드 목록 조회 (세션 userId 자동 설정)
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<LibraryVO> selectCardList(LibraryVO searchVO) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId != null) {
            searchVO.setUserId(userId);
        }
        searchVO.setArchiveYn("N");
        searchVO.setUseYn("Y");
        return libraryDAO.selectCardList(searchVO);
    }

    /**
     * 보관된 카드 목록 조회 (세션 userId 자동 설정, archiveYn='Y')
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<LibraryVO> selectArchiveCardList(LibraryVO searchVO) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId != null) {
            searchVO.setUserId(userId);
        }
        return libraryDAO.selectArchiveCardList(searchVO);
    }

    /**
     * 휴지통 카드 목록 조회 (useYn='N')
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<LibraryVO> selectTrashCardList(LibraryVO searchVO) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId != null) {
            searchVO.setUserId(userId);
        }
        return libraryDAO.selectTrashCardList(searchVO);
    }

    /**
     * 카드 상세 조회
     * @param searchVO cardId 필수
     * @return
     * @throws Exception
     */
    public LibraryVO selectCardDetail(LibraryVO searchVO) throws Exception {
        return libraryDAO.selectCardDetail(searchVO);
    }

    /**
     * 테이블 데이터 조회
     * @param card logId 필수 (요청 body의 card)
     * @return
     * @throws Exception
     */
    public LibraryVO.TableDataItem selectTableData(LibraryVO.CardItem card) throws Exception {
        if (card == null || CommonUtil.isEmpty(card.getLogId())) {
            return null;
        }
        return libraryDAO.selectTableData(card);
    }

    /**
     * 참조 매뉴얼(문서) 목록 조회
     * @param card logId 필수 (요청 body의 card)
     * @return
     * @throws Exception
     */
    public List<LibraryVO.DocItem> selectDocList(LibraryVO.CardItem card) throws Exception {
        if (card == null || CommonUtil.isEmpty(card.getLogId())) {
            return Collections.emptyList();
        }
        return libraryDAO.selectDocList(card);
    }

    /**
     * 카드 PIN 여부 업데이트
     * @param searchVO cardId, pinYn 필수
     * @return
     * @throws Exception
     */
    public int updateCardPin(LibraryVO searchVO) throws Exception {
        return libraryDAO.updateCardPin(searchVO);
    }

    /**
     * 카드 수정 (기존 카드만 UPDATE, 신규 등록 없음)
     * @param card cardId, userId 필수 (세션 userId로 본인 카드만 수정)
     * @return
     * @throws Exception
     */
    public int updateCard(LibraryVO.CardItem card) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId != null) {
            card.setUserId(userId);
        }
        return libraryDAO.updateCard(card);
    }

    /**
     * 신규 회원가입 시 디폴트 카테고리 등록 (세션 없이 userId 직접 전달)
     * @param userId 신규 가입된 사용자 ID
     * @throws Exception
     */
    public void insertDefaultCategoryForNewUser(String userId) throws Exception {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        LibraryVO.CategoryItem cat = new LibraryVO.CategoryItem();
        cat.setUserId(userId);
        cat.setCategoryNm("내 카테고리");
        cat.setColor(null);
        cat.setSortOrd(1);
        saveCategory(cat);
    }

    /**
     * 카테고리 등록/수정
     * @param searchVO categoryId 비어있으면 자동 생성
     * @return
     * @throws Exception
     */
    public void saveCategory(LibraryVO.CategoryItem searchVO) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId != null) {
            searchVO.setUserId(userId);
        }
        if (CommonUtil.isEmpty(searchVO.getCategoryId())) {
            searchVO.setCategoryId(keyGenerate.generateTableKey("KC", "TB_KNOW_CAT", "CATEGORY_ID"));
        }
        libraryDAO.insertCategory(searchVO);
    }

    /**
     * 카테고리 삭제 (하위 카드 존재 시 삭제 불가)
     * @param searchVO categoryId 필수 (세션 userId로 본인 카테고리만 삭제)
     * @return 삭제 성공 시 삭제 건수, 하위 카드 존재 시 -1
     * @throws Exception
     */
    public int deleteCategory(LibraryVO.CategoryItem searchVO) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId != null) {
            searchVO.setUserId(userId);
        }
        int cardCount = libraryDAO.selectCategoryCardCount(searchVO);
        if (cardCount > 0) {
            return -1;
        }
        return libraryDAO.deleteCategory(searchVO);
    }

    /**
     * 카테고리 순서 일괄 수정
     * @param searchVO items [{ categoryId, sortOrd }] 필수 (세션 userId로 본인 카테고리만 수정)
     * @return
     * @throws Exception
     */
    public int updateCategoryOrder(LibraryVO searchVO) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId == null || searchVO.getItems() == null || searchVO.getItems().isEmpty()) {
            return 0;
        }
        searchVO.setUserId(userId);
        return libraryDAO.updateCategoryOrder(searchVO);
    }

    /**
     * 카드 순서·카테고리 일괄 수정 (카테고리 간 이동 포함)
     * @param searchVO payload [{ categoryId, cards: [{ cardId, order }] }] 필수
     * @return
     * @throws Exception
     */
    public int updateCardOrder(LibraryVO searchVO) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId == null || searchVO.getPayload() == null || searchVO.getPayload().isEmpty()) {
            return 0;
        }
        List<LibraryVO.CardOrderPayload> filtered = searchVO.getPayload().stream()
                .filter(cat -> cat.getCards() != null && !cat.getCards().isEmpty())
                .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            return 0;
        }
        searchVO.setPayload(filtered);
        searchVO.setUserId(userId);
        return libraryDAO.updateCardOrder(searchVO);
    }

    /**
     * 카드 이동 (대상 카테고리의 max sortOrd + 1로 맨 뒤에 배치)
     * @param searchVO cardId, targetCategoryId 필수 (userId는 세션에서 설정)
     * @return
     * @throws Exception
     */
    public int moveCard(LibraryVO searchVO) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId == null || searchVO.getCardId() == null || searchVO.getTargetCategoryId() == null) {
            return 0;
        }
        searchVO.setUserId(userId);
        return libraryDAO.moveCard(searchVO);
    }

    /**
     * 휴지통 카드 완전 삭제 (USE_YN='N'인 해당 사용자의 카드 일괄 DELETE)
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int deleteTrashCard(LibraryVO searchVO) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId == null) {
            return 0;
        }
        searchVO.setUserId(userId);
        return libraryDAO.deleteTrashCard(searchVO);
    }

    /**
     * 차트 통계 속성 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<LibraryVO.ChartStatItem> selectChartStatList(LibraryVO searchVO) throws Exception {
        return libraryDAO.selectChartStatList(searchVO);
    }

    /**
     * 차트 라벨 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<LibraryVO.ChartDetailCdItem> selectChartDetailCdList(LibraryVO searchVO) throws Exception {
        return libraryDAO.selectChartDetailCdList(searchVO);
    }

    /**
     * 지식카드 차트 목록 조회
     * @param searchVO cardId 필수
     * @return
     * @throws Exception
     */
    public List<LibraryVO.KnowChartItem> selectKnowChartList(LibraryVO searchVO) throws Exception {
        if (searchVO == null || CommonUtil.isEmpty(searchVO.getCardId())) {
            return Collections.emptyList();
        }
        return libraryDAO.selectKnowChartList(searchVO);
    }

    /**
     * 지식카드 차트 저장 (신규 insert)
     * @param searchVO cardId, chartType, chartTargetKey, yAxisKeys 필수
     * @return successYn, returnMsg, data(chartId)
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveKnowChart(LibraryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        if (searchVO == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청 본문이 없습니다.");
            return resultMap;
        }
        String missingField = resolveKnowChartMissingField(searchVO);
        if (missingField != null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", missingField + "가(이) 필요합니다.");
            return resultMap;
        }

        String userId = SessionUtil.getUserId();
        if (CommonUtil.isEmpty(userId)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "로그인이 필요합니다.");
            return resultMap;
        }

        LibraryVO cardParam = new LibraryVO();
        cardParam.setCardId(searchVO.getCardId());
        LibraryVO card = libraryDAO.selectCardDetail(cardParam);
        if (card == null || !userId.equals(card.getUserId())) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "카드를 찾을 수 없습니다.");
            return resultMap;
        }

        LibraryVO.KnowChartSavePayload insertVO = new LibraryVO.KnowChartSavePayload();
        insertVO.setChartId(keyGenerate.generateTableKey("KH", "TB_KNOW_CARD_CHART", "CHART_ID"));
        insertVO.setCardId(searchVO.getCardId());
        insertVO.setChartType(searchVO.getChartType());
        insertVO.setChartTargetKey(searchVO.getChartTargetKey());
        insertVO.setYAxisKeysJson(AGENT_SUB_CFG_GSON.toJson(searchVO.getYAxisKeys()));
        insertVO.setSeriesKey(CommonUtil.nullToBlank(searchVO.getSeriesKey()));
        insertVO.setStatIdFilter(CommonUtil.nullToBlank(searchVO.getStatIdFilter()));
        insertVO.setStackYn("Y".equals(searchVO.getStackYn()) ? "Y" : "N");
        insertVO.setDualAxisYn("Y".equals(searchVO.getDualAxisYn()) ? "Y" : "N");
        insertVO.setYlChartType(searchVO.getYlChartType());
        insertVO.setYrChartType(searchVO.getYrChartType());
        insertVO.setSortOrd(searchVO.getSortOrd() != null ? searchVO.getSortOrd() : 0);
        insertVO.setCreateUserId(userId);

        libraryDAO.insertKnowChart(insertVO);

        Map<String, Object> data = new HashMap<>();
        data.put("chartId", insertVO.getChartId());

        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        resultMap.put("data", data);
        return resultMap;
    }

    private String resolveKnowChartMissingField(LibraryVO searchVO) {
        if (CommonUtil.isEmpty(searchVO.getCardId())) {
            return "cardId";
        }
        if (CommonUtil.isEmpty(searchVO.getChartType())) {
            return "chartType";
        }
        if (CommonUtil.isEmpty(searchVO.getChartTargetKey())) {
            return "chartTargetKey";
        }
        if (CommonUtil.isEmpty(searchVO.getYAxisKeys())) {
            return "yAxisKeys";
        }
        return null;
    }

    /**
     * 지식카드 차트 삭제
     * @param searchVO chartId 필수
     * @return successYn, returnMsg, data(chartId)
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteKnowChart(LibraryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        if (searchVO == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청 본문이 없습니다.");
            return resultMap;
        }
        if (CommonUtil.isEmpty(searchVO.getChartId())) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "chartId가 필요합니다.");
            return resultMap;
        }

        String userId = SessionUtil.getUserId();
        if (CommonUtil.isEmpty(userId)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "로그인이 필요합니다.");
            return resultMap;
        }

        searchVO.setUserId(userId);
        int deleted = libraryDAO.deleteKnowChart(searchVO);
        if (deleted == 0) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "삭제할 차트를 찾을 수 없습니다.");
            return resultMap;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("chartId", searchVO.getChartId());

        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        resultMap.put("data", data);
        return resultMap;
    }

    /**
     * 지식카드 차트 수정
     * @param searchVO chartId, chartType, chartTargetKey, yAxisKeys 필수
     * @return successYn, returnMsg, data(chartId)
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateKnowChart(LibraryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        if (searchVO == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청 본문이 없습니다.");
            return resultMap;
        }
        String missingField = resolveKnowChartUpdateMissingField(searchVO);
        if (missingField != null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", missingField + "가(이) 필요합니다.");
            return resultMap;
        }

        String userId = SessionUtil.getUserId();
        if (CommonUtil.isEmpty(userId)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "로그인이 필요합니다.");
            return resultMap;
        }

        searchVO.setUserId(userId);
        searchVO.setYAxisKeysJson(AGENT_SUB_CFG_GSON.toJson(searchVO.getYAxisKeys()));
        searchVO.setSeriesKey(CommonUtil.nullToBlank(searchVO.getSeriesKey()));
        searchVO.setStatIdFilter(CommonUtil.nullToBlank(searchVO.getStatIdFilter()));
        searchVO.setStackYn("Y".equals(searchVO.getStackYn()) ? "Y" : "N");
        searchVO.setDualAxisYn("Y".equals(searchVO.getDualAxisYn()) ? "Y" : "N");
        if (searchVO.getSortOrd() == null) {
            searchVO.setSortOrd(0);
        }

        int updated = libraryDAO.updateKnowChart(searchVO);
        if (updated == 0) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "변경할 차트를 찾을 수 없습니다.");
            return resultMap;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("chartId", searchVO.getChartId());

        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        resultMap.put("data", data);
        return resultMap;
    }

    private String resolveKnowChartUpdateMissingField(LibraryVO searchVO) {
        if (CommonUtil.isEmpty(searchVO.getChartId())) {
            return "chartId";
        }
        if (CommonUtil.isEmpty(searchVO.getChartType())) {
            return "chartType";
        }
        if (CommonUtil.isEmpty(searchVO.getChartTargetKey())) {
            return "chartTargetKey";
        }
        if (CommonUtil.isEmpty(searchVO.getYAxisKeys())) {
            return "yAxisKeys";
        }
        return null;
    }

    /**
     * AI 문서 생성 (요청: cardId, tmplId)
     * DAO·DB·외부 연동 등 이후 흐름은 미구현 — 응답 객체만 반환한다.
     *
     * @param requestVO cardId, tmplId 필수
     * @return 생성 결과 DTO (현재는 빈 객체)
     * @throws Exception
     */
    public Map<String, Object> createDoc(LibraryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        if (searchVO == null || CommonUtil.isEmpty(searchVO.getCardId()) || CommonUtil.isEmpty(searchVO.getTmplId())) {
            return resultMap;
        }

        LibraryVO cardContent = libraryDAO.selectCardChatContent(searchVO);
        if (cardContent == null) {
            return resultMap;
        }

        // 프롬프트 필요 정보 조회
        LibraryVO.TmplItem tmpl = libraryDAO.selectTmpl(searchVO); // 템플릿 정보 조회
        List<LibraryVO.TmplFieldItem> tmplFieldList = libraryDAO.selectTmplFieldList(searchVO); // 템플릿 필드 정보 조회
        UserVO userVO = SessionUtil.getUserVO(); // 사용자 정보 조회
        String userNm = userVO != null ? CommonUtil.nullToBlank(userVO.getUserNm()) : "";  // 사용자 이름 조회
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date()); // 오늘 날짜 조회

        if(tmpl.getTmplType().equals("T")) {
            
            // STEP1 : MULTILINE_YN = 'Y' 인 key 수집
            List<String> multilineJsonKeys = new ArrayList<>();
            if (tmplFieldList != null) {
                for (LibraryVO.TmplFieldItem fieldItem : tmplFieldList) {
                    if (fieldItem == null || CommonUtil.isEmpty(fieldItem.getJsonKey())) {
                        continue;
                    }
                    if ("Y".equals(fieldItem.getMultilineYn())) {
                        multilineJsonKeys.add(fieldItem.getJsonKey());
                    }
                }
            }

            // STEP2 : {{HTML_FIELD_INSTRUCTION}} 치환값 생성 (조건부)
            String htmlFieldInstruction = "";
            if (!multilineJsonKeys.isEmpty()) {
                htmlFieldInstruction = "key가 다음인 필드의 value는 HTML이 아닌 일반 텍스트 기반 JSON 문자열 배열이어야 합니다: "
                        + String.join(", ", multilineJsonKeys)
                        + ". 예: [\"항목1\", \"항목2\"]."
                        + " HTML 태그(<p>, <li> 등)는 포함하지 말 것."
                        + " 응답 JSON의 키(key) 순서는 본 프롬프트에 제시된 필드 목록에 나온 key 나열 순서와 동일하게 유지할 것."
                        + " 요청·명세에 정의된 필드 순서를 바꾸거나 뒤섞지 말고, 동일한 순서로 출력할 것.";
            }

            // STEP3 : {{FIELD_LIST}} 치환값 생성
            StringBuilder fieldList = new StringBuilder();
            if (tmplFieldList != null) {
                for (LibraryVO.TmplFieldItem fieldItem : tmplFieldList) {
                    if (fieldItem == null || CommonUtil.isEmpty(fieldItem.getJsonKey())) continue;
                    String jsonKey = fieldItem.getJsonKey();
                    String fieldNm = CommonUtil.isEmpty(fieldItem.getFieldNm()) ? jsonKey : fieldItem.getFieldNm();
                    fieldList.append("\nkey : ").append(jsonKey).append("_label (고정값: \"").append(fieldNm).append("\". 반드시 이 문자열만 사용. 내용 요약 금지)");
                    if ("Y".equals(fieldItem.getMultilineYn())) {
                        fieldList.append("\nkey : ").append(jsonKey).append(" (").append(fieldNm)
                                .append(") (JSON 문자열 배열로 응답. 예: [\"항목1\", \"항목2\"])");
                    } else {
                        fieldList.append("\nkey : ").append(jsonKey).append(" (").append(fieldNm).append(")");
                    }
                }
            }

            // STEP4 : DB에서 가져온 LLM_PROMPT 템플릿에 플레이스홀더 치환
            String promptTemplate = CommonUtil.nullToBlank(tmpl.getLlmPrompt());

            if (CommonUtil.isEmpty(promptTemplate)) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "프롬프트 템플릿이 없습니다.");
                resultMap.put("data", null);
                return resultMap;
            }

            String qContent = cardContent.getQContent() == null ? "" : cardContent.getQContent();
            String rContent = cardContent.getRContent() == null ? "" : cardContent.getRContent();
            if (!multilineJsonKeys.isEmpty()
                    && (TmplHtmlRenderService.containsMarkdownPipeTable(qContent)
                            || TmplHtmlRenderService.containsMarkdownPipeTable(rContent))) {
                htmlFieldInstruction += " 단, 답변에 마크다운 파이프 표가 있으면 "
                        + "표 구간만 HTML <table><tbody><tr><th>또는<td>...</table> 로 변환해 넣을 것(마크다운 | 파이프 표 금지). "
                        + "<p>, <li> 등 그 외 HTML은 포함하지 말고 <table> 관련 태그만 예외로 허용. "
                        + "표는 content(본문내용) 등 해당 필드 JSON 배열 항목 안에 넣어 보고서 본문 표 셀에 그려지게 할 것.";
            }
            List<String> extractedImgTags = new ArrayList<>();
            String strippedQContent = stripCreateDocContentImages(qContent, extractedImgTags);
            String strippedRContent = stripCreateDocContentImages(rContent, extractedImgTags);
            String prompt = promptTemplate
                    .replace("{{Q_CONTENT}}", strippedQContent)
                    .replace("{{R_CONTENT}}", strippedRContent)
                    .replace("{{TODAY}}", today)
                    .replace("{{USER_NM}}", userNm)
                    .replace("{{HTML_FIELD_INSTRUCTION}}", htmlFieldInstruction)
                    .replace("{{FIELD_LIST}}", fieldList.toString());
            String base64ImageInstruction = buildBase64ImageInstruction(extractedImgTags.size());
            if (!CommonUtil.isEmpty(base64ImageInstruction)) {
                prompt = prompt + "\n\n" + base64ImageInstruction;
            }
            String markdownTableInstruction = buildMarkdownTableInstruction(qContent, rContent);
            if (!CommonUtil.isEmpty(markdownTableInstruction)) {
                prompt = prompt + "\n\n" + markdownTableInstruction;
            }

            // STEP5 : AI 호출
            logger.info("prompt: {}", prompt);
            String res = chatbotService.callAiSummary(prompt, "createDoc");

            if (CommonUtil.isEmpty(res)) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "AI 문서 생성 실패");
                resultMap.put("data", null);
                return resultMap;
            }

            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "AI 문서 생성 성공");
            String renderedHtml = CommonUtil.nullToBlank(tmpl.getTmplHtml());
            JSONObject aiJson = parseAiTemplateJson(res);
            if (aiJson != null) {
                if (!extractedImgTags.isEmpty()) {
                    tmplHtmlRenderService.dedupeCreateDocImageTokens(aiJson, tmplFieldList);
                }
                renderedHtml = tmplHtmlRenderService.renderTemplateHtml(renderedHtml, aiJson, tmplFieldList);
                if (!extractedImgTags.isEmpty()) {
                    renderedHtml = restoreCreateDocImages(renderedHtml, extractedImgTags);
                }
            } else {
                logger.warn("createDoc HTML 렌더링 생략 - AI 응답 JSON 파싱 실패 (tmplId={})", searchVO.getTmplId());
            }
            resultMap.put("tmplHtml", renderedHtml);
            resultMap.put("data", res);
            if (!CommonUtil.isEmpty(searchVO.getRoomId())) {
                LibraryVO reportLog = new LibraryVO();
                reportLog.setRoomId(searchVO.getRoomId());
                reportLog.setIdxNo(searchVO.getIdxNo() != null ? searchVO.getIdxNo() : 1);
                reportLog.setUserId(SessionUtil.getUserId());
                reportLog.setReportData(res);
                reportLog.setAskQuery(searchVO.getAskQuery());
                libraryDAO.insertReportChatLog(reportLog);
            }

        }else{

            // STEP1 : DB에서 가져온 LLM_PROMPT 템플릿 조회
            String promptTemplate = CommonUtil.nullToBlank(tmpl.getLlmPrompt());

            if (CommonUtil.isEmpty(promptTemplate)) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "프롬프트 템플릿이 없습니다.");
                resultMap.put("data", null);
                return resultMap;
            }

            // STEP2 : 플레이스홀더 치환 (필드 관련 변수는 사용하지 않으므로 공백 처리)
            String qContent = cardContent.getQContent() == null ? "" : cardContent.getQContent();
            String rContent = cardContent.getRContent() == null ? "" : cardContent.getRContent();
            List<String> extractedImgTags = new ArrayList<>();
            String strippedQContent = stripCreateDocContentImages(qContent, extractedImgTags);
            String strippedRContent = stripCreateDocContentImages(rContent, extractedImgTags);
            String prompt = promptTemplate
                    .replace("{{Q_CONTENT}}", strippedQContent)
                    .replace("{{R_CONTENT}}", strippedRContent)
                    .replace("{{TODAY}}", today)
                    .replace("{{USER_NM}}", userNm);
            String base64ImageInstruction = buildBase64ImageInstruction(extractedImgTags.size());
            if (!CommonUtil.isEmpty(base64ImageInstruction)) {
                prompt = prompt + "\n\n" + base64ImageInstruction;
            }

            // STEP3 : AI 호출
            logger.info("prompt: {}", prompt);
            String res = chatbotService.callAiSummary(prompt, "createDoc");

            if (CommonUtil.isEmpty(res)) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "AI 문서 생성 실패");
                resultMap.put("data", null);
                return resultMap;
            }

            if (!extractedImgTags.isEmpty()) {
                res = restoreCreateDocImages(res, extractedImgTags);
            }

            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "AI 문서 생성 성공");
            resultMap.put("data", res);
            if (!CommonUtil.isEmpty(searchVO.getRoomId())) {
                LibraryVO reportLog = new LibraryVO();
                reportLog.setRoomId(searchVO.getRoomId());
                reportLog.setIdxNo(searchVO.getIdxNo() != null ? searchVO.getIdxNo() : 1);
                reportLog.setUserId(SessionUtil.getUserId());
                reportLog.setReportData(res);
                reportLog.setAskQuery(searchVO.getAskQuery());
                libraryDAO.insertReportChatLog(reportLog);
            }
        }

        return resultMap;
    }

    /** createDoc AI 응답에서 템플릿 렌더링용 JSON 객체를 추출한다. */
    private JSONObject parseAiTemplateJson(String answer) {
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
        try {
            Object parsed = new JSONParser().parse(jsonStr);
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            }
        } catch (Exception e) {
            logger.warn("createDoc JSON 파싱 실패: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 보고서 보완 요청: 동일 방의 최신 REPORT_DATA를 포함해 AI에 재요청하고 로그를 IDX_NO+1로 적재한다.
     *
     * @param searchVO roomId, askQuery 필수
     * @return successYn, returnMsg, data (AI 응답 문자열)
     * @throws Exception
     */
    public Map<String, Object> reAskReport(LibraryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        if (searchVO == null || CommonUtil.isEmpty(searchVO.getRoomId()) || CommonUtil.isEmpty(searchVO.getAskQuery())) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "roomId와 askQuery가 필요합니다.");
            resultMap.put("data", null);
            return resultMap;
        }
        // currentHtml: 프론트 에디터 전체 HTML (표 + 표 외 텍스트 포함)
        String currentHtml = searchVO.getCurrentHtml();
        if (CommonUtil.isEmpty(currentHtml)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "currentHtml이 필요합니다.");
            resultMap.put("data", null);
            return resultMap;
        }
        // 로그 적재용 IDX_NO 계산
        LibraryVO lastLog = libraryDAO.selectLastReportChatLog(searchVO);
        Integer lastIdx = (lastLog != null && lastLog.getIdxNo() != null) ? lastLog.getIdxNo() : 0;

        // AI 전송 전 <img> 태그를 플레이스홀더로 교체 (base64 이미지로 인한 payload 크기 및 타임아웃 방지)
        List<String> extractedImgTags = new ArrayList<>();
        String strippedHtml = stripImgTags(currentHtml, extractedImgTags);

        String prompt = buildReAskReportPrompt(strippedHtml, searchVO.getAskQuery());
        logger.info("reAskReport prompt: {}", prompt != null ? prompt : "");
        String res = chatbotService.callAiSummary(prompt, "reAskReport");
        if (CommonUtil.isEmpty(res)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "AI 보고서 보완 요청 실패");
            resultMap.put("data", null);
            return resultMap;
        }

        // AI 응답에서 플레이스홀더를 원래 <img> 태그로 복원
        if (!extractedImgTags.isEmpty()) {
            res = restoreImgTags(res, extractedImgTags);
        }
        LibraryVO reportLog = new LibraryVO();
        reportLog.setRoomId(searchVO.getRoomId());
        reportLog.setIdxNo(lastIdx + 1);
        reportLog.setUserId(SessionUtil.getUserId());
        reportLog.setReportData(res);
        reportLog.setAskQuery(searchVO.getAskQuery());
        libraryDAO.insertReportChatLog(reportLog);
        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "AI 보고서 보완 요청 성공");
        resultMap.put("data", res);
        return resultMap;
    }

    /**
     * 보고서 인사이트 분석: 참고 rContent와 currentHtml로 AI 인사이트를 생성하고 로그를 적재한다.
     *
     * @param requestBody body: { roomId, insightPlacement, rcontent, currentHtml, targetValueKey? }
     * @return successYn, returnMsg, data (AI 응답 문자열)
     * @throws Exception
     */
    public Map<String, Object> insightReport(Map<String, Object> requestBody) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        String roomId = getRequestString(requestBody, "roomId");
        String insightPlacement = getRequestString(requestBody, "insightPlacement");
        String rContent = getRequestString(requestBody, "rcontent", "rContent", "RContent");
        String currentHtml = getRequestString(requestBody, "currentHtml");
        String targetValueKey = getRequestString(requestBody, "targetValueKey");

        if (CommonUtil.isEmpty(roomId) || CommonUtil.isEmpty(insightPlacement) || CommonUtil.isEmpty(rContent)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "roomId, insightPlacement, rcontent가 필요합니다.");
            resultMap.put("data", null);
            return resultMap;
        }
        String placement = insightPlacement.trim();
        if (!INSIGHT_PLACEMENT_NEW_SECTION.equals(placement) && !INSIGHT_PLACEMENT_REPLACE.equals(placement)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "insightPlacement는 NEW_SECTION 또는 REPLACE여야 합니다.");
            resultMap.put("data", null);
            return resultMap;
        }
        if (INSIGHT_PLACEMENT_REPLACE.equals(placement) && CommonUtil.isEmpty(targetValueKey)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "REPLACE일 때 targetValueKey가 필요합니다.");
            resultMap.put("data", null);
            return resultMap;
        }
        if (CommonUtil.isEmpty(currentHtml)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "currentHtml이 필요합니다.");
            resultMap.put("data", null);
            return resultMap;
        }

        LibraryVO searchVO = new LibraryVO();
        searchVO.setRoomId(roomId);

        LibraryVO lastLog = libraryDAO.selectLastReportChatLog(searchVO);
        Integer lastIdx = (lastLog != null && lastLog.getIdxNo() != null) ? lastLog.getIdxNo() : 0;

        List<String> extractedImgTags = new ArrayList<>();
        String strippedHtml = stripImgTags(currentHtml, extractedImgTags);

        String promptId = INSIGHT_PLACEMENT_NEW_SECTION.equals(placement)
                ? PROMPT_ID_INSIGHT_NEW_SECTION
                : PROMPT_ID_INSIGHT_REPLACE;
        String resolvedTargetValueKey = INSIGHT_PLACEMENT_REPLACE.equals(placement) ? targetValueKey : null;
        String insightFieldId = INSIGHT_PLACEMENT_NEW_SECTION.equals(placement) ? INSIGHT_FIELD_ID : null;
        String prompt = buildInsightReportPrompt(promptId, strippedHtml, rContent, resolvedTargetValueKey, insightFieldId);
        if (CommonUtil.isEmpty(prompt)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "인사이트 분석 프롬프트가 없습니다.");
            resultMap.put("data", null);
            return resultMap;
        }
        logger.info("insightReport prompt (placement={}): {}", placement, prompt);
        String res = chatbotService.callAiSummary(prompt, "insightReport");
        if (CommonUtil.isEmpty(res)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "AI 보고서 인사이트 분석 실패");
            resultMap.put("data", null);
            return resultMap;
        }

        if (!extractedImgTags.isEmpty()) {
            res = restoreImgTags(res, extractedImgTags);
        }

        StringBuilder askQuery = new StringBuilder("INSIGHT:").append(placement);
        if (!CommonUtil.isEmpty(resolvedTargetValueKey)) {
            askQuery.append(":").append(resolvedTargetValueKey);
        }
        LibraryVO reportLog = new LibraryVO();
        reportLog.setRoomId(roomId);
        reportLog.setIdxNo(lastIdx + 1);
        reportLog.setUserId(SessionUtil.getUserId());
        reportLog.setReportData(res);
        reportLog.setAskQuery(askQuery.toString());
        libraryDAO.insertReportChatLog(reportLog);

        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "AI 보고서 인사이트 분석 성공");
        resultMap.put("data", res);
        return resultMap;
    }

    /**
     * 보고서 인사이트 분석 프롬프트 생성 (DB 템플릿의 {{R_CONTENT}}, {{CURRENT_HTML}} 등 치환)
     */
    private String buildInsightReportPrompt(String promptId, String currentHtml, String rContent,
            String targetValueKey, String insightFieldId) throws Exception {
        String promptTemplate = CommonUtil.nullToBlank(promptService.getPrompt(promptId, "N"));
        if (CommonUtil.isEmpty(promptTemplate)) {
            return "";
        }
        return promptTemplate
                .replace("{{R_CONTENT}}", rContent == null ? "" : rContent)
                .replace("{{CURRENT_HTML}}", currentHtml == null ? "" : currentHtml)
                .replace("{{TARGET_VALUE_KEY}}", targetValueKey == null ? "" : targetValueKey)
                .replace("{{INSIGHT_FIELD_ID}}", insightFieldId == null ? "" : insightFieldId);
    }

    /**
     * 보고서 보완 요청 프롬프트 생성
     * @param previousReportData
     * @param askQuery
     * @return
     * @throws Exception
     */
    private String buildReAskReportPrompt(String previousReportData, String askQuery) throws Exception {
        StringBuilder sb = new StringBuilder();
        String promptContent = promptService.getPrompt("PI000017", "Y"); // 보고서 재요청 프롬프트
        if (!CommonUtil.isEmpty(promptContent)) {
            sb.append(promptContent);
            sb.append("\n");
        }
        sb.append("이전 응답:\n");
        sb.append(previousReportData);
        sb.append("\n\n수정 지침:\n");
        sb.append(askQuery);
        
        return sb.toString();
    }

    /**
     * createDoc 프롬프트용: Q/R에서 이미지를 추출·플레이스홀더 치환한다.
     */
    private String stripCreateDocContentImages(String content, List<String> extractedImgTags) {
        if (CommonUtil.isEmpty(content)) {
            return content;
        }
        String stripped = stripImgTags(content, extractedImgTags, true);
        return stripStandaloneBase64DataUrls(stripped, extractedImgTags, true);
    }

    /**
     * &lt;img&gt; 없이 본문에 직접 포함된 data:image/...;base64,... 문자열을 플레이스홀더로 치환한다.
     */
    private String stripStandaloneBase64DataUrls(String content, List<String> extractedImgTags, boolean useImgToken) {
        if (CommonUtil.isEmpty(content)) {
            return content;
        }
        StringBuffer sb = new StringBuffer();
        Matcher matcher = INLINE_DATA_URL_PATTERN.matcher(content);
        int idx = extractedImgTags.size();
        while (matcher.find()) {
            String dataUrl = matcher.group();
            extractedImgTags.add(buildImgTagFromDataUrl(dataUrl));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(imgPlaceholderReplacement(idx, useImgToken)));
            idx++;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String imgPlaceholderReplacement(int idx, boolean useImgToken) {
        if (useImgToken) {
            return "[[" + TmplHtmlRenderService.CREATE_DOC_IMG_TOKEN + ":" + idx + "]]";
        }
        return "<img data-img-placeholder=\"" + idx + "\">";
    }

    /**
     * data URL만 있는 경우 원본 픽셀 크기를 읽어 width/height를 img 태그에 반영한다.
     * 이미 &lt;img&gt; 태그로 저장된 경우는 stripImgTags가 전체 태그(style 등)를 그대로 보존한다.
     */
    private String buildImgTagFromDataUrl(String dataUrl) {
        if (CommonUtil.isEmpty(dataUrl)) {
            return "<img src=\"\">";
        }
        try {
            String base64 = dataUrl;
            int comma = base64.indexOf("base64,");
            if (comma >= 0) {
                base64 = base64.substring(comma + "base64,".length());
            }
            base64 = base64.replaceAll("\\s+", "");
            byte[] bytes = Base64.getDecoder().decode(base64);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                int w = image.getWidth();
                int h = image.getHeight();
                return "<img src=\"" + dataUrl + "\" width=\"" + w + "\" height=\"" + h
                        + "\" style=\"width:" + w + "px;height:" + h + "px;\">";
            }
        } catch (Exception e) {
            logger.debug("createDoc 이미지 크기 추출 실패: {}", e.getMessage());
        }
        return "<img src=\"" + dataUrl + "\">";
    }

    /**
     * R/Q에 마크다운 파이프 표가 있을 때 AI가 응답 JSON에 HTML table을 넣도록 지시한다.
     */
    private String buildMarkdownTableInstruction(String qContent, String rContent) {
        if (!TmplHtmlRenderService.containsMarkdownPipeTable(qContent)
                && !TmplHtmlRenderService.containsMarkdownPipeTable(rContent)) {
            return "";
        }
        return "참고: 답변(R_CONTENT)에 마크다운 파이프 표(| 열1 | 열2 |, |---|---|, 데이터 행)가 있습니다. "
                + "보고서 JSON의 content(본문내용) 등 multiline 배열 필드에 반영할 때 "
                + "표는 반드시 HTML <table> 형식으로 변환해 넣을 것. 마크다운 파이프 표(| ... |)는 응답에 넣지 말 것. "
                + "예: \"<table><tbody><tr><th>기간</th><th>상품유형</th><th>개통 건수</th></tr>"
                + "<tr><td>2025-6</td><td>8VSB</td><td>4,804</td></tr>...</tbody></table>\". "
                + "첫 행은 <th>, 데이터 행은 <td>로 작성. 열·행·수치를 변경·생략·합치지 말 것. "
                + "표를 요약 문장·불릿 목록으로 바꾸지 말 것. "
                + "표 앞·뒤 설명(제목, ■ 소제목, 본문 설명, ▶ 관련 통계 정보 등)은 일반 텍스트로 같은 배열 항목 또는 인접 항목에 두고, "
                + "표 HTML은 해당 설명과 인접한 위치(보통 표 직전·직후)의 배열 항목에 넣어 본문 표 셀 안에 표가 그려지게 할 것. "
                + "표가 여러 개이면 각각 별도 <table>...</table> 블록으로 넣을 것.";
    }

    /**
     * createDoc: 추출된 이미지가 있을 때 AI가 플레이스홀더 토큰을 반드시 포함하도록 지시한다.
     */
    private String buildBase64ImageInstruction(int imageCount) {
        if (imageCount <= 0) {
            return "";
        }
        String token = TmplHtmlRenderService.CREATE_DOC_IMG_TOKEN;
        StringBuilder requiredTokens = new StringBuilder();
        for (int i = 0; i < imageCount; i++) {
            if (i > 0) {
                requiredTokens.append(", ");
            }
            requiredTokens.append("[[").append(token).append(":").append(i).append("]]");
        }
        return "【필수·이미지 포함】 대화(Q_CONTENT/R_CONTENT)에 인라인 이미지 " + imageCount + "개가 있습니다. "
                + "응답 JSON 전체(overview, content, conclusion 등 모든 필드 합산)에 아래 " + imageCount + "개 토큰을 반드시 모두 포함할 것. "
                + "하나라도 누락·대체·생략·텍스트 설명으로 바꾸면 잘못된 응답이다. "
                + "필수 토큰(이미지 1개당 응답 전체에서 정확히 1회만): " + requiredTokens + ". "
                + "동일 토큰을 overview·content·conclusion 등 여러 배열 필드나 여러 배열 항목에 중복 삽입하면 잘못된 응답이다. "
                + "이미지 토큰은 본문내용(content) 배열의 관련 항목 1곳에만 넣고, 개요(overview)·결론(conclusion) 등 다른 필드에는 같은 토큰을 넣지 말 것. "
                + "각 토큰은 응답 JSON 어디에도 단 1곳(단일 문자열 배열 항목 1개)에만 넣을 것. "
                + "JSON 문자열 배열 필드에 넣을 때 HTML 태그 대신 해당 토큰을 단독 문자열 배열 항목으로 넣을 것. "
                + "예: [\"그래프 설명\", \"[[" + token + ":0]]\", \"분석 내용\"] — 이 예에서 \"[[" + token + ":0]]\"는 응답 전체에 이 한 번만 등장해야 함. "
                + "data:image/...;base64,... 데이터를 응답에 직접 출력·복사·생성하지 말 것. "
                + "각 토큰은 대화에서 해당 이미지가 등장한 맥락·주제와 의미상 가장 가까운 단 하나의 배열 항목 위치에만 배치할 것. "
                + "보고서 맨 아래·별도 부록·무관한 섹션에만 몰아 넣지 말 것. "
                + "JSON 작성을 마치기 전, 필수 토큰 " + imageCount + "개가 응답 전체에 각각 정확히 1회만 포함되고 중복이 없는지 반드시 확인할 것.";
    }

    /**
     * createDoc 렌더 결과에서 createDoc 전용 토큰/플레이스홀더만 채팅에서 추출한 &lt;img&gt;로 복원한다.
     * 템플릿에 포함된 일반 &lt;img src=\"...\"&gt; 또는 reAsk용 data-img-placeholder는 건드리지 않는다.
     */
    private String restoreCreateDocImages(String text, List<String> extractedImgTags) {
        if (CommonUtil.isEmpty(text) || extractedImgTags.isEmpty()) {
            return text;
        }
        String attr = TmplHtmlRenderService.CREATE_DOC_IMG_ATTR;
        String token = TmplHtmlRenderService.CREATE_DOC_IMG_TOKEN;
        String restored = text;
        for (int i = 0; i < extractedImgTags.size(); i++) {
            String imgTag = extractedImgTags.get(i);
            restored = restored.replace("[[" + token + ":" + i + "]]", imgTag);
            restored = restored.replace("<img " + attr + "=\"" + i + "\">", imgTag);
            restored = restored.replace("&lt;img " + attr + "=&quot;" + i + "&quot;&gt;", imgTag);
            restored = restored.replace("&lt;img " + attr + "=\"" + i + "\"&gt;", imgTag);
        }
        return restored;
    }

    /**
     * HTML에서 <img> 태그를 추출하고 플레이스홀더로 교체한다.
     * base64 이미지 등이 포함된 경우 AI 전송 payload 크기를 줄이기 위해 사용한다.
     */
    private String stripImgTags(String html, List<String> extractedImgTags) {
        return stripImgTags(html, extractedImgTags, false);
    }

    private String stripImgTags(String html, List<String> extractedImgTags, boolean useImgToken) {
        if (CommonUtil.isEmpty(html)) return html;
        StringBuffer sb = new StringBuffer();
        Pattern imgPattern = Pattern.compile("<img(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = imgPattern.matcher(html);
        int idx = extractedImgTags.size();
        while (matcher.find()) {
            extractedImgTags.add(matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(imgPlaceholderReplacement(idx, useImgToken)));
            idx++;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * AI 응답 HTML에서 플레이스홀더를 원래 <img> 태그로 복원한다.
     */
    private String restoreImgTags(String html, List<String> extractedImgTags) {
        if (CommonUtil.isEmpty(html) || extractedImgTags.isEmpty()) return html;
        for (int i = 0; i < extractedImgTags.size(); i++) {
            String placeholder = "<img data-img-placeholder=\"" + i + "\">";
            html = html.replace(placeholder, extractedImgTags.get(i));
        }
        return html;
    }

    /**
     * 카드 공유 (TB_KNOW_CARD_SHARE + TB_NOTIFY userIds 수만큼 INSERT)
     * @param searchVO cardId, userIds 필수 / shareMsg 선택
     * @throws Exception
     */
    public void shareCard(LibraryVO.ShareCardPayload searchVO) throws Exception {
        if (searchVO == null || CommonUtil.isEmpty(searchVO.getCardId())
                || searchVO.getUserIds() == null || searchVO.getUserIds().isEmpty()) {
            return;
        }
        String fromUserId = SessionUtil.getUserId();
        UserVO userVO = SessionUtil.getUserVO();
        String userNm = userVO != null ? CommonUtil.nullToBlank(userVO.getUserNm()) : "";

        searchVO.setFromUserId(fromUserId);

        for (String toUserId : searchVO.getUserIds()) {
            // 1. 공유정보 INSERT
            String shareId = keyGenerate.generateTableKey("SI", "TB_KNOW_CARD_SHARE", "SHARE_ID");
            searchVO.setShareId(shareId);
            searchVO.setToUserId(toUserId);
            libraryDAO.insertCardShare(searchVO);

            // 2. 알림 INSERT
            CommonVO.NotifyVO notifyVO = new CommonVO.NotifyVO();
            notifyVO.setUserId(toUserId);
            notifyVO.setSendUserId(fromUserId);
            notifyVO.setNotifyTyCd("KS");
            notifyVO.setTitle("지식정보 공유");
            notifyVO.setContent(userNm + "님이 지식정보를 공유했습니다.");
            notifyVO.setRefId(searchVO.getShareId());
            commonService.insertNotify(notifyVO);
        }
    }

    /**
     * 리포트 채팅방 생성
     * @return
     * @throws Exception
     */
    public HashMap<String, Object> createReportChatRoom() throws Exception {
        LibraryVO vo = new LibraryVO();
        vo.setUserId(SessionUtil.getUserId());
        libraryDAO.insertReportChatRoom(vo);
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("roomId", vo.getRoomId());
        return resultMap;
    }

    private String getRequestString(Map<String, Object> requestBody, String... keys) {
        if (requestBody == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || requestBody.get(key) == null) {
                continue;
            }
            Object value = requestBody.get(key);
            return value instanceof String ? (String) value : String.valueOf(value);
        }
        return null;
    }

}
