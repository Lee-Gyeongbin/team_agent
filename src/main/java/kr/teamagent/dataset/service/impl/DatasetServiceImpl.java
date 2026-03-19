package kr.teamagent.dataset.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.dataset.service.DatasetVO;

@Service
public class DatasetServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private DatasetDAO datasetDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    /**
     * 데이터셋 요약 조회
     * @return
     * @throws Exception
     */
    public DatasetVO selectDatasetSummary() throws Exception {
        return datasetDAO.selectDatasetSummary();
    }

    /**
     * 데이터셋 목록 조회
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDatasetList() throws Exception {
        return datasetDAO.selectDatasetList();
    }

    /**
     * 데이터셋 단건 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public DatasetVO selectDataset(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDataset(datasetVO);
    }

    /**
     * 카테고리 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectCategoryList(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectCategoryList(datasetVO);
    }

    /**
     * 데이터셋 문서 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDatasetDocList(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDatasetDocList(datasetVO);
    }

    /**
     * 데이터셋 URL 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDatasetUrlList(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDatasetUrlList(datasetVO);
    }
    
    /**
     * 데이터셋 등록/수정
     * @param datasetVO
     * @return 저장된 DatasetVO
     * @throws Exception
     */
    public DatasetVO saveDataset(DatasetVO datasetVO) throws Exception {
        if (datasetVO.getDatasetId() == null || datasetVO.getDatasetId().trim().isEmpty()) {
            datasetVO.setDatasetId(keyGenerate.generateTableKey("DS", "TB_DS", "DATASET_ID"));
        }
        datasetDAO.saveDataset(datasetVO);
        return datasetDAO.selectDataset(datasetVO);
    }
}
