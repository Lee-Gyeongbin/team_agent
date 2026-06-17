package kr.teamagent.usermanage.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import kr.teamagent.common.exception.DuplicateEmailException;
import kr.teamagent.common.exception.DuplicateUserIdException;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.ExcelUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.orgmanage.service.OrgManageVO;
import kr.teamagent.orgmanage.service.impl.OrgManageDAO;
import kr.teamagent.orgmanage.service.impl.OrgManageServiceImpl;
import kr.teamagent.usermanage.service.UserManageVO;

@Service
public class UserManageServiceImpl extends EgovAbstractServiceImpl {

    private static final String USER_HDR_USER_ID = "사용자ID *";
    private static final String USER_HDR_USER_NM = "사용자명 *";
    private static final String USER_HDR_EMAIL = "이메일 *";
    private static final String USER_HDR_PHONE = "전화번호";
    private static final String USER_HDR_ORG_NM = "소속조직명";
    private static final String USER_HDR_USE_YN = "사용여부";
    private static final String USER_HDR_ACCT_STATUS = "계정상태";
    private static final String[] USER_EXCEL_HEADERS = {
            USER_HDR_USER_ID, USER_HDR_USER_NM, USER_HDR_EMAIL, USER_HDR_PHONE, USER_HDR_ORG_NM, USER_HDR_USE_YN,
            USER_HDR_ACCT_STATUS
    };
    /** ExcelUtil.applyHeaderRow 회색 헤더용 — 삭제·변경 시 다운로드 헤더 스타일이 깨짐 */
    private static final int[] AUTO_GEN_HEADER_COLS = { 6 };
    private static final int ORG_NM_COL_IDX = 4;
    private static final int USE_YN_COL_IDX = 5;
    private static final int ORG_LIST_COL_IDX = 7;
    private static final String ORG_LIST_NAME = "UserOrgNmList";
    private static final String FAIL_TYPE_DUPLICATE_USER_ID = "DUPLICATE_USER_ID";
    private static final String FAIL_TYPE_DUPLICATE_EMAIL = "DUPLICATE_EMAIL";
    private static final String FAIL_TYPE_FORMAT = "FORMAT";
    private static final String USER_ID_REQUIRED_MSG = "사용자ID는 필수값입니다.";
    private static final String USER_NM_REQUIRED_MSG = "사용자명은 필수값입니다.";
    private static final String EMAIL_REQUIRED_MSG = "이메일은 필수값입니다.";
    private static final String ORG_NM_HEADER = USER_HDR_ORG_NM;
    private static final String INVALID_ORG_NM_MSG = "존재하지 않는 소속조직명입니다.";
    private static final String INVALID_EMAIL_MSG = "이메일 형식이 올바르지 않습니다.";
    private static final String INVALID_PHONE_MSG = "전화번호는 숫자 9~11자리만 입력 가능합니다.";
    private static final String USE_YN_REQUIRED_MSG = "사용여부는 필수값입니다.";
    private static final String ACCT_STATUS_ACTIVE_CD = "001";
    private static final String ACCT_STATUS_INACTIVE_CD = "002";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^([\\w-]+(?:\\.[\\w-]+)*)@((?:[\\w-]+\\.)*\\w[\\w-]{0,66})\\.([a-zA-Z]{2,6}(?:\\.[a-zA-Z]{2})?)$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\d{9,11}$");
    private static final String USER_EXCEL_GUIDE_TEXT =
            "※ * 표시된 항목은 필수 입력값입니다.\n"
                    + "※ 사용자ID가 기존에 있으면 수정, 없으면 신규 등록됩니다. 신규 등록 시 임시 비밀번호가 자동 생성됩니다.\n"
                    + "  소속조직명(선택), 사용여부(Y/N) 만 입력하세요. 계정상태는 참고용입니다.";

    @Autowired
    private UserManageDAO userManageDAO;

    @Autowired
    private OrgManageDAO orgManageDAO;

