package kr.teamagent.dataset.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.dataset.service.DatasetVO;
import kr.teamagent.dataset.service.DatasetVO.DocIdItem;
import kr.teamagent.dataset.service.DatasetVO.UrlIdItem;

@Repository
public class DatasetDAO extends EgovComAbstractDAO {
    
    /**
     * 데이터셋 요약 조회
     * @return
     * @throws Exception
     */
    public DatasetVO selectDatasetSummary() throws Exception {
        return selectOne("dataset.selectDatasetSummary");
    }

    /**
     * 데이터셋 목록 조회
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDatasetList() throws Exception {
        return selectList("dataset.selectDatasetList");
    }

    /**
     * 데이터셋 등록/수정
     * @param datasetVO
     * @throws Exception
     */
    public int saveDataset(DatasetVO datasetVO) throws Exception {
        int result = 0;
        result += insert("dataset.saveDataset", datasetVO);
        result += insert("dataset.saveDatasetPreproc", datasetVO);
        return result;
    }

    /**
     * 데이터셋 단건 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public DatasetVO selectDataset(DatasetVO searchVO) throws Exception {
        return selectOne("dataset.selectDataset", searchVO);
    }

    /**
     * 데이터셋 매핑 문서 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<DocIdItem> selectDsDocList(DatasetVO searchVO) throws Exception {
        return selectList("dataset.selectDsDocList", searchVO);
    }

    /**
     * 데이터셋 매핑 URL 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<UrlIdItem> selectDsUrlList(DatasetVO searchVO) throws Exception {
        return selectList("dataset.selectDsUrlList", searchVO);
    }
    /**
     * 카테고리 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectCategoryList(DatasetVO searchVO) throws Exception {
        return selectList("dataset.selectCategoryList", searchVO);
    }

    /**
     * 데이터셋 문서 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDatasetDocList(DatasetVO searchVO) throws Exception {
        return selectList("dataset.selectDatasetDocList", searchVO);
    }

    /**
     * 데이터셋 URL 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDatasetUrlList(DatasetVO searchVO) throws Exception {
        return selectList("dataset.selectDatasetUrlList", searchVO);
    }

    /**
     * 데이터셋 삭제
     * @param datasetVO
     * @throws Exception
     */
    public int deleteDataset(DatasetVO datasetVO) throws Exception {
        return delete("dataset.deleteDataset", datasetVO);
    }
    /**
     * 데이터셋 전처리 삭제
     * @param datasetVO
     * @throws Exception
     */
    public int deleteDatasetPreproc(DatasetVO datasetVO) throws Exception {
        return delete("dataset.deleteDatasetPreproc", datasetVO);
    }
    
    /**
     * 데이터셋 문서 삭제
     * @param datasetVO
     * @throws Exception
     */
    public int deleteDatasetDoc(DatasetVO datasetVO) throws Exception {
        return delete("dataset.deleteDatasetDoc", datasetVO);
    }
    
    /**
     * 데이터셋 URL 삭제
     * @param datasetVO
     * @throws Exception
     */
    public int deleteDatasetUrl(DatasetVO datasetVO) throws Exception {
        return delete("dataset.deleteDatasetUrl", datasetVO);
    }

    /**
     * 데이터셋 문서 저장
     * @param datasetVO
     * @throws Exception
     */
    public void saveDsDoc(DatasetVO datasetVO) throws Exception {
        insert("dataset.saveDsDoc", datasetVO);
    }
    
    /**
     * 데이터셋 URL 저장
     * @param datasetVO
     * @throws Exception
     */
    public void saveDsUrl(DatasetVO datasetVO) throws Exception {
        insert("dataset.saveDsUrl", datasetVO);
    }
    
    /**
     * 데이터셋 수정
     * @param datasetVO
     * @throws Exception
     */
    public int updateDataSetStatus(DatasetVO datasetVO) throws Exception {
        return update("dataset.updateDataSetStatus", datasetVO);
    }

    /**
     * 데이터셋 매핑 이력 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDsHistList(DatasetVO searchVO) throws Exception {
        return selectList("dataset.selectDsHistList", searchVO);
    }
    /**
     * 데이터셋 매핑 이력 목록 카운트 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int selectDsHistListCnt(DatasetVO searchVO) throws Exception {
        return selectOne("dataset.selectDsHistListCnt", searchVO);
    }

    /**
     * 데이터셋 변경 이력 삭제
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int deleteDocDatasetHistory(DatasetVO searchVO) throws Exception {
        return delete("dataset.deleteDocDatasetHistory", searchVO);
    }

    /**
     * 데이터셋 변경 이력 등록
     * @param searchVO
     * @return
     * @throws Exception
     */
    public int insertDocDatasetHistory(DatasetVO searchVO) throws Exception {
        return insert("dataset.insertDocDatasetHistory", searchVO);
    }
}
