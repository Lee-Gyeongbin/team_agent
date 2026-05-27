package kr.teamagent.datadashboard.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DataDashboardVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    // ===== SQL 목록 (TB_CHAT_LOG 기반) =====

    /** 채팅 로그 ID */
    private Long logId;

    /** 위젯 제목 / SQL 제목 (= Q_CONTENT) */
    private String sqlTitle;

    /** SQL 쿼리 본문 (= TTSQ) */
    private String sqlContent;

    /** LLM이 추출한 파라미터 스키마 JSON (= TTSQ_PARAM) */
    private String ttsqParam;

    /** 에이전트 ID */
    private String agentId;

    /** 에이전트 이름 */
    private String agentNm;

    /** 데이터마트 ID (= REF_ID) */
    private String datamartId;

    /** 데이터마트 이름 */
    private String datamartNm;

    /** 생성일시 */
    private String createdAt;

    // ===== SQL 실행 =====

    /** SQL 실행 시 전달할 파라미터 맵 (JSON string) */
    private String sqlParams;

    // ===== 위젯 =====

    /** 위젯 ID */
    private String widgetId;

    /** 위젯 표시 제목 */
    private String title;

    /** 시각화 유형 (bar/line/pie/horizontalBar/table) */
    private String vizType;

    /** 컬럼 매핑 설정 JSON */
    private String vizConfig;

    /** 정렬 순서 */
    private Integer sortOrd;

    /** 차트 옵션 */
    private String chartOption;

    // ===== 코드 매핑 (TB_DM_COL_CODE) =====

    /** 컬럼 ID */
    private String colId;

    /** 실제 저장 코드값 */
    private String codeVal;

    /** 코드 한국어 의미 (TB_DM_COL_CODE.CODE_KOR_NM) */
    private String codeKorNm;

    /** 컬럼 한국어명 (TB_DM_COL.COL_KOR_NM) */
    private String colKorNm;

    // ===== 레이아웃 (GridStack x/y/w/h 기반) =====

    /** 레이아웃 ID */
    private String layoutId;

    /** GridStack 열 시작 위치 (0-based, 6열 그리드) */
    private Integer x;

    /** GridStack 행 시작 위치 (0-based) */
    private Integer y;

    /** GridStack 열 너비 (1~6) */
    private Integer w;

    /** GridStack 행 높이 (셀 단위) */
    private Integer h;

    /** GridStack 최소 열 너비 */
    private Integer minW;

    /** GridStack 최대 열 너비 */
    private Integer maxW;

    /** GridStack 최소 행 높이 */
    private Integer minH;

    /** GridStack 최대 행 높이 */
    private Integer maxH;

    /** 위젯 표시 여부 (true=표시, false=숨김) */
    private Boolean isVisible;

    /** 레이아웃 일괄 저장 항목 목록 (layoutSaveBatch 전용) */
    private List<LayoutBatchItemVO> layoutBatchList;

    @Getter
    @Setter
    public static class LayoutBatchItemVO {
        /** UPSERT용 레이아웃 ID (서비스에서 기존 재사용 또는 신규 생성) */
        private String layoutId;
        private String widgetId;
        private Integer sortOrd;
        private Integer x;
        private Integer y;
        private Integer w;
        private Integer h;
        private Integer minW;
        private Integer maxW;
        private Integer minH;
        private Integer maxH;
        /** 위젯 표시 여부 (true=표시, false=숨김) */
        private Boolean isVisible;
    }

}
