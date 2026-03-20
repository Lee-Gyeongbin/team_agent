package kr.teamagent.dataset.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.dataset.service.DatasetVO;
import kr.teamagent.dataset.service.DatasetVO.DocIdItem;
import kr.teamagent.dataset.service.DatasetVO.UrlIdItem;

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
     * 데이터셋 매핑 문서 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<DocIdItem> selectDsDocList(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDsDocList(datasetVO);
    }
    /**
     * 데이터셋 매핑 URL 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<UrlIdItem> selectDsUrlList(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDsUrlList(datasetVO);
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

        // TODO: 데이터셋 구축 시작 버튼 클릭 시 (datasetBuildStatusCd: 002) AI RAG 구축 시작 API 호출 -> 응답 받은 뒤 구축 성공일 경우 003 으로 세팅 
        if(datasetVO.getDatasetBuildStatusCd().equals("002")) {
            // AI RAG 구축 시작 API 호출 -> 응답 받은 뒤 구축 성공일 경우 003 으로 세팅
            datasetVO.setDatasetBuildStatusCd("003");
        }
        
        if (datasetVO.getDatasetId() == null || datasetVO.getDatasetId().trim().isEmpty()) {
            datasetVO.setDatasetId(keyGenerate.generateTableKey("DS", "TB_DS", "DATASET_ID"));
        }else {
            mode = "update";
        }
        // 데이터셋 관련 저장
        result += datasetDAO.saveDataset(datasetVO);

        // update 시: 기존 매핑은 항상 삭제 (체크 해제=전체 제거)
        if (mode.equals("update")) {
            datasetDAO.deleteDatasetDoc(datasetVO);
            datasetDAO.deleteDatasetUrl(datasetVO);
        }

        // incoming 리스트가 비어있지 않을 때만 다시 저장
        if (CommonUtil.isNotEmpty(datasetVO.getDocIdList())) {
            datasetDAO.saveDsDoc(datasetVO);
        }
        if (CommonUtil.isNotEmpty(datasetVO.getUrlIdList())) {
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
    public int updateDataSetStatus(DatasetVO datasetVO) throws Exception {
        int result =datasetDAO.updateDataSetStatus(datasetVO);
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

    /**
     * 데이터셋 매핑 이력 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDsHistList(DatasetVO datasetVO) throws Exception {
        if (datasetVO == null) {
            return java.util.Collections.emptyList();
        }
        
        int page = datasetVO.getPage() == null ? 1 : datasetVO.getPage();
        int pageSize = datasetVO.getPageSize() == null ? 5 : datasetVO.getPageSize();
        int offset = (page - 1) * pageSize;
        
        // MyBatis SQL에서 LIMIT offset, pageSize를 사용하기 위해 page/pageSize/offset 보정
        datasetVO.setPage(page);
        datasetVO.setPageSize(pageSize);
        datasetVO.setOffset(offset);
        return datasetDAO.selectDsHistList(datasetVO);
    }

    /**
     * 데이터셋 매핑 이력 목록 카운트 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public int selectDsHistListCnt(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDsHistListCnt(datasetVO);
    }

    /**
     * 데이터셋 변경 이력 삭제
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public int deleteDocDatasetHistory(DatasetVO datasetVO) throws Exception {
        return datasetDAO.deleteDocDatasetHistory(datasetVO);
    }

    /**
     * 데이터셋 변경 이력 등록
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public int insertDocDatasetHistory(DatasetVO datasetVO) throws Exception {
        datasetVO.setHistId(keyGenerate.generateTableKey("HI", "TB_DS_HIST", "HIST_ID"));
        datasetVO.setDelYn("N");
        datasetVO.setCreateUserId(SessionUtil.getUserId());
        datasetVO.setModifyUserId(SessionUtil.getUserId());
        return datasetDAO.insertDocDatasetHistory(datasetVO);
    }

    /**
     * 데이터셋 테스트
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public int testDataSet(DatasetVO datasetVO) throws Exception {
        int result = 0;
        // TODO 데이터셋 테스트 AI API 개발 완료 시 개발 필요
        return result;
    }
}
