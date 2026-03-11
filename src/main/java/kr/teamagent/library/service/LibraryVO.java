package kr.teamagent.library.service;

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

}
