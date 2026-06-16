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

public class ExcelUtil {

    public static final int DATA_VALIDATION_MAX_ROW = 500;
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

    private ExcelUtil() {
    }

    public static String getCellString(Row row, Integer colIdx, DataFormatter formatter) {
        if (colIdx == null) {
            return "";
        }
        return formatter.formatCellValue(row.getCell(colIdx)).trim();
    }

    public static Map<String, Integer> parseHeaderColumns(Row headerRow, DataFormatter formatter) {
        Map<String, Integer> colIdx = new HashMap<>();
        for (Cell cell : headerRow) {
            String header = formatter.formatCellValue(cell).trim();
            if (!header.isEmpty()) {
                colIdx.put(header, cell.getColumnIndex());
            }
        }
        return colIdx;
    }

    public static Row findHeaderRow(Sheet sheet, DataFormatter formatter, String requiredHeader) {
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

    public static void validateRequiredHeaderColumns(Map<String, Integer> colIdx, String excelName, String... headers) {
        for (String header : headers) {
            if (!colIdx.containsKey(header)) {
                throw new IllegalArgumentException("올바른 " + excelName + " 엑셀 파일이 아닙니다. (" + header + " 컬럼 필수)");
            }
        }
    }

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

    public static void applyDataCell(Row row, int colIdx, String value, XSSFCellStyle style) {
        Cell cell = row.createCell(colIdx);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

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
     * @param autoGenHeaderCols 자동 생성·참고용 컬럼 인덱스(회색 헤더). 서비스의 AUTO_GEN_HEADER_COLS와 일치해야 함.
     */
    public static void applyHeaderRow(Row header, String[] headers, int[] autoGenHeaderCols,
            XSSFCellStyle headerStyle, XSSFCellStyle autoGenHeaderStyle) {
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(isAutoGenHeaderColumn(autoGenHeaderCols, i) ? autoGenHeaderStyle : headerStyle);
        }
    }

    private static boolean isAutoGenHeaderColumn(int[] autoGenHeaderCols, int colIdx) {
        for (int autoGenColIdx : autoGenHeaderCols) {
            if (autoGenColIdx == colIdx) {
                return true;
            }
        }
        return false;
    }

    public static void adjustColumnWidths(Sheet sheet, int columnCount) {
        adjustColumnWidths(sheet, columnCount, COLUMN_MIN_WIDTH, COLUMN_WIDTH_PADDING);
    }

    public static void adjustColumnWidths(Sheet sheet, int columnCount, int minWidth, int widthPadding) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
            int width = Math.max(sheet.getColumnWidth(i) + widthPadding, minWidth);
            sheet.setColumnWidth(i, width);
        }
    }

    public static XSSFCellStyle createHeaderStyle(XSSFWorkbook workbook, boolean autoGen) {
        return createHeaderStyle(workbook, autoGen, HEADER_BG_RGB, AUTO_GEN_HEADER_BG_RGB);
    }

    public static XSSFCellStyle createHeaderStyle(XSSFWorkbook workbook, boolean autoGen, byte[] headerBgRgb,
            byte[] autoGenHeaderBgRgb) {
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
        applyThinBorder(style);
        style.setFillForegroundColor(new XSSFColor(autoGen ? autoGenHeaderBgRgb : headerBgRgb, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    public static XSSFCellStyle createDataStyle(XSSFWorkbook workbook) {
        XSSFCellStyle style = workbook.createCellStyle();
        applyThinBorder(style);
        style.setFillForegroundColor(new XSSFColor(new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF }, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

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

    public static void applyThinBorder(XSSFCellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

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
        int lastRow = DATA_VALIDATION_MAX_ROW + 1;
        for (int colIdx : colIndices) {
            addListValidation(sheet, DATA_START_ROW, lastRow, colIdx, new String[] { "Y", "N" }, null,
                    USE_YN_INVALID_MSG);
        }
    }

    public static Map<String, Object> buildFailDetail(int row, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("row", row);
        detail.put("reason", reason);
        return detail;
    }

    public static Map<String, Object> buildUploadResult(int successCount, int insertCount, int updateCount,
            List<Map<String, Object>> failDetails) {
        return buildUploadResult(successCount, insertCount, updateCount, failDetails, null);
    }

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

    public static boolean hasUploadFailures(Map<String, Object> uploadResult) {
        if (uploadResult == null) {
            return false;
        }
        Object details = uploadResult.get("failDetails");
        return details instanceof List && !((List<?>) details).isEmpty();
    }

    public static String buildUploadFailReturnMsg(List<Map<String, Object>> failDetails,
            Function<Map<String, Object>, String> messageMapper) {
        return buildUploadFailReturnMsg(failDetails, messageMapper, UPLOAD_FAIL_MSG_SHOW_MAX);
    }

    public static String buildUploadFailReturnMsg(List<Map<String, Object>> failDetails,
            Function<Map<String, Object>, String> messageMapper, int maxShow) {
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
        int showCount = Math.min(maxShow, messages.size());
        String joined = String.join(" ", messages.subList(0, showCount));
        if (messages.size() > showCount) {
            joined += " 외 " + (messages.size() - showCount) + "건";
        }
        return joined;
    }

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

    public static void writeXlsxResponse(HttpServletResponse response, XSSFWorkbook workbook) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        workbook.write(response.getOutputStream());
        response.getOutputStream().flush();
    }
}
