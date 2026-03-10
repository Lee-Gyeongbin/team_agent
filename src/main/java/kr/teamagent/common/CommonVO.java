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

}
