package kr.teamagent.usermanage.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.usermanage.service.UserManageVO;

@Service
public class UserManageServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private UserManageDAO userManageDAO;

    /**
     * 사용자 목록 조회
     * @param searchVO
     * @return list
     * @throws Exception
     */
    public List<UserManageVO> selectUserList(UserManageVO searchVO) throws Exception {
        return userManageDAO.selectUserList(searchVO);
    }

    /**
     * 사용자 정보 수정
     * @param userManageVO
     * @return 영향받은 행 수
     * @throws Exception
     */
    public int updateUser(UserManageVO userManageVO) throws Exception {
        return userManageDAO.updateUser(userManageVO);
    }

    /**
     * 사용자 정보 삭제
     * @param userManageVO
     * @return 영향받은 행 수
     * @throws Exception
     */
    public int deleteUser(UserManageVO userManageVO) throws Exception {
        return userManageDAO.deleteUser(userManageVO);
    }
    
    /**
     * 사용자 정보 복구
     * @param userManageVO
     * @return 영향받은 행 수
     * @throws Exception
     */
    public int restoreUser(UserManageVO userManageVO) throws Exception {
        return userManageDAO.restoreUser(userManageVO);
    }
}