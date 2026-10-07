package kr.teamagent.marketing.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.marketing.service.MarketingVO;

@Repository
public class MarketingDAO extends EgovComAbstractDAO {

    /** 마케팅 프로젝트 목록 조회 */
    public List<MarketingVO.ProjectVO> selectMarketingProjectList(MarketingVO.ProjectVO searchVO) throws Exception {
        return selectList("marketing.selectMarketingProjectList", searchVO);
    }

    /** 마케팅 프로젝트 단건 조회 */
    public MarketingVO.ProjectVO selectMarketingProject(MarketingVO.ProjectVO searchVO) throws Exception {
        return selectOne("marketing.selectMarketingProject", searchVO);
    }

    /** 마케팅 프로젝트 등록 */
    public int insertMarketingProject(MarketingVO.ProjectVO dataVO) throws Exception {
        return insert("marketing.insertMarketingProject", dataVO);
    }

    /** 마케팅 프로젝트 수정 */
    public int updateMarketingProject(MarketingVO.ProjectVO dataVO) throws Exception {
        return update("marketing.updateMarketingProject", dataVO);
    }

    /** 마케팅 프로젝트 삭제 */
    public int deleteMarketingProject(MarketingVO.ProjectVO dataVO) throws Exception {
        return delete("marketing.deleteMarketingProject", dataVO);
    }

    /** 프로젝트 소속 콘텐츠 시안 전체 삭제 */
    public int deleteMarketingContentsByProject(MarketingVO.ProjectVO dataVO) throws Exception {
        return delete("marketing.deleteMarketingContentsByProject", dataVO);
    }

    /** 프로젝트 소속 콘텐츠 전체 삭제 */
    public int deleteMarketingByProject(MarketingVO.ProjectVO dataVO) throws Exception {
        return delete("marketing.deleteMarketingByProject", dataVO);
    }

    /** 프로젝트 소속 첨부파일 전체 삭제 */
    public int deleteMarketingFilesByProject(MarketingVO.ProjectVO dataVO) throws Exception {
        return delete("marketing.deleteMarketingFilesByProject", dataVO);
    }

    /** 마케팅 기획서 단건 조회 */
    public MarketingVO.PlanVO selectMarketingPlan(MarketingVO.PlanVO searchVO) throws Exception {
        return selectOne("marketing.selectMarketingPlan", searchVO);
    }

    /** 마케팅 기획서 등록 */
    public int insertMarketingPlan(MarketingVO.PlanVO dataVO) throws Exception {
        return insert("marketing.insertMarketingPlan", dataVO);
    }

    /** 마케팅 기획서 수정 */
    public int updateMarketingPlan(MarketingVO.PlanVO dataVO) throws Exception {
        return update("marketing.updateMarketingPlan", dataVO);
    }

    /** 프로젝트 소속 기획서 삭제 */
    public int deleteMarketingPlanByProject(MarketingVO.ProjectVO dataVO) throws Exception {
        return delete("marketing.deleteMarketingPlanByProject", dataVO);
    }

    /** 프로젝트 멤버(공개범위) 목록 조회 */
    public List<MarketingVO.MemberVO> selectMarketingProjectMemberList(MarketingVO.ProjectVO searchVO) throws Exception {
        return selectList("marketing.selectMarketingProjectMemberList", searchVO);
    }

    /** 프로젝트 멤버 여부 확인 */
    public int countMarketingProjectMember(MarketingVO.ProjectVO searchVO) throws Exception {
        return (int) selectOne("marketing.countMarketingProjectMember", searchVO);
    }

    /** 프로젝트 멤버 등록 */
    public int insertMarketingProjectMember(MarketingVO.MemberVO dataVO) throws Exception {
        return insert("marketing.insertMarketingProjectMember", dataVO);
    }

    /** 프로젝트 소속 멤버 전체 삭제 */
    public int deleteMarketingProjectMembersByProject(MarketingVO.ProjectVO dataVO) throws Exception {
        return delete("marketing.deleteMarketingProjectMembersByProject", dataVO);
    }

