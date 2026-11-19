package kr.teamagent.inq.service;

import java.util.List;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

/** 문의사항 [TB_INQ] — 요청·응답 키는 DB 컬럼의 camelCase */
@Getter
@Setter
public class InqVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    private String inqId;
    private String title;
    private String content;
    /** 구분 코드 (공통코드 IS000002) — 목록 조회에서는 검색 조건으로도 사용 */
    private String catCd;
    private String catNm;
    private String agentId;
    private String agentNm;
    /** 처리상태 코드 (공통코드 IS000001) — 목록 조회에서는 검색 조건으로도 사용 */
    private String statusCd;
    private String statusNm;
    private String secretYn;
    private String pinYn;
    private String useYn;
    private String crtrId;
    private String crtrNm;
    private String createDt;
    private String modifyDt;
    /** 관리자 답변(DEPTH 1) 등록 여부 Y/N */
    private String answerYn;
    /** 비밀글 마스킹 여부 Y/N — 작성자·관리자 화면이 아니면 내용을 내려주지 않는다 */
    private String maskedYn;
    /** 관리자 화면 조회 여부 Y/N — 서버에서만 지정한다 */
    private String manageYn;

    /** 등록·수정 시 새로 첨부한 파일 메타 (업로드는 presigned URL로 선행) */
    private List<FileVO> files;
    /** 수정 시 제거할 첨부파일 ID 목록 */
    private List<String> removeFileIds;

    /** 문의 답변·댓글 [TB_INQ_ANSR] */
    @Getter
    @Setter
    public static class AnsrVO extends CommonVO {

        private static final long serialVersionUID = 1L;

        private String ansrId;
        private String inqId;
        private String upAnsrId;
        private String content;
        private String ansrUserId;
        private String ansrUserNm;
        private Integer depth;
        private Integer sortOrd;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    /** 공통코드 [TB_CODE] — flag 는 화면 배지 구분값(서버가 코드값으로 지정) */
    @Getter
    @Setter
    public static class CodeVO extends CommonVO {

        private static final long serialVersionUID = 1L;

        private String codeId;
        private String codeNm;
        private String flag;
    }

    /** 문의 첨부파일 [TB_INQ_FILE] */
    @Getter
    @Setter
    public static class FileVO extends CommonVO {

        private static final long serialVersionUID = 1L;

        private String inqFileId;
        private String inqId;
        private String filePath;
        private String fileNm;
        private Long fileSize;
        private String fileType;
        private String createDt;
        private String modifyDt;
        private String createId;
        private String modifyId;
    }
}
