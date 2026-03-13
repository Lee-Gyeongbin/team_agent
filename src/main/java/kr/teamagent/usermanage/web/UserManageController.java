package kr.teamagent.usermanage.web;

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
import kr.teamagent.common.web.BaseController;
import kr.teamagent.usermanage.service.UserManageVO;
import kr.teamagent.usermanage.service.impl.UserManageServiceImpl;

@Controller
@RequestMapping(value = { "/usermanage" })
public class UserManageController extends BaseController {

    @Autowired
    private UserManageServiceImpl userService;

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
        resultMap.put("list", userService.selectUserList(searchVO));
        return new ModelAndView("jsonView", resultMap);
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
            String userId = CommonUtil.nullToBlank(userManageVO.getUserId());
            String userNm = CommonUtil.nullToBlank(userManageVO.getUserNm());
            String email = CommonUtil.nullToBlank(userManageVO.getEmail());
            String phone = CommonUtil.nullToBlank(userManageVO.getPhone());
            if (userId.isEmpty()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "userId는 필수입니다.");
                return resultMap;
            }
            if (userNm.isEmpty()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "사용자명은 필수입니다.");
                return resultMap;
            }
            if (email.isEmpty()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "이메일은 필수입니다.");
                return resultMap;
            }
            if (phone.isEmpty()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "연락처는 필수입니다.");
                return resultMap;
            }
            if (CommonUtil.nullToBlank(userManageVO.getOrgId()).isEmpty()) userManageVO.setOrgId(null);
            if (userService.isDuplicateEmailForUpdate(userId, email)) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "이미 가입된 이메일입니다.");
                return resultMap;
            }

            resultMap.put("successYn", true);
            resultMap.put("data", userService.updateUser(userManageVO));
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
            resultMap.put("data", userService.deleteUser(userManageVO));
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
            resultMap.put("data", userService.restoreUser(userManageVO));
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", e.getMessage() != null ? e.getMessage() : "요청사항을 실패하였습니다.");
        }
        return resultMap;
    }
}