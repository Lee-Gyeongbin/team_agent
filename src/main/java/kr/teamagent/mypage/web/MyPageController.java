package kr.teamagent.mypage.web;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.web.BaseController;
import kr.teamagent.mypage.service.MyPageVO;
import kr.teamagent.mypage.service.impl.MyPageServiceImpl;

@Controller
@RequestMapping("/mypage")
public class MyPageController extends BaseController<Object> {

    @Autowired
    private MyPageServiceImpl myPageService;

    /** 세션 기준 userId 적용 성공 */
    private static final int MYPAGE_AUTH_OK = 0;
    /** 비로그인 또는 세션에 userId 없음 */
    private static final int MYPAGE_AUTH_NO_SESSION = 1;

    private int applySessionUserId(MyPageVO vo) {
        String loginUserId = SessionUtil.getUserId();
        if (CommonUtil.isEmpty(loginUserId)) {
            return MYPAGE_AUTH_NO_SESSION;
        }
        vo.setUserId(loginUserId);
        return MYPAGE_AUTH_OK;
    }

    /**
     * 마이페이지 정보 조회
     */
    @RequestMapping(value = "/list.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView list(MyPageVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        int auth = applySessionUserId(searchVO);
        if (auth != MYPAGE_AUTH_OK) {
            resultMap.put("dataList", Collections.emptyList());
            return new ModelAndView("jsonView", resultMap);
        }
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
        int auth = applySessionUserId(myPageVO);
        if (auth != MYPAGE_AUTH_OK) {
            resultMap.put("data", 0);
            return new ModelAndView("jsonView", resultMap);
        }
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
        if (searchVO == null) {
            searchVO = new MyPageVO.LoginHistoryVO();
        }
        String loginUserId = SessionUtil.getUserId();
        if (CommonUtil.isEmpty(loginUserId)) {
            resultMap.put("dataList", Collections.emptyList());
            return new ModelAndView("jsonView", resultMap);
        }
        searchVO.setUserId(loginUserId);
        resultMap.put("dataList", myPageService.selectUserLoginHistory(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 프로필 이미지 업로드용 presigned URL 발급
     */
    @RequestMapping(value = "/prepareProfileImageUpload.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> prepareProfileImageUpload(@RequestBody MyPageVO myPageVO) {
        try {
            return myPageService.prepareProfileImageUpload(myPageVO);
        } catch (Exception e) {
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage() != null ? e.getMessage() : "요청사항을 실패하였습니다.");
            return resultMap;
        }
    }

    /**
     * 프로필 이미지 경로 저장
     */
    @RequestMapping(value = "/updateUserProfileImg.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> updateUserProfileImg(@RequestBody MyPageVO myPageVO) {
        try {
            return myPageService.updateUserProfileImg(myPageVO);
        } catch (Exception e) {
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage() != null ? e.getMessage() : "요청사항을 실패하였습니다.");
            return resultMap;
        }
    }

    /**
     * 프로필 이미지 미리보기 URL 조회
     */
    @RequestMapping(value = "/viewUserProfileImg.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> viewUserProfileImg(@RequestBody(required = false) MyPageVO searchVO) {
        try {
            return myPageService.viewUserProfileImg(searchVO == null ? new MyPageVO() : searchVO);
        } catch (Exception e) {
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("viewType", "DOWNLOAD");
            resultMap.put("reason", "ERROR");
            resultMap.put("fileName", "");
            resultMap.put("downloadUrl", "");
            return resultMap;
        }
    }

}
