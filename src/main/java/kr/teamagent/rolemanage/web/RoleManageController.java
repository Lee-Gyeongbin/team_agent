package kr.teamagent.rolemanage.web;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import kr.teamagent.common.permission.RequirePermission;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.ExcelUtil;
import kr.teamagent.common.web.BaseController;
import kr.teamagent.rolemanage.service.RoleManageVO;
import kr.teamagent.rolemanage.service.impl.RoleManageServiceImpl;

@Controller
@RequestMapping("/rolemanage")
public class RoleManageController extends BaseController {

    @Autowired
    private RoleManageServiceImpl roleManageService;

    /* 권한 그룹 목록 조회 */
    @RequestMapping(value = "/selectRoleList.do", method = RequestMethod.GET)
    @RequirePermission(menu = "/role-manage", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> selectRoleList() {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("list", roleManageService.selectRoleList());
            return resultMap;
        } catch (Exception e) {
            log.error("[ROLE] selectRoleList 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 권한 그룹 단건 + 메뉴별 권한 조회 (roleId가 비어 있으면 신규 등록용 빈 권한) */
    @RequestMapping(value = "/selectRole.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/role-manage", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> selectRole(@RequestBody RoleManageVO searchVO) {
        try {
            Map<String, Object> resultMap = okResult();
            boolean isNew = CommonUtil.nullToBlank(searchVO.getRoleId()).trim().isEmpty();
            resultMap.put("data", isNew ? null : roleManageService.selectRole(searchVO.getRoleId()));
            resultMap.put("menuList", roleManageService.selectRoleMenuList(isNew ? null : searchVO.getRoleId()));
            return resultMap;
        } catch (Exception e) {
            log.error("[ROLE] selectRole 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 권한 그룹 저장 (등록/수정 + 메뉴별 권한) */
    @RequestMapping(value = "/saveRole.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/role-manage", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> saveRole(@RequestBody RoleManageVO.SaveRoleVO saveVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("roleId", roleManageService.saveRole(saveVO));
            return resultMap;
        } catch (Exception e) {
            log.error("[ROLE] saveRole 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 권한 그룹 삭제 */
    @RequestMapping(value = "/deleteRole.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/role-manage", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> deleteRole(@RequestBody RoleManageVO searchVO) {
        try {
            roleManageService.deleteRole(searchVO.getRoleId());
            return okResult();
        } catch (Exception e) {
            log.error("[ROLE] deleteRole 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 권한 그룹 사용자 목록 조회 */
    @RequestMapping(value = "/selectRoleUserList.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/role-manage", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> selectRoleUserList(@RequestBody RoleManageVO searchVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("list", roleManageService.selectRoleUserList(searchVO.getRoleId()));
            return resultMap;
        } catch (Exception e) {
            log.error("[ROLE] selectRoleUserList 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 조직(하위 조직 포함) 사용자 목록 조회 */
    @RequestMapping(value = "/selectOrgUserList.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/role-manage", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> selectOrgUserList(@RequestBody RoleManageVO.RoleUserVO searchVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("list", roleManageService.selectOrgUserList(searchVO.getOrgId()));
            return resultMap;
        } catch (Exception e) {
            log.error("[ROLE] selectOrgUserList 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 권한 그룹 사용자 저장 */
    @RequestMapping(value = "/saveRoleUser.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/role-manage", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> saveRoleUser(@RequestBody RoleManageVO.SaveRoleUserVO saveVO) {
        try {
            roleManageService.saveRoleUser(saveVO);
            return okResult();
        } catch (Exception e) {
            log.error("[ROLE] saveRoleUser 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 권한 그룹 엑셀 다운로드 */
    @RequestMapping(value = "/downloadRoleExcel.do", method = RequestMethod.GET)
    @RequirePermission(menu = "/role-manage", type = RequirePermission.Type.READ)
    public void downloadRoleExcel(HttpServletResponse response) throws Exception {
        roleManageService.downloadRoleExcel(response);
    }

    /* 권한 그룹 엑셀 업로드 */
    @RequestMapping(value = "/uploadRoleExcel.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/role-manage", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> uploadRoleExcel(@RequestParam("uploadFile") MultipartFile uploadFile) {
        try {
            return uploadResult(roleManageService.uploadRoleExcel(uploadFile));
        } catch (Exception e) {
            log.error("[ROLE] uploadRoleExcel 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 사용자별 권한 그룹 엑셀 다운로드 */
    @RequestMapping(value = "/downloadRoleUserExcel.do", method = RequestMethod.GET)
    @RequirePermission(menu = "/role-manage", type = RequirePermission.Type.READ)
    public void downloadRoleUserExcel(HttpServletResponse response) throws Exception {
        roleManageService.downloadRoleUserExcel(response);
    }

    /* 사용자별 권한 그룹 엑셀 업로드 */
    @RequestMapping(value = "/uploadRoleUserExcel.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/role-manage", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> uploadRoleUserExcel(@RequestParam("uploadFile") MultipartFile uploadFile) {
        try {
            return uploadResult(roleManageService.uploadRoleUserExcel(uploadFile));
        } catch (Exception e) {
            log.error("[ROLE] uploadRoleUserExcel 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 엑셀 업로드 결과 응답 (행 검증 실패가 있으면 successYn=false + 실패 안내) */
    private Map<String, Object> uploadResult(Map<String, Object> uploadResult) {
        boolean hasUploadFail = ExcelUtil.hasUploadFailures(uploadResult);
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", !hasUploadFail);
        if (hasUploadFail) {
            Object returnMsg = uploadResult.get("returnMsg");
            resultMap.put("returnMsg", returnMsg != null ? returnMsg : ExcelUtil.UPLOAD_FAIL_DEFAULT_MSG);
        }
        resultMap.put("data", uploadResult);
        return resultMap;
    }

    private Map<String, Object> okResult() {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        return resultMap;
    }

    private Map<String, Object> failResult(String message) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", false);
        resultMap.put("returnMsg", message);
        return resultMap;
    }

    /* DB 오류는 원문 대신 공용 실패 문구와 errorCode를 내려 프론트 공용 장애 안내(BaseController와 동일)가 처리하게 한다 */
    private Map<String, Object> failResult(Exception e) {
        if (!CommonUtil.isDbError(e)) {
            return failResult(e.getMessage());
        }
        Map<String, Object> resultMap = failResult(egovMessageSource.getMessage(AJAX_FAIL_MSG_CODE));
        resultMap.put("result", AJAX_FAIL);
        resultMap.put("errorCode", CommonUtil.RESP_DB_ERROR);
        return resultMap;
    }
}
