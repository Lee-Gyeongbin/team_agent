package kr.teamagent.mypage.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.mypage.service.MyPageVO;

@Service
public class MyPageServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private MyPageDAO myPageDAO;

    /**
     * 마이페이지 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<MyPageVO> selectMyPageList(MyPageVO searchVO) throws Exception {
        return myPageDAO.selectMyPageList(searchVO);
    }

    /**
     * 마이페이지 수정
     * @param myPageVO
     * @return
     * @throws Exception
     */
    public int updateMyPage(MyPageVO myPageVO) throws Exception {
        return myPageDAO.updateMyPage(myPageVO);
    }

    /**
     * 마이페이지 비밀번호 수정
     * @param passwordChangeVO
     * @return
     * @throws Exception
     */
    public int updateMyPagePassword(MyPageVO.PasswordChangeVO passwordChangeVO) throws Exception {
        return myPageDAO.updateMyPagePassword(passwordChangeVO);
    }

    /**
     * 사용자 비밀번호 조회 (마이페이지 비밀번호 변경용)
     * @param passwordChangeVO
     * @return 암호화된 비밀번호
     * @throws Exception
     */
    public String selectUserPassword(MyPageVO.PasswordChangeVO passwordChangeVO) throws Exception {
        return myPageDAO.selectUserPassword(passwordChangeVO);
    }

    /**
     * 사용자 로그인 이력 조회
     * @param searchVO userId가 설정된 조회 조건
     * @return
     * @throws Exception
     */
    public List<MyPageVO.LoginHistoryVO> selectUserLoginHistory(MyPageVO.LoginHistoryVO searchVO) throws Exception {
        return myPageDAO.selectUserLoginHistory(searchVO);
    }
}
