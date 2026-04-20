package kr.teamagent.datamart.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.datamart.service.DatamartVO;

@Repository
public class DatamartDAO extends EgovComAbstractDAO {

    /**
     * 데이터마트 목록 조회
     * @return
     * @throws Exception
     */
    public List<DatamartVO> selectDatamartList() throws Exception {
        return selectList("datamart.selectDatamartList");
    }

    /**
     * 데이터마트 단건 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public DatamartVO selectDatamart(DatamartVO searchVO) throws Exception {
        return selectOne("datamart.selectDatamart", searchVO);
    }

    /**
     * 데이터마트 등록/수정
     * @param datamartVO
     * @throws Exception
     */
    public void saveDatamart(DatamartVO datamartVO) throws Exception {
        insert("datamart.saveDatamart", datamartVO);
    }

    /**
     * 데이터마트 요약 정보 조회
     * @return
     * @throws Exception
     */
    public DatamartVO.SummaryVO selectDatamartSummary() throws Exception {
        return selectOne("datamart.selectDatamartSummary");
    }

    /**
     * 데이터마트 검증일시 업데이트
     * @param datamartVO
     * @return
     * @throws Exception
     */
    public int updateLastVerifyDt(DatamartVO datamartVO) throws Exception {
        return update("datamart.updateLastVerifyDt", datamartVO);
    }

    /**
     * 데이터마트 삭제
     * @param datamartVO datamartId 필수
     * @return
     * @throws Exception
     */
    public int deleteDatamart(DatamartVO datamartVO) throws Exception {
        return delete("datamart.deleteDatamart", datamartVO);
    }

    /**
     * 데이터마트 테이블 사용 여부 조회
     * @param paramMap datamartId, tblId
     * @return useYn (기본값 N)
     * @throws Exception
     */
    public String selectDmTblUseYn(Map<String, Object> paramMap) throws Exception {
        return selectOne("datamart.selectDmTblUseYn", paramMap);
    }

    /**
     * 데이터마트 메타 컬럼 목록 (DATAMART_ID + TBL_ID)
     * @param paramMap datamartId, tblId
     * @return TB_DM_COL 행 목록
     * @throws Exception
     */
    public List<DatamartVO.MetaColumnRowVO> selectDmColListByDatamartAndTbl(Map<String, Object> paramMap) throws Exception {
        return selectList("datamart.selectDmColListByDatamartAndTbl", paramMap);
    }

    /**
     * 데이터마트 메타 테이블 저장
     * @param paramMap datamartId, tblId ...
     * @return
     * @throws Exception
     */
    public int saveMetaTable(DatamartVO.MetaTableSavePayloadVO payload) throws Exception {
        return insert("datamart.saveMetaTable", payload);
    }

    /**
     * 데이터마트 메타 컬럼 전체 삭제 (DATAMART_ID 기준)
     * @param searchVO datamartId
     * @return 삭제 건수
     * @throws Exception
     */
    public int deleteDmColByDatamartId(DatamartVO searchVO) throws Exception {
        return delete("datamart.deleteDmColByDatamartId", searchVO);
    }

    /**
     * 데이터마트 메타 컬럼 일괄 등록
     * @param payload datamartId, tableList(각 columns)
     * @return 등록 건수
     * @throws Exception
     */
    public int insertDmColBatch(DatamartVO.MetaColumnSavePayloadVO payload) throws Exception {
        return insert("datamart.insertDmColBatch", payload);
    }

    /**
     * 데이터마트 메타 관계 전체 삭제 (DATAMART_ID 기준)
     * @param searchVO datamartId
     * @return 삭제 건수
     * @throws Exception
     */
    public int deleteDmRelByDatamartId(DatamartVO searchVO) throws Exception {
        return delete("datamart.deleteDmRelByDatamartId", searchVO);
    }

    /**
     * 데이터마트 메타 관계 일괄 등록
     * @param payload datamartId, relationshipList
     * @return 등록 건수
     * @throws Exception
     */
    public int saveMetaRelationship(DatamartVO.MetaRelationshipSavePayloadVO payload) throws Exception {
        return insert("datamart.saveMetaRelationship", payload);
    }

    /**
     * 데이터마트 코드값 매핑 전체 삭제 (DATAMART_ID 기준)
     * @param searchVO datamartId
     * @return 삭제 건수
     * @throws Exception
     */
    public int deleteDmColCodeByDatamartId(DatamartVO searchVO) throws Exception {
        return delete("datamart.deleteDmColCodeByDatamartId", searchVO);
    }

    /**
     * 데이터마트 코드값 매핑 일괄 등록
     * @param payload datamartId, codeColumnMappingList
     * @return 등록 건수
     * @throws Exception
     */
    public int insertDmColCodeBatch(DatamartVO.MetaCodeMappingSavePayloadVO payload) throws Exception {
        return insert("datamart.insertDmColCodeBatch", payload);
    }

    /**
     * 데이터마트 코드 매핑 목록 조회 (DATAMART_ID 기준 TB_DM_COL_CODE)
     * @param searchVO datamartId
     * @return 코드 매핑 행 목록
     * @throws Exception
     */
    public List<DatamartVO.MetaCodeValueRowVO> selectMetaCodeMappingRows(DatamartVO searchVO) throws Exception {
        return selectList("datamart.selectMetaCodeMappingRows", searchVO);
    }

    /**
     * 데이터마트 메타 관계 목록 (DATAMART_ID 기준 TB_DM_REL)
     * @param searchVO datamartId
     * @return 관계 행 목록
     * @throws Exception
     */
    public List<DatamartVO.MetaRelationshipRowVO> selectMetaRelationshipList(DatamartVO searchVO) throws Exception {
        return selectList("datamart.selectMetaRelationshipList", searchVO);
    }

}
