package kr.teamagent.codes.service;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CodesVO {

    /**
     * 코드 그룹 ID [TB_CODE_GRP]
     */
    private String codeGrpId;
    private String codeGrpNm;
    private String description;
    private String useYn;
    private String createDt;

    /**
     * 코드 ID [TB_CODE]
     */
    private String codeId;
    private String codeNm;
    private Integer sortOrd;

}
