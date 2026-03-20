package kr.teamagent.dataset.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.dataset.service.DatasetVO;

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
    public void saveDataset(DatasetVO datasetVO) throws Exception {
        insert("dataset.saveDataset", datasetVO);
        insert("dataset.saveDatasetPreproc", datasetVO);
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
    public void deleteDataset(DatasetVO datasetVO) throws Exception {
        delete("dataset.deleteDataset", datasetVO);
    }
    /**
     * 데이터셋 전처리 삭제
     * @param datasetVO
     * @throws Exception
     */
    public void deleteDatasetPreproc(DatasetVO datasetVO) throws Exception {
        delete("dataset.deleteDatasetPreproc", datasetVO);
    }
    
    /**
     * 데이터셋 문서 삭제
     * @param datasetVO
     * @throws Exception
     */
    public void deleteDatasetDoc(DatasetVO datasetVO) throws Exception {
        delete("dataset.deleteDatasetDoc", datasetVO);
    }
    
    /**
     * 데이터셋 URL 삭제
     * @param datasetVO
     * @throws Exception
     */
    public void deleteDatasetUrl(DatasetVO datasetVO) throws Exception {
        delete("dataset.deleteDatasetUrl", datasetVO);
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
    public int updateUseYn(DatasetVO datasetVO) throws Exception {
        return update("dataset.updateUseYn", datasetVO);
    }
}