    /** 마케팅 프로젝트 첨부파일 등록 */
    public int insertMarketingFile(MarketingVO.FileVO dataVO) throws Exception {
        return insert("marketing.insertMarketingFile", dataVO);
    }

    /** 마케팅 첨부파일명 수정 */
    public int updateMarketingFile(MarketingVO.FileVO dataVO) throws Exception {
        return update("marketing.updateMarketingFile", dataVO);
    }

    /** 마케팅 첨부파일 삭제 */
    public int deleteMarketingFile(MarketingVO.FileVO dataVO) throws Exception {
        return delete("marketing.deleteMarketingFile", dataVO);
    }

    /** 프로젝트 소속 첨부파일 목록 조회 */
    public List<MarketingVO.FileVO> selectMarketingFileList(MarketingVO.FileVO searchVO) throws Exception {
        return selectList("marketing.selectMarketingFileList", searchVO);
    }

    /** 마케팅 첨부파일 단건 조회 */
    public MarketingVO.FileVO selectMarketingFileById(MarketingVO.FileVO searchVO) throws Exception {
        return selectOne("marketing.selectMarketingFileById", searchVO);
    }

    /** 마케팅 콘텐츠 목록 조회 */
    public List<MarketingVO> selectMarketingList(MarketingVO searchVO) throws Exception {
        return selectList("marketing.selectMarketingList", searchVO);
    }

    /** 마케팅 콘텐츠 단건 조회 */
    public MarketingVO selectMarketing(MarketingVO searchVO) throws Exception {
        return selectOne("marketing.selectMarketing", searchVO);
    }

    /** 마케팅 콘텐츠 시안 목록 조회 */
    public List<MarketingVO> selectMarketingContents(MarketingVO searchVO) throws Exception {
        return selectList("marketing.selectMarketingContents", searchVO);
    }

    /** 마케팅 콘텐츠 시안 단건 조회 */
    public MarketingVO selectMarketingContent(MarketingVO searchVO) throws Exception {
        return selectOne("marketing.selectMarketingContent", searchVO);
    }

    /** 마케팅 콘텐츠 등록 */
    public int insertMarketing(MarketingVO dataVO) throws Exception {
        return insert("marketing.insertMarketing", dataVO);
    }

    /** 마케팅 콘텐츠 시안 등록 */
    public int insertMarketingContent(MarketingVO dataVO) throws Exception {
        return insert("marketing.insertMarketingContent", dataVO);
    }

    /** 마케팅 콘텐츠 시안 수정 */
    public int updateMarketingContent(MarketingVO dataVO) throws Exception {
        return update("marketing.updateMarketingContent", dataVO);
    }

    /** 생성 조건과 호출 기록 저장 */
    public int updateMarketingRequestJson(MarketingVO dataVO) throws Exception {
        return update("marketing.updateMarketingRequestJson", dataVO);
    }

    /** 마케팅 콘텐츠 제목 수정 */
    public int updateMarketingTitle(MarketingVO dataVO) throws Exception {
        return update("marketing.updateMarketingTitle", dataVO);
    }

    /** 마케팅 콘텐츠 상태 수정 */
    public int updateMarketingStatus(MarketingVO dataVO) throws Exception {
        return update("marketing.updateMarketingStatus", dataVO);
    }

    /** 마케팅 콘텐츠 생성 처리 상태 수정 */
    public int updateMarketingAiStatus(MarketingVO dataVO) throws Exception {
        return update("marketing.updateMarketingAiStatus", dataVO);
    }

    /** 발행 예정일 지정/변경 */
    public int updateMarketingSchedule(MarketingVO dataVO) throws Exception {
        return update("marketing.updateMarketingSchedule", dataVO);
    }

