package kr.teamagent.proposal.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.proposal.service.ProposalVO;

/**
 * PT 에이전트 DAO (namespace: proposal)
 */
@Repository
public class ProposalDAO extends EgovComAbstractDAO {

    /**
     * PT 프로젝트 목록 조회
     * @param searchVO ProjectVO
     * @return List<ProjectVO>
     */
    public List<ProposalVO.ProjectVO> selectPtProjectList(ProposalVO.ProjectVO searchVO) {
        return selectList("proposal.selectPtProjectList", searchVO);
    }

    /**
     * PT 프로젝트 등록
     * @param vo ProjectVO (ptProjectId, projectNm, rfpChatFileId, evalTableChatFileId, roomId, statusCd, createUserId)
     */
    public void insertProject(ProposalVO.ProjectVO vo) {
        insert("proposal.insertProject", vo);
    }

    /**
     * 채팅 로그에 PT 프로젝트 참조 업데이트 (TB_CHAT_LOG.SVC_TY='D', REF_ID=ptProjectId)
     * @param vo logId, ptProjectId 필요
     */
    public void updateChatLogRef(ProposalVO.ProjectVO vo) {
        update("proposal.updateChatLogRef", vo);
    }

    /**
     * PT 프로젝트 단건 조회
     * @param ptProjectId 프로젝트 ID
     * @return ProjectVO
     */
    public ProposalVO.ProjectVO selectProject(String ptProjectId) {
        return (ProposalVO.ProjectVO) selectOne("proposal.selectProject", ptProjectId);
    }

    /**
     * PT 프로젝트 상태 코드 업데이트
     * @param vo ptProjectId, statusCd
     */
    public void updateProjectStatus(ProposalVO.ProjectVO vo) {
        update("proposal.updateProjectStatus", vo);
    }

    /**
     * PT 프로젝트 작성지침 JSON 업데이트
     * @param vo ptProjectId, writingGuidelineJson
     */
    public void updateProjectWritingGuideline(ProposalVO.ProjectVO vo) {
        update("proposal.updateProjectWritingGuideline", vo);
    }

    /**
     * PT 프로젝트 제안 구분(TARGET_TYPE_CD) 업데이트
     * @param vo ptProjectId, targetTypeCd
     */
    public void updateProjectTargetType(ProposalVO.TargetTypeVO vo) {
        update("proposal.updateProjectTargetType", vo);
    }

    /**
     * PT 프로젝트 PROJECT_CONFIG_JSON 조회 (부분 update 시 merge용)
     * @param ptProjectId 프로젝트 ID
     * @return 현재 PROJECT_CONFIG_JSON 문자열 (null 가능)
     */
    public String selectProjectConfigJson(String ptProjectId) {
        return (String) selectOne("proposal.selectProjectConfigJson", ptProjectId);
    }

    /**
     * PT 프로젝트 PROJECT_CONFIG_JSON 업데이트
     * @param vo ptProjectId, projectConfigJson
     */
    public void updateProjectConfigJson(ProposalVO.ProjectVO vo) {
        update("proposal.updateProjectConfigJson", vo);
    }

    /**
     * 프로젝트의 요구사항 전체 삭제
     * @param ptProjectId 프로젝트 ID
     */
    public void deleteRequirementsByProject(String ptProjectId) {
        delete("proposal.deleteRequirementsByProject", ptProjectId);
    }

    /**
     * 요구사항 단건 등록
     * @param vo RequirementVO
     */
    public void insertRequirement(ProposalVO.RequirementVO vo) {
        insert("proposal.insertRequirement", vo);
    }

    /**
     * 프로젝트의 요구사항 목록 조회
     * @param ptProjectId 프로젝트 ID
     * @return List<RequirementVO>
     */
    public List<ProposalVO.RequirementVO> selectRequirements(String ptProjectId) {
        return selectList("proposal.selectRequirements", ptProjectId);
    }

    /**
     * 프로젝트의 평가기준 전체 삭제
     * @param ptProjectId 프로젝트 ID
     */
    public void deleteEvalCriteriaByProject(String ptProjectId) {
        delete("proposal.deleteEvalCriteriaByProject", ptProjectId);
    }

    /**
     * 평가기준 단건 등록
     * @param vo EvalCriteriaVO
     */
    public void insertEvalCriteria(ProposalVO.EvalCriteriaVO vo) {
        insert("proposal.insertEvalCriteria", vo);
    }

