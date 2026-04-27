package kr.teamagent.meeting.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.meeting.service.MeetingVO;

@Repository
public class MeetingDAO extends EgovComAbstractDAO {

    /** 회의 목록 조회 */
    public List<MeetingVO> selectMeetingList(MeetingVO searchVO) throws Exception {
        return selectList("ai.meeting.selectMeetingList", searchVO);
    }

    /** 회의 단건 조회 */
    public MeetingVO selectMeeting(MeetingVO searchVO) throws Exception {
        return selectOne("ai.meeting.selectMeeting", searchVO);
    }

    /** 회의록 조회 */
    public MeetingVO selectMeetingMinutes(MeetingVO searchVO) throws Exception {
        return selectOne("ai.meeting.selectMeetingMinutes", searchVO);
    }

    /** 회의 등록 */
    public int insertMeeting(MeetingVO dataVO) throws Exception {
        return insert("ai.meeting.insertMeeting", dataVO);
    }

    /** 회의 상태/종료일시 수정 */
    public int updateMeetingStatus(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.updateMeetingStatus", dataVO);
    }

    /** 회의 제목만 수정 (AI 자동 생성 등) */
    public int updateMeetingTitle(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.updateMeetingTitle", dataVO);
    }

    /** 회의 삭제 (USE_YN = 'N') */
    public int deleteMeeting(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.deleteMeeting", dataVO);
    }

    /** 회의록 등록 */
    public int insertMeetingMinutes(MeetingVO dataVO) throws Exception {
        return insert("ai.meeting.insertMeetingMinutes", dataVO);
    }

    /** 회의록 수정 */
    public int updateMeetingMinutes(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.updateMeetingMinutes", dataVO);
    }

    /** 회의별 인포그래픽 목록 조회 */
    public List<MeetingVO> selectMeetingInfographicList(MeetingVO searchVO) throws Exception {
        return selectList("ai.meeting.selectMeetingInfographicList", searchVO);
    }

    /** 회의별 인포그래픽 논리삭제 */
    public int deleteMeetingInfographicByMeetingId(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.deleteMeetingInfographicByMeetingId", dataVO);
    }

    /** 인포그래픽 등록 */
    public int insertMeetingInfographic(MeetingVO dataVO) throws Exception {
        return insert("ai.meeting.insertMeetingInfographic", dataVO);
    }

    // ── 화자 분리 ──────────────────────────────────────────────────────

    /** 화자 목록 조회 */
    public List<MeetingVO> selectSpeakerList(MeetingVO searchVO) throws Exception {
        return selectList("ai.meeting.selectSpeakerList", searchVO);
    }

    /** 화자 등록 */
    public int insertSpeaker(MeetingVO dataVO) throws Exception {
        return insert("ai.meeting.insertSpeaker", dataVO);
    }

    /** 화자 매핑(실명/사용자ID) 수정 */
    public int updateSpeakerMapping(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.updateSpeakerMapping", dataVO);
    }

    // ── 사용자 목록 ───────────────────────────────────────────────────

    /** 참석자 선택용 사용자 목록 */
    public List<MeetingVO> selectUserListForMeeting() throws Exception {
        return selectList("ai.meeting.selectUserListForMeeting");
    }
}
