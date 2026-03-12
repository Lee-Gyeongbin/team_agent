package kr.teamagent.library.service.impl;

import java.util.List;

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
        return libraryDAO.selectCardList(searchVO);
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
     * 카드 PIN 여부 업데이트
     * @param searchVO cardId, pinYn 필수
     * @return
     * @throws Exception
     */
    public int updateCardPin(LibraryVO searchVO) throws Exception {
        return libraryDAO.updateCardPin(searchVO);
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

}