    /**
     * 프로젝트의 평가기준 목록 조회
     * @param ptProjectId 프로젝트 ID
     * @return List<EvalCriteriaVO>
     */
    public List<ProposalVO.EvalCriteriaVO> selectEvalCriteria(String ptProjectId) {
        return selectList("proposal.selectEvalCriteria", ptProjectId);
    }

    /**
     * PT 파일 등록 (TB_PT_FILE)
     * @param vo PtFileVO (ptFileId, ptProjectId, filePurposeCd, filePath, fileNm, fileSize, fileType, createUserId)
     */
    public void insertPtFile(ProposalVO.PtFileVO vo) {
        insert("proposal.insertPtFile", vo);
    }

    /**
     * PT 파일 단건 조회 (TB_PT_FILE)
     * @param ptFileId PT_FILE_ID
     * @return PtFileVO
     */
    public ProposalVO.PtFileVO selectPtFileById(String ptFileId) {
        return (ProposalVO.PtFileVO) selectOne("proposal.selectPtFileById", ptFileId);
    }

    /**
     * 요구사항 단건 수정 (사용자 수동 보정)
     * @param vo RequirementVO (requirementId 필수)
     */
    public void updateRequirement(ProposalVO.RequirementVO vo) {
        update("proposal.updateRequirement", vo);
    }

    /**
     * 평가기준 단건 수정 (사용자 수동 보정)
     * @param vo EvalCriteriaVO (evalCriteriaId 필수)
     */
    public void updateEvalCriteria(ProposalVO.EvalCriteriaVO vo) {
        update("proposal.updateEvalCriteria", vo);
    }

    // ── Stage 2: 문제 정의 ──────────────────────────────────────────────

    /**
     * 프로젝트의 문제 정의 전체 삭제
     * @param ptProjectId 프로젝트 ID
     */
    public void deleteProblemDefinitionsByProject(String ptProjectId) {
        delete("proposal.deleteProblemDefinitionsByProject", ptProjectId);
    }

    /**
     * 문제 정의 단건 등록
     * @param vo ProblemDefinitionVO
     */
    public void insertProblemDefinition(ProposalVO.ProblemDefinitionVO vo) {
        insert("proposal.insertProblemDefinition", vo);
    }

    /**
     * 프로젝트의 문제 정의 목록 조회
     * @param ptProjectId 프로젝트 ID
     * @return List<ProblemDefinitionVO>
     */
    public List<ProposalVO.ProblemDefinitionVO> selectProblemDefinitions(String ptProjectId) {
        return selectList("proposal.selectProblemDefinitions", ptProjectId);
    }

    // ── Stage 2: Win Theme ───────────────────────────────────────────────

    /**
     * 프로젝트의 Win Theme 전체 삭제
     * @param ptProjectId 프로젝트 ID
     */
    public void deleteWinThemesByProject(String ptProjectId) {
        delete("proposal.deleteWinThemesByProject", ptProjectId);
    }

    /**
     * Win Theme 단건 등록
     * @param vo WinThemeVO
     */
    public void insertWinTheme(ProposalVO.WinThemeVO vo) {
        insert("proposal.insertWinTheme", vo);
    }

    /**
     * 프로젝트의 Win Theme 목록 조회
     * @param ptProjectId 프로젝트 ID
     * @return List<WinThemeVO>
     */
    public List<ProposalVO.WinThemeVO> selectWinThemes(String ptProjectId) {
        return selectList("proposal.selectWinThemes", ptProjectId);
    }

    // ── Stage 2: TOC ─────────────────────────────────────────────────────

    /**
     * 프로젝트의 TOC 전체 삭제
     * @param ptProjectId 프로젝트 ID
     */
    public void deleteTocByProject(String ptProjectId) {
        delete("proposal.deleteTocByProject", ptProjectId);
    }

    /**
     * TOC 단건 등록
     * @param vo TocVO
     */
    public void insertToc(ProposalVO.TocVO vo) {
        insert("proposal.insertToc", vo);
    }

    /**
     * 프로젝트의 TOC 목록 조회 (flat list, SORT_ORD 기준)
     * @param ptProjectId 프로젝트 ID
     * @return List<TocVO>
     */
    public List<ProposalVO.TocVO> selectTocList(String ptProjectId) {
        return selectList("proposal.selectTocList", ptProjectId);
    }

