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

    /** TB_CNT_URL */
    /** URL ID */
    private String urlId;
    /** URL 이름 */
    private String urlName;
    /** URL 주소 */
    private String urlAddr;
    /** 크롤링 간격 */
    private String crawlIntvl;
    /** 크롤링 깊이 */
    private String crawlDpth;
    /** 마지막 크롤링 일시 */
    private String lastCrawlDt;
    /** 크롤링 상태 코드 */
    private String urlCrawlStatusCd;

    /** 문서 파일 ID 목록 */
    private List<String> docFileIdList;
    /** URL ID 목록 (배치 삭제) */
    private List<String> urlIdList;
    /** true: URL_ID IS NOT NULL (URL탭 수집 파일 조회), null/false: URL_ID IS NULL (파일 탭) */
    private Boolean urlIdNotNull;
    /** 파일 라이브러리 저장 요청 목록 (배치) */
    private List<RepositoryVO> dataList;

    /** 파일 라이브러리 목록 페이징 (선택) */
    private Integer page;
    private Integer pageSize;
    /** MyBatis LIMIT offset — 서비스에서 page/pageSize로 계산 */
    private Integer startIndex;
}
