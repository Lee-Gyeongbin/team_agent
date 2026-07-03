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
     * URL 단건 조회 (urlId로 urlAddr 조회)
     */
    public RepositoryVO selectCntUrlById(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectCntUrlById", searchVO);
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
     * 카테고리 순서·부모 단건 변경
     */
    public int updateCategoryOrderItem(RepositoryVO searchVO) throws Exception {
        return update("repository.updateCategoryOrderItem", searchVO);
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

    // ===== URL =====

    /**
     * URL ID 목록으로 연결된 TB_DOC_FILE FILE_PATH 조회 (재수집 전 NCP 파일 삭제용)
     */
    public List<RepositoryVO> selectDocFilePathsByUrlIds(List<String> urlIdList) throws Exception {
        return selectList("repository.selectDocFilePathsByUrlIds", urlIdList);
    }

    /**
     * 활성 URL 전체 조회 (배치 스크래핑용)
     */
    public List<RepositoryVO> selectActiveUrlList() throws Exception {
        return selectList("repository.selectActiveUrlList", new RepositoryVO());
    }

    /**
     * 지정 URL ID 목록 조회 (선택 스크래핑용)
     */
    public List<RepositoryVO> selectUrlListByIds(List<String> urlIdList) throws Exception {
        return selectList("repository.selectUrlListByIds", urlIdList);
    }

    public Integer selectUrlListCnt(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectUrlListCnt", searchVO);
    }

    public List<RepositoryVO> selectUrlList(RepositoryVO searchVO) throws Exception {
        return selectList("repository.selectUrlList", searchVO);
    }

    public int insertUrl(RepositoryVO searchVO) throws Exception {
        return insert("repository.insertUrl", searchVO);
    }

    public int updateUrl(RepositoryVO searchVO) throws Exception {
        return update("repository.updateUrl", searchVO);
    }

    public int updateUrlUseYn(RepositoryVO searchVO) throws Exception {
        return update("repository.updateUrlUseYn", searchVO);
    }

    /**
     * URL ID 목록으로 연결된 TB_DOC_FILE 삭제
     */
    public int deleteDocFilesByUrlIdList(RepositoryVO searchVO) throws Exception {
        return delete("repository.deleteDocFilesByUrlIdList", searchVO);
    }

    public int deleteUrlByIdList(RepositoryVO searchVO) throws Exception {
        return delete("repository.deleteUrlByIdList", searchVO);
    }
}
