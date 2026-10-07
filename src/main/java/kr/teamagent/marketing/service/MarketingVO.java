package kr.teamagent.marketing.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MarketingVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    /** TB_MKT_CONTENT — 응답 키는 contentId */
    private String mktContentId;
    private String marketingProjectId;
    private String agentId;
    private String title;
    private String outputMode;
    private String statusCd;
    private String statusNm;
    /** AI 생성 처리 상태 (MK000002). 워크플로 STATUS_CD(MK000001)와 별개 */
    private String aiStatusCd;
    private String aiStatusNm;
    /** 발행 예정일시 */
    private String publishScheduledDt;
    /** 발행 완료 표시 Y/N */
    private String publishedYn;
    /** 발행 방식 — NOW | SCHEDULE | HOLD */
    private String publishTypeCd;
    /** 발행 몇 시간 전에 알릴지(시간 단위) */
    private Integer alertHour;
    private String contentType;
    private String requestJson;
    private Integer variantCount;
    @JsonIgnore
    private String generationSnapshotJson;
    /** 선택 시안과 검수·승인 대상 버전 */
    private Integer selectedVariantNo;
    private Long contentVersion;
    private String createUserId;
    private String createUserNm;
    private String modifyUserId;
    private String createDt;

    /** TB_MKT_CONTENT_VARIANT — variantNo는 응답 키 id/variantId */
    private String mktContentVariantId;
    private Integer variantNo;
    private String recommendYn;
    private String contentLabel;
    private String textContent;
    private String imageFile;
    @JsonIgnore
    private String textPromptJson;
    @JsonIgnore
    private String imagePromptJson;

    /** 직전 시안 존재 여부 */
    private String hasPreviousYn;

    /** 검색 조건 */
    private String keyword;
    private Integer periodDays;

    /** TB_MKT */
    @Getter
    @Setter
    public static class ProjectVO {
        /** MKT_ID */
        private String marketingProjectId;
        private String projectNm;
        private String approverUserId;
        /** null은 유지, 빈 문자열은 관리자 해제 */
        private String managerUserId;
        private String approverUserNm;
        private String orgNm;
        private String projectOverview;
        private String dueDt;
        private String statusCd;
        private String statusNm;
        private String createUserId;
        /** 목록 조회 전용 */
        private String createUserNm;
        private Integer contentCnt;
        private String createDt;
        private String modifyUserId;
        private String modifyDt;
        private String keyword;
        private String sortField;
        private String sortOrder;
        private Integer limit;
        private Integer offset;
        /** 목록 조회·멤버 검증용 — 서비스에서 세션값으로 채운다(클라이언트 입력 무시) */
        private String userId;
        /** 공개범위(멤버) userId 목록 — 저장 시 이 목록+작성자로 멤버 차이를 반영한다 */
        private List<String> memberUserIds;
    }

    /** TB_MKT_MEMBER */
    @Getter
    @Setter
    public static class MemberVO {
        private String marketingMemberId;
        private String manageYn;
        @JsonIgnore
        private String createUserId;
        private String marketingProjectId;
        private String userId;
        private String userNm;
        private String email;
    }

    /** TB_MKT_FILE */
    @Getter
    @Setter
    public static class FileVO {
        @JsonIgnore
        private String modifyUserId;
        /** MKT_FILE_ID */
        private String marketingFileId;
        private String marketingProjectId;
        private String filePath;
        /** 원본 파일명 */
        @JsonProperty("fileName")
        private String fileNm;
        private Long fileSize;
        private String fileType;
        private String mimeType;
        /** MK000003: 001콘텐츠 002브랜드 003이미지 */
        private String filePurposeCd;
        private String createUserId;
        private String createDt;
    }

    /** TB_MKT_PLAN — 프로젝트 1 : 기획서 1. PROJECT_OVERVIEW(목적)와 별개 */
    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PlanVO {
        @JsonIgnore
        private String marketingPlanId;
        private String marketingProjectId;
        private String goal;
        private String productNm;
        private String requestTxt;
        private String targetNm;
        private String keyMessage;
        private String recommendChannels;
        private String visualTxt;
        private List<PlanSectionVO> sections;
        /** DB SECTIONS_JSON 원문 — 서비스가 sections와 상호 변환. 응답엔 안 실음 */
        @JsonIgnore
        private String sectionsJson;
        /** 생성 요청 전용 — 칸별 marketingFileId */
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private List<String> contentFileIds;
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private List<String> brandFileIds;
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private List<String> imageFileIds;
        /** 수정 요청 전용 */
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String message;
        @JsonIgnore
        private String createUserId;
        @JsonIgnore
        private String modifyUserId;
    }

    /** 기획서 본문 1절 */
    @Getter
    @Setter
    public static class PlanSectionVO {
        private String title;
        private String body;
    }

    /** TB_MKT_WORKFLOW_HIST — AI 검수 이력 (콘텐츠당 최신 3건 유지, 화면은 최신 1건만 사용) */
    @Getter
    @Setter
    public static class ReviewVO {
        @JsonIgnore
        private String outputMode;
        /** MKT_HIST_ID → 프론트 reviewId */
        private String reviewId;
        /** MKT_CONTENT_ID → 프론트 contentId */
        private String contentId;
        private int score;
        /** PASS | REVIEW | FAIL */
        private String verdict;
        private String verdictLabel;
        /** 프론트 응답용 — 서비스가 checksJson을 파싱해서 채운다 (DB 컬럼과 직접 매핑 안 됨) */
        private List<CheckItemVO> checks;
        /** 프론트 응답용 — 서비스가 issuesJson을 파싱해서 채운다 (DB 컬럼과 직접 매핑 안 됨) */
        private List<IssueVO> issues;
        /** RESULT_JSON.checks — DAO select/insert가 다루는 컬럼, 서비스가 checks와 상호 변환. 응답엔 안 실음 */
        @JsonIgnore
        private String checksJson;
        /** RESULT_JSON.issues — DAO select/insert가 다루는 컬럼, 서비스가 issues와 상호 변환. 응답엔 안 실음 */
        @JsonIgnore
        private String issuesJson;
        private Integer variantNo;
        private Long contentVersion;
        private String reviewedDt;
        private String createUserId;
    }

    /** 검수 체크 항목 1건 — fact/brand/goal/channel/legal/complete/visual 중 하나 */
    @Getter
    @Setter
    public static class CheckItemVO {
        private String key;
        private String label;
        private int score;
        /** PASS | REVIEW | FAIL */
        private String status;
    }

    /** 검수에서 발견된 이슈 1건 */
    @Getter
    @Setter
    public static class IssueVO {
        private String targetType;
        private String issueId;
        /** FAIL | REVIEW */
        private String severity;
        private String title;
        private String description;
        private String fixSuggestion;
        /** Y | N */
        private String fixAppliedYn;
    }

    /** TB_MKT_WORKFLOW_HIST — 승인/반려 이력 (콘텐츠당 최신 3건 유지, 화면은 최신 1건만 사용) */
    @Getter
    @Setter
    public static class ApprovalVO {
        @JsonIgnore
        private String reviewHistId;
        @JsonIgnore
        private String outputMode;
        @JsonIgnore
        private Integer score;
        @JsonIgnore
        private String verdict;
        @JsonIgnore
        private String resultJson;
        /** MKT_HIST_ID → 프론트 approvalId */
        private String approvalId;
        /** MKT_CONTENT_ID → 프론트 contentId */
        private String contentId;
        private String memo;
        /** Y | N */
        private String approvedYn;
        private Integer variantNo;
        private Long contentVersion;
        private String reviewerNm;
        private String approvedDt;
        private String createUserId;
    }

    /** 캠페인 캘린더 이벤트 1건 — TB_MKT_CONTENT + TB_MKT 조인 결과 */
    @Getter
    @Setter
    public static class CalendarEventVO {
        /** 콘텐츠 ID를 이벤트 ID로 사용 */
        private String eventId;
        private String marketingProjectId;
        private String projectNm;
        private String contentId;
        private String title;
        private String statusCd;
        private String eventDt;
    }
}
