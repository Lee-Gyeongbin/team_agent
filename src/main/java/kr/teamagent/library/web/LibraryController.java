package kr.teamagent.library.web;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.library.service.impl.LibraryServiceImpl;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/library")
public class LibraryController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(LibraryController.class);

    @Autowired
    private LibraryServiceImpl libraryService;

    /**
     * 카테고리 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/categoryList.do")
    @ResponseBody
    public ModelAndView categoryList(LibraryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", libraryService.selectCategoryList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 카드 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/cardList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView cardList(@RequestBody LibraryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", libraryService.selectCardList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 보관된 카드 목록 조회 (archiveYn='Y')
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/archiveCardList.do")
    @ResponseBody
    public ModelAndView archiveCardList(LibraryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", libraryService.selectArchiveCardList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 휴지통 카드 목록 조회 (useYn='N')
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/trashCardList.do")
    @ResponseBody
    public ModelAndView trashCardList(LibraryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", libraryService.selectTrashCardList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 카드 상세 조회
     * @param searchVO cardId 필수
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/cardDetail.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView cardDetail(@RequestBody LibraryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", libraryService.selectCardDetail(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 참조 매뉴얼(문서) 목록 조회 API
     * @param searchVO body: { card: { logId, ... } } — 조회 키는 card.logId
     * @return jsonView dataList: DocItem[]
     * @throws Exception
     */
    @RequestMapping(value = "/docList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView docList(@RequestBody LibraryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        LibraryVO.CardItem card = searchVO != null ? searchVO.getCard() : null;
        resultMap.put("dataList", libraryService.selectDocList(card));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 테이블 데이터 조회 API
     * @param searchVO body: { card: { logId, ... } } — 조회 키는 card.logId
     * @return jsonView data: TableDataItem
     * @throws Exception
     */
    @RequestMapping(value = "/tableData.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView tableData(@RequestBody LibraryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        LibraryVO.CardItem card = searchVO != null ? searchVO.getCard() : null;
        resultMap.put("data", libraryService.selectTableData(card));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 카드 수정 (기존 카드만 UPDATE, 신규 등록 없음)
     * @param searchVO { card: { cardId, userId, categoryId, ... } }
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/saveCard.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView saveCard(@RequestBody LibraryVO searchVO) throws Exception {
        if (searchVO != null && searchVO.getCard() != null) {
            libraryService.updateCard(searchVO.getCard());
        }
        return makeSuccessJsonData();
    }

    /**
     * 카드 PIN 여부 업데이트
     * @param searchVO cardId, pinYn 필수
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/updateCardPin.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateCardPin(@RequestBody LibraryVO searchVO) throws Exception {
        libraryService.updateCardPin(searchVO);
        return makeSuccessJsonData();
    }

    /**
     * 카테고리 등록/수정
     * @param searchVO categoryId, categoryNm, color, sortOrd 등 (LibraryVO 전체)
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/saveCategory.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView saveCategory(@RequestBody LibraryVO searchVO) throws Exception {
        if (searchVO != null) {
            libraryService.saveCategory(searchVO.getCategory());
        }
        return makeSuccessJsonData();
    }

    /**
     * 카테고리 삭제
     * @param searchVO categoryId 필수 (LibraryVO 전체)
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/deleteCategory.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView deleteCategory(@RequestBody LibraryVO searchVO) throws Exception {
        if (searchVO != null) {
            int result = libraryService.deleteCategory(searchVO.getCategory());
            if (result == -1) {
                return makeFailJsonData("카테고리 하위에 카드가 존재하여 삭제할 수 없습니다.\n(보관함 또는 휴지통)");
            }
        }
        return makeSuccessJsonData();
    }

    /**
     * 카테고리 순서 변경
     * @param searchVO { items: [{ categoryId, sortOrd }] }
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/updateCategoryOrder.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateCategoryOrder(@RequestBody LibraryVO searchVO) throws Exception {
        if (searchVO != null && searchVO.getItems() != null) {
            libraryService.updateCategoryOrder(searchVO);
        }
        return makeSuccessJsonData();
    }

    /**
     * 카드 순서·카테고리 일괄 수정 (카테고리 간 이동 포함)
     * @param searchVO { payload: [{ categoryId, cards: [{ cardId, order }] }] }
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/updateCardOrder.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateCardOrder(@RequestBody LibraryVO searchVO) throws Exception {
        if (searchVO != null && searchVO.getPayload() != null) {
            libraryService.updateCardOrder(searchVO);
        }
        return makeSuccessJsonData();
    }

    /**
     * 카드 이동 (대상 카테고리 맨 뒤에 배치)
     * @param searchVO targetCategoryId, cardId 필수
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/moveCard.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView moveCard(@RequestBody LibraryVO searchVO) throws Exception {
        if (searchVO != null) {
            libraryService.moveCard(searchVO);
        }
        return makeSuccessJsonData();
    }

    /**
     * 휴지통 카드 완전 삭제 (USE_YN='N'인 해당 사용자의 카드 일괄 DELETE)
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/deleteTrashCard.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView deleteTrashCard(@RequestBody LibraryVO searchVO) throws Exception {
        libraryService.deleteTrashCard(searchVO);
        return makeSuccessJsonData();
    }

    /**
     * 차트 라벨 목록 조회
     * @param searchVO logId (프로토타입 시연용)
     * @return jsonView statList: ChartStatItem[], detailCdList: ChartDetailCdItem[]
     * @throws Exception
     */
    @RequestMapping(value = "/chartLabel.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView chartLabel(@RequestBody LibraryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("statList", libraryService.selectChartStatList(searchVO));
        resultMap.put("detailCdList", libraryService.selectChartDetailCdList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * AI 문서 생성 API
     * @param searchVO body: { cardId, tmplId }
     * @return jsonView successYn, returnMsg, data: CreateDocItem (서비스 스텁, 이후 DAO 등 연동 예정)
     * @throws Exception
     */
    @RequestMapping(value = "/createDoc.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView createDoc(@RequestBody LibraryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>(libraryService.createDoc(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 보고서 보완 요청 API (이전 REPORT_DATA + 사용자 askQuery로 AI 재호출)
     * @param searchVO body: { roomId, askQuery, generatedReport }
     * @return jsonView successYn, returnMsg, data
     * @throws Exception
     */
    @RequestMapping(value = "/reAskReport.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView reAskReport(@RequestBody LibraryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>(libraryService.reAskReport(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 리포트 채팅방 생성
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/createReportChatRoom.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView createReportChatRoom() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>(libraryService.createReportChatRoom());
        return new ModelAndView("jsonView", resultMap);
    }

}
