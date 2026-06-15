package kr.teamagent.orgmanage.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.ExcelUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.orgmanage.service.OrgManageVO;

@Service
public class OrgManageServiceImpl extends EgovAbstractServiceImpl {

    private static final String ORG_HDR_ORG_ID = "조직ID";
    private static final String ORG_HDR_ORG_NM = "조직명 *";
    private static final String ORG_HDR_PARENT_ORG_NM = "상위조직명";
    private static final String ORG_HDR_ORG_LEVEL = "조직레벨";
    private static final String ORG_HDR_SORT_ORD = "정렬순서";
    private static final String ORG_HDR_USE_YN = "사용여부 *";
    private static final String[] ORG_EXCEL_HEADERS = {
            ORG_HDR_ORG_ID, ORG_HDR_ORG_NM, ORG_HDR_PARENT_ORG_NM, ORG_HDR_ORG_LEVEL, ORG_HDR_SORT_ORD, ORG_HDR_USE_YN
    };
    /** ExcelUtil.applyHeaderRow 회색 헤더용 — 삭제·변경 시 다운로드 헤더 스타일이 깨짐 */
    private static final int[] AUTO_GEN_HEADER_COLS = { 0, 3, 4 };
    private static final int USE_YN_COL_IDX = 5;
    private static final String ORG_NM_USE_YN_REQUIRED_MSG = "조직명과 사용여부는 필수값입니다.";
    private static final String PARENT_ORG_NM_HEADER = ORG_HDR_PARENT_ORG_NM;
    private static final String PARENT_ORG_ID_HEADER = "상위조직ID";
    private static final String PARENT_ORG_FLEX_HEADER = "상위조직명 or 상위조직ID";
    private static final String ORG_EXCEL_GUIDE_TEXT =
            "※ * 표시된 항목은 필수 입력값입니다.\n"
                    + "※ 조직ID가 있으면 수정, 비어 있으면 신규 등록됩니다. 조직레벨·정렬순서는 업로드 시 무시됩니다.\n"
                    + "  상위조직명(선택) 만 입력하세요.";

    @Autowired
    private OrgManageDAO orgManageDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    @Autowired
    private FileServiceImpl fileService;

    /** trim된 조직명 → orgId (조직명은 trim·공백 없음 전제). */
    public void registerOrgName(Map<String, String> orgIdByName, String orgNm, String orgId) {
        if (orgNm == null) {
            return;
        }
        String key = orgNm.trim();
        if (!key.isEmpty()) {
            orgIdByName.putIfAbsent(key, orgId);
        }
    }

    public String resolveOrgIdByName(String orgNm, Map<String, String> orgIdByName) {
        if (orgNm == null) {
            return null;
        }
        String key = orgNm.trim();
        return key.isEmpty() ? null : orgIdByName.get(key);
    }

    /**
     * 조직 목록 조회
     * @param searchVO
     * @return list
     * @throws Exception
     */
    public List<OrgManageVO> selectOrgList(OrgManageVO searchVO) throws Exception {
        return orgManageDAO.selectOrgList(searchVO);
    }

    /**
     * 사용자 목록 조회 (동일 ORG_ID)
     * @param orgId
     * @return list
     * @throws Exception
     */
    public List<OrgManageVO> selectUserList(String orgId) throws Exception {
        List<OrgManageVO> userList = orgManageDAO.selectUserList(orgId);
        for (OrgManageVO user : userList) {
            String profileImgPath = user.getProfileImgPath();
            if (CommonUtil.isEmpty(profileImgPath)) {
                user.setProfileImgUrl("");
                continue;
            }
            try {
                Map<String, Object> raw = fileService.createViewPresignedUrlForStorageObject(toFileVO(profileImgPath));
                user.setProfileImgUrl(extractImageUrl(raw));
            } catch (Exception e) {
                user.setProfileImgUrl("");
            }
        }
        return userList;
    }

    private static FileVO toFileVO(String profileImgPath) {
        FileVO fileVO = new FileVO();
        String trimmedPath = profileImgPath.trim();
        fileVO.setFilePath(trimmedPath);
        int slashIndex = trimmedPath.lastIndexOf('/');
        fileVO.setFileName(slashIndex >= 0 ? trimmedPath.substring(slashIndex + 1) : trimmedPath);
        return fileVO;
    }

