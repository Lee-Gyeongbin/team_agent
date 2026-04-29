package kr.teamagent.orgmanage.service.impl;

import java.util.HashMap;
import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.orgmanage.service.OrgManageVO;

@Repository
public class OrgManageDAO extends EgovComAbstractDAO {

    /**
     * 조직 목록 조회
     * @param searchVO
     * @return list
     * @throws Exception
     */
    public List<OrgManageVO> selectOrgList(OrgManageVO searchVO) throws Exception {
        return selectList("orgManage.selectOrgList", searchVO);
    }

    /**
     * 동일 부모 조직 목록 조회 (최상위는 parentOrgId null)
     * @param parentOrgId
     * @return list
     * @throws Exception
     */
    public List<OrgManageVO> selectParentOrgId(String parentOrgId) throws Exception {
        return selectList("orgManage.selectParentOrgId", parentOrgId);
    }

    /**
     * 사용자 목록 조회
     * @param orgId
     * @return list
     * @throws Exception
     */
    public List<OrgManageVO> selectUserList(String orgId) throws Exception {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("orgId", orgId);
        return selectList("orgManage.selectUserList", paramMap);
    }

    /**
     * 조직 등록
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    public int insertOrg(OrgManageVO orgManageVO) throws Exception {
        return insert("orgManage.insertOrg", orgManageVO);
    }

    /**
     * 동일 부모 조직 내 최대 정렬순서 조회
     * @param parentOrgId
     * @return max sort order
     * @throws Exception
     */
    public int selectMaxSortOrderByParentOrgId(String parentOrgId) throws Exception {
        return (int) selectOne("orgManage.selectMaxSortOrderByParentOrgId", parentOrgId);
    }

    /**
     * 최상위 조직(ORG_LEVEL=1) 최대 정렬순서 조회
     * @return max sort order
     * @throws Exception
     */
    public int selectMaxSortOrderByTopLevel() throws Exception {
        return (int) selectOne("orgManage.selectMaxSortOrderByTopLevel", null);
    }

    /**
     * 조직 수정
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    public int updateOrg(OrgManageVO orgManageVO) throws Exception {
        return update("orgManage.updateOrg", orgManageVO);
    }

    /**
     * 동일 부모 조직 정렬순서 일괄 수정
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    public int updateSiblingSortOrderBatch(OrgManageVO orgManageVO) throws Exception {
        return update("orgManage.updateSiblingSortOrderBatch", orgManageVO);
    }

    /**
     * 조직 단건 조회
     * @param orgId
     * @return vo
     * @throws Exception
     */
    public OrgManageVO selectOrgByOrgId(String orgId) throws Exception {
        return (OrgManageVO) selectOne("orgManage.selectOrgByOrgId", orgId);
    }

    /**
     * 조직 삭제
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    public int deleteOrg(OrgManageVO orgManageVO) throws Exception {
        return update("orgManage.deleteOrg", orgManageVO);
    }
}


