package kr.teamagent.orgmanage.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.orgmanage.service.OrgManageVO;

@Service
public class OrgManageServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private OrgManageDAO orgManageDAO;

    /**
     * 조직 목록 조회
     * @param searchVO
     * @return list
     * @throws Exception
     */
    public List<OrgManageVO> selectOrgList(OrgManageVO searchVO) throws Exception {
        return orgManageDAO.selectOrgList(searchVO);
    }
}
