package kr.teamagent.library.service;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LibraryVO {

    /** 지식 카테고리 [TB_KNOW_CAT] */
    private String categoryId;
    private String userId;
    private String categoryNm;
    private String color;
    private Integer sortOrd;
    private String createDt;

    /** 지식 카드 [TB_KNOW_CARD] */
    private String cardId;
    /** 카드 이동 API용: 이동 대상 카테고리 ID */
    private String targetCategoryId;
    private String logId;
    private String svcTy;
    private String title;
    private String tags;
    private String pinYn;
    private String archiveYn;
    private String archiveDt;
    private String sqlCode;
    private String newYn;
    private String thumbImg;
    private String useYn;
    private String modifyDt;
    private String searchTitle;
    private String searchSort;

    /** 지식카드 차트 API [TB_KNOW_CARD_CHART] — knowChartList·saveKnowChart·deleteKnowChart 공통 바인딩 */
    private String chartId;
    private String chartType;
    private String chartTargetKey;
    /** Jackson JavaBeans: getYAxisKeys → JSON 키 YAxisKeys로 인식되는 문제 방지 */
    @JsonProperty("yAxisKeys")
    @JsonAlias("YAxisKeys")
    private List<String> yAxisKeys;
    /** MyBatis UPDATE/INSERT용 JSON 문자열 (서비스에서 yAxisKeys 직렬화) */
    @JsonIgnore
    private String yAxisKeysJson;
    private String seriesKey;
    private String statIdFilter;
    private String stackYn;
    private String dualAxisYn;
    private String ylChartType;
    private String yrChartType;

    /** TB_CHAT_LOG 조인 필드 (Jackson: rContent/qContent는 JavaBeans 규칙상 RContent로 역직렬화되는 문제 방지) */
    private String qContent;
    private String rContent;
    private String ttsq;
    private String reportHtml;
    private String agentId;
    private String agentNm;
    private String iconClassNm;
    private String colorHex;

    private CategoryItem category;

    /** 카드 수정 API용: { card: { cardId, userId, categoryId, ... } } */
    private CardItem card;

    @Getter
    @Setter
    public static class CategoryItem {
        private String categoryId;
        private String userId;
        private String categoryNm;
        private String color;
        private Integer sortOrd;
        private String createDt;
    }

    /** 카테고리 순서 변경 API용: { items: [{ categoryId, sortOrd }] } */
    private List<CategoryOrderItem> items;
    @Getter
    @Setter
    public static class CategoryOrderItem {
        private String categoryId;
        private Integer sortOrd;
    }

    /** 카드 순서 일괄 변경 API용: { payload: [{ categoryId, cards: [{ cardId, order }] }] } */
    private List<CardOrderPayload> payload;
    @Getter
    @Setter
    public static class CardOrderPayload {
        private String categoryId;
        private List<CardOrderItem> cards;
    }
    @Getter
    @Setter
    public static class CardOrderItem {
        private String cardId;
        private Integer sortOrd;
    }

    /** 카드 수정 API용 [TB_KNOW_CARD] - 기존 카드만 UPDATE */
    @Getter
    @Setter
    public static class CardItem {
        private String cardId;
        private String userId;
        private String categoryId;
        private String categoryNm;
        private String logId;
        private String svcTy;
        private String title;
        private String tags;
        private String pinYn;
        private String archiveYn;
        private String archiveDt;
        private Integer sortOrd;
        private String sqlCode;
        private String newYn;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    /** 테이블 데이터 조회 API 응답 [TB_CHAT_LOG] */
    @Getter
    @Setter
    public static class TableDataItem {
        private String logId;
        private String tableData;
        private String chartOption;
    }

    /** 참조 매뉴얼(문서) 목록 조회 API 응답 — TB_DOC_FILE 조회 결과 */
    @Getter
    @Setter
    public static class DocItem {
        private String docFileId;
        private String fileName;
        private String filePath;
        private Long fileSize;
        private String fileType;
        private String relatedPages;
    }

    /** 차트 통계 속성 목록 조회 API 응답 [stat_attr] */
    @Getter
    @Setter
    public static class ChartStatItem {
        private String statId;
        private String statNm;
    }

    /** 차트 라벨 목록 조회 API 응답 [stat_detail_item] */
    @Getter
    @Setter
    public static class ChartDetailCdItem {
        private String detailItemCd;
        private String detailItemNm;
    }

    /** 지식카드 차트 목록 조회 API 응답 [TB_KNOW_CARD_CHART] */
    @Getter
    @Setter
    public static class KnowChartItem {
        private String chartId;
        private String cardId;
        private String chartType;
        private String chartTargetKey;
        @JsonProperty("yAxisKeys")
        @JsonAlias("YAxisKeys")
        private String yAxisKeys;
        private String seriesKey;
        private String statIdFilter;
        private String stackYn;
        private String dualAxisYn;
        private String ylChartType;
        private String yrChartType;
        private Integer sortOrd;
        private String createDt;
    }

    /** 지식카드 차트 저장 API 요청 [TB_KNOW_CARD_CHART] */
    @Getter
    @Setter
    public static class KnowChartSavePayload {
        private String chartId;
        private String cardId;
        private String chartType;
        private String chartTargetKey;
        @JsonProperty("yAxisKeys")
        @JsonAlias("YAxisKeys")
        private List<String> yAxisKeys;
        /** MyBatis INSERT용 JSON 문자열 (서비스에서 yAxisKeys 직렬화) */
        private String yAxisKeysJson;
        private String seriesKey;
        private String statIdFilter;
        private String stackYn;
        private String dualAxisYn;
        private String ylChartType;
        private String yrChartType;
        private Integer sortOrd;
        private String createUserId;
    }

    /** AI 문서 생성 API 요청 */
    private String tmplId;

    /** createDoc API: svcTy='S'일 때 프론트엔드에서 캡처한 차트 이미지 base64 목록 */
    private List<String> chartImages;

    /** 리포트 채팅방 ID */
    private String roomId;

    /** TB_REPORT_CHAT_LOG.REPORT_DATA */
    private String reportData;

    /** reAskReport API 요청: 화면에서 전달된 이전 보고서 JSON (객체/문자열 모두 허용) */
    private String currentHtml;

    /** TB_REPORT_CHAT_LOG.ASK_QUERY */
    private String askQuery;

    /** insightReport API: NEW_SECTION | REPLACE */
    private String insightPlacement;

    /** insightReport API: REPLACE일 때 data-value-key */
    private String targetValueKey;

    /** TB_REPORT_CHAT_LOG.IDX_NO */
    private Integer idxNo;

    @Getter
    @Setter
    public static class TmplFieldItem {
        private String jsonKey;
        private String fieldNm;
        private String multilineYn;
        private String layoutType;
    }

    @Getter
    @Setter
    public static class TmplItem {
        private String tmplType;
        private String llmPrompt;
        private String tmplHtml;
    }

    /** Agent 서브 설정 일괄 조회 파라미터 */
    private List<String> agentIdList;

    /** 에이전트 정보 [TB_AGT] — selectAgentListForLibrary 응답 */
    @Getter
    @Setter
    public static class AgentItem {
        private String agentId;
        private String agentNm;
        private String svcTy;
        private String svcTyNm;
        private String description;
        private String apiPort;
        private String apiEndpoint;
        private String apiUrlCd;
        private Integer sortOrd;
        private String useYn;
        private String iconId;
        private String colorId;
        private String iconClassNm;
        private String colorHex;
        private AgtSubCfgVO subCfg;
    }

    /** Agent 서브 설정 [TB_AGT_SUB_CFG] */
    @Getter
    @Setter
    public static class AgtSubCfgVO {
        private String subCfgId;
        private String agentId;
        private String subTy;
        /** MyBatis JSON 컬럼(ADDITIONAL_CONFIG) 매핑용 */
        @JsonIgnore
        private String additionalConfig;
        @JsonProperty("additionalConfig")
        private Map<String, Object> additionalConfigMap;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    /** 카드 공유 요청 payload [TB_KNOW_CARD_SHARE, TB_NOTIFY] */
    @Getter
    @Setter
    public static class ShareCardPayload {
        /** 요청 필드 */
        private String cardId;
        private List<String> userIds;
        private String shareMsg;
        /** 서비스 내부 사용 필드 */
        private String shareId;
        private String fromUserId;
        private String toUserId;
        private String notifyId;
        private String content;
        private String notifyTitle;
    }

}
