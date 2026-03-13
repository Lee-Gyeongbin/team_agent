package kr.teamagent.orgmanage.service.impl;

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
    
}
