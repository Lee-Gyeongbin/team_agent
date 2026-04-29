package kr.teamagent.orgmanage.service;

import java.util.List;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

/**
 * 조직/사용자 화면용 VO. {@code selectOrgList} 는 조직 컬럼만,
 * {@code selectUserList} 는 사용자 컬럼을 채운다.
 */
@Getter
@Setter
public class OrgManageVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    /** TB_ORG.ORG_ID */
    private String orgId;
    private String orgNm;
    private String parentOrgId;
    private String orgLevel;
    private String sortOrder;
    private String useYn;
    private String createdDt;
    private String updatedDt;
    private String userId;
    private String userNm;
    private String email;
    private String phone;
    private String profileImgPath;
    private String profileImgUrl;
    private String acctStatusCd;
    private String acctStatusDesc;

    /** 동일 부모 내 정렬 일괄 반영용 (orgManage.updateSiblingSortOrderBatch) */
    private List<OrgSortOrderItem> items;

    @Getter
    @Setter
    public static class OrgSortOrderItem {
        private String orgId;
        private String sortOrder;
    }
}
