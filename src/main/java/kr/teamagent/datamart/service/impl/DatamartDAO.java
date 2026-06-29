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
     * 데이터마트 메타 컬럼 엑셀용 목록
     * @param searchVO datamartId
     * @return TB_DM_COL 행 목록
     * @throws Exception
     */
    public List<DatamartVO.MetaColumnExcelRowVO> selectDmColListForExcel(DatamartVO searchVO) throws Exception {
        return selectList("datamart.selectDmColListForExcel", searchVO);
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
     * 데이터마트 메타 테이블 전체 삭제 (DATAMART_ID 기준)
     * @param searchVO datamartId
     * @return 삭제 건수
     * @throws Exception
     */
    public int deleteDmTblByDatamartId(DatamartVO searchVO) throws Exception {
        return delete("datamart.deleteDmTblByDatamartId", searchVO);
    }

    /**
     * 데이터마트 메타 테이블 일괄 등록
     * @param payload datamartId, tableList
     * @return 등록 건수
     * @throws Exception
     */
    public int insertMetaTableBatch(DatamartVO.MetaTableSavePayloadVO payload) throws Exception {
        return insert("datamart.insertMetaTableBatch", payload);
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
     * 데이터마트 코드그룹 매핑 전체 삭제 (DATAMART_ID 기준)
     * @param searchVO datamartId
     * @return 삭제 건수
     * @throws Exception
     */
    public int deleteDmColCodeByDatamartId(DatamartVO searchVO) throws Exception {
        return delete("datamart.deleteDmColCodeByDatamartId", searchVO);
    }

    /**
     * 데이터마트 코드그룹 매핑 일괄 등록
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
     * @return 코드 매핑 목록 (TB_DM_COL_CODE + TB_CODE_GRP)
     * @throws Exception
     */
    public List<DatamartVO.MetaCodeColumnMappingVO> selectMetaCodeMappingRows(DatamartVO searchVO) throws Exception {
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

    /**
     * 데이터마트 동의어 전체 삭제 (DATAMART_ID 기준)
     * @param searchVO datamartId
     * @return 삭제 건수
     * @throws Exception
     */
    public int deleteDmSynonymByDatamartId(DatamartVO searchVO) throws Exception {
        return delete("datamart.deleteDmSynonymByDatamartId", searchVO);
    }

    /**
     * 데이터마트 퓨샷 전체 삭제 (DATAMART_ID 기준)
     * @param searchVO datamartId
     * @return 삭제 건수
     * @throws Exception
     */
    public int deleteDmFewshotByDatamartId(DatamartVO searchVO) throws Exception {
        return delete("datamart.deleteDmFewshotByDatamartId", searchVO);
    }

    /**
     * 데이터마트 동의어 목록 조회
     * @param searchVO datamartId
     * @return 동의어 행 목록
     * @throws Exception
     */
    public List<DatamartVO.MetaSynonymRowVO> selectMetaSynonymList(DatamartVO searchVO) throws Exception {
        return selectList("datamart.selectDatamartSynonymList", searchVO);
    }

    /**
     * 데이터마트 동의어 등록
     * @param datamartVO datamartId, synonymId, synonymWord, useYn ...
     * @return 등록 건수
     * @throws Exception
     */
    public int insertMetaSynonym(DatamartVO.MetaSynonymRowVO datamartVO) throws Exception {
        return insert("datamart.insertMetaSynonym", datamartVO);
    }

    /**
     * 데이터마트 퓨샷 목록 조회
     * @param searchVO datamartId
     * @return 퓨샷 행 목록
     * @throws Exception
     */
    public List<DatamartVO.MetaFewshotRowVO> selectMetaFewshotList(DatamartVO searchVO) throws Exception {
        return selectList("datamart.selectDatamartFewshotList", searchVO);
    }

    /**
     * 데이터마트 퓨샷 일괄 등록
     * @param payload datamartId, fewshotList
     * @return 등록 건수
     * @throws Exception
     */
    public int insertMetaFewshotBatch(DatamartVO.MetaFewshotSavePayloadVO payload) throws Exception {
        return insert("datamart.insertMetaFewshotBatch", payload);
    }

    /**
     * 데이터마트 약어사전 전체 삭제 (DATAMART_ID 기준)
     * @param searchVO datamartId
     * @return 삭제 건수
     * @throws Exception
     */
    public int deleteDmAbbrDictByDatamartId(DatamartVO searchVO) throws Exception {
        return delete("datamart.deleteDmAbbrDictByDatamartId", searchVO);
    }

    /**
     * 데이터마트 약어사전 목록 조회
     * @param searchVO datamartId
     * @return 약어사전 행 목록
     * @throws Exception
     */
    public List<DatamartVO.MetaAbbrDictRowVO> selectMetaAbbrDictList(DatamartVO searchVO) throws Exception {
        return selectList("datamart.selectDatamartAbbrDictList", searchVO);
    }

    /**
     * 데이터마트 약어사전 일괄 등록
     * @param payload datamartId, abbrDictList
     * @return 등록 건수
     * @throws Exception
     */
    public int insertMetaAbbrDictBatch(DatamartVO.MetaAbbrDictSavePayloadVO payload) throws Exception {
        return insert("datamart.insertMetaAbbrDictBatch", payload);
    }

    /**
     * 데이터마트 용어사전 저장 전 DATAMART_ID 단위 전체 삭제
     * @param searchVO datamartId
     * @return 삭제 건수
     * @throws Exception
     */
    public int deleteDmTermDictByDatamartId(DatamartVO searchVO) throws Exception {
        return delete("datamart.deleteDmTermDictByDatamartId", searchVO);
    }

    /**
     * 데이터마트 용어사전 목록 조회
     * @param searchVO datamartId
     * @return 용어사전 행 목록
     * @throws Exception
     */
    public List<DatamartVO.MetaTermDictRowVO> selectMetaTermDictList(DatamartVO searchVO) throws Exception {
        return selectList("datamart.selectDatamartTermDictList", searchVO);
    }

    /**
     * 데이터마트 용어사전 일괄 등록
     * @param payload datamartId, termList
     * @return 등록 건수
     * @throws Exception
     */
    public int insertMetaTermDictBatch(DatamartVO.MetaTermDictSavePayloadVO payload) throws Exception {
        return insert("datamart.insertMetaTermDictBatch", payload);
    }
}
