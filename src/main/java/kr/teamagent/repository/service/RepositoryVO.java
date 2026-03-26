package kr.teamagent.repository.service;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RepositoryVO {
    
    /** 검색 조건 */
    private String findContent;

    /** 카테고리 정보 */
    /** 카테고리 ID */
    private String categoryId;
    /** 카테고리 이름 */
    private String categoryName;
    /** 부모 카테고리 ID */
    private String parnCatId;
    /** 카테고리 레벨 */
    private String catLvl;
    /** 정렬 순서 */
    private String sortOrd;
    /** 정렬 경로 */
    private String sortPath;
    /** 깊이 */
    private String depth;
    /** 총 문서 개수 */
    private Integer totalCount;

    /** TB_DOC */
    /** RAG 지식원천 문서 마스터 */
    /** 문서 ID */
    private String docId;
    private String docFileId;
    /** 문서 제목 */
    private String docTitle;
    /** 작성자 */
    private String author;
    /** 보안 레벨 */
    private String secLvl;
    /** 문서 내용 */
    private String content;
    /** 파일 이름 */
    private String fileName;
    /** 파일 경로 */
    private String filePath;
    /** 파일 크기 */
    private String fileSize;
    /** 파일 타입 */
    private String fileType;
    /** 키워드 */
    private String keywords;
    /** 참조 URL */
    private String refUrl;
    /** 사용 여부 */
    private String useYn;
    /** 생성 일시 */
    private String createDt;
    /** 수정 일시 */
    private String modifyDt;
    /** 데이터셋 문서 개수 */
    private String dsDocCnt;

    private List<RepositoryVO> docIdList;

    /**
     * 문서 저장 시 NCP 업로드 완료 후 파일 메타 배열 (JSON 키: "file")
     */
    private List<RepositoryFileItem> file;

    @Getter
    @Setter
    public static class RepositoryFileItem {
        private String fileName;
        private String filePath;
        private String fileSize;
        private String fileType;
    }
}
