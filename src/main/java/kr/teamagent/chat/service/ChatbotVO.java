package kr.teamagent.chat.service;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatbotVO extends CommonVO {

    // 에이전트 정보(TB_AGT)
    private String agentNm;
    private String apiPort;
    private String apiEndpoint;
    private String apiUrlCd;
    private String iconId;
    private String colorId;
    private String iconClassNm;
    private String colorHex;

    private AgtSubCfgVO subCfg;

    /** Agent ID 목록 일괄 조회 파라미터 */
    private List<String> agentIdList;

    // CHAT 대화방 정보(TB_CHAT_ROOM)
    // 대화방 ID
    private Long roomId;
    // 대화방 제목
    private String roomTitle;
    private String title;
    // 사용자 ID
    private String userId;
    // 마지막 채팅 일시
    private String lastChatDt;
    private String content;
    // 참조 문서 존재 여부
    private String docExist;
    // 테이블 데이터 존재 여부
    private String tableExist;
    private String satisCd;

    // LLM 모델 정보(TB_LLM_MDL)
    // 모델 ID
    private String modelId;
    private String label;
    private String value;
    // 모델 이름
    private String modelName;
    // 제공자 ID
    private String providerId;
    // 버전
    private String version;
    // 입력 비용
    private String inputCost;
    // 출력 비용
    private String outputCost;
    // 사용 여부
    private String useYn;
    // 설명
    private String description;
    // 정렬 순서
    private String sortOrder;
    // 수정 일시
    private String modifyDt;

    /* AI 챗봇 질문/응답 로그 */
    private Long logId;
    private String agentId;
    // AI 서비스 타입
    private String svcTy;
    private String svcTyNm;
    // 참조 타겟 ID (RAG DATASET_ID, SQL DATAMART_ID 등)
    private String refId;
    // 질문 내용
    private String qContent;
    /** REST 요청 호환용 질문 필드 */
    private String query;
    // 응답 내용
    private String rContent;
    // 입력 토큰 수
    private int inTokens;
    // 출력 토큰 수
    private int outTokens;
    // 만족 여부 (Y/N)
    private String satisYn;
    // 만족 내용
    private String satisContent;
    // 만족 일시
    private String satisDt;
    // 파일 경로
    private String filePath;
    // 페이지 번호
    private int pageNo;
    // 관련 페이지 번호
    private String relatedPageNo;
    // 변환쿼리
    private String ttsq;
    // 재질문 횟수
    private int reaskCnt;
    // 테이블 데이터
    private String tableData;
    // 차트 옵션
    private String chartOption;
    /** Web 검색·그라운딩 출처 JSON — {"items":[{"url","title"},...]} */
    private String webGroundingJson;
    // SQL
    private String sql;
    // TEXT2SQL WHERE 조건 변수 스키마
    private String ttsqParam;
    // TEXT2SQL 기간 파라미터 JSON
    private String ttsqPeriodParam;

    /* AI 서비스 사용자별 일일 사용량 */
    // 사용 일자 (YYYYMMDD)
    private String usageDate;
    // 일일 사용 횟수
    private String usedCnt;

    /* AI 서비스 타입별 일일 사용 제한 설정 */
    // 일일 사용 가능 횟수
    private String availableCnt;
    private String createDt;

    // 사용량 체크
    private String usageChk;

    // 지역권한코드
    private String stAreaCd;

    // 관리자 유무 체크
    private String authFlag;
    private String mainDocFileId;
    private String mainPage;

    // 채팅 답변별 참조 문서 및 페이지 상세(TB_CHAT_REF)
    private String docFileId;
    // 문서 제목
    private String docTitle;
    // 문서 파일명
    private String fileName;
    // 관련 페이지 번호
    private String relatedPageNos;
    // 메인 페이지 번호
    private String mainPageNo;
    // 관련 페이지 번호
    private String relatedPages;
    /** 조회 시 메인 문서 파일 ID */
    private String showDocFileId;
    /** 조회 시 메인 페이지 번호 */
    private String showPageNo;

    private String statId;
    private String statNm;
    private String detailItemCd;
    private String detailItemNm;

    private String categoryId;

    /** 뉴스 관심 카테고리 저장 요청용 NC000001 CODE_ID 목록 */
    private List<String> newsCategoryCodeIdList;

    /** TB_USER_INTEREST_NEWS_CATEGORY (사용자당 1행, JSON 배열 문자열) */
    private String newscgId;
    private String newsCategoryCd;

    // 지식 카드 정보(TB_KNOW_CARD)
    private String cardId;
    private String tags;
    private String pinYn;
    private String archiveYn;
    private String archiveDt;
    private Integer sortOrd;
    private String sqlCode;
    private String thumbImg;


    /** Agent 서브 설정 (TB_AGT_SUB_CFG) */
    @Getter
    @Setter
    public static class AgtSubCfgVO {
        private String subCfgId;
        private String agentId;
        private String subTy;
        /** MyBatis JSON 컬럼(ADDITIONAL_CONFIG) 매핑용 */
        @JsonIgnore
        private String additionalConfig;
        @JsonProperty("additionalConfig")
        private Map<String, Object> additionalConfigMap;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    @Getter
    @Setter
    public static class KnowledgeItem {
        private String categoryId;
        private String userId;
        private String categoryNm;
        private String color;
        private Integer sortOrd;
        private String createDt;
    }

    /** 뉴스 추천 API 응답 카드 1건 (JSON 직렬화용). */
    @Getter
    @Setter
    public static class NewsRecommendCard {
        private int rank;
        private String source;
        private String title;
        private String category;
        private String summary;
        private String sourceUrl;
        private String imageUrl;
    }

    /** 연합뉴스 RSS 후보 기사 1건 (뉴스 큐레이션 수집·AI 프롬프트 매핑용). */
    @Getter
    @Setter
    public static class RssArticleRow {
        private int id;
        private String pressLabel;
        private String rssCategory;
        private String title;
        private String link;
        private String snippet;
        private String imageUrl;
    }

    // 고정 여부
    private String fixYn;

    /** TB_SHARE_TOKEN.SHARE_TOKEN */
    private String shareToken;
    /** TB_SHARE_TOKEN.EXPIRED_DT (조회 시 매핑, INSERT는 쿼리에서 DATE_ADD) */
    private String expiredDt;
    /** 공유 토큰 발급 시 첨부파일 공유 여부 (TB_SHARE_TOKEN.FILE_SHARE_YN, Y/N) */
    private String includeAttachment;
    /** 공유 로그 복사 요청 시 첨부파일 포함 여부 (Y: 파일 행 복사, N: 제외) */
    private String fileShareYn;

    /** TB_CHAT_FILE 채팅 첨부파일 */
    private Long chatFileId;
    /** orphan 처리 대상 chatFileId 목록 */
    private List<Long> chatFileIdList;
    private String storeFileName;
    private Long fileSize;
    private String fileType;
    private String expireDt;
    private String fileDelDt;

    /** 공유 로그 복사 시 원본 첨부의 CREATE_USER_ID (TB_CHAT_FILE) — insert 매핑용 */
    private String chatFileUploaderUserId;

    /**
     * selectChatLogList: 질문(LOG)별 TB_CHAT_FILE JSON 배열 문자열.
     * 각 원소는 {@link ChatAttachmentItem} 필드명과 동일한 JSON 키(chatFileId, fileName, filePath, mimeType, createUserId).
     */
    private String chatAttachmentList;

    /** selectChatLogList chatAttachmentList JSON 원소 구조 (TB_CHAT_FILE) */
    @Getter
    @Setter
    public static class ChatAttachmentItem {
        private String chatFileId;
        private String fileName;
        private String filePath;
        private String mimeType;
        /** TB_CHAT_FILE.CREATE_USER_ID (업로더) */
        private String createUserId;
    }

}
