package kr.teamagent.mypage.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.mypage.service.MyPagePasswordChangeVO;
import kr.teamagent.mypage.service.MyPageVO;
import kr.teamagent.mypage.service.impl.MyPageServiceImpl;

@Controller
@RequestMapping("/mypage")
public class MyPageController extends BaseController<Object> {

    @Autowired
    private MyPageServiceImpl myPageService;

    @SuppressWarnings("deprecation")
    private final StandardPasswordEncoder passwordEncoder = new StandardPasswordEncoder();

    /**
     * 마이페이지 정보 조회
     */
    @RequestMapping(value = "/list.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView list(@RequestBody MyPageVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", myPageService.selectMyPageList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 마이페이지 기본 정보 수정
     */
    @RequestMapping(value = "/update.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateMyPage(@RequestBody MyPageVO myPageVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", myPageService.updateMyPage(myPageVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 마이페이지 비밀번호 수정
     * @return Map (successYn, returnMsg, data: 변경된 행 수)
     */
    @RequestMapping(value = "/changePassword.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> updateMyPagePassword(@RequestBody MyPagePasswordChangeVO passwordChangeVO) {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            String currentPasswd = passwordChangeVO.getOldPassword();
            String newPasswd = passwordChangeVO.getNewPassword();

            // 현재 로그인 사용자 기준으로 userId 설정 (요청 바디의 userId는 신뢰하지 않음)
            String loginUserId = kr.teamagent.common.util.SessionUtil.getUserId();
            passwordChangeVO.setUserId(loginUserId);

            // DB에 저장된 암호화된 비밀번호 조회
            String encodedPassword = myPageService.selectUserPassword(passwordChangeVO);
            if (encodedPassword == null || !passwordEncoder.matches(currentPasswd, encodedPassword)) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "현재 비밀번호가 일치하지 않습니다.");
                return resultMap;
            }

            // 기존 SQL 매핑 유지를 위해 신규 비밀번호를 암호화하여 passwd 필드에 세팅
            passwordChangeVO.setPasswd(passwordEncoder.encode(newPasswd));

            int updatedRows = myPageService.updateMyPagePassword(passwordChangeVO);

            resultMap.put("successYn", updatedRows > 0);
            if (updatedRows > 0) {
                resultMap.put("data", updatedRows);
            } else {
                resultMap.put("returnMsg", "해당 userId의 사용자를 찾을 수 없습니다.");
            }
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage() != null ? e.getMessage() : "요청사항을 실패하였습니다.");
        }
        return resultMap;
    }

}
