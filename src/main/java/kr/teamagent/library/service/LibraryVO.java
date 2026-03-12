package kr.teamagent.library.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LibraryVO {

    /** saveCategory API용: { category: {...} } 래퍼 처리 */
    @JsonProperty("category")
    public void setCategoryFromWrapper(LibraryVO category) {
        if (category != null) {
            this.categoryId = category.getCategoryId();
            this.userId = category.getUserId();
            this.categoryNm = category.getCategoryNm();
            this.color = category.getColor();
            this.sortOrd = category.getSortOrd();
        }
    }

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

}