    /** 예약 시각 경과 콘텐츠 발행완료 일괄 전환 */
    public int advanceScheduledMarketingToPublished() throws Exception {
        return update("marketing.advanceScheduledMarketingToPublished");
    }

    /** 마케팅 콘텐츠 시안 전체 삭제 */
    public int deleteMarketingContents(MarketingVO dataVO) throws Exception {
        return delete("marketing.deleteMarketingContents", dataVO);
    }

    /** 마케팅 콘텐츠 삭제 */
    public int deleteMarketing(MarketingVO dataVO) throws Exception {
        return delete("marketing.deleteMarketing", dataVO);
    }

    /** 시안 직전 버전으로 되돌리기 */
    public int restoreMarketingContentPrevious(MarketingVO dataVO) throws Exception {
        return update("marketing.restoreMarketingContentPrevious", dataVO);
    }

    /** AI 검수 이력 등록 */
    public int insertReviewHistory(MarketingVO.ReviewVO dataVO) throws Exception {
        return insert("marketing.insertReviewHistory", dataVO);
    }

    /** AI 검수 이력 3건 초과분 정리 */
    public int pruneReviewHistory(String mktContentId) throws Exception {
        return delete("marketing.pruneReviewHistory", mktContentId);
    }

    /** AI 검수 최신 1건 조회 */
    public MarketingVO.ReviewVO selectLatestReview(String mktContentId) throws Exception {
        return selectOne("marketing.selectLatestReview", mktContentId);
    }

    /** 이슈 수정반영 표시 갱신 */
    public int updateReviewIssues(MarketingVO.ReviewVO dataVO) throws Exception {
        return update("marketing.updateReviewIssues", dataVO);
    }

    /** 승인/반려 이력 등록 */
    public int insertApprovalHistory(MarketingVO.ApprovalVO dataVO) throws Exception {
        return insert("marketing.insertApprovalHistory", dataVO);
    }

    /** 승인 이력 3건 초과분 정리 */
    public int pruneApprovalHistory(String mktContentId) throws Exception {
        return delete("marketing.pruneApprovalHistory", mktContentId);
    }

    /** 승인 최신 1건 조회 */
    public MarketingVO.ApprovalVO selectLatestApproval(String mktContentId) throws Exception {
        return selectOne("marketing.selectLatestApproval", mktContentId);
    }

    /** 캠페인 캘린더 이벤트 목록 */
    public List<MarketingVO.CalendarEventVO> selectCalendarEvents(String userId) throws Exception {
        return selectList("marketing.selectCalendarEvents", userId);
    }
    /** 콘텐츠 버전 증가와 승인 해제 */
    public int invalidateMarketingApproval(MarketingVO dataVO) throws Exception {
        return update("marketing.invalidateMarketingApproval", dataVO);
    }

    /** 콘텐츠 통합 이력 삭제 */
    public int deleteMarketingHistories(MarketingVO dataVO) throws Exception {
        return delete("marketing.deleteMarketingHistories", dataVO);
    }

    /** 프로젝트 멤버 삭제 */
    public int deleteMarketingProjectMember(MarketingVO.MemberVO dataVO) throws Exception {
        return delete("marketing.deleteMarketingProjectMember", dataVO);
    }

    /** 프로젝트 관리자 지정 */
    public int updateMarketingManager(MarketingVO.ProjectVO dataVO) throws Exception {
        return update("marketing.updateMarketingManager", dataVO);
    }

    /** 임시 파일 프로젝트 연결 */
    public int attachMarketingFile(MarketingVO.FileVO dataVO) throws Exception {
        return update("marketing.attachMarketingFile", dataVO);
    }

    /** 생성 결과 상태 저장 */
    public int completeMarketingGeneration(MarketingVO dataVO) throws Exception {
        return update("marketing.completeMarketingGeneration", dataVO);
    }

    /** 신규 멤버의 사용자 존재 확인 */
    public int countExistingUsers(List<String> userIds) throws Exception {
        return selectOne("marketing.countExistingUsers", userIds);
    }
}
