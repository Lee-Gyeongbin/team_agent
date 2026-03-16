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
     * 사용자 ID 중복 여부 조회 (생성 시 사용)
     * @param userManageVO userId
     * @return 중복 건수
     */
    public int countUserByUserId(UserManageVO userManageVO) throws Exception {
        Integer cnt = (Integer) selectOne("userManage.countUserByUserId", userManageVO);
        return cnt != null ? cnt : 0;
    }

    /**
     * 사용자 이메일 중복 여부 조회 (생성 시 사용)
     * @param userManageVO email
     * @return 중복 건수
     */
    public int countUserByEmail(UserManageVO userManageVO) throws Exception {
        Integer cnt = (Integer) selectOne("userManage.countUserByEmail", userManageVO);
        return cnt != null ? cnt : 0;
    }

    /**
     * 수정 시 이메일 중복 여부 조회
     * @param userManageVO userId, email
     * @return 중복 여부
     */
    public int countUserByEmailExcludingUserId(UserManageVO userManageVO) throws Exception {
        Integer cnt = (Integer) selectOne("userManage.countUserByEmailExcludingUserId", userManageVO);
        return cnt != null ? cnt : 0;
    }

    /**
     * 사용자 정보 생성
     * @param userManageVO
     * @return 영향받은 행 수
     * @throws Exception
     */
    public int insertUser(UserManageVO userManageVO) throws Exception {
        return insert("userManage.insertUser", userManageVO);
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

    /**
     * 사용자 비밀번호 초기화
     * @param userManageVO
     * @return 초기화된 행 수
     * @throws Exception
     */
    public int resetPassword(UserManageVO userManageVO) throws Exception {
        return update("userManage.resetPassword", userManageVO);
    }
}