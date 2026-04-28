package kr.teamagent.meeting.service;

import java.util.List;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MeetingVO extends CommonVO {

    // TB_MEETING - 회의 세션
    private Long   meetingId;
    private String meetingTitle;
    private String attendees;       // JSON 배열 [{userId, userNm}]
    private String status;
    private String statusNm;
    private String startDt;
    private String endDt;
    private String useYn;
    private String createUserId;
    private String createDt;
    private String modifyDt;
    private String isAutoTitle;

    // TB_MEETING_MINUTES - 회의록
    private Long   minutesId;
    private String fullText;
    private String summary;
    private String decisions;
    private String todoList;        // 구 actionItems → ToDoList JSON [{due_date, content, collaborators}]
    private String segments;        // 화자분리용 발화단락 JSON [{seq, text}]

    // TB_MEETING_INFOGRAPHIC - 주제별 인포그래픽
    private Long   infographicId;
    private String topicNm;
    private String topicSummary;
    private String treeText;
    private String infographicImg;
    private Integer sortOrd;
    private List<MeetingVO> infographicList;

    // TB_MEETING_SPEAKER - 화자 분리
    private Long   speakerId;
    private String speakerLabel;    // 화자1, 화자2 ...
    private String speakerNm;       // 매핑 후 실명
    private String speakerUserId;   // 매핑된 TB_USER.USER_ID
    private String utterances;      // JSON 배열 [{seq, text}]
    private List<MeetingVO> speakerList; // 화자 목록 (selectSpeakerList 결과)

    // TB_MEETING_AUDIO - 오디오 파일
    private Long    audioId;
    private String  filePath;           // NCP 오브젝트 키
    private String  originalFilename;
    private String  fileExt;
    private Long    fileSize;
    private Integer durationSec;
    private String  audioStatus;        // 001:대기 002:처리중 003:완료 004:실패
    private String  errorMsg;

    // TB_USER - 참석자 선택
    private String userNm;
}
