package kr.teamagent.common.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDataValidation;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 엑셀 업로드·다운로드 공통 유틸리티.
 * Apache POI 기반으로 헤더/안내행 생성, 셀 스타일, 드롭다운 검증, 업로드 결과 조립을 제공한다.
 */
public class ExcelUtil {

    public static final int DATA_VALIDATION_MAX_ROW = 500;
    public static final int DATA_VALIDATION_LAST_ROW = DATA_VALIDATION_MAX_ROW + 1;
    public static final int DATA_START_ROW = 2;
    public static final int HEADER_ROW_IDX = 1;
    public static final int GUIDE_ROW_IDX = 0;
    public static final int COLUMN_MIN_WIDTH = 4000;
    public static final int COLUMN_WIDTH_PADDING = 2048;
    public static final float GUIDE_ROW_HEIGHT = 54f;
    public static final float HEADER_ROW_HEIGHT = 22f;
    public static final int UPLOAD_FAIL_MSG_SHOW_MAX = 3;
    public static final String USE_YN_INVALID_MSG = "사용여부는 Y 또는 N만 입력 가능합니다.";
    public static final String UPLOAD_FAIL_DEFAULT_MSG = "엑셀 업로드 검증에 실패했습니다.";

    public static final byte[] HEADER_BG_RGB = { (byte) 0x1F, (byte) 0x4E, (byte) 0x79 };
    public static final byte[] AUTO_GEN_HEADER_BG_RGB = { (byte) 0xA6, (byte) 0xA6, (byte) 0xA6 };
    public static final byte[] DATA_BG_RGB = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };

    /** 인스턴스 생성 방지용 private 생성자 */
    private ExcelUtil() {
    }

    /** 지정 열의 셀 값을 문자열로 반환한다. colIdx가 null이면 빈 문자열을 반환한다. */
    public static String getCellString(Row row, Integer colIdx, DataFormatter formatter) {
        if (colIdx == null) {
            return "";
        }
        return formatter.formatCellValue(row.getCell(colIdx)).trim();
    }

    /** 헤더 행에서 컬럼명 → 열 인덱스 맵을 생성한다. 빈 헤더 셀은 제외한다. */
    private static Map<String, Integer> parseHeaderColumns(Row headerRow, DataFormatter formatter) {
        Map<String, Integer> colIdx = new HashMap<>();
        for (Cell cell : headerRow) {
            String header = formatter.formatCellValue(cell).trim();
            if (!header.isEmpty()) {
                colIdx.put(header, cell.getColumnIndex());
            }
        }
        return colIdx;
    }

    /** 시트에서 requiredHeader 컬럼을 포함하는 첫 번째 행을 헤더 행으로 찾는다. 없으면 null. */
    private static Row findHeaderRow(Sheet sheet, DataFormatter formatter, String requiredHeader) {
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            if (parseHeaderColumns(row, formatter).containsKey(requiredHeader)) {
                return row;
            }
        }
        return null;
    }

    /** 필수 헤더 컬럼 검증. 헤더 행 없음·컬럼 누락 시 failDetail 없이 IllegalArgumentException만 던진다. */
    private static void validateRequiredHeaderColumns(Map<String, Integer> colIdx, String excelName, String... headers) {
        if (headers == null || headers.length == 0) {
            return;
        }
        if (colIdx == null || colIdx.isEmpty()) {
            throw new IllegalArgumentException("올바른 " + excelName + " 엑셀 파일이 아닙니다. (헤더 행 없음)");
        }
        for (String header : headers) {
            if (!colIdx.containsKey(header)) {
                throw new IllegalArgumentException("올바른 " + excelName + " 엑셀 파일이 아닙니다. (" + header + " 컬럼 필수)");
            }
        }
    }

    /** 시트에서 헤더 행을 탐지하고 필수 컬럼을 검증한 뒤 컬럼 인덱스 맵을 반환한다. 실패 시 IllegalArgumentException. */
    public static Map<String, Integer> detectUploadHeader(Sheet sheet, DataFormatter formatter, String excelName,
            String anchorHeader, String... requiredHeaders) {
        Row headerRow = findHeaderRow(sheet, formatter, anchorHeader);
        Map<String, Integer> colIdx = headerRow != null ? parseHeaderColumns(headerRow, formatter) : null;
        validateRequiredHeaderColumns(colIdx, excelName, requiredHeaders);
        return colIdx;
    }

    /** 사용여부 값이 Y 또는 N인지 확인한다. */
    public static boolean isValidUseYn(String useYn) {
        return "Y".equals(useYn) || "N".equals(useYn);
    }

    /** 엑셀 안내행(0행) 또는 데이터 행에 잘못 들어온 안내 텍스트 스킵 판별 */
    public static boolean isGuideMarkerRow(String cellValue) {
        return cellValue != null && cellValue.startsWith("※");
    }

    /** 값이 있을 때만 Y/N 형식을 검증한다. 실패 시 failDetail, 통과 시 null */
    public static Map<String, Object> validateOptionalUseYn(int rowNum, String value, String label) {
        if (value != null && !value.isEmpty() && !isValidUseYn(value)) {
            return buildFailDetail(rowNum, label + "는 Y 또는 N만 입력 가능합니다.");
        }
        return null;
    }

    /** 데이터 행에 값과 스타일을 적용한 셀을 생성한다. */
    public static void applyDataCell(Row row, int colIdx, String value, XSSFCellStyle style) {
        Cell cell = row.createCell(colIdx);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** 안내 텍스트를 병합된 안내 행(0행)으로 추가한다. */
    public static void applyGuideRow(Sheet sheet, int rowIdx, int lastColIdx, String guideText, XSSFCellStyle guideStyle,
            float heightInPoints) {
        Row guideRow = sheet.createRow(rowIdx);
        Cell guideCell = guideRow.createCell(0);
        guideCell.setCellValue(guideText);
        guideCell.setCellStyle(guideStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, lastColIdx));
        guideRow.setHeightInPoints(heightInPoints);
    }

    /**
     * 헤더 행에 컬럼명과 스타일을 적용한다.
     *
     * @param autoGenHeaderCols 자동 생성·참고용 컬럼 인덱스(회색 헤더). 서비스의 AUTO_GEN_HEADER_COLS와 일치해야 함.
     */
    public static void applyHeaderRow(Row header, String[] headers, int[] autoGenHeaderCols,
            XSSFCellStyle headerStyle, XSSFCellStyle autoGenHeaderStyle) {
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            boolean autoGen = false;
            for (int autoGenColIdx : autoGenHeaderCols) {
                if (autoGenColIdx == i) {
                    autoGen = true;
                    break;
                }
            }
            cell.setCellStyle(autoGen ? autoGenHeaderStyle : headerStyle);
        }
    }

    /** 컬럼 너비 조정·헤더 행 고정 등 데이터 시트 공통 레이아웃을 적용한다. */
    public static void applyDataSheetLayout(Sheet sheet, int columnCount) {
        adjustColumnWidths(sheet, columnCount);
        sheet.createFreezePane(0, DATA_START_ROW);
    }

    /** 컬럼 너비를 자동 조정하고 최소 너비·패딩을 적용한다. */
    private static void adjustColumnWidths(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
            int width = Math.max(sheet.getColumnWidth(i) + COLUMN_WIDTH_PADDING, COLUMN_MIN_WIDTH);
            sheet.setColumnWidth(i, width);
        }
    }

    /** 헤더 셀 스타일을 생성한다. autoGen이 true이면 회색·이탤릭 자동생성 헤더 스타일이다. */
    public static XSSFCellStyle createHeaderStyle(XSSFWorkbook workbook, boolean autoGen) {
        XSSFCellStyle style = workbook.createCellStyle();
        XSSFFont font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        if (autoGen) {
            font.setItalic(true);
        }
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setFillForegroundColor(new XSSFColor(autoGen ? AUTO_GEN_HEADER_BG_RGB : HEADER_BG_RGB, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** 데이터 영역 셀 스타일(흰 배경·얇은 테두리)을 생성한다. */
    public static XSSFCellStyle createDataStyle(XSSFWorkbook workbook) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setFillForegroundColor(new XSSFColor(DATA_BG_RGB, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** 안내 행 셀 스타일(이탤릭·회색·줄바꿈)을 생성한다. */
    public static XSSFCellStyle createGuideStyle(XSSFWorkbook workbook) {
        XSSFCellStyle style = workbook.createCellStyle();
        XSSFFont font = workbook.createFont();
        font.setItalic(true);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    /**
     * 드롭다운 목록용 숨김 열을 채우고 Named range를 등록한다.
     * addListValidation에서 formulaListName으로 참조한다.
     */
    public static void prepareHiddenListColumn(XSSFWorkbook workbook, XSSFSheet sheet, int colIdx, List<String> values,
            String rangeName) {
        int rowIndex = 0;
        for (String value : values) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                row = sheet.createRow(rowIndex);
            }
            row.createCell(colIdx).setCellValue(value);
            rowIndex++;
        }
        if (values.isEmpty()) {
            Row row = sheet.getRow(0);
            if (row == null) {
                row = sheet.createRow(0);
            }
            row.createCell(colIdx).setCellValue("");
            rowIndex = 1;
        }
        sheet.setColumnHidden(colIdx, true);

        String sheetName = sheet.getSheetName().replace("'", "''");
        String colLetter = CellReference.convertNumToColString(colIdx);
        Name namedRange = workbook.createName();
        namedRange.setNameName(rangeName);
        namedRange.setRefersToFormula("'" + sheetName + "'!$" + colLetter + "$1:$" + colLetter + "$" + rowIndex);
    }

    /**
     * 열 드롭다운 검증. explicitValues가 있으면 고정 목록, null이면 formulaListName(Named range) 사용.
     */
    public static void addListValidation(XSSFSheet sheet, int firstRow, int lastRow, int colIdx,
            String[] explicitValues, String formulaListName, String errorMessage) {
        XSSFDataValidationHelper helper = new XSSFDataValidationHelper(sheet);
        DataValidationConstraint constraint;
        if (explicitValues != null) {
            constraint = helper.createExplicitListConstraint(explicitValues);
        } else {
            constraint = helper.createFormulaListConstraint(formulaListName);
        }
        CellRangeAddressList addressList = new CellRangeAddressList(firstRow, lastRow, colIdx, colIdx);
        XSSFDataValidation validation = (XSSFDataValidation) helper.createValidation(constraint, addressList);
        validation.setSuppressDropDownArrow(true);
        validation.setShowErrorBox(true);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("입력 오류", errorMessage);
        validation.setEmptyCellAllowed(true);
        sheet.addValidationData(validation);
    }

    /** 데이터 영역에 Y/N 드롭다운을 적용한다. */
    public static void addUseYnListValidations(XSSFSheet sheet, int... colIndices) {
        for (int colIdx : colIndices) {
            addListValidation(sheet, DATA_START_ROW, DATA_VALIDATION_LAST_ROW, colIdx, new String[] { "Y", "N" }, null,
                    USE_YN_INVALID_MSG);
        }
    }

    /** 업로드 검증 실패 상세 정보(row, reason) 맵을 생성한다. */
    public static Map<String, Object> buildFailDetail(int row, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("row", row);
        detail.put("reason", reason);
        return detail;
    }

    /** 엑셀 업로드 처리 결과 맵을 생성한다. returnMsg가 있으면 함께 포함한다. */
    public static Map<String, Object> buildUploadResult(int successCount, int insertCount, int updateCount,
            List<Map<String, Object>> failDetails, String returnMsg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("successCount", successCount);
        result.put("insertCount", insertCount);
        result.put("updateCount", updateCount);
        result.put("failDetails", failDetails);
        if (returnMsg != null) {
            result.put("returnMsg", returnMsg);
        }
        return result;
    }

    /** 업로드 결과에 실패 상세(failDetails)가 하나 이상 있는지 확인한다. */
    public static boolean hasUploadFailures(Map<String, Object> uploadResult) {
        if (uploadResult == null) {
            return false;
        }
        Object details = uploadResult.get("failDetails");
        return details instanceof List && !((List<?>) details).isEmpty();
    }

    /** 실패 상세 목록을 사용자 메시지로 변환한다. 중복 제거 후 UPLOAD_FAIL_MSG_SHOW_MAX건까지 표시한다. */
    public static String buildUploadFailReturnMsg(List<Map<String, Object>> failDetails,
            Function<Map<String, Object>, String> messageMapper) {
        if (failDetails == null || failDetails.isEmpty()) {
            return UPLOAD_FAIL_DEFAULT_MSG;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> detail : failDetails) {
            seen.add(messageMapper.apply(detail));
        }
        List<String> messages = new ArrayList<>(seen);
        if (messages.isEmpty()) {
            return UPLOAD_FAIL_DEFAULT_MSG;
        }
        if (messages.size() == 1) {
            return messages.get(0);
        }
        int showCount = Math.min(UPLOAD_FAIL_MSG_SHOW_MAX, messages.size());
        String joined = String.join(" ", messages.subList(0, showCount));
        if (messages.size() > showCount) {
            joined += " 외 " + (messages.size() - showCount) + "건";
        }
        return joined;
    }

    /** 안내행·헤더행이 포함된 시트를 생성한다. 다운로드 템플릿 초기화에 사용한다. */
    public static XSSFSheet createSheetWithHeader(XSSFWorkbook workbook, String sheetName,
            String[] headers, int[] autoGenHeaderCols, String guideText) {
        XSSFSheet sheet = workbook.createSheet(sheetName);
        XSSFCellStyle headerStyle = createHeaderStyle(workbook, false);
        XSSFCellStyle autoGenHeaderStyle = createHeaderStyle(workbook, true);
        applyGuideRow(sheet, GUIDE_ROW_IDX, headers.length - 1, guideText, createGuideStyle(workbook), GUIDE_ROW_HEIGHT);
        Row header = sheet.createRow(HEADER_ROW_IDX);
        header.setHeightInPoints(HEADER_ROW_HEIGHT);
        applyHeaderRow(header, headers, autoGenHeaderCols, headerStyle, autoGenHeaderStyle);
        return sheet;
    }

    /** XSSFWorkbook을 xlsx Content-Type으로 HTTP 응답에 쓴다. */
    public static void writeXlsxResponse(HttpServletResponse response, XSSFWorkbook workbook) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        workbook.write(response.getOutputStream());
        response.getOutputStream().flush();
    }
}
