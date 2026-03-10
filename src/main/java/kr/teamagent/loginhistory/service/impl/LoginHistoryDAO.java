package kr.teamagent.loginhistory.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.loginhistory.service.LoginHistoryVO;

@Repository
public class LoginHistoryDAO extends EgovComAbstractDAO {
    

    public List<LoginHistoryVO> selectLoginHistoryList(LoginHistoryVO searchVO) throws Exception {
        return selectList("loginhistory.selectLoginHistoryList", searchVO);
    }

}