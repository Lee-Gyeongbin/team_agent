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
        private String hasAttachYn;
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
        private String actionCompleteYn;
        private Date   actionCompleteDt;
        private String promptId;
        private Date   analyzedDt;
    }

    /** TB_MAIL_FOLLOWUP - 팔로업 */
    @Getter @Setter
    public static class MailFollowupVO {
        private String followupId;
        private String sentMailId;
        private String recipientAddr;
        private Date   expectedReplyDt;
        private String statusCd;
        private String repliedMailId;
        private Date   createDt;
        private Date   updateDt;
        // 조회용 조인 필드
        private String subject;
        private String fromName;
        private Date   mailDt;
        private String statusNm;
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
    }

    /** 분류된 메일 목록 아이템 (MSG + AI_ANALYSIS JOIN) */
    @Getter @Setter
    public static class ClassifiedMailVO {
        private String mailId;
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
