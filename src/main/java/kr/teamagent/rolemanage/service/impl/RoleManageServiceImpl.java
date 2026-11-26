package kr.teamagent.rolemanage.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.ExcelUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.rolemanage.service.RoleManageVO;

@Service
public class RoleManageServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(RoleManageServiceImpl.class);

    private static final String YES = "Y";
    private static final String NO = "N";

    private static final String ROLE_HDR_ROLE_ID = "권한ID";
    private static final String ROLE_HDR_ROLE_NAME = "권한명 *";
    private static final String ROLE_HDR_DESCRIPTION = "설명";
    private static final String ROLE_HDR_USE_YN = "사용여부";
    private static final String ROLE_HDR_DEFAULT_YN = "기본적용";
    private static final String[] ROLE_EXCEL_HEADERS = {
            ROLE_HDR_ROLE_ID, ROLE_HDR_ROLE_NAME, ROLE_HDR_DESCRIPTION, ROLE_HDR_USE_YN, ROLE_HDR_DEFAULT_YN
    };
    /** ExcelUtil.applyHeaderRow 회색 헤더용 — 권한ID는 자동 생성(참고용) */
    private static final int[] ROLE_AUTO_GEN_HEADER_COLS = { 0 };
    private static final int ROLE_USE_YN_COL_IDX = 3;
    private static final int ROLE_DEFAULT_YN_COL_IDX = 4;
    private static final String ROLE_EXCEL_GUIDE_TEXT =
            "※ * 표시된 항목은 필수 입력값입니다.\n"
                    + "※ 권한ID는 자동 생성(참고용)이며, 권한ID가 기존에 있으면 수정, 비어 있으면 신규 등록됩니다. 메뉴별 권한은 화면에서 설정합니다.\n"
                    + "  사용여부·기본적용은 Y/N만 입력하세요.";

    private static final String USER_ROLE_HDR_USER_ID = "사용자ID *";
    private static final String USER_ROLE_HDR_USER_NM = "사용자명";
    private static final String USER_ROLE_HDR_EMAIL = "이메일";
    private static final String USER_ROLE_HDR_ORG_NM = "소속조직";
    private static final String USER_ROLE_HDR_ROLE_NAME = "권한그룹 *";
    private static final String[] USER_ROLE_EXCEL_HEADERS = {
            USER_ROLE_HDR_USER_ID, USER_ROLE_HDR_USER_NM, USER_ROLE_HDR_EMAIL, USER_ROLE_HDR_ORG_NM,
            USER_ROLE_HDR_ROLE_NAME
    };
    /** 사용자명·이메일·소속조직은 참고용 회색 헤더 */
    private static final int[] USER_ROLE_AUTO_GEN_HEADER_COLS = { 1, 2, 3 };
    private static final String USER_ROLE_EXCEL_GUIDE_TEXT =
            "※ 업로드하면 파일에 있는 사용자의 권한그룹이 권한그룹명 기준으로 변경됩니다. 회색 헤더(사용자명·이메일·소속조직)는 참고용입니다.\n"
                    + "  한 사용자에게 여러 권한그룹을 주려면 사용자ID를 같게 하고 행을 나누어 입력하세요. 사용 중인 권한그룹명만 입력할 수 있습니다.";

    @Autowired
    private RoleManageDAO roleManageDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    /**
     * 권한 그룹 목록 조회
     * @return 권한 그룹 목록
     * @throws Exception
     */
    public List<RoleManageVO> selectRoleList() throws Exception {
        return roleManageDAO.selectRoleList();
    }

    /**
     * 권한 그룹 단건 조회
     * @param roleId 권한 그룹 ID
     * @return 권한 그룹 (없으면 IllegalArgumentException)
     * @throws Exception
     */
    public RoleManageVO selectRole(String roleId) throws Exception {
        RoleManageVO role = roleManageDAO.selectRole(roleId);
        if (role == null) {
            throw new IllegalArgumentException("존재하지 않는 권한 그룹입니다.");
        }
        return role;
    }

    /**
     * 그룹별 메뉴 권한 목록 조회 (roleId가 비어 있으면 모두 미부여 상태)
     * @param roleId 권한 그룹 ID
     * @return 전체 메뉴 + 부여 현황
     * @throws Exception
     */
    public List<RoleManageVO.RoleMenuVO> selectRoleMenuList(String roleId) throws Exception {
        return roleManageDAO.selectRoleMenuList(roleId);
    }

    /**
     * 권한 그룹 사용자 목록 조회
     * @param roleId 권한 그룹 ID
     * @return 해당 그룹에 지정된 사용자 목록
     * @throws Exception
     */
    public List<RoleManageVO.RoleUserVO> selectRoleUserList(String roleId) throws Exception {
        return roleManageDAO.selectRoleUserList(roleId);
    }

    /**
     * 조직(하위 조직 포함) 사용자 목록 조회 — 사용 중인 사용자만
     * @param orgId 조직 ID
     * @return 사용자 목록
     * @throws Exception
     */
    public List<RoleManageVO.RoleUserVO> selectOrgUserList(String orgId, String nameKeyword) throws Exception {
        RoleManageVO.RoleUserVO searchVO = new RoleManageVO.RoleUserVO();
        String keyword = CommonUtil.nullToBlank(nameKeyword).trim();
        searchVO.setNameKeyword(keyword);
        searchVO.setOrgId(keyword.isEmpty() ? orgId : null);
        return roleManageDAO.selectOrgUserList(searchVO);
    }

    /**
     * 권한 그룹 저장 (그룹 정보 + 메뉴별 권한)
     * @param saveVO 그룹 정보와 메뉴 권한 목록
     * @return 저장된 권한 그룹 ID
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public String saveRole(RoleManageVO.SaveRoleVO saveVO) throws Exception {
        String loginUserId = SessionUtil.getUserId();
        boolean isNew = CommonUtil.nullToBlank(saveVO.getRoleId()).trim().isEmpty();

        saveVO.setRoleId(isNew ? null : saveVO.getRoleId().trim());
        saveVO.setRoleName(CommonUtil.nullToBlank(saveVO.getRoleName()).trim());
        saveVO.setUseYn(normalizeYn(saveVO.getUseYn(), YES, "사용여부"));
        saveVO.setDefaultYn(NO.equals(saveVO.getUseYn()) ? NO : normalizeYn(saveVO.getDefaultYn(), NO, "기본적용"));
        saveVO.setCreateId(loginUserId);
        saveVO.setModifyId(loginUserId);
        validateRole(saveVO);

        if (isNew) {
            saveVO.setRoleId(keyGenerate.generateTableKey("RI", "TB_ROLE_GRP", "ROLE_ID"));
            roleManageDAO.insertRole(saveVO);
        } else if (roleManageDAO.updateRole(saveVO) != 1) {
            throw new IllegalArgumentException("존재하지 않는 권한 그룹입니다.");
        }

        if (YES.equals(saveVO.getDefaultYn())) {
            roleManageDAO.resetDefaultRole(saveVO);
        }
        saveRoleMenuList(saveVO, loginUserId);
        return saveVO.getRoleId();
    }

    /**
     * 권한 그룹 삭제 — 다른 그룹이 없는 소속 사용자는 기본 권한 그룹으로 이동 후 메뉴 권한 → 그룹 순서로 삭제
     * @param roleId 권한 그룹 ID
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(String roleId) throws Exception {
        selectRole(roleId);
        if (roleManageDAO.selectRoleCount() <= 1) {
            throw new IllegalArgumentException("마지막 권한 그룹은 삭제할 수 없습니다.");
        }
        String fallbackRoleId = selectFallbackRoleId(roleId, "이동할 기본 권한 그룹이 없어 삭제할 수 없습니다.");

        RoleManageVO.RoleUserVO moveVO = new RoleManageVO.RoleUserVO();
        moveVO.setFromRoleId(roleId);
        moveVO.setRoleId(fallbackRoleId);
        moveVO.setCreateId(SessionUtil.getUserId());
        roleManageDAO.insertUserRoleFromRole(moveVO);
        roleManageDAO.deleteUserRoleByRoleId(roleId);
        roleManageDAO.deleteRoleMenuByRoleId(roleId);
        if (roleManageDAO.deleteRole(roleId) != 1) {
            throw new IllegalArgumentException("존재하지 않는 권한 그룹입니다.");
        }
    }

    /**
     * 권한 그룹 사용자 저장 — 선택한 사용자는 이 그룹을 추가 지정, 제외한 사용자는 이 그룹만 해제(다른 그룹이 없으면 기본 권한 그룹으로 이동)
     * @param saveVO 권한 그룹 ID와 선택 사용자 ID 목록
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveRoleUser(RoleManageVO.SaveRoleUserVO saveVO) throws Exception {
        String roleId = CommonUtil.nullToBlank(saveVO.getRoleId()).trim();
        RoleManageVO role = selectRole(roleId);
        if (!YES.equals(role.getUseYn())) {
            throw new IllegalArgumentException("사용 중인 권한 그룹만 사용자를 지정할 수 있습니다.");
        }

        Set<String> selectedIds = new LinkedHashSet<>();
        if (saveVO.getUserIdList() != null) {
            for (String userId : saveVO.getUserIdList()) {
                if (!CommonUtil.nullToBlank(userId).trim().isEmpty()) {
                    selectedIds.add(userId.trim());
                }
            }
        }
        Set<String> currentIds = new LinkedHashSet<>();
        for (RoleManageVO.RoleUserVO current : roleManageDAO.selectRoleUserList(roleId)) {
            currentIds.add(current.getUserId());
        }

        String loginUserId = SessionUtil.getUserId();
        for (String userId : selectedIds) {
            if (!currentIds.contains(userId)) {
                assignUserRole(userId, roleId, loginUserId);
            }
        }

        List<String> removedIds = new ArrayList<>(currentIds);
        removedIds.removeAll(selectedIds);
        if (removedIds.isEmpty()) {
            return;
        }
        String fallbackRoleId = null;
        for (String userId : removedIds) {
            RoleManageVO.RoleUserVO removeVO = new RoleManageVO.RoleUserVO();
            removeVO.setUserId(userId);
            removeVO.setRoleId(roleId);
            roleManageDAO.deleteUserRole(removeVO);
            if (roleManageDAO.selectUserRoleCount(userId) == 0) {
                if (fallbackRoleId == null) {
                    fallbackRoleId = selectFallbackRoleId(roleId, "이동할 기본 권한 그룹이 없어 해제할 수 없습니다.");
                }
                assignUserRole(userId, fallbackRoleId, loginUserId);
            }
        }
    }

    /**
     * 권한 그룹 엑셀 다운로드
     * @param response
     * @throws Exception
     */
    public void downloadRoleExcel(HttpServletResponse response) throws Exception {
        List<RoleManageVO> list = roleManageDAO.selectRoleList();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = ExcelUtil.createSheetWithHeader(workbook, "권한그룹목록", ROLE_EXCEL_HEADERS,
                    ROLE_AUTO_GEN_HEADER_COLS, ROLE_EXCEL_GUIDE_TEXT);
            XSSFCellStyle rowStyle = ExcelUtil.createDataStyle(workbook);
            int rowNum = ExcelUtil.DATA_START_ROW;
            for (RoleManageVO vo : list) {
                Row row = sheet.createRow(rowNum++);
                ExcelUtil.applyDataCell(row, 0, CommonUtil.nullToBlank(vo.getRoleId()), rowStyle);
                ExcelUtil.applyDataCell(row, 1, CommonUtil.nullToBlank(vo.getRoleName()), rowStyle);
                ExcelUtil.applyDataCell(row, 2, CommonUtil.nullToBlank(vo.getDescription()), rowStyle);
                ExcelUtil.applyDataCell(row, 3, CommonUtil.nullToBlank(vo.getUseYn()), rowStyle);
                ExcelUtil.applyDataCell(row, 4, CommonUtil.nullToBlank(vo.getDefaultYn()), rowStyle);
            }
            ExcelUtil.applyDataSheetLayout(sheet, ROLE_EXCEL_HEADERS.length);
            ExcelUtil.addUseYnListValidations(sheet, ROLE_USE_YN_COL_IDX, ROLE_DEFAULT_YN_COL_IDX);
            ExcelUtil.writeXlsxResponse(response, workbook);
        }
    }

    /**
     * 권한 그룹 엑셀 업로드 — 행 검증이 모두 통과해야 저장한다(하나라도 실패하면 저장하지 않음)
     * @param file 업로드 파일
     * @return successCount, insertCount, updateCount, failDetails, returnMsg(실패 시)
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> uploadRoleExcel(MultipartFile file) throws Exception {
        List<Map<String, Object>> failDetails = new ArrayList<>();
        List<RoleManageVO.SaveRoleVO> saveList = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();

        Map<String, RoleManageVO> roleById = new HashMap<>();
        for (RoleManageVO role : roleManageDAO.selectRoleList()) {
            roleById.put(role.getRoleId(), role);
        }
        Set<String> nameInFile = new LinkedHashSet<>();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> colIdx = ExcelUtil.detectUploadHeader(sheet, formatter, "권한그룹",
                    ROLE_HDR_ROLE_NAME, ROLE_HDR_ROLE_NAME);
            for (int i = ExcelUtil.DATA_START_ROW; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String roleId = ExcelUtil.getCellString(row, colIdx.get(ROLE_HDR_ROLE_ID), formatter);
                String roleName = ExcelUtil.getCellString(row, colIdx.get(ROLE_HDR_ROLE_NAME), formatter);
                String description = ExcelUtil.getCellString(row, colIdx.get(ROLE_HDR_DESCRIPTION), formatter);
                String useYn = ExcelUtil.getCellString(row, colIdx.get(ROLE_HDR_USE_YN), formatter);
                String defaultYn = ExcelUtil.getCellString(row, colIdx.get(ROLE_HDR_DEFAULT_YN), formatter);
                if (ExcelUtil.isGuideMarkerRow(roleId) || (roleId.isEmpty() && roleName.isEmpty()
                        && description.isEmpty() && useYn.isEmpty() && defaultYn.isEmpty())) {
                    continue;
                }

                int rowNum = i + 1;
                String reason = validateRoleExcelRow(roleId, roleName, useYn, defaultYn, roleById, nameInFile);
                if (reason != null) {
                    failDetails.add(ExcelUtil.buildFailDetail(rowNum, reason));
                    continue;
                }

                RoleManageVO.SaveRoleVO saveVO = new RoleManageVO.SaveRoleVO();
                saveVO.setRoleId(roleId);
                saveVO.setRoleName(roleName);
                saveVO.setDescription(description);
                saveVO.setUseYn(useYn);
                saveVO.setDefaultYn(defaultYn);
                saveList.add(saveVO);
            }
        }

        int insertCount = 0;
        int updateCount = 0;
        if (failDetails.isEmpty()) {
            String loginUserId = SessionUtil.getUserId();
            for (RoleManageVO.SaveRoleVO saveVO : saveList) {
                boolean isNew = saveVO.getRoleId().isEmpty();
                saveVO.setRoleId(isNew ? null : saveVO.getRoleId());
                saveVO.setUseYn(normalizeYn(saveVO.getUseYn(), YES, "사용여부"));
                saveVO.setDefaultYn(NO.equals(saveVO.getUseYn()) ? NO : normalizeYn(saveVO.getDefaultYn(), NO, "기본적용"));
                saveVO.setCreateId(loginUserId);
                saveVO.setModifyId(loginUserId);
                if (isNew) {
                    saveVO.setRoleId(keyGenerate.generateTableKey("RI", "TB_ROLE_GRP", "ROLE_ID"));
                    roleManageDAO.insertRole(saveVO);
                    insertCount++;
                } else {
                    if (roleManageDAO.updateRole(saveVO) != 1) {
                        throw new IllegalArgumentException("존재하지 않는 권한 그룹입니다.");
                    }
                    updateCount++;
                }
                if (YES.equals(saveVO.getDefaultYn())) {
                    roleManageDAO.resetDefaultRole(saveVO);
                }
            }
        }

        return buildExcelUploadResult(insertCount + updateCount, insertCount, updateCount, failDetails);
    }

    /**
     * 사용자별 권한 그룹 엑셀 다운로드
     * @param response
     * @throws Exception
     */
    public void downloadRoleUserExcel(HttpServletResponse response) throws Exception {
        List<RoleManageVO.RoleUserVO> list = roleManageDAO.selectUserRoleList();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = ExcelUtil.createSheetWithHeader(workbook, "사용자권한목록", USER_ROLE_EXCEL_HEADERS,
                    USER_ROLE_AUTO_GEN_HEADER_COLS, USER_ROLE_EXCEL_GUIDE_TEXT);
            XSSFCellStyle rowStyle = ExcelUtil.createDataStyle(workbook);
            int rowNum = ExcelUtil.DATA_START_ROW;
            for (RoleManageVO.RoleUserVO vo : list) {
                Row row = sheet.createRow(rowNum++);
                ExcelUtil.applyDataCell(row, 0, CommonUtil.nullToBlank(vo.getUserId()), rowStyle);
                ExcelUtil.applyDataCell(row, 1, CommonUtil.nullToBlank(vo.getUserNm()), rowStyle);
                ExcelUtil.applyDataCell(row, 2, CommonUtil.nullToBlank(vo.getEmail()), rowStyle);
                ExcelUtil.applyDataCell(row, 3, CommonUtil.nullToBlank(vo.getOrgNm()), rowStyle);
                ExcelUtil.applyDataCell(row, 4, CommonUtil.nullToBlank(vo.getRoleName()), rowStyle);
            }
            ExcelUtil.applyDataSheetLayout(sheet, USER_ROLE_EXCEL_HEADERS.length);
            ExcelUtil.writeXlsxResponse(response, workbook);
        }
    }

    /**
     * 사용자별 권한 그룹 엑셀 업로드 — 행 검증이 모두 통과해야 저장한다(하나라도 실패하면 저장하지 않음)
     * @param file 업로드 파일
     * @return successCount, insertCount, updateCount, failDetails, returnMsg(실패 시)
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> uploadRoleUserExcel(MultipartFile file) throws Exception {
        List<Map<String, Object>> failDetails = new ArrayList<>();
        Map<String, Set<String>> roleIdSetByUserId = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter();

        Set<String> userIdSet = new LinkedHashSet<>();
        for (RoleManageVO.RoleUserVO user : roleManageDAO.selectUserRoleList()) {
            userIdSet.add(user.getUserId());
        }
        Map<String, String> activeRoleIdByName = new HashMap<>();
        for (RoleManageVO role : roleManageDAO.selectRoleList()) {
            if (YES.equals(role.getUseYn())) {
                activeRoleIdByName.put(role.getRoleName(), role.getRoleId());
            }
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> colIdx = ExcelUtil.detectUploadHeader(sheet, formatter, "사용자 권한",
                    USER_ROLE_HDR_USER_ID, USER_ROLE_HDR_USER_ID, USER_ROLE_HDR_ROLE_NAME);
            for (int i = ExcelUtil.DATA_START_ROW; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String userId = ExcelUtil.getCellString(row, colIdx.get(USER_ROLE_HDR_USER_ID), formatter);
                String roleName = ExcelUtil.getCellString(row, colIdx.get(USER_ROLE_HDR_ROLE_NAME), formatter);
                if (ExcelUtil.isGuideMarkerRow(userId) || (userId.isEmpty() && roleName.isEmpty())) {
                    continue;
                }

                int rowNum = i + 1;
                if (userId.isEmpty()) {
                    failDetails.add(ExcelUtil.buildFailDetail(rowNum, "사용자ID는 필수값입니다."));
                } else if (!userIdSet.contains(userId)) {
                    failDetails.add(ExcelUtil.buildFailDetail(rowNum, "등록되지 않은 사용자ID(" + userId + ")입니다."));
                } else if (!activeRoleIdByName.containsKey(roleName)) {
                    failDetails.add(ExcelUtil.buildFailDetail(rowNum,
                            "사용 중인 권한그룹명이 아닙니다(" + (roleName.isEmpty() ? "빈 값" : roleName) + ")."));
                } else if (!roleIdSetByUserId.computeIfAbsent(userId, key -> new LinkedHashSet<>())
                        .add(activeRoleIdByName.get(roleName))) {
                    failDetails.add(ExcelUtil.buildFailDetail(rowNum,
                            "파일에 중복된 사용자ID·권한그룹(" + userId + ", " + roleName + ")입니다."));
                }
            }
        }

        int updateCount = 0;
        if (failDetails.isEmpty()) {
            String loginUserId = SessionUtil.getUserId();
            for (Map.Entry<String, Set<String>> entry : roleIdSetByUserId.entrySet()) {
                roleManageDAO.deleteUserRoleByUserId(entry.getKey());
                for (String roleId : entry.getValue()) {
                    assignUserRole(entry.getKey(), roleId, loginUserId);
                }
                updateCount++;
            }
        }

        return buildExcelUploadResult(updateCount, 0, updateCount, failDetails);
    }

    /** Y/N 값을 검증해 반환한다. 비어 있으면 기본값을 쓴다. */
    private String normalizeYn(String value, String defaultValue, String label) {
        String yn = CommonUtil.nullToBlank(value).trim();
        if (yn.isEmpty()) {
            return defaultValue;
        }
        if (!ExcelUtil.isValidUseYn(yn)) {
            throw new IllegalArgumentException(label + "는 Y 또는 N만 입력 가능합니다.");
        }
        return yn;
    }

    /** 권한 그룹 저장 값 검증 (권한명 필수·중복) */
    private void validateRole(RoleManageVO roleVO) throws Exception {
        if (roleVO.getRoleName().isEmpty()) {
            throw new IllegalArgumentException("권한명은 필수값입니다.");
        }
        if (roleManageDAO.selectRoleNameCount(roleVO) > 0) {
            throw new IllegalArgumentException("이미 사용 중인 권한명입니다.");
        }
    }

    /** 엑셀 권한 그룹 행 검증. 문제가 없으면 null, 있으면 실패 사유를 반환한다. */
    private String validateRoleExcelRow(String roleId, String roleName, String useYn, String defaultYn,
            Map<String, RoleManageVO> roleById, Set<String> nameInFile) {
        if (roleName.isEmpty()) {
            return "권한명은 필수값입니다.";
        }
        if (!roleId.isEmpty() && !roleById.containsKey(roleId)) {
            return "존재하지 않는 권한ID(" + roleId + ")입니다.";
        }
        if ((!useYn.isEmpty() && !ExcelUtil.isValidUseYn(useYn)) || (!defaultYn.isEmpty() && !ExcelUtil.isValidUseYn(defaultYn))) {
            return "사용여부·기본적용은 Y 또는 N만 입력 가능합니다.";
        }
        if (!nameInFile.add(roleName)) {
            return "파일에 중복된 권한명(" + roleName + ")입니다.";
        }
        for (RoleManageVO role : roleById.values()) {
            if (role.getRoleName().equals(roleName) && !role.getRoleId().equals(roleId)) {
                return "이미 사용 중인 권한명(" + roleName + ")입니다.";
            }
        }
        return null;
    }

    /** 엑셀 업로드 결과 맵을 만든다. 실패가 있으면 안내 메시지를 함께 담는다. */
    private Map<String, Object> buildExcelUploadResult(int successCount, int insertCount, int updateCount,
            List<Map<String, Object>> failDetails) {
        String returnMsg = failDetails.isEmpty() ? null
                : ExcelUtil.buildUploadFailReturnMsg(failDetails, detail -> detail.get("row") + "행: " + detail.get("reason"));
        return ExcelUtil.buildUploadResult(successCount, insertCount, updateCount, failDetails, returnMsg);
    }

    /** 신규 사용자에게 기본 적용 권한 그룹을 지정한다. 기본 적용 그룹이 없으면 지정하지 않는다 (권한 행 없음 = 권한 없음) */
    public void assignDefaultRole(String userId, String createId) throws Exception {
        RoleManageVO.RoleUserVO userRoleVO = new RoleManageVO.RoleUserVO();
        userRoleVO.setUserId(userId);
        userRoleVO.setCreateId(createId);
        if (roleManageDAO.insertDefaultUserRole(userRoleVO) == 0) {
            logger.warn("[ROLE] 사용 중인 기본 적용 권한 그룹이 없어 신규 사용자에게 권한을 지정하지 못했습니다. userId={}", userId);
        }
    }

    /** 이동 대상 권한 그룹 ID를 조회한다. 없으면 message로 IllegalArgumentException */
    private String selectFallbackRoleId(String roleId, String message) throws Exception {
        String fallbackRoleId = roleManageDAO.selectFallbackRoleId(roleId);
        if (fallbackRoleId == null) {
            throw new IllegalArgumentException(message);
        }
        return fallbackRoleId;
    }

    /** 사용자에게 권한 그룹을 추가 지정한다 (기존 지정은 유지) */
    private void assignUserRole(String userId, String roleId, String loginUserId) throws Exception {
        RoleManageVO.RoleUserVO userRoleVO = new RoleManageVO.RoleUserVO();
        userRoleVO.setUserId(userId);
        userRoleVO.setRoleId(roleId);
        userRoleVO.setCreateId(loginUserId);
        if (roleManageDAO.insertUserRole(userRoleVO) != 1) {
            throw new IllegalArgumentException("등록되지 않은 사용자ID(" + userId + ")입니다.");
        }
    }

    /** 그룹의 메뉴 권한을 모두 지우고 조회 또는 기능 권한이 있는 메뉴만 다시 등록한다 (권한 행 없음 = 권한 없음) */
    private void saveRoleMenuList(RoleManageVO.SaveRoleVO saveVO, String loginUserId) throws Exception {
        roleManageDAO.deleteRoleMenuByRoleId(saveVO.getRoleId());
        if (saveVO.getMenuList() == null) {
            return;
        }
        for (RoleManageVO.RoleMenuVO menuVO : saveVO.getMenuList()) {
            menuVO.setReadYn(normalizeYn(menuVO.getReadYn(), NO, "조회 권한"));
            menuVO.setFuncYn(normalizeYn(menuVO.getFuncYn(), NO, "기능 권한"));
            if (NO.equals(menuVO.getReadYn()) && NO.equals(menuVO.getFuncYn())) {
                continue;
            }
            if (NO.equals(menuVO.getReadYn())) {
                throw new IllegalArgumentException("기능 권한은 조회 권한이 있는 메뉴에만 설정할 수 있습니다(" + menuVO.getMenuId() + ").");
            }
            menuVO.setRoleId(saveVO.getRoleId());
            menuVO.setCreateId(loginUserId);
            if (roleManageDAO.insertRoleMenu(menuVO) != 1) {
                throw new IllegalArgumentException("권한을 설정할 수 없는 메뉴입니다(" + menuVO.getMenuId() + ").");
            }
        }
    }
}
