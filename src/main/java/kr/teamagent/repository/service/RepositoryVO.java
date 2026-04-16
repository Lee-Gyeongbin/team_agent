package kr.teamagent.repository.service;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RepositoryVO {
    
    private String mergeTest2;
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

    /** TB_DOC_FILE */
    private String docFileId;
    /** 문서 파일 개수 */
    private String fileCnt;
    /** 문서 제목 */
    private String docTitle;
    /** 작성자 */
    private String author;
    /** 보안 레벨 */
    private String secLvl;
    /** 문서 설명 */
    private String docDesc;
    /** 파일 이름 */
    private String fileName;
    /** 파일 경로 */
    private String filePath;
    /** 파일 크기 */
    private String fileSize;
    /** 파일 타입 */
    private String fileType;
    /** 파일 순번 */
    private Integer fileOrd;
    /** 키워드 */
    private String keywords;
    /** 문서 출처 URL */
    private String docSrc;
    /** 사용 여부 */
    private String useYn;
    /** 생성 일시 */
    private String createDt;
    /** 등록자 ID */
    private String createUserId;
    /** 수정 일시 */
    private String modifyDt;
    /** 수정자 ID */
    private String modifyUserId;
    /** 구축된 데이터셋 포함 개수 */
    private Integer activeDsCnt;
    /** 연결 데이터셋명 */
    private String dsNm;

    /** 문서 파일 ID 목록 */
    private List<String> docFileIdList;

    /** 파일 라이브러리 목록 페이징 (선택) */
    private Integer page;
    private Integer pageSize;
    /** MyBatis LIMIT offset — 서비스에서 page/pageSize로 계산 */
    private Integer startIndex;

    private String mergeTest;
}