    /**
     * TOC 항목 제목 수정 (단건)
     * @param vo tocId, sectionNm
     */
    public void updateTocItem(ProposalVO.TocVO vo) {
        update("proposal.updateTocItem", vo);
    }

    /**
     * TOC 항목 삭제 (tocId + 자식 소목차 연쇄 삭제)
     * @param tocId TOC_ID
     */
    public void deleteTocItem(String tocId) {
        delete("proposal.deleteTocItem", tocId);
    }

    /**
     * TOC 항목 순서(SORT_ORD) 업데이트 (단건)
     * @param vo tocId, sortOrd
     */
    public void updateTocSortOrd(ProposalVO.TocVO vo) {
        update("proposal.updateTocSortOrd", vo);
    }

    // ── Stage 2: 평가기준 SLIDE_REFLECT_POSITION 업데이트 ───────────────

    /**
     * 평가기준 SLIDE_REFLECT_POSITION 업데이트
     * @param vo EvalCriteriaVO (evalCriteriaId, slideReflectPosition)
     */
    public void updateEvalCriteriaSlideReflectPosition(ProposalVO.EvalCriteriaVO vo) {
        update("proposal.updateEvalCriteriaSlideReflectPosition", vo);
    }

    // ── Stage 2 캐시 확인 ──────────────────────────────────────────────────────

    /**
     * Stage2 실행 여부 확인 (TB_PT_PROBLEM_DEFINITION 존재 수)
     * @param ptProjectId 프로젝트 ID
     * @return 행 수 (0이면 아직 미실행)
     */
    public int countProblemDefinitions(String ptProjectId) {
        Integer cnt = (Integer) selectOne("proposal.countProblemDefinitions", ptProjectId);
        return cnt != null ? cnt : 0;
    }

    /**
     * TOC 단건 조회
     * @param tocId TOC_ID
     * @return TocVO
     */
    public ProposalVO.TocVO selectTocById(String tocId) {
        return (ProposalVO.TocVO) selectOne("proposal.selectTocById", tocId);
    }

    /**
     * 소목차(leaf, PARENT_TOC_ID IS NOT NULL) 목록 조회
     * @param ptProjectId 프로젝트 ID
     * @return 소목차 TocVO 목록 (SORT_ORD 기준)
     */
    public List<ProposalVO.TocVO> selectLeafTocList(String ptProjectId) {
        return selectList("proposal.selectLeafTocList", ptProjectId);
    }

    /**
     * 현재 소목차 다음 소목차 조회 (SORT_ORD 기준)
     * @param vo ptProjectId, tocId(현재)
     * @return 다음 TocVO (없으면 null)
     */
    public ProposalVO.TocVO selectNextLeafToc(java.util.Map<String, Object> vo) {
        return (ProposalVO.TocVO) selectOne("proposal.selectNextLeafToc", vo);
    }

    // ── Step D: TB_PT_SLIDE ────────────────────────────────────────────────────

    /**
     * 슬라이드 단건 등록
     * @param vo SlideVO
     */
    public void insertSlide(ProposalVO.SlideVO vo) {
        insert("proposal.insertSlide", vo);
    }

    /**
     * 소목차의 슬라이드 목록 조회
     * @param tocId TOC_ID
     * @return List<SlideVO>
     */
    public List<ProposalVO.SlideVO> selectSlidesByToc(String tocId) {
        return selectList("proposal.selectSlidesByToc", tocId);
    }

    /**
     * 프로젝트의 슬라이드 최대 SLIDE_NO 조회
     * @param ptProjectId 프로젝트 ID
     * @return 현재 최대 SLIDE_NO (없으면 0)
     */
    public int selectMaxSlideNo(String ptProjectId) {
        Integer max = (Integer) selectOne("proposal.selectMaxSlideNo", ptProjectId);
        return max != null ? max : 0;
    }

    /**
     * 슬라이드 단건 수정
     * @param vo SlideVO (slideId 필수, 나머지 null이면 유지)
     */
    public void updateSlide(ProposalVO.SlideVO vo) {
        update("proposal.updateSlide", vo);
    }

    /**
     * 소목차의 슬라이드 전체 삭제 (재생성 전 초기화)
     * @param tocId TOC_ID
     */
    public void deleteSlidesByToc(String tocId) {
        delete("proposal.deleteSlidesByToc", tocId);
    }
}
