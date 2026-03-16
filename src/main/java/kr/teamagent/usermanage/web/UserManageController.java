package kr.teamagent.usermanage.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;
import org.apache.commons.lang3.RandomStringUtils;

import kr.teamagent.common.exception.DuplicateEmailException;
import kr.teamagent.common.exception.DuplicateUserIdException;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.web.BaseController;
import kr.teamagent.usermanage.service.UserManageVO;
import kr.teamagent.usermanage.service.impl.UserManageServiceImpl;

@Controller
@RequestMapping(value = { "/usermanage" })
public class UserManageController extends BaseController {

    @Autowired
    private UserManageServiceImpl userManageService;

    @SuppressWarnings("deprecation")
    private final StandardPasswordEncoder passwordEncoder = new StandardPasswordEncoder();

    /**
     * 사용자 목록 조회
     * @param searchVO
     * @return jsonView (list)
     * @throws Exception
     */
    @RequestMapping(value = "/selectUserList.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView list(UserManageVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", userManageService.selectUserList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }


    /**
     * 사용자 정보 생성
     * @param userManageVO
     * @return Map (successYn, returnMsg, data: 영향받은 행 수)
     * @throws Exception
     */
    @RequestMapping(value = "/insertUser.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> insert(@RequestBody UserManageVO userManageVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            String loginUserId = SessionUtil.getUserId();
            if (loginUserId != null) {
                userManageVO.setCrtrId(loginUserId);
            }
            String tempPassword = generateTempPassword(10);
            userManageVO.setPasswd(passwordEncoder.encode(tempPassword));

            resultMap.put("successYn", true);
            resultMap.put("data", userManageService.insertUser(userManageVO));
            resultMap.put("tempPassword", tempPassword);
        } catch (DuplicateEmailException e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage());
        } catch (DuplicateUserIdException e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage());
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage() != null ? e.getMessage() : "요청사항을 실패하였습니다.");
        }
        return resultMap;
    }

    /**
     * 사용자 정보 수정
     * @param userManageVO
     * @return Map (successYn, returnMsg, data: 영향받은 행 수)
     * @throws Exception
     */
    @RequestMapping(value = "/updateUser.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> update(@RequestBody UserManageVO userManageVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            String loginUserId = SessionUtil.getUserId();
            if (loginUserId != null) {
                userManageVO.setMdfrId(loginUserId);
            }

            if (CommonUtil.nullToBlank(userManageVO.getOrgId()).isEmpty()) userManageVO.setOrgId(null);

            resultMap.put("successYn", true);
            resultMap.put("data", userManageService.updateUser(userManageVO));
        } catch (DuplicateEmailException e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage());
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage() != null ? e.getMessage() : "요청사항을 실패하였습니다.");
        }
        return resultMap;
    }

    /**
     * 사용자 정보 삭제
     * @param userManageVO
     * @return Map (successYn, returnMsg, data: 영향받은 행 수)
     * @throws Exception
     */
    @RequestMapping(value = "/deleteUser.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> delete(@RequestBody UserManageVO userManageVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (CommonUtil.nullToBlank(userManageVO.getUserId()).isEmpty()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "userId는 필수입니다.");
                return resultMap;
            }
            resultMap.put("successYn", true);
            resultMap.put("data", userManageService.deleteUser(userManageVO));
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage() != null ? e.getMessage() : "요청사항을 실패하였습니다.");
        }
        return resultMap;
    }

    /**
     * 사용자 정보 복구
     * @param userManageVO
     * @return Map (successYn, returnMsg, data: 영향받은 행 수)
     * @throws Exception
     */
    @RequestMapping(value = "/restoreUser.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> restore(@RequestBody UserManageVO userManageVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (CommonUtil.nullToBlank(userManageVO.getUserId()).isEmpty()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "userId는 필수입니다.");
                return resultMap;
            }
            resultMap.put("successYn", true);
            resultMap.put("data", userManageService.restoreUser(userManageVO));
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage() != null ? e.getMessage() : "요청사항을 실패하였습니다.");
        }
        return resultMap;
    }

    /**
     * 사용자 비밀번호 초기화
     * @param userManageVO
     * @return Map (successYn, returnMsg, data: 초기화된 행 수)
     * @throws Exception
     */
    @RequestMapping(value = "/resetPassword.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> resetPassword(@RequestBody UserManageVO userManageVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            String userId = CommonUtil.nullToBlank(userManageVO.getUserId());
            if (userId.isEmpty()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "userId는 필수입니다.");
                return resultMap;
            }

            String tempPassword = generateTempPassword(10);
            userManageVO.setPasswd(passwordEncoder.encode(tempPassword));

            int updatedRows = userManageService.resetPassword(userManageVO);

            resultMap.put("successYn", updatedRows > 0);
            if (updatedRows > 0) {
                resultMap.put("data", updatedRows);
                resultMap.put("tempPassword", tempPassword);
            } else {
                resultMap.put("returnMsg", "해당 userId의 사용자를 찾을 수 없습니다.");
            }
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage() != null ? e.getMessage() : "요청사항을 실패하였습니다.");
        }
        return resultMap;
    }

    private String generateTempPassword(int length) {
        return RandomStringUtils.randomAlphanumeric(length);
    }
}