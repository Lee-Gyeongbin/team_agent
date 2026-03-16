package kr.teamagent.usermanage.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.exception.DuplicateEmailException;
import kr.teamagent.common.exception.DuplicateUserIdException;
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
     * 생성 시 동일 ID가 이미 존재하는지 여부
     * @param userId 생성하려는 사용자 ID
     * @return true면 중복
     */
    public boolean isDuplicateUserIdForInsert(String userId) throws Exception {
        UserManageVO vo = new UserManageVO();
        vo.setUserId(userId);
        return userManageDAO.countUserByUserId(vo) > 0;
    }

    /**
     * 생성 시 동일 이메일이 이미 존재하는지 여부
     * @param email 생성하려는 이메일
     * @return true면 중복
     */
    public boolean isDuplicateEmailForInsert(String email) throws Exception {
        UserManageVO vo = new UserManageVO();
        vo.setEmail(email);
        return userManageDAO.countUserByEmail(vo) > 0;
    }

    /**
     * 수정 시 동일 이메일을 다른 사용자가 사용 중인지 여부
     * @param userId 이메일을 변경하려는 사용자 ID
     * @param email 수정하려는 이메일
     * @return true면 중복
     */
    public boolean isDuplicateEmailForUpdate(String userId, String email) throws Exception {
        UserManageVO vo = new UserManageVO();
        vo.setUserId(userId);
        vo.setEmail(email);
        return userManageDAO.countUserByEmailExcludingUserId(vo) > 0;
    }

    /**
     * 사용자 정보 생성
     * @param userManageVO
     * @return 생성된 행 수
     * @throws Exception
     */
    public int insertUser(UserManageVO userManageVO) throws Exception {
        String userId = userManageVO.getUserId();
        if (isDuplicateUserIdForInsert(userId)) {
            throw new DuplicateUserIdException();
        }

        String email = userManageVO.getEmail();
        if (email != null && !email.isEmpty() && isDuplicateEmailForInsert(email)) {
            throw new DuplicateEmailException();
        }

        return userManageDAO.insertUser(userManageVO);
    }

    /**
     * 사용자 정보 수정
     * @param userManageVO
     * @return 수정된 행 수
     * @throws Exception
     */
    public int updateUser(UserManageVO userManageVO) throws Exception {
        String userId = userManageVO.getUserId();
        String email = userManageVO.getEmail();
        if (email != null && !email.isEmpty() && isDuplicateEmailForUpdate(userId, email)) {
            throw new DuplicateEmailException();
        }
        return userManageDAO.updateUser(userManageVO);
    }

    /**
     * 사용자 정보 삭제
     * @param userManageVO
     * @return 삭제된 행 수
     * @throws Exception
     */
    public int deleteUser(UserManageVO userManageVO) throws Exception {
        return userManageDAO.deleteUser(userManageVO);
    }
    
    /**
     * 사용자 정보 복구
     * @param userManageVO
     * @return 복구된 행 수
     * @throws Exception
     */
    public int restoreUser(UserManageVO userManageVO) throws Exception {
        return userManageDAO.restoreUser(userManageVO);
    }

    /**
     * 사용자 비밀번호 초기화
     * @param userManageVO
     * @return 초기화된 행 수
     * @throws Exception
     */
    public int resetPassword(UserManageVO userManageVO) throws Exception {
        return userManageDAO.resetPassword(userManageVO);
    }
}