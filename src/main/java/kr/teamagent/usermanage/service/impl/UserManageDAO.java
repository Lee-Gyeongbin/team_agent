package kr.teamagent.usermanage.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.usermanage.service.UserManageVO;

@Repository
public class UserManageDAO extends EgovComAbstractDAO {

    /**
     * 사용자 목록 조회
     * @param searchVO
     * @return list
     * @throws Exception
     */
    public List<UserManageVO> selectUserList(UserManageVO searchVO) throws Exception {
        return selectList("userManage.selectUserList", searchVO);
    }

    /**
     * 사용자 정보 수정
     * @param userManageVO
     * @return 영향받은 행 수
     * @throws Exception
     */
    public int updateUser(UserManageVO userManageVO) throws Exception {
        return update("userManage.updateUser", userManageVO);
    }

    /**
     * 사용자 정보 삭제
     * @param userManageVO
     * @return 영향받은 행 수
     * @throws Exception
     */
    public int deleteUser(UserManageVO userManageVO) throws Exception {
        return update("userManage.deleteUser", userManageVO);
    }

    /**
     * 사용자 정보 복구
     * @param userManageVO
     * @return 영향받은 행 수
     * @throws Exception
     */
    public int restoreUser(UserManageVO userManageVO) throws Exception {
        return update("userManage.restoreUser", userManageVO);
    }
}