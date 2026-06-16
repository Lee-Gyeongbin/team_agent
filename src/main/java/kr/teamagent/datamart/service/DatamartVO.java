package kr.teamagent.datamart.service;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DatamartVO {

    /** TB_DM */
    private String datamartId;
    private String dmNm;
    private String description;
    private String dbType;
    private String dbVersion;
    private String host;
    private Integer port;
    private String dbNm;
    private String username;
    private String pwdEnc;
    private String schNm;
    private String connOpt;
    private String readonlyYn;
    private String ipWlistYn;
    private String sslYn;
    private Integer tblCnt;
    private String lastVerifyDt;
    private Integer sortOrd;
    private String useYn;
    private String createDt;
    private String modifyDt;
    private String testType;

    @Getter
    @Setter
    public static class SummaryVO {
        private int totalCount;
        private int activeCount;
        private int inactiveCount;
        private int dataSourceCount;
        private String lastScanDate;
        private String connectedSystems;
    }

    @Getter
    @Setter
    public static class MetaTableSavePayloadVO {
        private String datamartId;
        private List<MetaTableItemVO> tableList;
    }

    @Getter
    @Setter
    public static class MetaTableItemVO {
        private String id;
        private String physicalNm;
        private String logicalNm;
        private Integer colCnt;
        private String useYn;
        private String tableDescKo;
    }

    /**
     * 메타 관리 > 컬럼 일괄 저장 요청 (datamartId + 테이블별 columns)
     */
    @Getter
    @Setter
    public static class MetaColumnSavePayloadVO {
        private String datamartId;
        private List<MetaColumnSaveTableItemVO> tableList;
    }

    /**
     * 컬럼 저장 API용 테이블 항목 (TB_DM_COL.TBL_ID 매핑용 id + 저장 대상 columns)
     */
    @Getter
    @Setter
    public static class MetaColumnSaveTableItemVO {
        private String id;
        private List<MetaColumnRowVO> columns;
    }

    /**
     * 컬럼 메타 엑셀 다운로드/업로드 행 (TB_DM_COL + TBL_ID)
     */
    @Getter
    @Setter
    public static class MetaColumnExcelRowVO {
        private String tblId;
        private String colId;
        private String colPhyNm;
        private String colKorNm;
        private String colDesc;
        private String dataType;
        private String dataLen;
        private String pkYn;
        private String fkYn;
        private String nullableYn;
        private String hasCodeYn;
        private String aiHint;
        private Integer sortOrd;
        private String useYn;
    }

    /**
     * TB_DM_COL 저장용 컬럼 행 (프론트 DatamartMetaColumnRow 대응)
     */
    @Getter
    @Setter
    public static class MetaColumnRowVO {
        private String colId;
        private String colPhyNm;
        private String colKorNm;
        private String colDesc;
        private String dataType;
        private String dataLen;
        private String pkYn;
        private String fkYn;
        private String nullableYn;
        private String hasCodeYn;
        private String aiHint;
        private Integer sortOrd;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    /**
     * 메타 관리 > 관계(조인) 일괄 저장 요청 (datamartId + relationshipList)
     */
    @Getter
    @Setter
    public static class MetaRelationshipSavePayloadVO {
        private String datamartId;
        private List<MetaRelationshipRowVO> relationshipList;
    }

    /**
     * 메타 관리 > 코드값 매핑 저장 요청 (datamartId + codeColumnMappingList)
     */
    @Getter
    @Setter
    public static class MetaCodeMappingSavePayloadVO {
        private String datamartId;
        private List<MetaCodeColumnMappingVO> codeColumnMappingList;
    }

    /**
     * TB_DM_COL_CODE 컬럼↔코드그룹 매핑 (조회: TB_CODE_GRP JOIN)
     */
    @Getter
    @Setter
    public static class MetaCodeColumnMappingVO {
        private String tblId;
        private String colId;
        private String codeGrpId;
        private String codeGrpNm;
        private String description;
        private String aiHint;
        private Integer sortOrd;
        private String useYn;
    }

    /**
     * TB_DM_REL 저장용 관계 행 (프론트 DatamartMetaRelationship 대응)
     */
    @Getter
    @Setter
    public static class MetaRelationshipRowVO {
        private String datamartId;
        private String relId;
        private String fromTblId;
        private String fromColId;
        private String toTblId;
        private String toColId;
        private String cardinality;
        private String joinType;
        private String relDesc;
        private Integer sortOrd;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    @Getter
    @Setter
    public static class MetaSynonymRowVO {
        private String datamartId;
        private String synonymId;
        private String synonymWord;
        private String representYn;
        private Integer sortOrd;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    @Getter
    @Setter
    public static class MetaSynonymSavePayloadVO {
        private String datamartId;
        private List<MetaSynonymRowVO> synonymList;
    }

    @Getter
    @Setter
    public static class MetaFewshotRowVO {
        private String datamartId;
        private String fewshotId;
        private String userQuestion;
        private String aiUnderstand;
        private String aiRefExam;
        private String sqlExam;
        private Integer sortOrd;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    @Getter
    @Setter
    public static class MetaFewshotSavePayloadVO {
        private String datamartId;
        private List<MetaFewshotRowVO> fewshotList;
    }
}
