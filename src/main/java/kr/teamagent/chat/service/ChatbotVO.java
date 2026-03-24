package kr.teamagent.chat.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatbotVO extends CommonVO {

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
    // AI 서비스 타입
    private String svcTy;
    private String svcTyNm;
    // 참조 타겟 ID (RAG DATASET_ID, SQL DATAMART_ID 등)
    private String refId;
    // 질문 내용
    private String qContent;
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
    // SQL
    private String sql;

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

    // 채팅 답변별 참조 문서 및 페이지 상세(TB_CHAT_REF)
    // 문서 고유 ID 
    private String docId;
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

    private String statId;
    private String statNm;
    private String detailItemCd;
    private String detailItemNm;
}
