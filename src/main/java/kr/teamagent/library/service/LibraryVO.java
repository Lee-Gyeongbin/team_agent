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
    private String logId;
    private String svcTy;
    private String title;
    private String tags;
    private String pinYn;
    private String archiveYn;
    private String srcDocs;
    private String sqlCode;
    private String chartCfg;
    private String qryRslt;
    private String useYn;
    private String modifyDt;

    /** TB_CHAT_LOG 조인 필드 */
    private String qContent;
    private String rContent;
    private String ttsq;

    private CategoryItem category;

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

}
