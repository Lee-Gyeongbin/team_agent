package kr.teamagent.orgmanage.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrgManageVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    private String orgId;
    private String orgNm;
    private String parentOrgId;
    private String orgLevel;
    private String sortOrder;
    private String useYn;
    private String createdDt;
    private String updatedDt;
}
