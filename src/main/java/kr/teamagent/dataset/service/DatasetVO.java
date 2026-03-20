package kr.teamagent.dataset.service;

import java.math.BigDecimal;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DatasetVO {

    /** 공통 */
    private String datasetId;
    private String dsNm;
    private String description;
    private String version;
    private String datasetBuildStatusCd;
    private String useYn;
    private String modifyDt;
    private String chunkAlgoCd;
    private String hdrInclCd;
    private String embedModelCd;
    private String vectorDbCd;
    private String embedNormCd;
    private String poolStratCd;
    private String dimReducCd;
    private String categoryId;
    private String categoryName;
    private String paryCatId;
    private Integer catLvl;
    private Integer sortOrd;
    private String createDt;

    /** 데이터셋 상세/목록 */
    private Integer chunkCnt;
    private BigDecimal srchQual;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer minChunkSz;
    private String lowercaseYn;
    private String wspNormYn;
    private String specChrRmYn;
    private String singleCellText;

    private String htmlRmYn;
    private String stopwordRmYn;
    private String codeKeepYn;
    
    private String sentSplitAlgoCd;
    private String langDetectCd;

    private Integer docCnt;
    private Integer urlCnt;

    private String embedModelNm;
    private String vectorDbNm;
    private String chunkAlgoNm;
    private String hdrInclNm;

    /** 상단 요약 */
    private Integer totalDatasetCount;
    private Integer activeDatasetCount;
    private Integer inactiveDatasetCount;
    private Integer totalVectorCount;
    private BigDecimal avgSearchQuality;
    private Integer totalSourceCount;
    private Integer totalDocCount;
    private Integer totalUrlCount;
    
    /** TB_DS_DOC */
    /** 문서 ID */
    private String docId;
    /** 문서 제목 */
    private String docTitle;

    /** TB_DS_URL */
    /** URL ID */
    private String urlId;
    /** URL 이름 */
    private String urlName;
    /** URL 주소 */
    private String urlAddr;
    /** 문서 ID 목록 */
    private List<DocIdItem> docIdList;
    /** URL ID 목록 */
    private List<UrlIdItem> urlIdList;

    /** TB_DS_HIST */
    /** 이력 ID */
    private String histId;
    /** 버전 번호 */
    private String verNo;
    /** 변경 내용 */
    private String chgContent;
    /** 삭제 여부 */
    private String delYn;

    private Integer page;
    private Integer pageSize;
    private Integer offset;
    private Integer totalCnt;

    /**
     * TB_DS_DOC 매핑용 DTO
     * - 프론트에서 { "docId": "...", "datasetId": "..." } 형태로 내려오는 경우를 지원
     */
    @Getter
    @Setter
    public static class DocIdItem {
        private String datasetId;
        private String docId;
    }

    /**
     * TB_DS_URL 매핑용 DTO
     * - 프론트에서 { "urlId": "...", "datasetId": "..." } 형태로 내려오는 경우를 지원
     */
    @Getter
    @Setter
    public static class UrlIdItem {
        private String datasetId;
        private String urlId;
    }
}
