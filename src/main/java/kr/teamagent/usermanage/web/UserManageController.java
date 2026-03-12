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

        // ORG_ID 빈 문자열이면 null로 설정 (FK TB_ORG 참조 오류 방지)
        String orgId = CommonUtil.nullToBlank(userManageVO.getOrgId());
        if (orgId.isEmpty()) {
            userManageVO.setOrgId(null);
        }

        try {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            resultMap.put("data", userService.updateUser(userManageVO));
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

    /**
     * 사용자 정보 삭제
     * @param userManageVO
     * @return jsonView (result: 영향받은 행 수)
     * @throws Exception
     */
    @RequestMapping(value = "/deleteUser.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView delete(@RequestBody UserManageVO userManageVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();

        String userId = CommonUtil.nullToBlank(userManageVO.getUserId());
        if (userId.isEmpty()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "userId는 필수입니다.");
            return new ModelAndView("jsonView", resultMap);
        }

        resultMap.put("result", userService.deleteUser(userManageVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 사용자 정보 복구
     * @param userManageVO
     * @return jsonView (result: 영향받은 행 수)
     * @throws Exception
     */
    @RequestMapping(value = "/restoreUser.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView restore(@RequestBody UserManageVO userManageVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();

        String userId = CommonUtil.nullToBlank(userManageVO.getUserId());
        if (userId.isEmpty()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "userId는 필수입니다.");
            return new ModelAndView("jsonView", resultMap);
        }

        resultMap.put("result", userService.restoreUser(userManageVO));
        return new ModelAndView("jsonView", resultMap);
    }
}