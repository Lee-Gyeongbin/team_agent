package kr.teamagent.common;

import egovframework.com.cmm.ComDefaultVO;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class CommonVO extends ComDefaultVO {

    private static final long serialVersionUID = 924024280774492177L;

    private String userId;
    private String userNm;
    private String atchFileId;

    @Getter
    @Setter
    public static class ColorVO {
        private String colorId;
        private String colorNm;
        private String colorKey;
        private String colorHex;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    @Getter
    @Setter
    public static class IconVO {
        private String iconId;
        private String iconNm;
        private String iconClassNm;
        private String iconFileNm;
        private String iconFilePath;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

    /** 알림 [TB_NOTIFY] */
    @Getter
    @Setter
    public static class NotifyVO {
        private String notifyId;
        private String userId;
        private String sendUserId;
        private String sendUserNm;
        private String notifyTyCd;
        private String title;
        private String content;
        private String refId;
        private String readYn;
        private String readDt;
        private String useYn;
        private String createDt;
        private String modifyDt;
    }

}
