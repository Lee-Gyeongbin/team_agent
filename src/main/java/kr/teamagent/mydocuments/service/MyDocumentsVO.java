package kr.teamagent.mydocuments.service;

import java.util.List;

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
    /** 에이전트 ID [TB_MY_DOC.AGENT_ID] */
    private String agentId;
    /** 에이전트명 [TB_AGT.AGENT_NM] */
    private String agentNm;
    /** 에이전트 아이콘 클래스명 [TB_ICON.ICON_CLASS_NM] */
    private String iconClassNm;
    /** 에이전트 색상 HEX [TB_COLOR.COLOR_HEX] */
    private String colorHex;
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
    /** 생성일시 */
    private String createDt;
    /** 수정일시 */
    private String modifyDt;

    /** 목록 검색: 문서명 */
    private String searchDocNm;
    /** 목록 정렬 (latest | oldest | name, custom=수동 SORT_ORD) */
    private String searchSort;

    /** 내 문서 공유 요청 payload [TB_MY_DOC_SHARE, TB_NOTIFY] */
    @Getter
    @Setter
    public static class ShareDocPayload {
        /** 요청 필드 */
        private String docId;
        private List<String> userIds;
        private String shareMsg;
        /** 서비스 내부 사용 필드 */
        private String shareId;
        private String fromUserId;
        private String toUserId;
    }

}
