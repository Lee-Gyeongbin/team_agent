package kr.teamagent.dataset.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.util.CommonUtil;
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
    public int saveDataset(DatasetVO datasetVO) throws Exception {
        int result = 0;
        String mode = "insert";
        if (datasetVO.getDatasetId() == null || datasetVO.getDatasetId().trim().isEmpty()) {
            datasetVO.setDatasetId(keyGenerate.generateTableKey("DS", "TB_DS", "DATASET_ID"));
        }else {
            mode = "update";
        }
        // 데이터셋 관련 저장
        result += datasetDAO.saveDataset(datasetVO);

        if(CommonUtil.isNotEmpty(datasetVO.getDocIdList())) {
            if (mode.equals("update")) {
                datasetDAO.deleteDatasetDoc(datasetVO);
            }
            datasetDAO.saveDsDoc(datasetVO);
        }
        if(CommonUtil.isNotEmpty(datasetVO.getUrlIdList())) {
            if (mode.equals("update")) {
                datasetDAO.deleteDatasetUrl(datasetVO);
            }
            datasetDAO.saveDsUrl(datasetVO);
        }
        
        return result;
    }

    /**
     * 데이터셋 수정
     * @param datasetVO
     * @return 수정된 DatasetVO
     * @throws Exception
     */
    public int updateUseYn(DatasetVO datasetVO) throws Exception {
        int result =datasetDAO.updateUseYn(datasetVO);
        return result;
    }

    /**
     * 데이터셋 삭제
     * @param datasetVO
     * @throws Exception
     */
    public int deleteDataset(DatasetVO datasetVO) throws Exception {
        int result = 0;
        // 데이터셋 삭제 전 데이터셋 문서 및 URL 삭제
        result += datasetDAO.deleteDatasetDoc(datasetVO);
        result += datasetDAO.deleteDatasetUrl(datasetVO);
        // 데이터셋 삭제
        result += datasetDAO.deleteDataset(datasetVO);
        // 데이터셋 전처리 삭제
        result += datasetDAO.deleteDatasetPreproc(datasetVO);
        return result;
    }
}
