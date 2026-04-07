package kr.teamagent.library.service.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.SessionUtil;

@Service
public class LibraryServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(LibraryServiceImpl.class);

    @Autowired
    LibraryDAO libraryDAO;

    @Autowired
    KeyGenerate keyGenerate;

    @Autowired
    ChatbotServiceImpl chatbotService;

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
        List<LibraryVO.TmplFieldItem> tmplFieldList = libraryDAO.selectTmplFieldList(searchVO);

        String prompt = "다음 내용을 보고서 형식으로 정리해 주세요. "
                + "질문: " + (cardContent.getQContent() == null ? "" : cardContent.getQContent())
                + " 답변: " + (cardContent.getRContent() == null ? "" : cardContent.getRContent())
                + "\n반드시 JSON 형식으로만 응답하세요. JSON 외 다른 텍스트, 마크다운, 코드블록은 절대 포함하지 마세요. 모든 value는 완전한 HTML 문자열이어야 합니다. 모든 태그는 반드시 열고 닫을 것(<p>...</p>, <li>...</li>). 허용 태그: h3, p, ul, ol, li, strong만 사용. 응답 전 태그가 모두 정상적으로 닫혔는지 검증 후 출력할 것."
                + "\n예시 출력 형식:\n{\"title_label\":\"제목\",\"title\":\"채용절차 프로세스 보고서\",\"overview_label\":\"개요\",\"overview\":\"<p>...</p>\", ...}\n"
                + "\n출력 형식은 반드시 위와 같이 출력하세요.";

        StringBuilder promptBuilder = new StringBuilder(prompt);
        if (tmplFieldList != null) {
            for (LibraryVO.TmplFieldItem fieldItem : tmplFieldList) {
                if (fieldItem == null || CommonUtil.isEmpty(fieldItem.getJsonKey())) {
                    continue;
                }
                String jsonKey = fieldItem.getJsonKey();
                String fieldNm = CommonUtil.isEmpty(fieldItem.getFieldNm()) ? jsonKey : fieldItem.getFieldNm();

                promptBuilder.append("key : ").append(jsonKey).append("_label (").append(jsonKey).append("의 라벨명)");
                promptBuilder.append("\nkey : ").append(jsonKey).append(" (").append(fieldNm).append(")");
            }
        }

        prompt = promptBuilder.toString();


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

        return resultMap;
    }

}
