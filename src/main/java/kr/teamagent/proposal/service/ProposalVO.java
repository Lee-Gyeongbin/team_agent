package kr.teamagent.proposal.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;


public class ProposalVO {

    /**
     * TB_PT_PROJECT - PT 프로젝트 기본 정보
     */
    @Data
    public static class ProjectVO {
        /** PT_PROJECT_ID (PK, VARCHAR 20) */
        private String ptProjectId;
        /** 프로젝트명 */
        private String projectNm;
        /** 발주기관명 */
        private String orgNm;
        /** 사업개요 */
        private String projectOverview;
        /** 제안 구분 코드 (G=공공, P=민간, 기본값 G) */
        private String targetTypeCd;
        /** 제출 마감일 (YYYY-MM-DD) */
        private String dueDt;
        /** 프로젝트 상태 코드 (PT000002: 001=작성중, 002=검수중, 003=완료, 004=보류) */
        private String statusCd;
        /** 프로젝트 상태명 (TB_CODE.CODE_NM) */
        private String statusNm;
        /** 제안서 작성지침 JSON (writingGuideline 객체, raw JSON string) */
        private String writingGuidelineJson;
        /** 프로젝트 설정 JSON */
        private String projectConfigJson;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
        /** 수정자 ID */
        private String modifyUserId;
        /** 수정일시 */
        private String modifyDt;
        /** 목록 검색 키워드 (프로젝트명/발주기관명) */
        private String keyword;
        /** 페이징 limit */
        private Integer limit;
        /** 페이징 offset */
        private Integer offset;
        /** Stage1 완료 여부 (Y/N) — selectPtProjectList 에서만 반환 */
        private String stage1DoneYn;
    }

