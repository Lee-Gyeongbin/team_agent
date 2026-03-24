package kr.teamagent.repository.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.repository.service.RepositoryVO;

@Service
public class RepositoryServiceImpl extends EgovAbstractServiceImpl {
    @Autowired
    private RepositoryDAO repositoryDAO;

    /**
     * 카테고리 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<RepositoryVO> selectCategoryList(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectCategoryList(searchVO);
    }

    /**
     * RAG 지식원천 문서 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<RepositoryVO> selectDocRepositoryList(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectDocRepositoryList(searchVO);
    }
    
    /**
     * RAG 지식원천 문서 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Integer selectDocRepositoryListCnt(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectDocRepositoryListCnt(searchVO);
    }
}
