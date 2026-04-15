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
     * 문서별 파일 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<RepositoryVO> selectDocFileListByDocId(RepositoryVO searchVO) throws Exception {
        return selectList("repository.selectDocFileListByDocId", searchVO);
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
     * 문서 파일 삭제
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int deleteDocumentFile(RepositoryVO searchVO) throws Exception {
        return delete("repository.deleteDocumentFile", searchVO);
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
     * 문서 수정
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int updateDocument(RepositoryVO searchVO) throws Exception {
        return update("repository.updateDocument", searchVO);
    }

    /**
     * 문서 파일 저장
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int saveDocumentFile(RepositoryVO searchVO) throws Exception {
        return insert("repository.saveDocumentFile", searchVO);
    }

    /**
     * 문서 파일 삭제
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int deleteDocumentFileByDocId(RepositoryVO searchVO) throws Exception {
        return delete("repository.deleteDocumentFileByDocId", searchVO);
    }

    /**
     * 문서 파일 선택 삭제
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int deleteDocumentFileByIds(RepositoryVO searchVO) throws Exception {
        return delete("repository.deleteDocumentFileByIds", searchVO);
    }

    /**
     * 문서에 연결된 파일을 풀(DOC_ID NULL)로 되돌림
     */
    public int unlinkDocFilesByDocIdAndFileIds(RepositoryVO searchVO) throws Exception {
        return update("repository.unlinkDocFilesByDocIdAndFileIds", searchVO);
    }

    /**
     * 문서 ID 목록에 해당하는 파일 연결 해제 (문서 삭제 등)
     */
    public int unlinkDocFilesByDocIdList(RepositoryVO searchVO) throws Exception {
        return update("repository.unlinkDocFilesByDocIdList", searchVO);
    }

    /**
     * 풀 파일을 문서에 연결 (DOC_ID, FILE_ORD 설정)
     */
    public int updateDocFileLinkToDocument(RepositoryVO searchVO) throws Exception {
        return update("repository.updateDocFileLinkToDocument", searchVO);
    }

    /**
     * 문서 첨부 파일 순서 갱신
     */
    public int updateDocFileOrd(RepositoryVO searchVO) throws Exception {
        return update("repository.updateDocFileOrd", searchVO);
    }

    /**
     * 링크 가능한 파일 행 존재 여부 (DOC_ID IS NULL 또는 동일 문서)
     */
    public Integer selectCountDocFileLinkable(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectCountDocFileLinkable", searchVO);
    }

    /**
     * 문서에 연결된 DOC_FILE_ID 목록 (FILE_ORD 순)
     */
    public List<String> selectDocFileIdsByDocId(RepositoryVO searchVO) throws Exception {
        return selectList("repository.selectDocFileIdsByDocId", searchVO);
    }

    /**
     * 문서를 포함한 ACTIVE(003) 데이터셋 상태를 REBUILD_REQUIRED(005)로 변경
     */
    public int updateDatasetBuildStatusToRebuildRequiredByDocId(RepositoryVO searchVO) throws Exception {
        return update("repository.updateDatasetBuildStatusToRebuildRequiredByDocId", searchVO);
    }

    /**
     * 문서가 매핑된 ACTIVE(003) 데이터셋의 TB_DS_DOC 변경 플래그 갱신
     */
    public int updateDsDocFileChangedByDocId(RepositoryVO searchVO) throws Exception {
        return update("repository.updateDsDocFileChangedByDocId", searchVO);
    }

    /**
     * 해당 DOC_FILE_ID가 문서에 연결된 매핑 건수
     */
    public Integer selectCountDocFileMapByDocFileId(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectCountDocFileMapByDocFileId", searchVO);
    }

    /**
     * 파일 라이브러리(DOC_ID IS NULL) 목록 건수
     */
    public Integer selectDocFileLibraryListCnt(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectDocFileLibraryListCnt", searchVO);
    }

    /**
     * 파일 라이브러리(DOC_ID IS NULL) 목록
     */
    public List<RepositoryVO> selectDocFileLibraryList(RepositoryVO searchVO) throws Exception {
        return selectList("repository.selectDocFileLibraryList", searchVO);
    }

    /**
     * 풀 전용 파일 메타 INSERT (DOC_ID NULL)
     */
    public int insertDocFilePool(RepositoryVO searchVO) throws Exception {
        return insert("repository.insertDocFilePool", searchVO);
    }

    /**
     * 파일 라이브러리 행 삭제 (DOC_ID IS NULL인 행만)
     */
    public int deleteDocFilePoolById(RepositoryVO searchVO) throws Exception {
        return delete("repository.deleteDocFilePoolById", searchVO);
    }

    /**
     * 파일 풀 행 조회 (DOC_ID IS NULL)
     */
    public RepositoryVO selectDocFilePoolById(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectDocFilePoolById", searchVO);
    }

    /**
     * 문서 파일 최대 순번 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Integer selectMaxFileOrdByDocId(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectMaxFileOrdByDocId", searchVO);
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
     * 문서 존재 여부 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Integer selectDocumentExistCnt(RepositoryVO searchVO) throws Exception {
        return selectOne("repository.selectDocumentExistCnt", searchVO);
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
