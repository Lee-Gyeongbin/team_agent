package kr.teamagent.library.service.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.prompt.service.impl.PromptServiceImpl;

@Service
public class LibraryServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(LibraryServiceImpl.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    LibraryDAO libraryDAO;

    @Autowired
    KeyGenerate keyGenerate;

    @Autowired
    ChatbotServiceImpl chatbotService;

    @Autowired
    PromptServiceImpl promptService;

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
                htmlFieldInstruction = "key가 다음인 필드의 value는 완전한 HTML 문자열이어야 합니다: "
                        + String.join(", ", multilineJsonKeys)
                        + ". 모든 태그는 반드시 열고 닫을 것(<p>...</p>, <li>...</li>)."
                        + " 허용 태그: h3, p, ul, ol, li, strong만 사용."
                        + " 응답 전 해당 필드의 태그가 모두 정상적으로 닫혔는지 검증 후 출력할 것."
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
                    fieldList.append("\nkey : ").append(jsonKey).append(" (").append(fieldNm).append(")");
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
            String prompt = promptTemplate
                    .replace("{{Q_CONTENT}}", qContent)
                    .replace("{{R_CONTENT}}", rContent)
                    .replace("{{TODAY}}", today)
                    .replace("{{USER_NM}}", userNm)
                    .replace("{{HTML_FIELD_INSTRUCTION}}", htmlFieldInstruction)
                    .replace("{{FIELD_LIST}}", fieldList.toString());

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
            resultMap.put("tmplHtml", CommonUtil.nullToBlank(tmpl.getTmplHtml()));
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
            String prompt = promptTemplate
                    .replace("{{Q_CONTENT}}", qContent)
                    .replace("{{R_CONTENT}}", rContent)
                    .replace("{{TODAY}}", today)
                    .replace("{{USER_NM}}", userNm);

            // STEP3 : AI 호출
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
        String prompt = buildReAskReportPrompt(currentHtml, searchVO.getAskQuery());
        logger.info("reAskReport prompt: {}", prompt != null ? prompt : "");
        String res = chatbotService.callAiSummary(prompt, "reAskReport");
        if (CommonUtil.isEmpty(res)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "AI 보고서 보완 요청 실패");
            resultMap.put("data", null);
            return resultMap;
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

}