    /**
     * Step A: 템플릿 설정 요청 VO
     */
    @Data
    public static class TemplateConfigVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 템플릿 방식 코드 (fix=보완, new=생성) */
        private String mode;
        /** 템플릿 파일 ID (TB_PT_FILE.PT_FILE_ID, fix 모드 필수) */
        private String templateFileId;
        /** 문서 사이즈 코드 (a4, 169, 43) */
        private String docSize;
    }

    /**
     * TB_PT_FILE - PT 제안서 첨부파일
     */
    @Data
    public static class PtFileVO {
        /** PT_FILE_ID (PK, VARCHAR 20, prefix PTF) */
        private String ptFileId;
        /** 프로젝트 ID */
        private String ptProjectId;
        /** 파일 용도 코드 (PT000011: 001=RFP원문, 002=평가표, 003=템플릿, 004=기타참고자료, 005=자사정보, 006=경쟁사정보) */
        private String filePurposeCd;
        /** NCP 오브젝트 경로 (pt-file/{ptProjectId}/{ptFileId}_{원본파일명}) */
        private String filePath;
        /** 원본 파일명 */
        @JsonProperty("fileName")
        private String fileNm;
        /** 파일 크기 (bytes) */
        private Long fileSize;
        /** MIME 타입 */
        private String fileType;
        /** MIME 타입 (요청 전용, DB 저장 시 fileType보다 우선) */
        private String mimeType;
        /** 저장 파일명 (요청 전용, TB_PT_FILE 미저장) */
        private String storeFileName;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
    }

    /**
     * TB_PT_REQUIREMENT - PT 요구사항
     */
    @Data
    public static class RequirementVO {
        /** REQUIREMENT_ID (PK, VARCHAR 20) */
        private String requirementId;
        /** 프로젝트 ID */
        private String ptProjectId;
        /** 요구사항 식별번호 (RFP 원문 그대로, 예: SFR-010) */
        private String reqNo;
        /** 요구사항 분류 코드 (보조 통계용, 애매하면 null) */
        private String reqCategoryCd;
        /** 요구사항 원문 내용 */
        private String reqContent;
        /** 필수 여부 (Y/N) */
        private String mandatoryYn;
        /** 출처 유형 코드 (PT000004: 001=사실, 002=전략적해석, 003=확인필요) */
        private String sourceTypeCd;
        /** 근거 페이지 */
        private String rfpPageRef;
        /** 평가 영향도 (관련 평가항목) */
        private String evalImpact;
        /** 제안서 대응방향 */
        private String responseDirection;
        /** 대응 증빙자료 유형 */
        private String requiredEvidence;
        /** 확인 필요 여부 (Y/N) */
        private String confirmNeededYn;
        /** 확인 필요 사유 */
        private String confirmNeededNote;
        /** 정렬 순서 */
        private Integer sortOrd;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
        /** 수정자 ID */
        private String modifyUserId;
        /** 수정일시 */
        private String modifyDt;
    }

    /**
     * TB_PT_EVAL_CRITERIA - PT 평가기준
     */
    @Data
    public static class EvalCriteriaVO {
        /** EVAL_CRITERIA_ID (PK, VARCHAR 20) */
        private String evalCriteriaId;
        /** 프로젝트 ID */
        private String ptProjectId;
        /** 평가항목명 */
        private String evalItemNm;
        /** 배점 */
        private double score;
        /** 평가 의도 (평가위원이 확인하려는 핵심 질문) */
        private String evalIntent;
        /** 고득점 조건 */
        private String highScoreCondition;
        /** 필수 증빙 */
        private String requiredEvidence;
        /** 차별화 방향 */
        private String differentiationDirection;
        /** 슬라이드 반영 위치 */
        private String slideReflectPosition;
        /** 정렬 순서 */
        private Integer sortOrd;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
        /** 수정자 ID */
        private String modifyUserId;
        /** 수정일시 */
        private String modifyDt;
    }

    /**
     * Stage2 프롬프트 전용 경량 요구사항 VO
     * - LLM에 전달하는 필드만 포함 (불필요 필드 제거로 토큰 절감)
     */
    @Data
    public static class RequirementLiteVO {
        /** 요구사항 식별번호 */
        private String reqNo;
        /** 요구사항 분류 코드 */
        private String reqCategoryCd;
        /** 요구사항 원문 내용 (400자 초과 시 문장 경계에서 절삭) */
        private String reqContent;
        /** 필수 여부 (Y/N) */
        private String mandatoryYn;
    }

    /**
     * Stage2 프롬프트 전용 경량 평가기준 VO
     * - LLM에 전달하는 필드만 포함 (불필요 필드 제거로 토큰 절감)
     */
    @Data
    public static class EvalCriteriaLiteVO {
        /** 평가항목명 */
        private String evalItemNm;
        /** 배점 */
        private double score;
    }

    /**
     * Stage 1 분석 결과 (LLM 파싱 결과 + DB 조회 결과 조합)
     */
    @Data
    public static class Stage1ResultVO {
        /** 프로젝트 ID */
        private String ptProjectId;
        /** 제안서 작성지침 JSON (raw JSON string) */
        private String writingGuidelineJson;
        /** 요구사항 목록 */
        private List<RequirementVO> requirements;
        /** 평가기준 목록 */
        private List<EvalCriteriaVO> evalCriteria;
    }

    /**
     * TB_PT_PROBLEM_DEFINITION - 발주기관 문제 정의
     */
    @Data
    public static class ProblemDefinitionVO {
        /** PROBLEM_ID (PK, VARCHAR 20) */
        private String problemId;
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 문제 유형 코드 (PT000009: 001기술적 002업무적 003조직적 004운영적 005보안품질) */
        private String problemTypeCd;
        /** 현재 문제 */
        private String currentProblem;
        /** 근본 원인 */
        private String rootCause;
        /** 방치 시 위험 */
        private String riskIfIgnored;
        /** 목표 */
        private String goal;
        /** 필요 역량 */
        private String requiredCapability;
        /** 전략 요약 */
        private String strategySummary;
        /** KPI */
        private String kpi;
        /** 출처 유형 코드 (default '002') */
        private String sourceTypeCd;
        /** 정렬 순서 */
        private Integer sortOrd;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
    }

    /**
     * TB_PT_WIN_THEME - Win Theme
     */
    @Data
    public static class WinThemeVO {
        /** WIN_THEME_ID (PK, VARCHAR 20) */
        private String winThemeId;
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 핵심 메시지 */
        private String coreMessage;
        /** 고객 문제 */
        private String customerProblem;
        /** 제안 전략 */
        private String proposalStrategy;
        /** 근거/증빙 */
        private String evidence;
        /** 기대 효과 */
        private String expectedEffect;
        /** 차별화 포인트 */
        private String differentiation;
        /** 정렬 순서 */
        private Integer sortOrd;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
    }

    /**
     * TB_PT_TOC - 제안서 목차
     */
    @Data
    public static class TocVO {
        /** TOC_ID (PK, VARCHAR 20) */
        private String tocId;
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 부모 TOC ID (self reference) */
        private String parentTocId;
        /** 섹션 번호 */
        private String sectionNo;
        /** 섹션명 */
        private String sectionNm;
        /** 연결 평가기준 ID */
        private String linkedEvalCriteriaId;
        /** 커버 요구사항 REQUIREMENT_ID 배열 (JSON 직렬화, DB 저장용) */
        private String coveredReqIdsJson;
        /** 계획 슬라이드 수 */
        private int plannedSlideCnt;
        /** 정렬 순서 */
        private Integer sortOrd;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
        // ── 파싱/계층 구조용 (DB 컬럼 아님, transient) ──
        /** LLM 응답에서 받은 평가항목명 */
        private String linkedEvalCriteriaNm;
        /** LLM이 지정한 커버 요구사항 목록 */
        private java.util.List<String> coveredReqNos;
        /** 계층 레벨 (1=대분류, 2=소분류) */
        private int level;
        /** 부모 no (level 2용) */
        private String parentNo;
        /** 섹션 번호 원문 (level-1 구조 파악용) */
        private String no;
        /** 조회 응답용 계층 구조 */
        private java.util.List<TocVO> children;
    }

    /**
     * Step C: 제안 설정 저장 요청 VO
     * PROJECT_CONFIG_JSON.settings 에 저장되는 값 (targetTypeCd 제외 — TB_PT_PROJECT 컬럼 직접 UPDATE)
     */
    @Data
    public static class ProjectSettingsVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 자사 정보 파일 ID 목록 (FILE_PURPOSE_CD='005') */
        private List<String> companyFileIds;
        /** 경쟁사 정보 파일 ID 목록 (FILE_PURPOSE_CD='006') */
        private List<String> competitorFileIds;
        /** 기타 참고자료 파일 ID 목록 (FILE_PURPOSE_CD='004') */
        private List<String> etcRefFileIds;
        /** 문체 스타일 코드 (formal=공식·격식체, plain=간결·실무체, persuasive=설득·강조체) */
        private String writingStyle;
        /** 기본색조 3순위 (hex 코드) */
        private List<String> baseColors;
        /** 강조색조 2순위 (hex 코드) */
        private List<String> accentColors;
    }

    /**
     * Step C: 제안 설정 조회 응답 VO
     */
    @Data
    public static class ProjectSettingsResponseVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 제안 구분 코드 (TB_PT_PROJECT.TARGET_TYPE_CD) */
        private String targetTypeCd;
        /** 문체 스타일 코드 */
        private String writingStyle;
        /** 자사 정보 파일 목록 */
        private List<PtFileVO> companyFiles;
        /** 경쟁사 정보 파일 목록 */
        private List<PtFileVO> competitorFiles;
        /** 기타 참고자료 파일 목록 */
        private List<PtFileVO> etcRefFiles;
        /** 기본색조 hex 코드 3개 */
        private List<String> baseColors;
        /** 강조색조 hex 코드 2개 */
        private List<String> accentColors;
    }

    /**
     * Step C: 제안 대상(공공/민간) 변경 요청 VO
     */
    @Data
    public static class TargetTypeVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 제안 구분 코드 (G=공공, P=민간) */
        private String targetTypeCd;
    }

    /**
     * Step B: TOC 순서 변경 요청 VO
     */
    @Data
    public static class TocReorderVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 항목별 순서 정보 — tocId + sortOrd 만 사용 */
        private List<TocVO> items;
    }

    /**
     * Stage 2 분석 결과 (전략 분석: 문제정의 + Win Theme + 목차)
     */
    @Data
    public static class Stage2ResultVO {
        /** 프로젝트 ID */
        private String ptProjectId;
        /** 문제 정의 목록 */
        private java.util.List<ProblemDefinitionVO> problemDefinitions;
        /** Win Theme 목록 */
        private java.util.List<WinThemeVO> winThemes;
        /** 목차 (계층 구조, children 포함) */
        private java.util.List<TocVO> toc;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step D: 본문 생성
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * TB_PT_SLIDE - 생성된 슬라이드
     *
     * DDL (미적용 시 실행 필요):
     * CREATE TABLE TB_PT_SLIDE (
     *   SLIDE_ID            VARCHAR(20)  NOT NULL PRIMARY KEY,
     *   PT_PROJECT_ID       VARCHAR(20)  NOT NULL,
     *   TOC_ID              VARCHAR(20)  NOT NULL,
     *   SLIDE_NO            INT          DEFAULT 0,
     *   COLOR_INDEX         INT          DEFAULT 0,
     *   LAYOUT_TYPE         VARCHAR(50)  NULL,
     *   SLIDE_JSON          JSON         NULL,
     *   IMAGE_GEN_HINT      TEXT         NULL,
     *   RENDERED_IMAGE_PATH VARCHAR(500) NULL,
     *   RENDER_STATUS_CD    VARCHAR(3)   DEFAULT '001',
     *   CREATE_USER_ID      VARCHAR(50)  NULL,
     *   CREATE_DT           DATETIME     NULL,
     *   MODIFY_DT           DATETIME     NULL,
     *   INDEX idx_pt_slide_project (PT_PROJECT_ID),
     *   INDEX idx_pt_slide_toc     (TOC_ID)
     * );
     * RENDER_STATUS_CD: 001=대기, 002=생성중, 003=완료, 004=실패
     */
    @Data
    public static class SlideVO {
        /** SLIDE_ID (PK, VARCHAR 20, prefix PTS) */
        private String slideId;
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 소목차 ID */
        private String tocId;
        /** 전체 덱 기준 순번 (1부터) */
        private int slideNo;
        /** COLOR_INDEX = slideNo % 3 */
        private int colorIndex;
        /** 레이아웃 타입 (cover/section_divider/infographic) */
        private String layoutType;
        /** 슬라이드 JSON 골격 (raw JSON string) */
        private String slideJson;
        /** 이미지 생성 힌트 (Stage3.5 조립 결과) */
        private String imageGenHint;
        /** 렌더링된 이미지 NCP 경로 */
        private String renderedImagePath;
        /** 렌더 상태 코드 (001=대기, 002=생성중, 003=완료, 004=실패) */
        private String renderStatusCd;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
        /** 수정일시 */
        private String modifyDt;
    }

    /**
     * D-4 소목차 확인 결과
     */
    @Data
    public static class SectionConfirmResultVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 확인한 소목차 ID */
        private String tocId;
        /** 모든 소목차 완료 여부 (true 시 Step E로 이동) */
        private boolean done;
        /** 다음 미완료 소목차 ID (done=false 일 때) */
        private String nextTocId;
        /** confirm 거부 사유 (미완료 슬라이드 존재 시) */
        private String rejectReason;
        /** confirm 거부 시 미완료 슬라이드 목록 */
        private java.util.List<SlideVO> pendingSlides;
    }

    /**
     * D-3 소목차 채팅 요청 VO
     */
    @Data
    public static class SectionChatVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 소목차 ID */
        private String tocId;
        /** 사용자 보완 요청 메시지 */
        private String message;
        /** LLM 모델 ID */
        private String modelId;
        /** 에이전트 ID */
        private String agentId;
    }

    /**
     * D-3 소목차 채팅 응답 VO
     */
    @Data
    public static class SectionChatResultVO {
        /** 재생성된 슬라이드 목록 */
        private java.util.List<SlideVO> updatedSlides;
        /** AI 응답 요약 메시지 */
        private String aiMessage;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step E: 검토 — 전역 보완 채팅 + Stage4 평가 시뮬레이션
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * TB_PT_REVIEW — Stage4 평가 시뮬레이션 결과 한 건
     *
     * DDL:
     *   CREATE TABLE TB_PT_REVIEW (
     *     REVIEW_ID          VARCHAR(20)  NOT NULL PRIMARY KEY,
     *     PT_PROJECT_ID      VARCHAR(20)  NOT NULL,
     *     SLIDE_ID           VARCHAR(20)  NULL,          -- NULL = 전체 단위 지적
     *     EVAL_CRITERIA_ID   VARCHAR(20)  NULL,
     *     SEVERITY_CD        VARCHAR(3)   NULL,          -- PT000008: 001=치명적 002=중요 003=보완권고
     *     EVAL_CRITERIA_NM   VARCHAR(255) NULL,          -- LLM 반환값 원문 (매칭 실패 시 참조용)
     *     CURRENT_ISSUE      TEXT         NULL,
     *     SCORE_IMPACT       TEXT         NULL,
     *     FIX_DIRECTION      TEXT         NULL,
     *     FIX_SUGGESTION_TXT TEXT         NULL,
     *     EXPECTED_SCORE     DECIMAL(5,2) NULL,
     *     CREATE_USER_ID     VARCHAR(50)  NULL,
     *     CREATE_DT          DATETIME     NULL,
     *     INDEX idx_pt_review_project (PT_PROJECT_ID),
     *     INDEX idx_pt_review_slide   (SLIDE_ID)
     *   );
     */
    @Data
    public static class ReviewVO {
        /** REVIEW_ID (PK, VARCHAR 20, prefix PTR-) */
        private String reviewId;
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 대상 슬라이드 ID (NULL = 전체 단위 지적) */
        private String slideId;
        /** 연결된 평가기준 ID (NULL 허용) */
        private String evalCriteriaId;
        /** 심각도 코드 (001=치명적, 002=중요, 003=보완권고) */
        private String severityCd;
        /** LLM이 반환한 evalCriteriaNm 원문 */
        private String evalCriteriaNm;
        /** 현재 문제 내용 */
        private String currentIssue;
        /** 점수 영향 */
        private String scoreImpact;
        /** 수정 방향 */
        private String fixDirection;
        /** 수정 제안 내용 */
        private String fixSuggestionTxt;
        /** 예상 점수 */
        private Double expectedScore;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
    }

    /**
     * E — 전역 보완 채팅 요청 VO
     */
    @Data
    public static class ReviewChatVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 사용자 보완 요청 메시지 */
        private String message;
        /** LLM 모델 ID */
        private String modelId;
        /** 에이전트 ID */
        private String agentId;
    }

    /**
     * E — 전역 보완 채팅 응답 VO
     */
    @Data
    public static class ReviewChatResultVO {
        /** 재생성된 슬라이드 목록 */
        private java.util.List<SlideVO> updatedSlides;
        /** AI 응답 요약 메시지 */
        private String aiMessage;
    }

    /**
     * E — Stage4 평가 시뮬레이션 실행 응답 VO
     */
    @Data
    public static class EvalSimulationResultVO {
        /** 저장된 리뷰 건수 */
        private int savedCount;
        /** 심각도순 정렬된 리뷰 목록 */
        private java.util.List<ReviewVO> reviews;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step F: 출력 — PPTX/PDF 내보내기
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * TB_PT_EXPORT — 출력 빌드 이력 한 건
     *
     * DDL:
     *   CREATE TABLE TB_PT_EXPORT (
     *     EXPORT_ID         VARCHAR(20)  NOT NULL PRIMARY KEY,
     *     PT_PROJECT_ID     VARCHAR(20)  NOT NULL,
     *     FORMAT_CD         VARCHAR(10)  NOT NULL,   -- 'pdf' | 'pptx'
     *     BUILD_STATUS_CD   VARCHAR(3)   NOT NULL DEFAULT '001',
     *                                               -- 001=대기 002=빌드중 003=캐시재사용 004=완료 005=실패
     *     FILE_PATH         VARCHAR(500) NULL,       -- NCP 파일 경로
     *     FILE_SIZE         BIGINT       NULL,
     *     DOWNLOAD_URL      VARCHAR(2000) NULL,      -- 최근 발급된 presigned 다운로드 URL
     *     ERROR_MSG         TEXT         NULL,
     *     COMPLETE_DT       DATETIME     NULL,
     *     CREATE_USER_ID    VARCHAR(50)  NULL,
     *     CREATE_DT         DATETIME     NULL,
     *     MODIFY_DT         DATETIME     NULL,
     *     INDEX idx_pt_export_project (PT_PROJECT_ID)
     *   );
     */
    @Data
    public static class ExportVO {
        /** EXPORT_ID (PK, VARCHAR 20, prefix PTE-) */
        private String exportId;
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 포맷 코드 ('pdf' | 'pptx') */
        private String formatCd;
        /** 빌드 상태 (001=대기, 002=빌드중, 003=캐시재사용, 004=완료, 005=실패) */
        private String buildStatusCd;
        /** NCP 파일 경로 (스토리지 키) */
        private String filePath;
        /** 파일 크기 (bytes) */
        private Long fileSize;
        /** 최근 발급된 presigned 다운로드 URL */
        private String downloadUrl;
        /** 오류 메시지 (실패 시) */
        private String errorMsg;
        /** 완료 일시 */
        private String completeDt;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
        /** 수정일시 */
        private String modifyDt;
    }

    /**
     * F — 출력 요청 VO
     */
    @Data
    public static class ExportRequestVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 포맷 ('pdf' | 'pptx') */
        private String format;
        /** 에이전트 ID (로그용) */
        private String agentId;
    }
}
