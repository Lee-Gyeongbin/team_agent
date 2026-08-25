package kr.teamagent.proposal.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;


public class ProposalVO {

    /**
     * TB_PROMPT - 프롬프트 기본 정보
     * PromptVO
     */
    @Data
    public static class PromptVO {
        /** PROMPT_ID */
        private String promptId;
        /** 프롬프트명 */
        private String promptName;
        /** 프롬프트 유형 코드 */
        private String promptTypeCd;
        /** 프롬프트 내용 */
        private String content;
        /** LLM 적용 여부 (Y/N) */
        private String applyLlmYn;
        /** 사용 여부 (Y/N) */
        private String useYn;
        /** 단계 코드
         *  RFP 구조화 추출 S1_EXTRACT
         *  문제정의 S2A_PROBLEM_TOC
         *  승리주제 S2B_WINTHEME
         *  문제정의 커버드 요구사항 S2C_COVEREDREQNOS
         *  슬라이드 생성 S3_SLIDE
         *  슬라이드 템플릿 S3_TEMPLATE
         *  슬라이드 커버 템플릿 S3_COVER_TEMPLATE
         *  슬라이드 인포그래픽 이미지 S3_IMAGE
         *  슬라이드 검토 S4_REVIEW
         */
        private String stageCd;
    }
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
        /** 사용자가 도달한 최대 단계 번호 (0템플릿 1목차·요구사항 2설정 3템플릿생성 4전략검토 5본문생성 6출력) */
        private Integer maxStepNo;
        /**
         * Stage2 진행 상태 코드 (PT000013:
         * 001=미시작, 002=문제정의 저장 완료(S2C/S2B 진행·재개 대상), 003=완료, 004=실패)
         */
        private String stage2StatusCd;
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
        /** 요구사항 분류 원문 (RFP 표의 구분/유형 컬럼 값, 없으면 null) */
        private String reqCategoryTxt;
        /** 요구사항 원문 내용 */
        private String reqContent;
        /** 요구사항 상세설명 (RFP 상세설명 항목 원문, 없으면 null) */
        private String reqDetailTxt;
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
        /** REQUIREMENT_ID (PK) — TOC 매핑 시 LLM이 이 값을 coveredReqIds로 반환 */
        private String requirementId;
        /** 요구사항 식별번호 (001 타입만 존재, 002 전략적해석은 null) */
        private String reqNo;
        /** 요구사항 분류 원문 (RFP 표의 구분/유형 컬럼 값, 없으면 null) */
        private String reqCategoryTxt;
        /** 요구사항 원문 내용 (400자 초과 시 문장 경계에서 절삭) */
        private String reqContent;
        /** 필수 여부 (Y/N) */
        private String mandatoryYn;
    }

    /**
     * Stage3 프롬프트 전용 경량 요구사항 VO
     * - 슬라이드 생성에 실제 필요한 필드만 포함 (불필요 필드 제거로 토큰 절감)
     * - requirementId/ptProjectId/sortOrd/감사 필드 제외
     */
    @Data
    public static class RequirementStage3VO {
        /** 요구사항 식별번호 (RFP 원문 그대로, 예: SFR-010) */
        private String reqNo;
        /** 요구사항 분류 원문 (RFP 표의 구분/유형 컬럼 값, 없으면 null) */
        private String reqCategoryTxt;
        /** 요구사항 원문 내용 */
        private String reqContent;
        /** 요구사항 상세설명 (RFP 원문 그대로, 없으면 null) */
        private String reqDetailTxt;
        /** 필수 여부 (Y/N) */
        private String mandatoryYn;
        /** 출처 유형 코드 (001=사실, 002=전략적해석, 003=확인필요) */
        private String sourceTypeCd;
        /** 제안서 대응방향 */
        private String responseDirection;
        /** 대응 증빙자료 유형 (evidence 기반 슬라이드 작성에 활용) */
        private String requiredEvidence;
        // rfpPageRef: 슬라이드 생성에 불필요 — 제거
        // evalImpact: linkedEc(연결 평가기준)로 대체 — 제거
    }

    /**
     * Stage3 프롬프트 전용 경량 Win Theme VO
     * - winThemeId/ptProjectId/sortOrd/감사 필드 제외
     */
    @Data
    public static class WinThemeStage3VO {
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
    }

    /**
     * Stage3 프롬프트 전용 경량 문제정의 VO
     * - problemId/ptProjectId/sortOrd/감사 필드 제외
     */
    @Data
    public static class ProblemDefinitionStage3VO {
        /** 문제 유형 코드 */
        private String problemTypeCd;
        /** 현재 문제 */
        private String currentProblem;
        /** 목표 */
        private String goal;
        /** 전략 요약 */
        private String strategySummary;
        /** KPI */
        private String kpi;
        // rootCause, riskIfIgnored, requiredCapability: 전략 분석용, 슬라이드 생성에 불필요 — 제거
    }

    /**
     * Stage2 프롬프트 전용 경량 평가기준 VO
     * - LLM에 전달하는 필드만 포함 (불필요 필드 제거로 토큰 절감)
     */
    @Data
    public static class EvalCriteriaLiteVO {
        /** 평가기준 ID */
        private String evalCriteriaId;
        /** 평가항목명 */
        private String evalItemNm;
        /** 배점 */
        private double score;
    }

    /**
     * TB_PT_RFP_ISSUE - RFP 현황/문제점/개선방향 원문 추출
     */
    @Data
    public static class RfpIssueVO {
        /** ISSUE_ID (PK, VARCHAR 20, prefix PTI) */
        private String issueId;
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 이슈 유형 코드 (001=문제점, 002=개선방향, 003=추진배경/필요성) */
        private String issueTypeCd;
        /** RFP 원문 그대로 (요약/재해석 금지) */
        private String issueContent;
        /** RFP상 소제목 (예: "협력자원 관리 미흡") — 없으면 null, LLM 생성 금지 */
        private String issueLabel;
        /** 원문 챕터 경로 (예: "Ⅱ.3.가") */
        private String sourceSection;
        /** 원문 페이지 번호 */
        private Integer sourcePage;
        /** 정렬 순서 */
        private int sortOrd;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
    }

    /**
     * Stage 1 분석 결과 (LLM 파싱 결과 + DB 조회 결과 조합)
     */
    @Data
    public static class Stage1ResultVO {
        /** 프로젝트 ID */
        private String ptProjectId;
        /** 제안서 작성지침 JSON (raw JSON string) — pageLimit, formatRules만 포함 */
        private String writingGuidelineJson;
        /** 요구사항 목록 */
        private List<RequirementVO> requirements;
        /** 평가기준 목록 */
        private List<EvalCriteriaVO> evalCriteria;
        /** 제안서 목차 목록 (toc) — Stage1에서 직접 TB_PT_TOC에 insert */
        private List<TocVO> tocList;
        /** RFP 현황/문제점/개선방향 원문 추출 목록 (없으면 빈 리스트) */
        private List<RfpIssueVO> rfpIssues = new java.util.ArrayList<>();
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
        /** 출처 유형 코드 (001=rfpIssues 근거, 002=요구사항 추론, LLM 판단 반환) */
        private String sourceTypeCd;
        /** 근거로 사용한 ISSUE_ID(PTI000001 등) 배열 JSON — 컬럼명: SOURCE_ISSUE_IDS_JSON */
        private String sourceIssueIdsJson;
        /** 근거로 사용한 REQUIREMENT_ID(PTQ000001 등) 배열 JSON — 컬럼명: SOURCE_REQ_IDS_JSON */
        private String sourceRequirementIdsJson;
        /** 정렬 순서 */
        private Integer sortOrd;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
        /** 사용자 직접 편집 시각 — Win Theme stale 판정에 사용 */
        private String modifyDt;
        /** 수정자 ID */
        private String modifyUserId;
        /** 문제정의 제목 (LLM 생성, PROBLEM_TITLE_TXT) — null이면 currentProblem 기반 폴백 */
        private String problemTitleTxt;
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
        /** 겨냥한 PROBLEM_ID(PTP000001 등) 배열 JSON — 컬럼명: SOURCE_PROBLEM_IDS_JSON */
        private String sourceProblemIdsJson;
        /** 정렬 순서 */
        private Integer sortOrd;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
        /** 사용자 직접 편집 시각 (필드 수정 시 갱신, 재생성 시엔 CREATE_DT 갱신) */
        private String modifyDt;
        /** 수정자 ID */
        private String modifyUserId;
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
        /**
         * 매핑 컬럼(COVERED_REQ_IDS_JSON / LINKED_EVAL_CRITERIA_ID / PLANNED_SLIDE_CNT) 변경 시각.
         * Step6 stale 판별: TOC.MODIFY_DT vs SLIDE.CREATE_DT
         */
        private String modifyDt;
        /** 수정자 ID */
        private String modifyUserId;
        // ── 파싱/계층 구조용 (DB 컬럼 아님, transient) ──
        /** LLM 응답에서 받은 평가항목명 */
        private String linkedEvalCriteriaNm;
        /** LLM이 지정한 커버 요구사항 REQUIREMENT_ID 목록 (transient, DB 비저장) */
        private java.util.List<String> coveredReqIds;
        /** 계층 레벨 (1=대분류, 2=소분류) */
        private int level;
        /** 부모 no (level 2용) */
        private String parentNo;
        /** 섹션 번호 원문 (level-1 구조 파악용) */
        private String no;
        /** RFP 세부 작성 지침 (항목별 작성 방법, DB 컬럼 GUIDE_CONTENT) */
        private String guideContent;
        /** 목차 원본 유형 코드 (001=RFP원본목차, 002=AI생성 세부목차) */
        private String originTypeCd;
        /** 콘텐츠 개요 텍스트 (LONGTEXT, NULL) */
        private String contentOutlineTxt;
        /** 개요 상태 코드 (001=미생성, 002=초안, 003=확정) */
        private String outlineStatusCd;
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
        /** 제안사명 (출력물 푸터 우측) */
        private String submitterNm;
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
        /** 제안사명 (출력물 푸터 우측) */
        private String submitterNm;
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
        /** 수정자 ID */
        private String modifyUserId;
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
        /** 레이아웃 타입 (LAYOUT_TYPE_CD) */
        private String layoutType;
        /** 커버 요구사항 ID 목록 JSON (REQ_IDS_JSON) */
        private String reqIdsJson;
        /** 아이브로우 텍스트 (EYEBROW_TXT) */
        private String eyebrowTxt;
        /** 슬라이드 제목 (TITLE_TXT, NOT NULL) */
        private String titleTxt;
        /** 부제목 (SUBTITLE_TXT) */
        private String subtitleTxt;
        /** 하이라이트 배너 텍스트 (HIGHLIGHT_BANNER_TXT) */
        private String highlightBannerTxt;
        /** 컴포넌트 JSON (COMPONENTS_JSON) */
        private String componentsJson;
        /** 스텝 플로우 바 JSON (STEP_FLOW_BAR_JSON) */
        private String stepFlowBarJson;
        /** 결론 리본 텍스트 (CONCLUSION_RIBBON_TXT) */
        private String conclusionRibbonTxt;
        /** 이미지 생성 힌트 (IMAGE_GEN_HINT) — components_json 기반으로 doStyleAssembly에서 조립 */
        private String imageGenHint;
        /** 정렬 순서 (SORT_ORD) */
        private Integer sortOrd;
        /** 렌더링된 이미지 NCP 경로 (PPTX 내보내기용 원본) */
        private String renderedImagePath;
        /** 템플릿 프레임 합성 이미지 NCP 경로 (Step D 미리보기용) */
        private String compositeImagePath;
        /** 렌더 상태 코드 (001=대기, 002=생성중, 003=완료, 004=실패) */
        private String renderStatusCd;
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
     * 형제 소목차 완료 슬라이드 제목 조회 결과 (중복 방지용)
     */
    @Data
    public static class SiblingSlideVO {
        /** 소목차 섹션명 */
        private String sectionNm;
        /** 슬라이드 제목 */
        private String titleTxt;
        /** 하이라이트 배너 텍스트 */
        private String highlightBannerTxt;
        /** 아이브로우 텍스트 */
        private String eyebrowTxt;
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
    // Step F: 출력 — PPTX/PDF 내보내기
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * TB_PT_EXPORT — 출력 빌드 이력 한 건
     *
     * DDL:
     *   CREATE TABLE TB_PT_EXPORT (
     *     EXPORT_ID          VARCHAR(20)   NOT NULL PRIMARY KEY,  -- PTX- prefix
     *     PT_PROJECT_ID      VARCHAR(20)   NOT NULL,
     *     BUILD_STATUS_CD    VARCHAR(10)   NOT NULL DEFAULT '001',
     *                        -- 코드 PT000010: 001=대기 002=이미지생성중 003=PPT조립중 004=완료 005=실패
     *     TOTAL_SLIDE_CNT    INT           NOT NULL DEFAULT 0,
     *     RENDERED_SLIDE_CNT INT           NOT NULL DEFAULT 0,     -- 진행률 표시용
     *     FILE_NM            VARCHAR(200)  NULL,                   -- 완성된 파일명
     *     FILE_PATH          VARCHAR(500)  NULL,                   -- NCP 오브젝트 경로
     *     FILE_SIZE          BIGINT        NULL,
     *     EXPORT_TYPE_CD     CHAR(3)       NOT NULL DEFAULT '001', -- PT_EXPORT_TYPE: 001=PPTX 002=PDF
     *     INPUT_FINGERPRINT  VARCHAR(64)   NULL,                   -- 빌드 입력 SHA-256 (캐시 재사용 판단용)
     *     ERROR_MSG          VARCHAR(1000) NULL,
     *     COMPLETE_DT        DATETIME      NULL,
     *     CREATE_DT          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
     *     CREATE_USER_ID     VARCHAR(20)   NOT NULL
     *   );
     *   -- 기존 테이블 반영:
     *   -- ALTER TABLE TB_PT_EXPORT ADD COLUMN INPUT_FINGERPRINT VARCHAR(64) NULL COMMENT '빌드 입력 SHA-256';
     */
    @Data
    public static class ExportVO {
        /** EXPORT_ID (PK, VARCHAR 20, prefix PTX-) */
        private String exportId;
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 내보내기 형식 코드 (PT_EXPORT_TYPE: 001=PPTX, 002=PDF) */
        private String exportTypeCd;
        /** 빌드 상태 코드 (PT000010: 001=대기, 002=이미지생성중, 003=PPT조립중, 004=완료, 005=실패) */
        private String buildStatusCd;
        /** 전체 슬라이드 수 */
        private Integer totalSlideCnt;
        /** 이미지 생성 완료 슬라이드 수 (진행률 표시용) */
        private Integer renderedSlideCnt;
        /** 완성된 파일명 */
        private String fileNm;
        /** NCP 오브젝트 경로 */
        private String filePath;
        /** 파일 크기 (bytes) */
        private Long fileSize;
        /** 빌드 입력 SHA-256 지문 (캐시 재사용 판단용) */
        private String inputFingerprint;
        /** 오류 메시지 (실패 시) */
        private String errorMsg;
        /** 완료 일시 */
        private String completeDt;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;

        // ── DB에 저장되지 않는 런타임 필드 ──────────────────────────────────
        /** presigned 다운로드 URL — DB 미저장, 완료 시 동적 발급 */
        private String downloadUrl;
        /** 캐시 재사용 여부 — DB 미저장, startExport/selectReusableExport 응답 시 설정 */
        private Boolean cacheReused;
    }

    /**
     * F — 출력 요청 VO
     * 내보내기 형식은 PROJECT_CONFIG_JSON.template.docSize 기반으로 서버에서 자동 결정:
     * docSize "a4" → PDF, 그 외(169, 43) → PPTX
     */
    @Data
    public static class ExportRequestVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 에이전트 ID (로그용) */
        private String agentId;
        /** true면 캐시 무시하고 항상 신규 빌드 */
        private Boolean forceRebuild;
        /**
         * 출력 방식.
         * "image"  = 인포그래픽 이미지 기반 (기존 동작).
         * "component" = JSON componentsJson 기반 POI 네이티브 렌더링 (텍스트 편집 가능).
         * null/미전달 시 image 기반으로 동작.
         */
        private String outputMode;
    }

    /**
     * TB_PT_TEMPLATE - PT 슬라이드 헤더/푸터 템플릿
     */
    @Data
    public static class PtTemplateVO {
        /** TEMPLATE_ID (PK, VARCHAR 20, prefix PTM) */
        private String templateId;
        /** 프로젝트 ID (UNIQUE) */
        private String ptProjectId;
        /** 헤더 컴포넌트 레이아웃 JSON (LLM 생성) */
        private String headerComponentsJson;
        /** 푸터 컴포넌트 레이아웃 JSON (LLM 생성) */
        private String footerComponentsJson;
        /** 컬러 JSON */
        private String colorJson;
        /** 생성 상태 코드 (001=대기, 002=생성중, 003=완료, 004=실패) */
        private String genStatusCd;
        /** 오류 메시지 */
        private String errorMsg;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
        /** 수정자 ID */
        private String modifyUserId;
        /** 수정일시 */
        private String modifyDt;
        /** 템플릿 프레임 이미지 NCP 경로 (Step E 확정 후 비동기 생성) */
        private String frameImagePath;
        /** 표지 배경 이미지 NCP 경로 (사용자 트리거 시 동기 생성) */
        private String coverImagePath;
        /** 표지 이미지 생성 상태 코드 (PT000007 재사용: 001=대기, 002=생성중, 003=완료, 004=실패) */
        private String coverGenStatusCd;
        /** 간지 배경 이미지 NCP 경로 (사용자 트리거 시 동기 생성) */
        private String dividerImagePath;
        /** 간지 이미지 생성 상태 코드 (001=대기, 002=생성중, 003=완료, 004=실패) */
        private String dividerGenStatusCd;

        // ── DB에 저장되지 않는 런타임 필드 ──────────────────────────────────
        /** 표지 보완요청 채팅 AI 응답 요약 — DB 미저장, chatCover 응답 시 설정 */
        private String aiMessage;
    }

    /**
     * 템플릿 재생성 요청 VO
     */
    @Data
    public static class PtTemplateRegenerateVO {
        /** 프로젝트 ID */
        private String ptProjectId;
    }

    /**
     * 표지 보완요청 채팅 요청 VO
     */
    @Data
    public static class CoverChatVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 에이전트 ID */
        private String agentId;
        /** 사용자 보완 요청 메시지 */
        private String message;
    }

    /**
     * 간지 보완요청 채팅 요청 VO
     */
    @Data
    public static class DividerChatVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 에이전트 ID */
        private String agentId;
        /** 사용자 보완 요청 메시지 */
        private String message;
    }

    /**
     * Stage4 평가 시뮬레이션 결과 VO (TB_PT_REVIEW)
     */
    @Data
    public static class ReviewVO {
        /** 리뷰 ID */
        private String reviewId;
        /** 프로젝트 ID */
        private String ptProjectId;
        /** 슬라이드 ID */
        private String slideId;
        /** 평가기준 ID */
        private String evalCriteriaId;
        /** 심각도 코드 (001=높음, 002=중간, 003=낮음) */
        private String severityCd;
        /** 평가항목명 */
        private String evalCriteriaNm;
        /** 현재 문제 */
        private String currentIssue;
        /** 점수 영향 */
        private String scoreImpact;
        /** 수정 방향 */
        private String fixDirection;
        /** 수정 제안 내용 */
        private String fixSuggestionTxt;
        /** 예상 점수 */
        private java.math.BigDecimal expectedScore;
        /** 생성자 ID */
        private String createUserId;
        /** 생성일시 */
        private String createDt;
    }

    /**
     * Stage2 전략검토 요약 (Step5 상태패널 + 폴링용)
     */
    @Data
    public static class Stage2SummaryVO {
        private String stage2StatusCd;
        private int problemDefinitionCount;
        private int winThemeCount;
        private int winThemeStaleCount;
        private int uncoveredRequirementCount;
        private String problemDefinitionsGeneratedDt;
        private String winThemesGeneratedDt;
    }

    /**
     * Win Theme 조회 응답 (stale 정보 포함)
     */
    @Data
    public static class WinThemeStaleDetailVO {
        private String problemId;
        /** MODIFIED 또는 DELETED */
        private String reason;
        private String problemModifyDt;
    }

    @Data
    public static class WinThemeResponseVO {
        private String winThemeId;
        private String ptProjectId;
        private String coreMessage;
        private String customerProblem;
        private String proposalStrategy;
        private String evidence;
        private String expectedEffect;
        private String differentiation;
        /** 파싱된 배열 (DB의 SOURCE_PROBLEM_IDS_JSON) */
        private List<String> sourceProblemDefinitionIds;
        private String generatedDt;
        private String modifyDt;
        private boolean stale;
        private List<WinThemeStaleDetailVO> staleDetails;
    }

    /**
     * 문제정의 조회 응답 (파싱된 배열 포함)
     */
    @Data
    public static class ProblemDefinitionResponseVO {
        private String problemId;
        private String ptProjectId;
        private String problemTypeCd;
        private String currentProblem;
        private String rootCause;
        private String riskIfIgnored;
        private String goal;
        private String requiredCapability;
        private String strategySummary;
        private String kpi;
        private String sourceTypeCd;
        private List<String> sourceIssueIds;
        private List<String> sourceRequirementIds;
        private String generatedDt;
        private String modifyDt;
        private String manualYn;
        /** 문제정의 제목 (LLM 생성) — null이면 프론트에서 currentProblem 폴백 */
        private String problemTitleTxt;
    }

    /**
     * TOC 매핑 조회 응답
     */
    @Data
    public static class TocMappingNodeVO {
        private String tocId;
        private String title;
        private String parentTocId;
        private List<String> coveredReqIds;
        private String linkedEvalCriteriaId;
        private int sortOrd;
    }

    @Data
    public static class EvalCriteriaOptionVO {
        private String evalCriteriaId;
        private String evalItemNm;
        private double score;
    }

    @Data
    public static class TocMappingResponseVO {
        private List<TocMappingNodeVO> tocNodes;
        private List<String> unassignedRequirementIds;
        private List<EvalCriteriaOptionVO> evalCriteriaOptions;
    }

    /**
     * 문제정의 수동 수정/추가 요청 VO
     */
    @Data
    public static class ProblemDefinitionUpdateVO {
        private String ptProjectId;
        private String problemId;
        private String problemTypeCd;
        private String currentProblem;
        private String rootCause;
        private String riskIfIgnored;
        private String goal;
        private String requiredCapability;
        private String strategySummary;
        private String kpi;
        private String modifyUserId;
        /** 문제정의 제목 — 단건 재생성(regenerateTitle=true) 시에만 갱신됨 */
        private String problemTitleTxt;
    }

    /**
     * Win Theme 수동 수정/추가 요청 VO
     */
    @Data
    public static class WinThemeUpdateVO {
        private String ptProjectId;
        private String winThemeId;
        private String coreMessage;
        private String customerProblem;
        private String proposalStrategy;
        private String evidence;
        private String expectedEffect;
        private String differentiation;
        private List<String> sourceProblemDefinitionIds;
        private String modifyUserId;
    }

    /**
     * TOC 매핑 단건 수정 요청
     */
    @Data
    public static class TocMappingUpdateVO {
        private String tocId;
        private List<String> coveredReqIds;
        private String linkedEvalCriteriaId;
        private String modifyUserId;
    }

    /**
     * Stage2 개별 재실행 요청
     */
    @Data
    public static class Stage2RegenerateVO {
        private String ptProjectId;
        private String modelId;
        private String agentId;
        private String userFeedback;
        private int totalSlideBudget;
    }

    /**
     * 문제정의 단건 보완(refine) 요청 — 선택 PD의 sourceIssue/Req만 LLM에 전달
     */
    @Data
    public static class ProblemDefinitionRefineVO {
        private String ptProjectId;
        private String problemId;
        private String userFeedback;
        private String modelId;
        private String agentId;
        /**
         * true이면 LLM에게 title 필드도 요청하고 PROBLEM_TITLE_TXT를 갱신한다.
         * false(기본)이면 채팅 보완요청으로 간주하여 title은 변경하지 않는다.
         */
        private Boolean regenerateTitle;
    }

    @Data
    public static class ChatCoverVO {
        /** PT 프로젝트 ID */
        private String ptProjectId;
        /** 템플릿 ID */
        private String templateId;
        /** 사용자 보완 요청 메시지 */
        private String message;
    }

    /**
     * 스텝 프롬프트 조회/수정 VO (TB_PROMPT 기반)
     */
    @Data
    public static class PromptEditVO {
        /** PROMPT_ID (PK) */
        private String promptId;
        /** 프롬프트명 */
        private String promptName;
        /** 단계 코드 (STAGE_CD) */
        private String stageCd;
        /** 현재 프롬프트 내용 (수정 가능) */
        private String content;
        /** 원본 프롬프트 내용 (원본 복구용, 수정 불가) */
        private String originalContent;
        /** 조회 요청용 stageCd 목록 (DB 미저장, selectStepPrompts 파라미터용) */
        private List<String> stageCds;
    }

    /**
     * 콘텐츠 개요 보완 채팅 요청 VO
     */
    @Data
    public static class TocOutlineChatVO {
        private String tocId;
        private String message;
        private String modelId;
        private String agentId;
    }

    /**
     * 콘텐츠 개요 확정 요청 VO
     */
    @Data
    public static class TocOutlineConfirmVO {
        private String tocId;
        private String outlineTxt;
    }
}
