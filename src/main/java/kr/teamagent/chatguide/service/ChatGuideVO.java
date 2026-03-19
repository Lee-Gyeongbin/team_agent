package kr.teamagent.chatguide.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatGuideVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    private String guideId;
    private String guideTpCd;
    private String guideKey;
    private String enblYn;
    private String content;
    private String dsplCond;
    private String startDt;
    private String endDt;
    private String createDt;
    private Integer maxChars;
    private String modifyDt;

}
