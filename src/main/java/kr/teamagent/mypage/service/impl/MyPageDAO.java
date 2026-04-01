package kr.teamagent.mypage.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.mypage.service.MyPagePasswordChangeVO;
import kr.teamagent.mypage.service.MyPageVO;

@Repository
public class MyPageDAO extends EgovComAbstractDAO {

    public List<MyPageVO> selectMyPageList(MyPageVO searchVO) throws Exception {
        return selectList("mypage.selectMyPageList", searchVO);
    }

    public int updateMyPage(MyPageVO myPageVO) throws Exception {
        return update("mypage.updateMyPage", myPageVO);
    }

    public int updateMyPagePassword(MyPagePasswordChangeVO passwordChangeVO) throws Exception {
        return update("mypage.updateMyPagePassword", passwordChangeVO);
    }

    public String selectUserPassword(MyPagePasswordChangeVO passwordChangeVO) throws Exception {
        return (String) selectOne("mypage.selectUserPassword", passwordChangeVO);
    }
}
