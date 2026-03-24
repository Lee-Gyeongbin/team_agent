package kr.teamagent.repository.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.repository.service.RepositoryVO;

@Service
public class RepositoryServiceImpl extends EgovAbstractServiceImpl {
    @Autowired
    private RepositoryDAO repositoryDAO;

    @Autowired
    private KeyGenerate keyGenerate;

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

    /**
     * 문서 삭제
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> deleteDocument(RepositoryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            int result = repositoryDAO.deleteDocument(searchVO);
            if (result > 0) {
                resultMap.put("successYn", true);
                resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return resultMap;
    }

    /**
     * 문서 저장
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> saveDocument(RepositoryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            searchVO.setDocId(keyGenerate.generateTableKey("DC", "TB_DOC", "DOC_ID"));
            searchVO.setUseYn("Y");
            int result = repositoryDAO.saveDocument(searchVO);
            if (result > 0) {
                resultMap.put("successYn", true);
                resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return resultMap;
    }
}