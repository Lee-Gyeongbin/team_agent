package kr.teamagent.mypage.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.mypage.service.MyPageVO;
import kr.teamagent.mypage.service.impl.MyPageServiceImpl;

@Controller
@RequestMapping("/mypage")
public class MyPageController extends BaseController<Object> {

    @Autowired
    private MyPageServiceImpl myPageService;

    /**
     * 마이페이지 정보 조회
     */
    @RequestMapping(value = "/list.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView list(MyPageVO searchVO) throws Exception {
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
    public Map<String, Object> updateMyPagePassword(@RequestBody MyPageVO.PasswordChangeVO passwordChangeVO) {
        try {
            return myPageService.changePassword(passwordChangeVO);
        } catch (Exception e) {
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage() != null ? e.getMessage() : "요청사항을 실패하였습니다.");
            return resultMap;
        }
    }

    /**
     * 사용자 로그인 이력 조회 (세션 사용자 기준, 본문: fromDt, toDt, ipAddr, result — 빈 문자열은 미적용)
     */
    @RequestMapping(value = "/selectUserLoginHistory.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView selectUserLoginHistory(@RequestBody(required = false) MyPageVO.LoginHistoryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", myPageService.selectUserLoginHistory(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 프로필 이미지 업로드용 presigned URL 발급
     */
    @RequestMapping(value = "/prepareProfileImageUpload.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> prepareProfileImageUpload(@RequestBody(required = false) MyPageVO myPageVO) {
        return myPageService.prepareProfileImageUpload(myPageVO == null ? new MyPageVO() : myPageVO);
    }

    /**
     * 프로필 이미지 경로 저장
     */
    @RequestMapping(value = "/updateUserProfileImg.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> updateUserProfileImg(@RequestBody(required = false) MyPageVO myPageVO) {
        return myPageService.updateUserProfileImg(myPageVO == null ? new MyPageVO() : myPageVO);
    }

    /**
     * 프로필 이미지 미리보기 URL 조회
     */
    @RequestMapping(value = "/viewUserProfileImg.do", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> viewUserProfileImg(@RequestBody(required = false) MyPageVO searchVO) {
        return myPageService.viewUserProfileImg(searchVO == null ? new MyPageVO() : searchVO);
    }

    /**
     * 프로필 이미지 삭제
     */
    @RequestMapping(value = "/deleteUserProfileImg.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> deleteUserProfileImg(@RequestBody(required = false) MyPageVO myPageVO) {
        return myPageService.deleteUserProfileImg(myPageVO == null ? new MyPageVO() : myPageVO);
    }
}
