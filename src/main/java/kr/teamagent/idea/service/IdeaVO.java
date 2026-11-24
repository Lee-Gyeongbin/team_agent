package kr.teamagent.idea.service;

import java.util.List;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

/**
 * 아이디어 (TB_IDEA)
 * 구분: 공통코드 ID000002
 */
@Getter
@Setter
public class IdeaVO extends CommonVO {
    private static final long serialVersionUID = 1L;

    private String ideaId;
    private String ideaTypeCd;
    private String agentId;
    private String agentNm;
    private String title;
    private String content;
    private String statusCd;
    private Integer likeCnt;
    /** 로그인 사용자의 추천 여부 (Y/N) */
    private String likedYn;
    private String createDt;
    private String modifyDt;
    private String createId;
    private String createNm;
    private String modifyId;

    /** 조회 조건 — 구분 코드 */
    private String searchTypeCd;

    /** 첨부파일 목록 (상세) */
    private List<IdeaFileVO> fileList;
    /** 수정 시 삭제할 첨부파일 ID */
    private List<String> deleteFileIds;

    /** 아이디어 첨부파일 (TB_IDEA_FILE) */
    @Getter
    @Setter
    public static class IdeaFileVO {
        private String ideaFileId;
        private String ideaId;
        private String filePath;
        private String fileNm;
        private Long fileSize;
        private String fileType;
        private String createDt;
        private String createId;
    }

    /** 공통코드 항목 (구분·진행상태 선택 목록) */
    @Getter
    @Setter
    public static class IdeaCodeVO {
        private String codeId;
        private String codeNm;
    }
}
