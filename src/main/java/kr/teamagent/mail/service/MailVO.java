package kr.teamagent.mail.service;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MailVO {

    /** 메일 제목 */
    private String subject;

    /** 발신자 전체 문자열 (예: 홍길동 <gildong@example.com>) */
    private String from;

    /** 발신자 이름만 (이름이 없으면 이메일 주소) */
    private String fromName;

    /** 수신 일시 */
    private Date receivedDate;

    /** 발송 일시 (보낸 메일함 전용) */
    private Date sentDate;

    /** 수신자 전체 문자열 (보낸 메일함 전용) */
    private String to;

    /** 수신자 이름만 (보낸 메일함 전용, 이름이 없으면 이메일 주소) */
    private String toName;

    /** 본문 (text/plain 우선, 없으면 text/html에서 태그 제거) */
    private String body;

    /** MIME Message-ID 헤더 값 */
    private String messageId;

    /**
     * 읽음 여부.
     * 필드명에 is 접두사 + Lombok boolean getter → Jackson이 "read"로 직렬화하는 문제를 방지하기 위해
     * @JsonProperty로 JSON 키를 명시한다.
     */
    @JsonProperty("isRead")
    private boolean isRead;

    // ====================================================================
    // DB 기반 메일 브리핑 고도화 - 내부 VO 클래스들
    // ====================================================================

    /** TB_MAIL_ACCOUNT - 메일 계정 */
    @Getter @Setter
    public static class MailAccountVO {
        private String  accountId;
        private String  userId;
        private String  emailAddr;
        private String  imapHost;
        private Integer imapPort;
        private String  authTypeCd;
        private byte[]  credentialEnc;
        private byte[]  credentialIv;
        private Integer keyVersion;
        private String  useYn;
        private Date    lastLoginDt;
        private Date    createDt;
        private Date    updateDt;
    }

    /** TB_MAIL_SYNC_STATE - 동기화 상태 */
    @Getter @Setter
    public static class MailSyncStateVO {
        private String accountId;
        private String folderCd;
        private Long   uidValidity;
        private Long   lastSyncUid;
        private Date   lastSyncDt;
    }

    /** TB_MAIL_MSG - 메일 메시지 (DB 버전) */
    @Getter @Setter
    public static class MailMsgVO {
        private String mailId;
        private String accountId;
        private String folderCd;
        private String imapUid;
        private String msgIdHeader;
        private String inReplyTo;
        private String subject;
        private String fromAddr;
        private String fromName;
        private String toAddrJson;
        private String ccAddrJson;
        private Date   mailDt;
        private String bodyText;
        private String deletedYn;
        private Date   createDt;
    }

    /** TB_MAIL_AI_ANALYSIS - AI 분석 결과 */
    @Getter @Setter
    public static class MailAiAnalysisVO {
        private String mailId;
        private String mailPurposeCd;
        private String actionRequiredCd;
        private String urgencyCd;
        private String importanceCd;
        private String workCategoryCd;
        private Date   dueDt;
        private String summary;
        private String replyExpectedYn;       // 회신기대여부 (LLM 판단, Y/N)
        private Date   expectedReplyDueDt;    // 예상 회신기한 (LLM 추출, 없으면 NULL)
        private String actionCompleteYn;
        private Date   actionCompleteDt;
        private Date   analyzedDt;
    }

    /** 보낸메일함 분류 리스트 조회 파라미터 */
    @Getter @Setter
    public static class SentListParamVO {
        private String accountId;
        private String tabType;    // "all", "pending", "done"
        private String startDate;  // yyyy-MM-dd
        private String endDate;    // yyyy-MM-dd
        private int    pageNum;    // 0-based OFFSET
        private int    pageSize;
    }

    /** 보낸메일함 분류 목록 아이템 */
    @Getter @Setter
    public static class SentClassifiedItemVO {
        private String  mailId;
        private String  imapUid;             // TB_MAIL_MSG.IMAP_UID — 그룹웨어 메일 URL의 uid 파라미터
        private Long    uidValidity;         // TB_MAIL_SYNC_STATE.UID_VALIDITY (SENT 메일박스) — boxnameSeq 파라미터
        private String  subject;
        private String  toAddrRaw;           // TO_ADDR_JSON[0] raw (파싱 후 toName/toAddr 채움)
        private String  toName;              // 수신자 이름
        private String  toAddr;              // 수신자 이메일
        private Date    mailDt;
        private String  replyExpectedYn;     // Y/N (AI 분석 — 사용자가 N으로 직접 변경 가능)
        private String  repliedYn;           // Y=회신수신(IN_REPLY_TO 매칭), N=미수신
        private Integer elapsedDays;         // 발송일 ~ 오늘 또는 발송일 ~ 회신수신일 (백엔드 계산)
        private Integer replyElapsedHours;   // 회신 소요 시간(시간 단위, repliedYn='Y'일 때만)
    }

    /** 답장 대기 많은 상대 */
    @Getter @Setter
    public static class TopPendingRecipientVO {
        private String toAddrRaw;   // TO_ADDR_JSON[0] raw (파싱 후 toName/toAddr 채움)
        private String toAddr;
        private String toName;
        private int    pendingCount;
    }

    /** 이번 주 회신 통계 */
    @Getter @Setter
    public static class WeeklyReplyStatsVO {
        private double avgReplyDays;         // 평균 회신 대기 (일)
        private double prevAvgReplyDays;     // 전주 평균
        private int    replyRate;            // 회신율 (%) = 완료/기대 * 100
        private int    prevReplyRate;        // 전주 회신율
        private int    pendingCount;         // 이번주 회신 대기 건수
        private int    doneCount;            // 이번주 회신 완료 건수
    }

    /** TB_MAIL_REPLY_DRAFT - 회신 초안 */
    @Getter @Setter
    public static class MailReplyDraftVO {
        private String draftId;
        private String mailId;
        private String draftContent;
        private Date   createDt;
    }

    /** KPI 조회 결과 */
    @Getter @Setter
    public static class MailKpiVO {
        private int totalCount;
        private int replyRequiredCount;
        private int urgentCount;
        private int todayDueCount;
    }

    /** 분류된 메일함 리스트 조회 파라미터 */
    @Getter @Setter
    public static class MailListParamVO {
        private String   accountId;
        private String   tabType;        // "all", "action", "reply"
        private String   searchField;    // "from", "to", "body"
        private String   searchKeyword;
        private String[] purposeCds;
        private String[] actionCds;
        private String[] urgencyCds;
        private String[] importanceCds;
        private String[] categoryCds;
        private int      pageNum;
        private int      pageSize;
        private String   startDate;   // yyyy-MM-dd
        private String   endDate;     // yyyy-MM-dd
    }

    /** 분류된 메일 목록 아이템 (MSG + AI_ANALYSIS JOIN) */
    @Getter @Setter
    public static class ClassifiedMailVO {
        private String mailId;
        private String imapUid;
        private String subject;
        private String fromAddr;
        private String fromName;
        private Date   mailDt;
        private String mailPurposeCd;
        private String mailPurposeNm;
        private String actionRequiredCd;
        private String actionRequiredNm;
        private String urgencyCd;
        private String urgencyNm;
        private String importanceCd;
        private String importanceNm;
        private String workCategoryCd;
        private String workCategoryNm;
        private Date   dueDt;
        private String summary;
        private String actionCompleteYn;
        private String folderCd;
        private String bodyText;
    }
}
