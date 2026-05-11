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
    private String cardId;
    private String refId;

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

    /** 공유 지식 카드 [TB_KNOW_CARD] */
    @Getter
    @Setter
    public static class SharedCardVO {
        private String cardId;
        private String userId;
        private String categoryId;
        private String logId;
        private String svcTy;
        private String title;
        private String tags;
        private String pinYn;
        private String archiveYn;
        private String archiveDt;
        private Integer sortOrd;
        private String sqlCode;
        private String newYn;
        private String thumbImg;
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
        private String saveYn;
        /** 공유 받은 지식 카드 저장 시 대상 카테고리 ID */
        private String categoryId;
    }

}
