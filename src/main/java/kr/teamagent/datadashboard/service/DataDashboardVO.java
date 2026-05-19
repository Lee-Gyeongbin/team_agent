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

    /** 너비 (1=절반, 2=전체) */
    private Integer colSpan;

    /** 정렬 순서 */
    private Integer sortOrd;

    /** 차트 옵션 */
    private String chartOption;

    // ===== 위젯 순서 변경 =====

    /** 순서 변경 항목 목록 */
    private List<WidgetOrderItemVO> orderList;

    @Getter
    @Setter
    public static class WidgetOrderItemVO {
        private String widgetId;
        private Integer sortOrd;
    }

}
