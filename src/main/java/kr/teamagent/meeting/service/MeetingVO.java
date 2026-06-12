package kr.teamagent.meeting.service;

import java.util.List;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MeetingVO extends CommonVO {

    private String id;
    private String minutesContent;
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
    private String integrateYn;
    private String showSpeakerYn;   // 결정사항 내 발언자 표시 여부 (Y: 표시, N: 숨김)

    // TB_MEETING_MINUTES - 회의록
    private Long   minutesId;
    private String fullText;
    /** LLM 구조화 필드 등 단일 JSON (예: summary, decisions, todo_list) */
    private String flatData;
    // 수정된 회의록 내용
    private String editedContent;
    // 생성된 회의록 내용
    private String generatedContent;
    private String segments;        // 화자분리용 발화단락 JSON [{seq, text}]

    // TB_MEETING_INFOGRAPHIC - 주제별 인포그래픽
    private Long   infographicId;
    private String topicNm;
    private String topicSummary;
    private String treeText;
    private String infographicImg;
    private Integer sortOrd;
    /** 001:대기 002:생성중 003:완료 004:실패 */
    private String infographicStatus;
    private List<MeetingVO> infographicList;

    // TB_MEETING_SPEAKER - 화자 분리
    private Long   speakerId;
    private String speakerLabel;    // 화자1, 화자2 ...
    private String speakerNm;       // 매핑 후 실명
    private String speakerUserId;   // 매핑된 TB_USER.USER_ID
    private String utterances;      // JSON 배열 [{start, end, text}]
    private List<MeetingVO> speakerList; // 화자 목록 (selectSpeakerList 결과 또는 일괄 저장 요청)
    private String mergeSpeakerYn;  // 동명이인 머지 여부 (Y/N)

    // TB_MEETING_AUDIO - 오디오 파일
    private Long    audioId;
    private String  filePath;           // NCP 오브젝트 키
    private String  originalFilename;
    private String  fileExt;
    private Long    fileSize;
    private Integer durationSec;
    private String  audioStatus;        // 001:대기 002:처리중 003:완료 004:실패
    private String  errorMsg;

    // TB_MEETING - Heartbeat / 비정상종료
    private String lastHeartbeatDt;  // 마지막 heartbeat 수신일시
    private String abnormalYn;       // 비정상종료 여부 (Y/N)

    // TB_USER - 참석자 선택
    private String userNm;

    // TB_TMPL_FIELD - 회의록 템플릿 필드
    private String fieldId;
    private String tmplId;
    private String jsonKey;
    private String fieldNm;
    private String multilineYn;

    private List<Long> meetingIds;
    private Long integrationId;
    private Long parentMeetingId;
    private List<Long> childMeetingIds;
    private Long childMeetingId;

    // 목록 검색 필터 파라미터 (선택적, 기존 파라미터와 하위 호환)
    private String statusCd;          // 상태 필터 (MT000001 코드: 001/002)
    private String startDate;          // 검색 시작일 (yyyy-MM-dd)
    private String endDate;            // 검색 종료일 (yyyy-MM-dd)
    private String sortField;          // 정렬 기준 컬럼 (CREATE_DT | MEETING_TITLE)
    private String sortOrder;          // 정렬 방향 (ASC | DESC)
    private String hasMeetingMinutes;  // 회의록 존재 여부 필터 (Y: 있는 것만, 빈 문자열: 전체)
    // integrateYn 은 기존 필드 재사용
}
