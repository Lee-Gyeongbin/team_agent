package kr.teamagent.library.service;

import java.util.List;

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

    /** TB_CHAT_LOG 조인 필드 */
    private String qContent;
    private String rContent;
    private String ttsq;

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
    }

    /** 참조 매뉴얼(문서) 목록 조회 API 응답 — TB_DOC 조회 결과 [TB_DOC] */
    @Getter
    @Setter
    public static class DocItem {
        private String docId;
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

    /** AI 문서 생성 API 요청 */
    private String tmplId;

    /** 리포트 채팅방 ID */
    private String roomId;

    /** TB_REPORT_CHAT_LOG.REPORT_DATA */
    private String reportData;

    /** reAskReport API 요청: 화면에서 전달된 이전 보고서 JSON (객체/문자열 모두 허용) */
    private Object generatedReport;

    /** TB_REPORT_CHAT_LOG.ASK_QUERY */
    private String askQuery;

    /** TB_REPORT_CHAT_LOG.IDX_NO */
    private Integer idxNo;

    @Getter
    @Setter
    public static class TmplFieldItem {
        private String jsonKey;
        private String fieldNm;
        private String multilineYn;
    }

    @Getter
    @Setter
    public static class TmplItem {
        private String tmplType;
        private String llmPrompt;
    }

}
