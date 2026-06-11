package kr.teamagent.mtlcare.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.mtlcare.service.MtlcareVO;

@Repository
public class MtlcareDAO extends EgovComAbstractDAO {

    /**
     * 진단 결과 저장
     */
    public int insertResult(MtlcareVO.ResultVO resultVO) throws Exception {
        return insert("mtlcare.insertResult", resultVO);
    }

    /**
     * 진단 결과 조회 (resultId 기준)
     */
    public MtlcareVO.ResultVO selectResult(MtlcareVO.ResultVO searchVO) throws Exception {
        return (MtlcareVO.ResultVO) selectOne("mtlcare.selectResult", searchVO);
    }

    /**
     * TMPL_TYPE 기준 시스템 템플릿 정보 조회 (TB_TMPL)
     */
    public MtlcareVO.TmplVO selectTmplByType(MtlcareVO.TmplVO searchVO) throws Exception {
        return (MtlcareVO.TmplVO) selectOne("mtlcare.selectTmplByType", searchVO);
    }

    /**
     * 템플릿 필드 목록 조회 (TB_TMPL_FIELD)
     */
    @SuppressWarnings("unchecked")
    public List<LibraryVO.TmplFieldItem> selectTmplFieldList(MtlcareVO.TmplVO searchVO) throws Exception {
        return (List<LibraryVO.TmplFieldItem>) list("mtlcare.selectTmplFieldList", searchVO);
    }

    /**
     * 면담 리포트 저장
     */
    public int insertReport(MtlcareVO.ReportVO reportVO) throws Exception {
        return insert("mtlcare.insertReport", reportVO);
    }

    /**
     * 면담 리포트 상세 조회 (연결된 진단 결과 포함)
     */
    public MtlcareVO.ReportVO selectReport(MtlcareVO.ReportVO searchVO) throws Exception {
        return (MtlcareVO.ReportVO) selectOne("mtlcare.selectReport", searchVO);
    }

    /**
     * 면담 리포트 확인 처리 (STATUS=CONFIRMED, CONFIRM_DT)
     */
    public int updateReportConfirm(MtlcareVO.ReportVO reportVO) throws Exception {
        return update("mtlcare.updateReportConfirm", reportVO);
    }
}
