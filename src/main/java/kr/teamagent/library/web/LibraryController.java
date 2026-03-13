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
    @RequestMapping(value = "/cardList.do")
    @ResponseBody
    public ModelAndView cardList(LibraryVO searchVO) throws Exception {
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
            libraryService.deleteCategory(searchVO.getCategory());
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

}
