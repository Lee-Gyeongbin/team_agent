package kr.teamagent.library.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.library.service.LibraryVO;

@Repository
public class LibraryDAO extends EgovComAbstractDAO {

    /**
     * 카테고리 목록 조회
     * @param searchVO userId 필수 (세션에서 설정됨)
     * @return
     * @throws Exception
     */
    public List<LibraryVO> selectCategoryList(LibraryVO searchVO) throws Exception {
        return selectList("library.selectCategoryList", searchVO);
    }

    /**
     * 카드 목록 조회
     * @param searchVO userId 필수 (세션에서 설정됨)
     * @return
     * @throws Exception
     */
    public List<LibraryVO> selectCardList(LibraryVO searchVO) throws Exception {
        return selectList("library.selectCardList", searchVO);
    }

    /**
     * 카드 상세 조회
     * @param searchVO cardId 필수
     * @return
     * @throws Exception
     */
    public LibraryVO selectCardDetail(LibraryVO searchVO) throws Exception {
        return selectOne("library.selectCardDetail", searchVO);
    }

    /**
     * 카드 PIN 여부 업데이트
     * @param searchVO cardId, pinYn 필수
     * @return
     * @throws Exception
     */
    public int updateCardPin(LibraryVO searchVO) throws Exception {
        return update("library.updateCardPin", searchVO);
    }

    /**
     * 카드 수정 (기존 카드만 UPDATE)
     * @param card cardId, userId 필수
     * @return
     * @throws Exception
     */
    public int updateCard(LibraryVO.CardItem card) throws Exception {
        return update("library.updateCard", card);
    }

    /**
     * 카테고리 등록/수정
     * @param searchVO categoryId, userId, categoryNm, color, sortOrd
     * @return
     * @throws Exception
     */
    public int insertCategory(LibraryVO.CategoryItem searchVO) throws Exception {
        return insert("library.insertCategory", searchVO);
    }

    /**
     * 카테고리 삭제
     * @param searchVO categoryId, userId 필수
     * @return
     * @throws Exception
     */
    public int deleteCategory(LibraryVO.CategoryItem searchVO) throws Exception {
        return delete("library.deleteCategory", searchVO);
    }

    /**
     * 카테고리 순서 일괄 수정
     * @param searchVO userId(세션), items [{ categoryId, sortOrd }] 필수
     * @return
     * @throws Exception
     */
    public int updateCategoryOrder(LibraryVO searchVO) throws Exception {
        return update("library.updateCategoryOrder", searchVO);
    }

    /**
     * 카드 순서·카테고리 일괄 수정
     * @param searchVO userId(세션), cardOrderItems [{ cardId, categoryId, sortOrd }] 필수
     * @return
     * @throws Exception
     */
    public int updateCardOrder(LibraryVO searchVO) throws Exception {
        return update("library.updateCardOrder", searchVO);
    }

    /**
     * 카드 이동 (대상 카테고리 맨 뒤에 배치)
     * @param searchVO cardId, targetCategoryId, userId(세션) 필수
     * @return
     * @throws Exception
     */
    public int moveCard(LibraryVO searchVO) throws Exception {
        return update("library.moveCard", searchVO);
    }

}
