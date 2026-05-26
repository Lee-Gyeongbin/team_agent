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

    /** 회의록 조회 */
    public List<MeetingVO> selectMeetingMinutesByMeetingId(MeetingVO searchVO) throws Exception {
        return selectList("ai.meeting.selectMeetingMinutesByMeetingId", searchVO);
    }

    /** 회의 참석자 조회 */
    public String selectMeetingAttendees(MeetingVO searchVO) throws Exception {
        return selectOne("ai.meeting.selectMeetingAttendees", searchVO);
    }

    /** 오디오 파일 조회 */
    public MeetingVO selectMeetingAudio(MeetingVO searchVO) throws Exception {
        return selectOne("ai.meeting.selectMeetingAudio", searchVO);
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

    /** 인포그래픽 이미지/상태 수정 */
    public int updateMeetingInfographic(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.updateMeetingInfographic", dataVO);
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

    /** 화자 utterances + 이름 수정 (머지 결과 저장) */
    public int updateSpeakerUtterances(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.updateSpeakerUtterances", dataVO);
    }

    /** 화자 행 삭제 (머지 후 중복 제거) */
    public int deleteSpeaker(MeetingVO dataVO) throws Exception {
        return delete("ai.meeting.deleteSpeaker", dataVO);
    }

    /** 회의 화자 전체 삭제 */
    public int deleteSpeakersByMeetingId(MeetingVO dataVO) throws Exception {
        return delete("ai.meeting.deleteSpeakersByMeetingId", dataVO);
    }

    // ── 오디오 파일 ──────────────────────────────────────────────────

    /** 오디오 파일 등록 */
    public int insertMeetingAudio(MeetingVO dataVO) throws Exception {
        return insert("ai.meeting.insertMeetingAudio", dataVO);
    }

    /** 오디오 처리 상태 수정 */
    public int updateMeetingAudioStatus(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.updateMeetingAudioStatus", dataVO);
    }

    // ── 사용자 목록 ───────────────────────────────────────────────────

    /** 참석자 선택용 사용자 목록 */
    public List<MeetingVO> selectUserListForMeeting() throws Exception {
        return selectList("ai.meeting.selectUserListForMeeting");
    }

    // ── Heartbeat / 비정상종료 ─────────────────────────────────────────

    /** Heartbeat 수신 일시 갱신 */
    public int updateMeetingHeartbeat(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.updateMeetingHeartbeat", dataVO);
    }

    /** Cancel Beacon: STATUS=003, ABNORMAL_YN=Y */
    public int updateMeetingCancelAbnormal(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.updateMeetingCancelAbnormal", dataVO);
    }

    /** 스케줄러: Heartbeat 미수신 회의 일괄 비정상종료 처리 */
    public int updateExpiredMeetingsAbnormal() throws Exception {
        return update("ai.meeting.updateExpiredMeetingsAbnormal");
    }

    /** 복구 완료: ABNORMAL_YN=N, STATUS=002 */
    public int updateMeetingRecover(MeetingVO dataVO) throws Exception {
        return update("ai.meeting.updateMeetingRecover", dataVO);
    }

    /** 비정상종료 회의 목록 조회 (현재 로그인 사용자) */
    public List<MeetingVO> selectAbnormalMeetingList(MeetingVO searchVO) throws Exception {
        return selectList("ai.meeting.selectAbnormalMeetingList", searchVO);
    }

    /** 오디오 레코드 전체 삭제 (복구 전 정리용) */
    public int deleteAudioByMeetingId(MeetingVO dataVO) throws Exception {
        return delete("ai.meeting.deleteAudioByMeetingId", dataVO);
    }
    /** 회의록 물리삭제 */
    public int deleteMeetingMinutes(MeetingVO dataVO) throws Exception {
        return delete("ai.meeting.deleteMeetingMinutes", dataVO);
    }

    /** 인포그래픽 물리삭제 */
    public int deleteInfographicByMeetingIdPhysical(MeetingVO dataVO) throws Exception {
        return delete("ai.meeting.deleteInfographicByMeetingIdPhysical", dataVO);
    }

    /** 해당 회의가 원본인 통합 회의록 조회 */
    public List<MeetingVO> selectIntegrationByChildMeetingId(MeetingVO searchVO) throws Exception {
        return selectList("ai.meeting.selectIntegrationByChildMeetingId", searchVO);
    }

    /** 통합 연결 삭제 (원본 삭제 시) */
    public int deleteIntegrationByChildMeetingId(MeetingVO dataVO) throws Exception {
        return delete("ai.meeting.deleteIntegrationByChildMeetingId", dataVO);
    }

    /** 통합 연결 삭제 (통합 회의록 삭제 시) */
    public int deleteIntegrationByParentMeetingId(MeetingVO dataVO) throws Exception {
        return delete("ai.meeting.deleteIntegrationByParentMeetingId", dataVO);
    }

    /** 통합 회의 원본 목록 조회 */
    public List<MeetingVO> selectMeetingIntegrationList(MeetingVO searchVO) throws Exception {
        return selectList("ai.meeting.selectMeetingIntegrationList", searchVO);
    }

    /** 회의록 통합 등록 */
    public int insertMeetingIntegration(MeetingVO dataVO) throws Exception {
        return insert("ai.meeting.insertMeetingIntegration", dataVO);
    }
}
