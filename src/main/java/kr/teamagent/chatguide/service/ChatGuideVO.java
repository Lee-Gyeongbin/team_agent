package kr.teamagent.chatguide.service;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatGuideVO {

    private String guideId;
    private String guideTpCd;
    private String guideKey;
    private String enblYn;
    private String content;
    private String title;
    private String dsplCond;
    private String autoDetectYn;
    private String startDt;
    private String endDt;
    private String advanceNoticeCd;
    private String advanceNoticeNm;
    private String autoDsplYn;
    private Integer maxChars;
    private String modifyDt;

    /** 안내멘트 저장 요청용 (noticeSave.do) */
    @Getter
    @Setter
    public static class NoticeSaveVO {
        private ChatGuideVO feature;
        private ChatGuideVO guide;
        private ChatGuideVO limitation;
        private ChatGuideVO privacy;
    }

    /** 오류메시지 저장 요청용 (errorMessageSave.do) */
    @Getter
    @Setter
    public static class ErrorMessageSaveVO {
        private List<ChatGuideVO> apiErrors;
        private List<ChatGuideVO> inputErrors;
        private List<ChatGuideVO> responseErrors;
    }

    /** 점검/장애 묶음 저장 요청용 (maintenanceSave.do) — { dataList: [ ChatGuideVO, ... ] } */
    @Getter
    @Setter
    public static class MaintenanceSaveVO {
        private List<ChatGuideVO> dataList;
    }

}
