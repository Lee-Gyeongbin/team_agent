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

    /** 마감일이 지난 작성중(001) 프로젝트를 검수중(002)으로 일괄 전환 */
    public int advanceOverdueProjectsToReview() throws Exception {
        return update("marketing.advanceOverdueProjectsToReview");
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

    /** 마케팅 콘텐츠 수정일 갱신 (시안 수정 시) */
    public int touchMarketing(MarketingVO dataVO) throws Exception {
        return update("marketing.touchMarketing", dataVO);
    }

    /** 마케팅 콘텐츠 제목 수정 */
    public int updateMarketingTitle(MarketingVO dataVO) throws Exception {
        return update("marketing.updateMarketingTitle", dataVO);
    }

    /** 마케팅 콘텐츠 생성 상태 수정 */
    public int updateMarketingStatus(MarketingVO dataVO) throws Exception {
        return update("marketing.updateMarketingStatus", dataVO);
    }

    /** 발행 예정일 지정/변경 */
    public int updateMarketingSchedule(MarketingVO dataVO) throws Exception {
        return update("marketing.updateMarketingSchedule", dataVO);
    }

    /** 발행 완료 표시/해제 */
    public int updateMarketingPublished(MarketingVO dataVO) throws Exception {
        return update("marketing.updateMarketingPublished", dataVO);
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
}
