package kr.teamagent.repository.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.repository.service.RepositoryVO;

@Repository
public class RepositoryDAO extends EgovComAbstractDAO {

    /**
     * 카테고리 목록 조회
     * @return
     * @throws Exception
     */
    public List<RepositoryVO> selectCategoryList(RepositoryVO searchVO) throws Exception {
        return selectList("repository.selectCategoryList", searchVO);
    }

    /**
     * RAG 지식원천 문서 목록 조회
     * @return
     * @throws Exception
     */
    public List<RepositoryVO> selectDocRepositoryList(RepositoryVO searchVO) throws Exception {
        return selectList("repository.selectDocRepositoryList", searchVO);
    }
    
    /**
     * RAG 지식원천 문서 목록 조회
     * @return
     * @throws Exception
     */
    public Integer selectDocRepositoryListCnt(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectDocRepositoryListCnt", searchVO);
    }

    /**
     * 문서 상세 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public RepositoryVO selectDetailByDocId(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectDetailByDocId", searchVO);
    }

    /**
     * 문서 존재 여부 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Integer selectDocExistCnt(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectDocExistCnt", searchVO);
    }

    /**
     * 문서 삭제
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int deleteDocument(RepositoryVO searchVO) throws Exception {
        return delete("repository.deleteDocument", searchVO);
    }
    
    /**
     * 문서 저장
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int saveDocument(RepositoryVO searchVO) throws Exception {
        return insert("repository.saveDocument", searchVO);
    }

    /**
     * 카테고리 저장
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int saveCategory(RepositoryVO searchVO) throws Exception {
        return insert("repository.saveCategory", searchVO);
    }

    /**
     * 카테고리 수정
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int renameCategory(RepositoryVO searchVO) throws Exception {
        return update("repository.renameCategory", searchVO);
    }
}
