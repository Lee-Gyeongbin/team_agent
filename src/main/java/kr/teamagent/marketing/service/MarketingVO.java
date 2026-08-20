package kr.teamagent.marketing.service;

import com.fasterxml.jackson.annotation.JsonProperty;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MarketingVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    /** TB_MKT */
    private String mktId;
    private String marketingProjectId;
    private String agentId;
    private String title;
    private String outputMode;
    private String statusCd;
    /** 발행 예정일시 */
    private String publishScheduledDt;
    /** 발행 완료 표시 Y/N */
    private String publishedYn;
    private String contentType;
    private String requestJson;
    private String createUserId;
    private String createUserNm;
    private String modifyUserId;
    private String createDt;

    /** TB_MKT_CONTENT */
    private String mktContentId;
    private Integer contentNo;
    private String recommendYn;
    private String contentLabel;
    private String textContent;
    private String imageFile;

    /** 직전 시안 존재 여부 */
    private String hasPreviousYn;

    /** 검색 조건 */
    private String keyword;
    private Integer periodDays;

    /** TB_MKT_PROJECT */
    @Getter
    @Setter
    public static class ProjectVO {
        /** MKT_PROJECT_ID */
        private String marketingProjectId;
        private String projectNm;
        private String orgNm;
        private String projectOverview;
        private String dueDt;
        private String statusCd;
        private String statusNm;
        private String projectConfigJson;
        private String createUserId;
        private String createDt;
        private String modifyUserId;
        private String modifyDt;
        private String keyword;
        private String sortField;
        private String sortOrder;
        private Integer limit;
        private Integer offset;
    }

    /** TB_MKT_FILE */
    @Getter
    @Setter
    public static class FileVO {
        /** MKT_FILE_ID */
        private String marketingFileId;
        private String marketingProjectId;
        private String filePath;
        /** 원본 파일명 */
        @JsonProperty("fileName")
        private String fileNm;
        private Long fileSize;
        private String fileType;
        private String mimeType;
        private String createUserId;
        private String createDt;
    }
}
