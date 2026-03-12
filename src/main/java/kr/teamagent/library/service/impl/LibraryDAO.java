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
     * 카테고리 등록/수정
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int insertCategory(LibraryVO searchVO) throws Exception {
        return insert("library.insertCategory", searchVO);
    }

}
