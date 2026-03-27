package kr.teamagent.notice.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NoticeVO extends CommonVO {
    private static final long serialVersionUID = 1L;

    private String noticeId;
    private String title;
    private String content;
    private String pinYn;
    private Integer viewCnt;
    private String useYn;
    private String crtrId;
    private String createDt;
    private String modifyDt;
}
