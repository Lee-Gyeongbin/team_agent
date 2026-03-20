package kr.teamagent.datamart.service.impl;

import java.util.List;

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

}