    private static String extractImageUrl(Map<String, Object> raw) {
        if (raw == null) {
            return "";
        }
        Object viewType = raw.get("viewType");
        if (viewType == null || !"IMAGE".equals(String.valueOf(viewType))) {
            return "";
        }
        Object url = raw.get("url");
        return url == null ? "" : String.valueOf(url);
    }

    /**
     * 조직 등록
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    public int insertOrg(OrgManageVO orgManageVO) throws Exception {
        orgManageVO.setOrgId(keyGenerate.generateTableKey("ORG", "TB_ORG", "ORG_ID", 3));
        applyOrgHierarchyValues(orgManageVO);

        if (orgManageVO.getUseYn() == null || orgManageVO.getUseYn().trim().isEmpty()) {
            orgManageVO.setUseYn("Y");
        }
        return orgManageDAO.insertOrg(orgManageVO);
    }

    /**
     * 조직 수정
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    public int updateOrg(OrgManageVO orgManageVO) throws Exception {
        applyOrgHierarchyValues(orgManageVO);
        return orgManageDAO.updateOrg(orgManageVO);
    }

    /**
     * 조직 정렬순서 수정
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public int updateOrgSortOrder(OrgManageVO orgManageVO) throws Exception {
        if (orgManageVO == null || CommonUtil.isEmpty(orgManageVO.getOrgId())) {
            return 0;
        }

        if (CommonUtil.isEmpty(orgManageVO.getSortOrder())) {
            return 0;
        }

        OrgManageVO currentOrg = orgManageDAO.selectOrgByOrgId(orgManageVO.getOrgId());
        if (currentOrg == null) {
            return 0;
        }
        String currentParentOrgId = normalizeParentOrgId(currentOrg.getParentOrgId());

        return reindexSiblingSortOrder(currentParentOrgId, orgManageVO.getOrgId(), orgManageVO.getSortOrder());
    }

    /**
     * 조직 삭제
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    public int deleteOrg(OrgManageVO orgManageVO) throws Exception {
        return orgManageDAO.deleteOrg(orgManageVO);
    }

    /**
     * 조직 엑셀 다운로드
     * @param response
     * @throws Exception
     */
    public void downloadOrgExcel(HttpServletResponse response) throws Exception {
        List<OrgManageVO> list = orderOrgListByTree(selectOrgList(new OrgManageVO()));
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = createOrgExcelSheet(workbook, list);
            applyOrgExcelSheetOptions(sheet);
            ExcelUtil.writeXlsxResponse(response, workbook);
        }
    }

    private XSSFSheet createOrgExcelSheet(XSSFWorkbook workbook, List<OrgManageVO> list) {
        XSSFSheet sheet = ExcelUtil.createSheetWithHeader(workbook, "조직목록", ORG_EXCEL_HEADERS, AUTO_GEN_HEADER_COLS,
                ORG_EXCEL_GUIDE_TEXT);

        Map<String, String> orgNameMap = new HashMap<>();
        for (OrgManageVO vo : list) {
            orgNameMap.put(vo.getOrgId(), vo.getOrgNm());
        }
        writeOrgExcelRows(workbook, sheet, list, orgNameMap);
        return sheet;
    }

    private void writeOrgExcelRows(XSSFWorkbook workbook, XSSFSheet sheet, List<OrgManageVO> list,
            Map<String, String> orgNameMap) {
        XSSFCellStyle rowStyle = ExcelUtil.createDataStyle(workbook);
        int rowNum = ExcelUtil.DATA_START_ROW;
        for (OrgManageVO vo : list) {
            String parentOrgNm = CommonUtil.nullToBlank(orgNameMap.get(vo.getParentOrgId()));

            Row row = sheet.createRow(rowNum++);
            ExcelUtil.applyDataCell(row, 0, CommonUtil.nullToBlank(vo.getOrgId()), rowStyle);
            ExcelUtil.applyDataCell(row, 1, CommonUtil.nullToBlank(vo.getOrgNm()), rowStyle);
            ExcelUtil.applyDataCell(row, 2, parentOrgNm, rowStyle);
            ExcelUtil.applyDataCell(row, 3, CommonUtil.nullToBlank(vo.getOrgLevel()), rowStyle);
            ExcelUtil.applyDataCell(row, 4, CommonUtil.nullToBlank(vo.getSortOrder()), rowStyle);
            ExcelUtil.applyDataCell(row, 5, CommonUtil.nullToBlank(vo.getUseYn()), rowStyle);
        }
    }

    private void applyOrgExcelSheetOptions(XSSFSheet sheet) {
        ExcelUtil.adjustColumnWidths(sheet, ORG_EXCEL_HEADERS.length);
        sheet.createFreezePane(0, ExcelUtil.DATA_START_ROW);
        ExcelUtil.addUseYnListValidations(sheet, USE_YN_COL_IDX);
    }

    /**
     * 조직 엑셀 업로드
     * @param file
     * @return successCount, insertCount, updateCount, failDetails, returnMsg(실패 시)
     * @throws Exception
     */
    public Map<String, Object> uploadOrgExcel(MultipartFile file) throws Exception {
        int successCount = 0;
        int insertCount = 0;
        int updateCount = 0;
        List<Map<String, Object>> failDetails = new ArrayList<>();
        List<OrgExcelRow> excelRows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        boolean parentOrgIdFirst = false;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = ExcelUtil.findHeaderRow(sheet, formatter, ORG_HDR_ORG_NM);
            if (headerRow == null) {
                throw new IllegalArgumentException("올바른 조직 엑셀 파일이 아닙니다. (헤더 행 없음)");
            }

            Map<String, Integer> colIdx = ExcelUtil.parseHeaderColumns(headerRow, formatter);
            ExcelUtil.validateRequiredHeaderColumns(colIdx, "조직", ORG_HDR_ORG_NM, ORG_HDR_USE_YN);

            Integer orgNmCol = colIdx.get(ORG_HDR_ORG_NM);
            Integer orgIdCol = colIdx.get(ORG_HDR_ORG_ID);
            Integer parentOrgCol = resolveParentOrgColumnIndex(colIdx);
            parentOrgIdFirst = isParentOrgIdColumn(colIdx);
            Integer useYnCol = colIdx.get(ORG_HDR_USE_YN);

            for (int i = headerRow.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                String orgNm = ExcelUtil.getCellString(row, orgNmCol, formatter);
                String orgId = ExcelUtil.getCellString(row, orgIdCol, formatter);
                String parentOrgNm = ExcelUtil.getCellString(row, parentOrgCol, formatter);
                String useYn = ExcelUtil.getCellString(row, useYnCol, formatter);
                if (orgNm.startsWith("※")) {
                    continue;
                }
                if (isEmptyOrgExcelRow(orgId, orgNm, parentOrgNm, useYn)) {
                    continue;
                }

                boolean orgNmEmpty = orgNm.isEmpty();
                boolean useYnEmpty = useYn.isEmpty();
                if (orgNmEmpty != useYnEmpty) {
                    failDetails.add(buildFailDetail(i + 1, orgNmEmpty ? parentOrgNm : orgNm, ORG_NM_USE_YN_REQUIRED_MSG));
                    continue;
                }
                if (orgNmEmpty) {
                    continue;
                }

                if (!ExcelUtil.isValidUseYn(useYn)) {
                    failDetails.add(buildFailDetail(i + 1, orgNm, ExcelUtil.USE_YN_INVALID_MSG));
                    continue;
                }

                excelRows.add(new OrgExcelRow(i + 1, orgId, orgNm, parentOrgNm, useYn));
            }
        }

        Map<String, String> orgIdByName = selectOrgIdByNameMap();
        List<OrgExcelRow> pendingRows = new ArrayList<>(excelRows);
        while (!pendingRows.isEmpty()) {
            boolean processed = false;
            Iterator<OrgExcelRow> iterator = pendingRows.iterator();
            while (iterator.hasNext()) {
                OrgExcelRow excelRow = iterator.next();
                String parentOrgId = resolveParentOrgId(excelRow.parentOrgNm, orgIdByName, parentOrgIdFirst);
                if (!excelRow.parentOrgNm.isEmpty() && parentOrgId == null) {
                    continue;
                }

                try {
                    boolean updateRow = !excelRow.orgId.isEmpty();
                    OrgManageVO vo = new OrgManageVO();
                    vo.setOrgId(excelRow.orgId);
                    vo.setOrgNm(excelRow.orgNm);
                    vo.setParentOrgId(parentOrgId);
                    vo.setUseYn(excelRow.useYn);
                    saveOrgExcelRow(vo);
                    if (vo.getOrgNm() != null && !vo.getOrgNm().trim().isEmpty()) {
                        registerOrgName(orgIdByName, vo.getOrgNm(), vo.getOrgId());
                    }
                    successCount++;
                    if (updateRow) {
                        updateCount++;
                    } else {
                        insertCount++;
                    }
                } catch (Exception e) {
                    failDetails.add(buildFailDetail(excelRow.rowNum, excelRow.orgNm, e.getMessage()));
                }
                iterator.remove();
                processed = true;
            }
            if (!processed) {
                for (OrgExcelRow excelRow : pendingRows) {
                    failDetails.add(buildFailDetail(excelRow.rowNum, excelRow.orgNm,
                            "존재하지 않는 상위조직(명/ID): " + excelRow.parentOrgNm));
                }
                pendingRows.clear();
            }
        }

        String returnMsg = failDetails.isEmpty() ? null
                : ExcelUtil.buildUploadFailReturnMsg(failDetails, OrgManageServiceImpl::orgFailDetailMessage);
        return ExcelUtil.buildUploadResult(successCount, insertCount, updateCount, failDetails, returnMsg);
    }

    private static String orgFailDetailMessage(Map<String, Object> detail) {
        Object reason = detail.get("reason");
        if (reason == null) {
            return ExcelUtil.UPLOAD_FAIL_DEFAULT_MSG;
        }
        String message = String.valueOf(reason).trim();
        return message.isEmpty() ? ExcelUtil.UPLOAD_FAIL_DEFAULT_MSG : message;
    }

    /**
     * 엑셀 업로드 행 저장. 조직ID가 있으면 기존 조직 수정, 없으면 신규 등록한다.
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    private int saveOrgExcelRow(OrgManageVO orgManageVO) throws Exception {
        String orgId = orgManageVO.getOrgId();
        if (orgId == null || orgId.trim().isEmpty()) {
            orgManageVO.setOrgId(null);
            return insertOrg(orgManageVO);
        }

        String trimmedOrgId = orgId.trim();
        OrgManageVO currentOrg = selectOrgByOrgIdNormalized(trimmedOrgId);
        if (currentOrg == null) {
            throw new IllegalArgumentException("존재하지 않는 조직ID: " + trimmedOrgId);
        }

        String resolvedOrgId = currentOrg.getOrgId();
        orgManageVO.setOrgId(resolvedOrgId);
        if (Objects.equals(resolvedOrgId, orgManageVO.getParentOrgId())) {
            throw new IllegalArgumentException("상위조직명은 자기 자신으로 지정할 수 없습니다.");
        }

        String currentParentOrgId = normalizeParentOrgId(currentOrg.getParentOrgId());
        applyParentOrgAndLevel(orgManageVO);
        if (Objects.equals(currentParentOrgId, orgManageVO.getParentOrgId())) {
            orgManageVO.setSortOrder(currentOrg.getSortOrder());
        } else if (orgManageVO.getParentOrgId() != null) {
            int maxSortOrder = orgManageDAO.selectMaxSortOrderByParentOrgId(orgManageVO.getParentOrgId());
            orgManageVO.setSortOrder(String.valueOf(maxSortOrder + 1));
        } else {
            orgManageVO.setSortOrder(String.valueOf(orgManageDAO.selectMaxSortOrderByTopLevel() + 1));
        }
        return orgManageDAO.updateOrg(orgManageVO);
    }

    /**
     * 조직 계층 값 적용
     * @param orgManageVO
     * @throws Exception
     */
    private void applyParentOrgAndLevel(OrgManageVO orgManageVO) throws Exception {
        String parentOrgId = normalizeParentOrgId(orgManageVO.getParentOrgId());
        orgManageVO.setParentOrgId(parentOrgId);

        int orgLevel = 1;
        if (parentOrgId != null) {
            OrgManageVO parentOrg = orgManageDAO.selectOrgByOrgId(parentOrgId);
            String parentOrgLevel = parentOrg != null ? parentOrg.getOrgLevel() : null;
            int parentLevel = 0;
            if (parentOrgLevel != null && !parentOrgLevel.trim().isEmpty()) {
                parentLevel = Integer.parseInt(parentOrgLevel);
            }
            orgLevel = parentLevel + 1;
        }
        orgManageVO.setOrgLevel(String.valueOf(orgLevel));
    }

    /**
     * 신규 조직 등록 시 계층 값 적용
     * @param orgManageVO
     * @throws Exception
     */
    private void applyOrgHierarchyValues(OrgManageVO orgManageVO) throws Exception {
        applyParentOrgAndLevel(orgManageVO);
        String parentOrgId = orgManageVO.getParentOrgId();
        int maxSortOrder;
        if (parentOrgId != null) {
            maxSortOrder = orgManageDAO.selectMaxSortOrderByParentOrgId(parentOrgId);
        } else {
            maxSortOrder = orgManageDAO.selectMaxSortOrderByTopLevel();
        }
        orgManageVO.setSortOrder(String.valueOf(maxSortOrder + 1));
    }

    /**
     * 부모 조직 ID 정규화
     * @param parentOrgId
     * @return
     */
    private String normalizeParentOrgId(String parentOrgId) {
        if (parentOrgId == null) {
            return null;
        }
        String trimmedParentOrgId = parentOrgId.trim();
        return trimmedParentOrgId.isEmpty() ? null : trimmedParentOrgId;
    }

    /**
     * 조직도 표시 순서(루트 → 하위, 형제는 정렬순서)로 목록을 재정렬한다.
     */
    private List<OrgManageVO> orderOrgListByTree(List<OrgManageVO> flatList) {
        if (flatList == null || flatList.isEmpty()) {
            return flatList;
        }

        Map<String, OrgManageVO> byId = new HashMap<>();
        Map<String, List<OrgManageVO>> childrenByParentId = new HashMap<>();
        for (OrgManageVO vo : flatList) {
            byId.put(vo.getOrgId(), vo);
        }

        List<OrgManageVO> roots = new ArrayList<>();
        for (OrgManageVO vo : flatList) {
            String parentOrgId = normalizeParentOrgId(vo.getParentOrgId());
            if (parentOrgId == null || !byId.containsKey(parentOrgId)) {
                roots.add(vo);
            } else {
                childrenByParentId.computeIfAbsent(parentOrgId, k -> new ArrayList<>()).add(vo);
            }
        }
        for (List<OrgManageVO> siblings : childrenByParentId.values()) {
            siblings.sort(OrgManageServiceImpl::compareOrgSiblingOrder);
        }
        roots.sort(OrgManageServiceImpl::compareOrgSiblingOrder);

        List<OrgManageVO> ordered = new ArrayList<>(flatList.size());
        Set<String> visited = new HashSet<>();
        for (OrgManageVO root : roots) {
            appendOrgSubtreePreorder(root, childrenByParentId, ordered, visited);
        }
        for (OrgManageVO vo : flatList) {
            if (!visited.contains(vo.getOrgId())) {
                appendOrgSubtreePreorder(vo, childrenByParentId, ordered, visited);
            }
        }
        return ordered;
    }

    private static void appendOrgSubtreePreorder(OrgManageVO node, Map<String, List<OrgManageVO>> childrenByParentId,
            List<OrgManageVO> ordered, Set<String> visited) {
        if (!visited.add(node.getOrgId())) {
            return;
        }
        ordered.add(node);
        List<OrgManageVO> children = childrenByParentId.get(node.getOrgId());
        if (children == null) {
            return;
        }
        for (OrgManageVO child : children) {
            appendOrgSubtreePreorder(child, childrenByParentId, ordered, visited);
        }
    }

    private static int compareOrgSiblingOrder(OrgManageVO a, OrgManageVO b) {
        int sortCompare = Integer.compare(parseSortOrderValue(a.getSortOrder()), parseSortOrderValue(b.getSortOrder()));
        if (sortCompare != 0) {
            return sortCompare;
        }
        return Objects.compare(a.getOrgId(), b.getOrgId(), String::compareTo);
    }

    private static int parseSortOrderValue(String sortOrder) {
        if (sortOrder == null || sortOrder.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(sortOrder.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isEmptyOrgExcelRow(String orgId, String orgNm, String parentOrgNm, String useYn) {
        return orgId.isEmpty() && orgNm.isEmpty() && parentOrgNm.isEmpty() && useYn.isEmpty();
    }

    private static Integer resolveParentOrgColumnIndex(Map<String, Integer> colIdx) {
        String parentOrgHeader = findParentOrgHeader(colIdx);
        return parentOrgHeader == null ? null : colIdx.get(parentOrgHeader);
    }

    /** 상위조직ID(또는 혼합 헤더) 컬럼이면 ORG_ID 조회를 이름 맵보다 먼저 시도한다. */
    private static boolean isParentOrgIdColumn(Map<String, Integer> colIdx) {
        String parentOrgHeader = findParentOrgHeader(colIdx);
        return PARENT_ORG_ID_HEADER.equals(parentOrgHeader) || PARENT_ORG_FLEX_HEADER.equals(parentOrgHeader);
    }

    private static String findParentOrgHeader(Map<String, Integer> colIdx) {
        if (colIdx.containsKey(PARENT_ORG_NM_HEADER)) {
            return PARENT_ORG_NM_HEADER;
        }
        if (colIdx.containsKey(PARENT_ORG_ID_HEADER)) {
            return PARENT_ORG_ID_HEADER;
        }
        if (colIdx.containsKey(PARENT_ORG_FLEX_HEADER)) {
            return PARENT_ORG_FLEX_HEADER;
        }
        return null;
    }

    private Map<String, String> selectOrgIdByNameMap() throws Exception {
        Map<String, String> orgIdByName = new HashMap<>();
        List<OrgManageVO> list = selectOrgList(new OrgManageVO());
        for (OrgManageVO vo : list) {
            if (vo.getOrgNm() == null) {
                continue;
            }
            String orgNm = vo.getOrgNm().trim();
            if (orgNm.isEmpty()) {
                continue;
            }
            registerOrgName(orgIdByName, orgNm, vo.getOrgId());
        }
        return orgIdByName;
    }

    private String resolveParentOrgId(String parentOrgValue, Map<String, String> orgIdByName, boolean idFirst)
            throws Exception {
        if (parentOrgValue == null || parentOrgValue.trim().isEmpty()) {
            return null;
        }
        String trimmed = normalizeExcelParentToken(parentOrgValue.trim());
        if (idFirst) {
            return resolveParentOrgIdByIdThenName(trimmed, orgIdByName);
        }
        return resolveParentOrgIdByNameThenId(trimmed, orgIdByName);
    }

    private String resolveParentOrgIdByIdThenName(String trimmed, Map<String, String> orgIdByName) throws Exception {
        OrgManageVO parentById = selectOrgByOrgIdNormalized(trimmed);
        if (parentById != null) {
            return parentById.getOrgId();
        }
        return resolveOrgIdByName(trimmed, orgIdByName);
    }

    private String resolveParentOrgIdByNameThenId(String trimmed, Map<String, String> orgIdByName) throws Exception {
        String byName = resolveOrgIdByName(trimmed, orgIdByName);
        if (byName != null) {
            return byName;
        }
        OrgManageVO parentOrg = selectOrgByOrgIdNormalized(trimmed);
        return parentOrg != null ? parentOrg.getOrgId() : null;
    }

    /**
     * 엑셀 등에서 ORG 접두 조직ID가 소문자(org001)로 온 경우 DB 저장값(ORG001)과 맞춘다.
     */
    private OrgManageVO selectOrgByOrgIdNormalized(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String token = normalizeExcelParentToken(raw.trim());
        if (token.isEmpty()) {
            return null;
        }
        OrgManageVO vo = orgManageDAO.selectOrgByOrgId(token);
        if (vo != null) {
            return vo;
        }
        String canonical = canonicalOrgIdIfOrgNumeric(token);
        if (!canonical.equals(token)) {
            return orgManageDAO.selectOrgByOrgId(canonical);
        }
        return null;
    }

    /**
     * KeyGenerate 기본 접두(ORG) + 일련번호 형태에서 접두 대소문자만 다른 입력을 정규화한다.
     */
    private static String canonicalOrgIdIfOrgNumeric(String token) {
        if (token == null || token.length() <= 3) {
            return token;
        }
        if (token.regionMatches(true, 0, "org", 0, 3)) {
            String suffix = token.substring(3);
            if (suffix.matches("\\d+")) {
                return "ORG" + suffix;
            }
        }
        return token;
    }

    private static String normalizeExcelParentToken(String raw) {
        if (raw.matches("^\\d+\\.0+$")) {
            return raw.substring(0, raw.indexOf('.'));
        }
        return raw;
    }

    private static Map<String, Object> buildFailDetail(int row, String orgNm, String reason) {
        Map<String, Object> detail = ExcelUtil.buildFailDetail(row, reason);
        detail.put("orgNm", orgNm);
        return detail;
    }

    /**
     * 동일 부모 조직 내 정렬순서 재조정.
     * {@code sortOrder}는 동일 부모 형제 중 1부터 시작하는 목표 순번으로 해석한다.
     *
     * @param parentOrgId
     * @param movedOrgId 이동 대상 ORG_ID
     * @param desiredOneBasedSortOrder 목표 순번 (1..형제수)
     * @return
     * @throws Exception
     */
    private int reindexSiblingSortOrder(String parentOrgId, String movedOrgId, String desiredOneBasedSortOrder)
            throws Exception {
        List<OrgManageVO> siblingList = orgManageDAO.selectParentOrgId(parentOrgId);
        if (siblingList == null || siblingList.isEmpty()) {
            return 0;
        }
        List<OrgManageVO> ordered;
        if (!CommonUtil.isEmpty(movedOrgId) && !CommonUtil.isEmpty(desiredOneBasedSortOrder)) {
            ordered = orderSiblingsForMove(siblingList, movedOrgId, desiredOneBasedSortOrder);
        } else {
            ordered = new ArrayList<>(siblingList);
        }
        List<OrgManageVO.OrgSortOrderItem> items = new ArrayList<>();
        int sortOrder = 1;
        for (OrgManageVO sibling : ordered) {
            OrgManageVO.OrgSortOrderItem item = new OrgManageVO.OrgSortOrderItem();
            item.setOrgId(sibling.getOrgId());
            item.setSortOrder(String.valueOf(sortOrder++));
            items.add(item);
        }
        OrgManageVO batch = new OrgManageVO();
        batch.setParentOrgId(parentOrgId);
        batch.setItems(items);
        return orgManageDAO.updateSiblingSortOrderBatch(batch);
    }

    /**
     * 현재 DB 정렬 순서를 유지한 채 {@code movedOrgId}만 목표 순번 위치로 옮긴 목록을 만든다.
     * 목표 순번은 1-based이며 형제 수 범위로 보정한다.
     */
    private static List<OrgManageVO> orderSiblingsForMove(List<OrgManageVO> siblingList, String movedOrgId,
            String desiredOneBasedSortOrder) {
        int n = siblingList.size();
        int targetPos;
        try {
            targetPos = Integer.parseInt(desiredOneBasedSortOrder.trim());
        } catch (NumberFormatException e) {
            return new ArrayList<>(siblingList);
        }
        if (targetPos < 1) {
            targetPos = 1;
        }
        if (targetPos > n) {
            targetPos = n;
        }
        OrgManageVO moved = null;
        List<OrgManageVO> rest = new ArrayList<>();
        for (OrgManageVO s : siblingList) {
            if (Objects.equals(movedOrgId, s.getOrgId())) {
                moved = s;
            } else {
                rest.add(s);
            }
        }
        if (moved == null) {
            return new ArrayList<>(siblingList);
        }
        int insertIdx = targetPos - 1;
        if (insertIdx > rest.size()) {
            insertIdx = rest.size();
        }
        if (insertIdx < 0) {
            insertIdx = 0;
        }
        rest.add(insertIdx, moved);
        return rest;
    }

    private static class OrgExcelRow {
        private final int rowNum;
        private final String orgId;
        private final String orgNm;
        private final String parentOrgNm;
        private final String useYn;

        private OrgExcelRow(int rowNum, String orgId, String orgNm, String parentOrgNm, String useYn) {
            this.rowNum = rowNum;
            this.orgId = orgId;
            this.orgNm = orgNm;
            this.parentOrgNm = parentOrgNm;
            this.useYn = useYn;
        }
    }
}
