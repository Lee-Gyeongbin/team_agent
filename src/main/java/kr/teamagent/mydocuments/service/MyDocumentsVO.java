package kr.teamagent.mydocuments.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MyDocumentsVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    /** 문서 ID [TB_MY_DOC.DOC_ID] */
    private String docId;
    /** 템플릿 ID */
    private String tmplId;
    /** 문서명 */
    private String docNm;
    /** 편집 HTML */
    private String docHtml;
    /** 원본 HTML */
    private String originHtml;
    /** 서비스 타입 */
    private String svcTy;
    /** 참조/원문 콘텐츠 (제목 생성·저장용). Jackson JavaBeans: getRContent → JSON 키 RContent로 인식되는 문제 방지 */
    @JsonProperty("rContent")
    @JsonAlias("RContent")
    private String rContent;
    /** 문서 상태 */
    private String docStatus;
    /** 정렬 순서 */
    private Integer sortOrd;
    /** 신규 여부 [TB_MY_DOC.NEW_YN] */
    private String newYn;

}
