package kr.teamagent.idea.service;

import java.util.List;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

/**
 * 아이디어 (TB_IDEA) — 사용자(아이디어)·관리자(아이디어 관리) 공통
 * 구분: 공통코드 ID000002 / 진행상태: 공통코드 ID000001
 */
@Getter
@Setter
public class IdeaVO extends CommonVO {
    private static final long serialVersionUID = 1L;

    private String ideaId;
    private String ideaTypeCd;
    private String ideaTypeNm;
    private String agentId;
    private String agentNm;
    private String title;
    private String content;
    private String statusCd;
    private String statusNm;
    private Integer likeCnt;
    /** 로그인 사용자의 추천 여부 (Y/N) */
    private String likedYn;
    private String cmtContent;
    private String cmtUserId;
    private String cmtUserNm;
    private String cmtDt;
    /** 관리자 코멘트 존재 여부 (Y/N) */
    private String cmtYn;
    private String createDt;
    private String modifyDt;
    private String createId;
    private String createNm;
    private String modifyId;

    /** 조회 조건 — 구분 코드 / 진행상태 코드 */
    private String searchTypeCd;
    private String searchStatusCd;

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

    /** 에이전트 선택 목록 (TB_AGT, USE_YN = 'Y') */
    @Getter
    @Setter
    public static class IdeaAgentVO {
        private String agentId;
        private String agentNm;
    }
}
