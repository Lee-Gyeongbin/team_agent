package kr.teamagent.loginhistory.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.loginhistory.service.LoginHistoryVO;

@Service
public class LoginHistoryServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private LoginHistoryDAO loginHistoryDAO;

    /**
     * 로그인 히스토리 목록 조회
     * @param searchVO
     * @return list
     * @throws Exception
     */
    public List<LoginHistoryVO> selectLoginHistoryList(LoginHistoryVO searchVO) throws Exception {
        return loginHistoryDAO.selectLoginHistoryList(searchVO);
    }
}