    @Autowired
    private OrgManageServiceImpl orgManageService;

    @SuppressWarnings("deprecation")
    private final StandardPasswordEncoder passwordEncoder = new StandardPasswordEncoder();

    /**
     * 사용자 목록 조회
     * @param searchVO
     * @return list
     * @throws Exception
     */
    public List<UserManageVO> selectUserList(UserManageVO searchVO) throws Exception {
        return userManageDAO.selectUserList(searchVO);
    }

    /**
     * 사용자 엑셀 다운로드
     * @param response
     * @throws Exception
     */
    public void downloadUserExcel(HttpServletResponse response) throws Exception {
        List<UserManageVO> list = selectUserList(new UserManageVO());
        ActiveOrgMaps activeOrgs = buildActiveOrgMaps();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = createUserExcelSheet(workbook, list, activeOrgs);
            applyUserExcelSheetOptions(workbook, sheet, activeOrgs.orgNames);
            ExcelUtil.writeXlsxResponse(response, workbook);
        }
    }

    private XSSFSheet createUserExcelSheet(XSSFWorkbook workbook, List<UserManageVO> list, ActiveOrgMaps activeOrgs) {
        XSSFSheet sheet = ExcelUtil.createSheetWithHeader(workbook, "사용자목록", USER_EXCEL_HEADERS, AUTO_GEN_HEADER_COLS,
                USER_EXCEL_GUIDE_TEXT);
        writeUserExcelRows(workbook, sheet, list, activeOrgs.orgNameById);
        return sheet;
    }

    private void writeUserExcelRows(XSSFWorkbook workbook, XSSFSheet sheet, List<UserManageVO> list,
            Map<String, String> orgNameById) {
        XSSFCellStyle rowStyle = ExcelUtil.createDataStyle(workbook);
        int rowNum = ExcelUtil.DATA_START_ROW;
        for (UserManageVO vo : list) {
            String orgNm = CommonUtil.nullToBlank(orgNameById.get(vo.getOrgId()));

            Row row = sheet.createRow(rowNum++);
            ExcelUtil.applyDataCell(row, 0, CommonUtil.nullToBlank(vo.getUserId()), rowStyle);
            ExcelUtil.applyDataCell(row, 1, CommonUtil.nullToBlank(vo.getUserNm()), rowStyle);
            ExcelUtil.applyDataCell(row, 2, CommonUtil.nullToBlank(vo.getEmail()), rowStyle);
            ExcelUtil.applyDataCell(row, 3, CommonUtil.nullToBlank(vo.getPhone()), rowStyle);
            ExcelUtil.applyDataCell(row, 4, orgNm, rowStyle);
            ExcelUtil.applyDataCell(row, 5, CommonUtil.nullToBlank(vo.getUseYn()), rowStyle);
            ExcelUtil.applyDataCell(row, 6, CommonUtil.nullToBlank(vo.getAcctStatusDesc()), rowStyle);
        }
    }

    private void applyUserExcelSheetOptions(XSSFWorkbook workbook, XSSFSheet sheet, List<String> orgNames) {
        ExcelUtil.applyDataSheetLayout(sheet, USER_EXCEL_HEADERS.length);
        ExcelUtil.prepareHiddenListColumn(workbook, sheet, ORG_LIST_COL_IDX, orgNames, ORG_LIST_NAME);
        if (!orgNames.isEmpty()) {
            ExcelUtil.addListValidation(sheet, ExcelUtil.DATA_START_ROW, ExcelUtil.DATA_VALIDATION_LAST_ROW,
                    ORG_NM_COL_IDX, null, ORG_LIST_NAME, "등록된 소속조직명만 선택할 수 있습니다.");
        }
        ExcelUtil.addUseYnListValidations(sheet, USE_YN_COL_IDX);
    }

    /**
     * 사용자 엑셀 업로드
     * @param file
     * @return successCount, insertCount, updateCount, failDetails, returnMsg(실패 시)
     * @throws Exception
     */
    public Map<String, Object> uploadUserExcel(MultipartFile file) throws Exception {
        int successCount = 0;
        int insertCount = 0;
        int updateCount = 0;
        List<Map<String, Object>> failDetails = new ArrayList<>();
        List<UserExcelRow> excelRows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        Set<String> userIdsInFile = new HashSet<>();
        Set<String> emailsInFile = new HashSet<>();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> colIdx = ExcelUtil.detectUploadHeader(sheet, formatter, "사용자",
                    USER_HDR_USER_NM, USER_HDR_USER_NM, USER_HDR_USER_ID, USER_HDR_EMAIL);

            Integer userIdCol = colIdx.get(USER_HDR_USER_ID);
            Integer userNmCol = colIdx.get(USER_HDR_USER_NM);
            Integer emailCol = colIdx.get(USER_HDR_EMAIL);
            Integer phoneCol = colIdx.get(USER_HDR_PHONE);
            Integer orgCol = colIdx.get(ORG_NM_HEADER);
            Integer useYnCol = colIdx.get(USER_HDR_USE_YN);

            for (int i = ExcelUtil.DATA_START_ROW; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                String userId = ExcelUtil.getCellString(row, userIdCol, formatter);
                String userNm = ExcelUtil.getCellString(row, userNmCol, formatter);
                String email = ExcelUtil.getCellString(row, emailCol, formatter);
                String phone = ExcelUtil.getCellString(row, phoneCol, formatter);
                String orgNm = ExcelUtil.getCellString(row, orgCol, formatter);
                String useYn = ExcelUtil.getCellString(row, useYnCol, formatter);
                if (isSkippableUserExcelRow(userId, userNm, email, phone, orgNm, useYn)) {
                    continue;
                }

                String phoneDigits = normalizePhoneDigits(phone);
                Map<String, Object> failDetail = buildUserExcelRowFailDetail(i + 1, userId, userNm, email, phoneDigits,
                        useYn);
                if (failDetail != null) {
                    failDetails.add(failDetail);
                    continue;
                }

                String userIdKey = userId.toLowerCase();
                if (!userIdsInFile.add(userIdKey)) {
                    failDetails.add(buildFailDetail(i + 1, userId, userId, FAIL_TYPE_DUPLICATE_USER_ID));
                    continue;
                }

                String emailKey = email.toLowerCase();
                if (!emailsInFile.add(emailKey)) {
                    failDetails.add(buildFailDetail(i + 1, userId, email, FAIL_TYPE_DUPLICATE_EMAIL));
                    continue;
                }

                excelRows.add(new UserExcelRow(i + 1, userId, userNm, email, phoneDigits, orgNm, useYn));
            }
        }

        ActiveOrgMaps activeOrgs = buildActiveOrgMaps();
        String loginUserId = SessionUtil.getUserId();

        for (UserExcelRow excelRow : excelRows) {
            try {
                String orgId = orgManageService.resolveOrgIdByName(excelRow.orgNm, activeOrgs.orgIdByName);
                if (!excelRow.orgNm.isEmpty() && orgId == null) {
                    failDetails.add(buildFailDetail(excelRow.rowNum, excelRow.userId, INVALID_ORG_NM_MSG + excelRow.orgNm,
                            FAIL_TYPE_FORMAT));
                    continue;
                }

                boolean updateRow = isDuplicateUserIdForInsert(excelRow.userId);
                boolean duplicateEmail = updateRow
                        ? isDuplicateEmailForUpdate(excelRow.userId, excelRow.email)
                        : isDuplicateEmailForInsert(excelRow.email);
                if (duplicateEmail) {
                    failDetails.add(buildFailDetail(excelRow.rowNum, excelRow.userId, excelRow.email,
                            FAIL_TYPE_DUPLICATE_EMAIL));
                    continue;
                }

                UserManageVO vo = new UserManageVO();
                vo.setUserId(excelRow.userId);
                vo.setUserNm(excelRow.userNm);
                vo.setEmail(excelRow.email);
                vo.setPhone(excelRow.phone);
                vo.setOrgId(orgId);
                applyUserStatusFromExcel(vo, excelRow.useYn);
                if (loginUserId != null) {
                    vo.setCrtrId(loginUserId);
                    vo.setMdfrId(loginUserId);
                }

                if (updateRow) {
                    saveUserExcelRow(vo, true);
                    updateCount++;
                } else {
                    String tempPassword = RandomStringUtils.randomAlphanumeric(10);
                    vo.setPasswd(passwordEncoder.encode(tempPassword));
                    saveUserExcelRow(vo, false);
                    insertCount++;
                }
                successCount++;
            } catch (DuplicateUserIdException e) {
                failDetails.add(buildFailDetail(excelRow.rowNum, excelRow.userId, excelRow.userId,
                        FAIL_TYPE_DUPLICATE_USER_ID));
            } catch (DuplicateEmailException e) {
                failDetails.add(buildFailDetail(excelRow.rowNum, excelRow.userId, excelRow.email,
                        FAIL_TYPE_DUPLICATE_EMAIL));
            } catch (Exception e) {
                failDetails.add(buildFailDetail(excelRow.rowNum, excelRow.userId, CommonUtil.nullToBlank(e.getMessage()),
                        FAIL_TYPE_FORMAT));
            }
        }

        String returnMsg = failDetails.isEmpty() ? null
                : ExcelUtil.buildUploadFailReturnMsg(failDetails, UserManageServiceImpl::buildFailDetailDisplayMessage);
        return ExcelUtil.buildUploadResult(successCount, insertCount, updateCount, failDetails, returnMsg);
    }

    private void saveUserExcelRow(UserManageVO userManageVO, boolean updateRow) throws Exception {
        if (CommonUtil.nullToBlank(userManageVO.getOrgId()).isEmpty()) {
            userManageVO.setOrgId(null);
        }
        if (updateRow) {
            updateUser(userManageVO);
        } else {
            insertUser(userManageVO);
        }
    }

    private static void applyUserStatusFromExcel(UserManageVO vo, String useYn) {
        vo.setUseYn(useYn == null || useYn.isEmpty() ? "Y" : useYn);
        validateAndApplyUserStatus(vo);
    }

    /**
     * 사용여부(Y/N) 검증 후 계정상태 코드(001/002)를 VO에 반영한다.
     */
    private static void validateAndApplyUserStatus(UserManageVO vo) {
        String useYn = CommonUtil.nullToBlank(vo.getUseYn());
        if (useYn.isEmpty()) {
            throw new IllegalArgumentException(USE_YN_REQUIRED_MSG);
        }
        if (!ExcelUtil.isValidUseYn(useYn)) {
            throw new IllegalArgumentException(ExcelUtil.USE_YN_INVALID_MSG);
        }
        vo.setUseYn(useYn);
        vo.setAcctStatusCd("Y".equals(useYn) ? ACCT_STATUS_ACTIVE_CD : ACCT_STATUS_INACTIVE_CD);
    }

    private static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    private static String normalizePhoneDigits(String phone) {
        if (phone == null || phone.isEmpty()) {
            return "";
        }
        return phone.replaceAll("\\D", "");
    }

    private static boolean isValidPhoneDigits(String phoneDigits) {
        if (phoneDigits == null || phoneDigits.isEmpty()) {
            return true;
        }
        return PHONE_PATTERN.matcher(phoneDigits).matches();
    }

    /**
     * 생성 시 동일 ID가 이미 존재하는지 여부
     * @param userId 생성하려는 사용자 ID
     * @return true면 중복
     */
    public boolean isDuplicateUserIdForInsert(String userId) throws Exception {
        UserManageVO vo = new UserManageVO();
        vo.setUserId(userId);
        return userManageDAO.countUserByUserId(vo) > 0;
    }

    /**
     * 생성 시 동일 이메일이 이미 존재하는지 여부
     * @param email 생성하려는 이메일
     * @return true면 중복
     */
    public boolean isDuplicateEmailForInsert(String email) throws Exception {
        UserManageVO vo = new UserManageVO();
        vo.setEmail(email);
        return userManageDAO.countUserByEmail(vo) > 0;
    }

    /**
     * 수정 시 동일 이메일을 다른 사용자가 사용 중인지 여부
     * @param userId 이메일을 변경하려는 사용자 ID
     * @param email 수정하려는 이메일
     * @return true면 중복
     */
    public boolean isDuplicateEmailForUpdate(String userId, String email) throws Exception {
        UserManageVO vo = new UserManageVO();
        vo.setUserId(userId);
        vo.setEmail(email);
        return userManageDAO.countUserByEmailExcludingUserId(vo) > 0;
    }

    /**
     * 사용자 정보 생성
     * @param userManageVO
     * @return 생성된 행 수
     * @throws Exception
     */
    public int insertUser(UserManageVO userManageVO) throws Exception {
        String userId = userManageVO.getUserId();
        if (isDuplicateUserIdForInsert(userId)) {
            throw new DuplicateUserIdException();
        }

        String email = userManageVO.getEmail();
        if (email != null && !email.isEmpty() && isDuplicateEmailForInsert(email)) {
            throw new DuplicateEmailException();
        }

        validateAndApplyUserStatus(userManageVO);
        return userManageDAO.insertUser(userManageVO);
    }

    /**
     * 사용자 정보 수정
     * @param userManageVO
     * @return 수정된 행 수
     * @throws Exception
     */
    public int updateUser(UserManageVO userManageVO) throws Exception {
        String userId = userManageVO.getUserId();
        String email = userManageVO.getEmail();
        if (email != null && !email.isEmpty() && isDuplicateEmailForUpdate(userId, email)) {
            throw new DuplicateEmailException();
        }
        validateAndApplyUserStatus(userManageVO);
        return userManageDAO.updateUser(userManageVO);
    }

    /**
     * 사용자 정보 삭제
     * @param userManageVO
     * @return 삭제된 행 수
     * @throws Exception
     */
    public int deleteUser(UserManageVO userManageVO) throws Exception {
        return userManageDAO.deleteUser(userManageVO);
    }

    /**
     * 사용자 정보 복구
     * @param userManageVO
     * @return 복구된 행 수
     * @throws Exception
     */
    public int restoreUser(UserManageVO userManageVO) throws Exception {
        return userManageDAO.restoreUser(userManageVO);
    }

    /**
     * 사용자 비밀번호 초기화
     * @param userManageVO
     * @return 초기화된 행 수
     * @throws Exception
     */
    public int resetPassword(UserManageVO userManageVO) throws Exception {
        return userManageDAO.resetPassword(userManageVO);
    }

    private ActiveOrgMaps buildActiveOrgMaps() throws Exception {
        List<String> orgNames = new ArrayList<>();
        Map<String, String> orgNameById = new HashMap<>();
        Map<String, String> orgIdByName = new HashMap<>();
        Set<String> seenNames = new HashSet<>();
        for (OrgManageVO vo : orgManageDAO.selectOrgList(new OrgManageVO())) {
            if (vo.getOrgId() != null) {
                orgNameById.put(vo.getOrgId(), CommonUtil.nullToBlank(vo.getOrgNm()));
            }
            if (vo.getOrgNm() == null) {
                continue;
            }
            String orgNm = vo.getOrgNm().trim();
            if (orgNm.isEmpty()) {
                continue;
            }
            orgManageService.registerOrgName(orgIdByName, orgNm, vo.getOrgId());
            if (seenNames.add(orgNm)) {
                orgNames.add(orgNm);
            }
        }
        return new ActiveOrgMaps(orgNames, orgNameById, orgIdByName);
    }

    private static boolean isEmptyUserExcelRow(String userId, String userNm, String email, String phone,
            String orgNm, String useYn) {
        return userId.isEmpty() && userNm.isEmpty() && email.isEmpty() && phone.isEmpty()
                && orgNm.isEmpty() && useYn.isEmpty();
    }

    private static boolean isSkippableUserExcelRow(String userId, String userNm, String email, String phone,
            String orgNm, String useYn) {
        return ExcelUtil.isGuideMarkerRow(userNm) || isEmptyUserExcelRow(userId, userNm, email, phone, orgNm, useYn);
    }

    private static Map<String, Object> buildUserExcelRowFailDetail(int rowNum, String userId, String userNm,
            String email, String phoneDigits, String useYn) {
        if (userId.isEmpty()) {
            return buildFailDetail(rowNum, userNm, USER_ID_REQUIRED_MSG, FAIL_TYPE_FORMAT);
        }
        if (userNm.isEmpty()) {
            return buildFailDetail(rowNum, userId, USER_NM_REQUIRED_MSG, FAIL_TYPE_FORMAT);
        }
        if (email.isEmpty()) {
            return buildFailDetail(rowNum, userId, EMAIL_REQUIRED_MSG, FAIL_TYPE_FORMAT);
        }
        if (!useYn.isEmpty() && !ExcelUtil.isValidUseYn(useYn)) {
            return buildFailDetail(rowNum, userId, ExcelUtil.USE_YN_INVALID_MSG, FAIL_TYPE_FORMAT);
        }
        if (!isValidEmail(email)) {
            return buildFailDetail(rowNum, userId, INVALID_EMAIL_MSG, FAIL_TYPE_FORMAT);
        }
        if (!isValidPhoneDigits(phoneDigits)) {
            return buildFailDetail(rowNum, userId, INVALID_PHONE_MSG, FAIL_TYPE_FORMAT);
        }
        return null;
    }

    private static Map<String, Object> buildFailDetail(int row, String userId, String reason, String failType) {
        Map<String, Object> detail = ExcelUtil.buildFailDetail(row, reason);
        detail.put("userId", userId);
        detail.put("failType", failType);
        return detail;
    }

    private static String buildFailDetailDisplayMessage(Map<String, Object> detail) {
        String failType = String.valueOf(detail.get("failType"));
        String reason = CommonUtil.nullToBlank((String) detail.get("reason"));

        if (FAIL_TYPE_DUPLICATE_USER_ID.equals(failType)) {
            return new DuplicateUserIdException().getMessage();
        }
        if (FAIL_TYPE_DUPLICATE_EMAIL.equals(failType)) {
            return new DuplicateEmailException().getMessage();
        }
        if (reason.isEmpty()) {
            return ExcelUtil.UPLOAD_FAIL_DEFAULT_MSG;
        }
        return reason;
    }

    private static class ActiveOrgMaps {
        private final List<String> orgNames;
        private final Map<String, String> orgNameById;
        private final Map<String, String> orgIdByName;

        private ActiveOrgMaps(List<String> orgNames, Map<String, String> orgNameById, Map<String, String> orgIdByName) {
            this.orgNames = orgNames;
            this.orgNameById = orgNameById;
            this.orgIdByName = orgIdByName;
        }
    }

    private static class UserExcelRow {
        private final int rowNum;
        private final String userId;
        private final String userNm;
        private final String email;
        private final String phone;
        private final String orgNm;
        private final String useYn;

        private UserExcelRow(int rowNum, String userId, String userNm, String email, String phone, String orgNm,
                String useYn) {
            this.rowNum = rowNum;
            this.userId = userId;
            this.userNm = userNm;
            this.email = email;
            this.phone = phone;
            this.orgNm = orgNm;
            this.useYn = useYn;
        }
    }
}
