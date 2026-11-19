package kr.teamagent.inq.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.inq.service.InqVO;

@Repository
public class InqDAO extends EgovComAbstractDAO {

    /** 문의사항 목록 조회 — inq.selectInqList */
    public List<InqVO> selectInqList(InqVO searchVO) throws Exception {
        return selectList("inq.selectInqList", searchVO);
    }

    /** 문의사항 목록 건수 조회 — inq.selectInqListCnt */
    public int selectInqListCnt(InqVO searchVO) throws Exception {
        return selectOne("inq.selectInqListCnt", searchVO);
    }

    /** 문의사항 엑셀용 전체 목록 조회 — inq.selectInqExcelList */
    public List<InqVO> selectInqExcelList(InqVO searchVO) throws Exception {
        return selectList("inq.selectInqExcelList", searchVO);
    }

    /** 문의사항 엑셀용 답변·댓글 전체 조회 — inq.selectInqExcelAnsrList */
    public List<InqVO.AnsrVO> selectInqExcelAnsrList(InqVO searchVO) throws Exception {
        return selectList("inq.selectInqExcelAnsrList", searchVO);
    }

    /** 문의하기 관리 기능 권한 보유 사용자 목록 조회 — inq.selectInqManagerUserIdList */
    public List<String> selectInqManagerUserIdList(String menuPath) throws Exception {
        return selectList("inq.selectInqManagerUserIdList", menuPath);
    }

    /** 문의사항 단건 조회 — inq.selectInq */
    public InqVO selectInq(InqVO searchVO) throws Exception {
        return selectOne("inq.selectInq", searchVO);
    }

    /** 문의사항 등록 — inq.insertInq */
    public int insertInq(InqVO dataVO) throws Exception {
        return insert("inq.insertInq", dataVO);
    }

    /** 문의사항 수정 — inq.updateInq */
    public int updateInq(InqVO dataVO) throws Exception {
        return update("inq.updateInq", dataVO);
    }

    /** 문의사항 처리상태 변경 — inq.updateInqStatus */
    public int updateInqStatus(InqVO dataVO) throws Exception {
        return update("inq.updateInqStatus", dataVO);
    }

    /** 문의사항 삭제(논리) — inq.deleteInq */
    public int deleteInq(InqVO dataVO) throws Exception {
        return update("inq.deleteInq", dataVO);
    }

    /** 문의 답변·댓글 목록 조회 — inq.selectInqAnsrList */
    public List<InqVO.AnsrVO> selectInqAnsrList(InqVO.AnsrVO searchVO) throws Exception {
        return selectList("inq.selectInqAnsrList", searchVO);
    }

    /** 문의 답변·댓글 단건 조회 — inq.selectInqAnsr */
    public InqVO.AnsrVO selectInqAnsr(InqVO.AnsrVO searchVO) throws Exception {
        return selectOne("inq.selectInqAnsr", searchVO);
    }

    /** 문의 답변·댓글 등록 — inq.insertInqAnsr */
    public int insertInqAnsr(InqVO.AnsrVO dataVO) throws Exception {
        return insert("inq.insertInqAnsr", dataVO);
    }

    /** 문의 답변·댓글 수정 — inq.updateInqAnsr */
    public int updateInqAnsr(InqVO.AnsrVO dataVO) throws Exception {
        return update("inq.updateInqAnsr", dataVO);
    }

    /** 문의 답변·댓글 삭제(논리) — inq.deleteInqAnsr */
    public int deleteInqAnsr(InqVO.AnsrVO dataVO) throws Exception {
        return update("inq.deleteInqAnsr", dataVO);
    }

    /** 문의 첨부파일 목록 조회 — inq.selectInqFileList */
    public List<InqVO.FileVO> selectInqFileList(InqVO.FileVO searchVO) throws Exception {
        return selectList("inq.selectInqFileList", searchVO);
    }

    /** 문의 첨부파일 단건 조회 — inq.selectInqFile */
    public InqVO.FileVO selectInqFile(InqVO.FileVO searchVO) throws Exception {
        return selectOne("inq.selectInqFile", searchVO);
    }

    /** 문의 첨부파일 등록 — inq.insertInqFile */
    public int insertInqFile(InqVO.FileVO dataVO) throws Exception {
        return insert("inq.insertInqFile", dataVO);
    }

    /** 문의 첨부파일 삭제 — inq.deleteInqFile */
    public int deleteInqFile(InqVO.FileVO dataVO) throws Exception {
        return delete("inq.deleteInqFile", dataVO);
    }

    /** 공통코드 목록 조회 — inq.selectInqCodeList */
    public List<InqVO.CodeVO> selectInqCodeList(String codeGrpId) throws Exception {
        return selectList("inq.selectInqCodeList", codeGrpId);
    }

    /** 공통코드명 조회 — inq.selectInqCodeNm */
    public String selectInqCodeNm(Map<String, Object> param) throws Exception {
        return selectOne("inq.selectInqCodeNm", param);
    }

    /** 사용 중인 에이전트 건수 조회 — inq.selectInqAgentCnt */
    public int selectInqAgentCnt(String agentId) throws Exception {
        return selectOne("inq.selectInqAgentCnt", agentId);
    }
}
