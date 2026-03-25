package kr.teamagent.library.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
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
     * 카테고리 삭제
     * @param searchVO categoryId 필수 (세션 userId로 본인 카테고리만 삭제)
     * @return
     * @throws Exception
     */
    public void deleteCategory(LibraryVO.CategoryItem searchVO) throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId != null) {
            searchVO.setUserId(userId);
        }
        libraryDAO.deleteCategory(searchVO);
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

}
