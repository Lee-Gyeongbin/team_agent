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

}
