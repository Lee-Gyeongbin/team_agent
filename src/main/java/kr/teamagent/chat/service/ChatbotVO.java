package kr.teamagent.chat.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatbotVO extends CommonVO {

    private Long roomId;
    private String roomTitle;
    /* AI 챗봇 질문/응답 로그 */
    private Long logId;
    // AI 서비스 타입
    private String svcTy;
    private String svcTyNm;
    // 질문 내용
    private String qContent;
    // 응답 내용
    private String rContent;
    // 만족 여부 (Y/N)
    private String satisYn;
    // 파일 경로
    private String filePath;
    // 페이지
    private int page;
    // 변환쿼리
    private String ttsq;

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
}
