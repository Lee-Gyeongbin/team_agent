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

    public Integer selectDocFileLibraryListCnt(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectDocFileLibraryListCnt", searchVO);
    }

    /**
     * 파일 라이브러리 목록
     */
    public List<RepositoryVO> selectDocFileLibraryList(RepositoryVO searchVO) throws Exception {
        return selectList("repository.selectDocFileLibraryList", searchVO);
    }

    /**
     * 파일 메타 INSERT
     */
    public int insertDocFilePool(RepositoryVO searchVO) throws Exception {
        return insert("repository.insertDocFilePool", searchVO);
    }

    /**
     * 파일 메타데이터 수정
     */
    public int updateDocFilePoolMeta(RepositoryVO searchVO) throws Exception {
        return update("repository.updateDocFilePoolMeta", searchVO);
    }

    /**
     * 파일 사용 여부 변경
     */
    public int updateDocFilePoolUseYn(RepositoryVO searchVO) throws Exception {
        return update("repository.updateDocFilePoolUseYn", searchVO);
    }
    /**
     * 데이터셋 구축 상태 변경
     */
    public int updateDatasetBuildStatusCd(RepositoryVO searchVO) throws Exception {
        return update("repository.updateDatasetBuildStatusCd", searchVO);
    }
    /**
     * 파일 라이브러리 행 배치 삭제
     */
    public int deleteDocFilePoolByIdList(RepositoryVO searchVO) throws Exception {
        return delete("repository.deleteDocFilePoolByIdList", searchVO);
    }

    /**
     * 파일 단건 조회
     */
    public RepositoryVO selectDocFilePoolById(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectDocFilePoolById", searchVO);
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

    /**
     * 카테고리 삭제
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int deleteCategory(RepositoryVO searchVO) throws Exception {
        return delete("repository.deleteCategory", searchVO);
    }
}
