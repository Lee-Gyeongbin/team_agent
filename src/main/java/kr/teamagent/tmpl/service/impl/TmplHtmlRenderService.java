package kr.teamagent.tmpl.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.stereotype.Service;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.library.service.LibraryVO;

@Service
public class TmplHtmlRenderService extends EgovAbstractServiceImpl {

    /** createDoc 채팅 이미지 전용. 템플릿/reAskReport의 data-img-placeholder와 충돌하지 않도록 분리한다. */
    public static final String CREATE_DOC_IMG_TOKEN = "CDOC_IMG";
    public static final String CREATE_DOC_IMG_ATTR = "data-createdoc-img";

    private static final Pattern CREATE_DOC_IMG_TOKEN_PATTERN =
            Pattern.compile("\\[\\[" + CREATE_DOC_IMG_TOKEN + ":(\\d+)\\]\\]");
    private static final Pattern CREATE_DOC_IMG_TAG_PATTERN = Pattern.compile(
            "<img\\s+" + CREATE_DOC_IMG_ATTR + "=\"(\\d+)\">", Pattern.CASE_INSENSITIVE);

    /** 마크다운 파이프 표 행 (| col | col |) */
    private static final Pattern MARKDOWN_TABLE_ROW_PATTERN = Pattern.compile("^\\s*\\|.+\\|\\s*$");

    /**
     * 텍스트에 마크다운 파이프 표가 2행 이상 연속으로 포함되어 있는지 확인한다.
     */
    public static boolean containsMarkdownPipeTable(String text) {
        if (CommonUtil.isEmpty(text)) {
            return false;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int consecutive = 0;
        for (String line : lines) {
            if (isMarkdownTableRow(line)) {
                consecutive++;
                if (consecutive >= 2) {
                    return true;
                }
            } else {
                consecutive = 0;
            }
        }
        return false;
    }

    /**
     * 템플릿 HTML 문자열의 placeholder({{jsonKey}})를 LLM 응답 JSON 값으로 치환한다.
     * 필드 메타(`tmplFieldList`)를 기준으로 렌더링 규칙을 결정한다.
     * 일반 필드는 단순 치환하고, LAYOUT_TYPE=table은 tr 단위 확장/치환한다.
     * MULTILINE_YN=Y는 줄바꿈/배열을 리스트 표현으로 렌더링한다.
     * @param tmplHtml 템플릿 원본 HTML(TB_TMPL.TMPL_HTML)
     * @param json LLM 응답 JSON 객체
     * @param tmplFieldList 템플릿 필드 메타 목록(TB_TMPL_FIELD)
     * @return 치환 완료된 HTML
     */
    public String renderTemplateHtml(String tmplHtml, JSONObject json, List<LibraryVO.TmplFieldItem> tmplFieldList) throws Exception {
        return renderTemplateHtml(tmplHtml, json, tmplFieldList, true);
    }

    /**
     * showSpeaker=false 이면 MULTILINE 필드의 각 항목 끝에 붙은 (발언자) 패턴을 제거한다.
     * LLM은 항상 발언자를 포함해 생성하고, 표시 여부는 렌더링 시점에 결정한다.
     * @param showSpeaker true: 발언자 표시, false: 발언자 숨김
     */
    public String renderTemplateHtml(String tmplHtml, JSONObject json, List<LibraryVO.TmplFieldItem> tmplFieldList, boolean showSpeaker) throws Exception {
        if (CommonUtil.isEmpty(tmplHtml)) {
            return "";
        }
        String html = tmplHtml;
        if (tmplFieldList == null) {
            return html;
        }

        for (LibraryVO.TmplFieldItem field : tmplFieldList) {
            if (field == null || CommonUtil.isEmpty(field.getJsonKey())) {
                continue;
            }
            String key = field.getJsonKey();
            boolean multiline = "Y".equals(field.getMultilineYn());
            // table 레이아웃은 동일 row 복제 규칙이 필요하므로 전용 처리.
            if ("table".equalsIgnoreCase(stringValue(field.getLayoutType()))) {
                html = renderTableRows(html, key, json.get(key), multiline, showSpeaker);
                continue;
            }
            // table 외 레이아웃은 placeholder 1:1 치환.
            String value = renderTemplateValue(json.get(key), multiline, showSpeaker);
            html = html.replace("{{" + key + "}}", value);
        }
        // 모든 치환 완료 후 잔여 {{no}}가 있으면 제거한다.
        html = html.replace("{{no}}", "");
        return html;
    }

    /**
     * LAYOUT_TYPE=table 필드는 {{jsonKey}}가 포함된 tr을 배열 길이만큼 복제한다.
     * placeholder를 포함한 tr만 대상으로 처리한다.
     * 행에 placeholder가 1개면 배열 원소 수만큼 row를 복제하고,
     * 여러 개면 복제 대신 단일 값으로 치환한다.
     * 매칭되는 tr이 없으면 문서 전체 replace fallback을 수행한다.
     */
    private String renderTableRows(String html, String key, Object value, boolean multiline) {
        return renderTableRows(html, key, value, multiline, true);
    }

    private String renderTableRows(String html, String key, Object value, boolean multiline, boolean showSpeaker) {
        if (CommonUtil.isEmpty(html) || CommonUtil.isEmpty(key)) {
            return html;
        }
        String placeholder = "{{" + key + "}}";
        if (!html.contains(placeholder)) {
            return html;
        }

        Pattern trPattern = Pattern.compile("(?is)<tr\\b[^>]*>.*?</tr>");
        Matcher matcher = trPattern.matcher(html);
        StringBuilder out = new StringBuilder();
        int lastEnd = 0;
        boolean matchedRow = false;

        while (matcher.find()) {
            out.append(html, lastEnd, matcher.start());
            String tr = matcher.group();
            if (!tr.contains(placeholder)) {
                out.append(tr);
                lastEnd = matcher.end();
                continue;
            }

            matchedRow = true;
            // {{no}}는 예약 순번 플레이스홀더이므로 실제 데이터 placeholder 수에서 제외한다.
            int dataPlaceholderCount = countTemplatePlaceholders(tr.replace("{{no}}", ""));
            if (dataPlaceholderCount == 1) {
                List<String> rows = extractTemplateRowValues(value);
                if (rows.isEmpty()) {
                    // 값이 비어 있으면 placeholder만 제거해 빈 cell로 유지.
                    out.append(tr.replace(placeholder, "").replace("{{no}}", ""));
                } else {
                    // 배열 길이만큼 동일한 tr을 복제하며 {{no}}에 순번(1부터)을 채운다.
                    for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                        String rowText = showSpeaker ? rows.get(rowIdx) : stripSpeakerAnnotation(rows.get(rowIdx));
                        String cellValue = formatTemplateCellHtml(rowText);
                        out.append(tr.replace(placeholder, cellValue)
                                     .replace("{{no}}", String.valueOf(rowIdx + 1)));
                    }
                }
            } else {
                // 같은 row에 placeholder가 여러 개 섞여 있으면 복제 로직보다 단일 치환이 안전하다.
                String singleValue = renderTemplateValue(value, multiline, showSpeaker);
                out.append(tr.replace(placeholder, singleValue).replace("{{no}}", ""));
            }
            lastEnd = matcher.end();
        }
        out.append(html.substring(lastEnd));

        if (!matchedRow) {
            String fallback = renderTemplateValue(value, multiline, showSpeaker);
            return html.replace(placeholder, fallback).replace("{{no}}", "");
        }
        return out.toString();
    }

    /**
     * HTML 템플릿 내부에 들어갈 값으로 변환한다.
     * 배열/문자열/null을 공통 규칙으로 정규화하고, 최종적으로 XSS 방지를 위해 HTML escape 처리한다.
     */
    private String renderTemplateValue(Object value, boolean multiline) {
        return renderTemplateValue(value, multiline, true);
    }

    private String renderTemplateValue(Object value, boolean multiline, boolean showSpeaker) {
        if (value == null) {
            return "";
        }
        if (value instanceof JSONArray) {
            return renderJsonArrayValue((JSONArray) value, multiline, showSpeaker);
        }
        String text = stringValue(value);
        // FLAT_DATA·LLM이 배열을 문자열("[\"a\",\"b\"]")로 넘겨도 JSONArray와 동일 규칙 적용
        JSONArray parsedArr = tryParseJsonArray(text);
        if (parsedArr != null) {
            return renderJsonArrayValue(parsedArr, multiline, showSpeaker);
        }
        return escapeHtml(text).replace("\r\n", "\n").replace("\n", multiline ? "<br/>" : " ");
    }

    /** JSONArray 또는 JSON 배열 문자열을 MULTILINE_YN에 맞게 HTML로 변환한다. */
    private String renderJsonArrayValue(JSONArray arr, boolean multiline, boolean showSpeaker) {
        if (multiline) {
            return renderTemplateListValue(arr, showSpeaker);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(escapeHtml(stringValue(arr.get(i))));
        }
        return sb.toString();
    }

    /** "[...]" 형태 문자열을 JSON 배열로 파싱. 실패 시 null. */
    private JSONArray tryParseJsonArray(String text) {
        if (text.isEmpty() || !text.startsWith("[") || !text.endsWith("]")) {
            return null;
        }
        try {
            Object parsed = new JSONParser().parse(text);
            if (parsed instanceof JSONArray) {
                return (JSONArray) parsed;
            }
        } catch (Exception ignore) {
            // 배열 문자열이 아니면 일반 문자열로 처리한다.
        }
        return null;
    }

    /**
     * MULTILINE_YN=Y 배열 값을 리스트 형태의 HTML 문자열로 렌더링한다.
     * 각 원소는 줄 단위로 분해한 뒤 빈 줄을 제거한다.
     * 리스트 마커가 없으면 기본 "- "를 붙이고, 항목 사이는 "<br/>"로 구분한다.
     * showSpeaker=false 이면 각 항목 끝의 (발언자) 패턴을 제거한다.
     */
    private String renderTemplateListValue(JSONArray arr) {
        return renderTemplateListValue(arr, true);
    }

    private String renderTemplateListValue(JSONArray arr, boolean showSpeaker) {
        if (arr == null || arr.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Object row : arr) {
            String item = stringValue(row);
            if (item.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("<br/>");
            }
            String processed = showSpeaker ? item : stripSpeakerAnnotationPerLine(item);
            sb.append(formatTemplateCellHtml(processed));
        }
        return sb.toString();
    }

    /** 항목 단위로 (발언자) 제거 — 줄 단위 분해 없이 표·문단 구조를 유지한다. */
    private static String stripSpeakerAnnotationPerLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(stripSpeakerAnnotation(line));
        }
        return sb.toString();
    }

    private static boolean containsHtmlTableTag(String text) {
        return text != null && text.toLowerCase().contains("<table");
    }

    /**
     * 텍스트 + HTML table 혼합 본문: table 태그는 그대로 두고 앞뒤만 escape한다.
     */
    private static String renderTextWithEmbeddedHtmlTables(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        Pattern tablePattern = Pattern.compile("(?is)<table\\b[^>]*>.*?</table>");
        Matcher matcher = tablePattern.matcher(normalized);
        StringBuilder out = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            String before = normalized.substring(lastEnd, matcher.start());
            if (!before.isEmpty()) {
                appendWithBr(out, renderPlainTextWithMarkdownTables(before));
            }
            appendWithBr(out, matcher.group());
            lastEnd = matcher.end();
        }
        String tail = normalized.substring(lastEnd);
        if (!tail.isEmpty()) {
            appendWithBr(out, renderPlainTextWithMarkdownTables(tail));
        }
        return out.toString();
    }

    /**
     * 문자열 끝의 (발언자) 패턴을 제거한다.
     * 예: "출시 일정 확정 (홍길동)" → "출시 일정 확정"
     *     "- 예산 증액 (김철수)" → "- 예산 증액"
     * 마지막 괄호쌍만 제거하므로 중간의 괄호는 유지된다.
     */
    private static String stripSpeakerAnnotation(String text) {
        if (text == null) return "";
        // 끝에 위치한 \s*(한글/영문/공백 포함 괄호)\s* 패턴 제거
        return text.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
    }

    /**
     * 리스트 항목의 선행 마커를 정규화한다.
     * 이미 -, *, • 로 시작하면 유지한다.
     * 숫자 목록(1), 1. 형태도 유지한다.
     * 그 외 텍스트는 "- " 접두어를 추가한다.
     */
    private String normalizeListMarker(String line) {
        if (line.startsWith("-") || line.startsWith("*") || line.startsWith("•")) {
            return line;
        }
        if (line.matches("^\\d+[.)]\\s?.*")) {
            return line;
        }
        return "- " + line;
    }

    /**
     * 템플릿 table row 복제를 위한 값 목록 추출.
     * 입력 타입이 배열(JSONArray/배열 문자열)이면 항목 리스트를 그대로 추출하고,
     * 그 외에는 단일 항목 리스트로 반환한다.
     */
    private List<String> extractTemplateRowValues(Object value) {
        List<String> rows = new ArrayList<>();
        if (value == null) {
            return rows;
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (Object item : arr) {
                String text = stringValue(item);
                if (!text.isEmpty()) {
                    rows.add(text);
                }
            }
            return rows;
        }

        String text = stringValue(value);
        if (text.isEmpty()) {
            return rows;
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                Object parsed = new JSONParser().parse(text);
                if (parsed instanceof JSONArray) {
                    JSONArray arr = (JSONArray) parsed;
                    for (Object item : arr) {
                        String line = stringValue(item);
                        if (!line.isEmpty()) {
                            rows.add(line);
                        }
                    }
                    return rows;
                }
            } catch (Exception ignore) {
                // 배열 문자열 파싱 실패 시 단일 row로 처리한다.
            }
        }
        rows.add(text);
        return rows;
    }

    /**
     * 템플릿 행 내부 placeholder({{...}}) 개수를 센다.
     * row 복제 여부를 판단하는 기준값으로 사용한다.
     */
    private int countTemplatePlaceholders(String text) {
        if (CommonUtil.isEmpty(text)) {
            return 0;
        }
        Matcher matcher = Pattern.compile("\\{\\{[^}]+\\}\\}").matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String stringValue(Object obj) {
        return obj == null ? "" : String.valueOf(obj).trim();
    }

    /**
     * 셀/리스트 텍스트를 HTML로 변환한다.
     * [[CDOC_IMG:N]] 토큰만 escape 없이 createDoc 전용 img 플레이스홀더로 치환한다.
     */
    private static String formatTemplateCellHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (containsHtmlTableTag(normalized)) {
            return renderTextWithEmbeddedHtmlTables(normalized);
        }
        if (!CREATE_DOC_IMG_TOKEN_PATTERN.matcher(normalized).find()
                && !CREATE_DOC_IMG_TAG_PATTERN.matcher(normalized).find()) {
            return renderPlainTextWithMarkdownTables(normalized);
        }
        StringBuilder sb = new StringBuilder();
        Pattern combined = Pattern.compile(
                "\\[\\[" + CREATE_DOC_IMG_TOKEN + ":(\\d+)\\]\\]|<img\\s+" + CREATE_DOC_IMG_ATTR + "=\"(\\d+)\">",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = combined.matcher(normalized);
        int lastEnd = 0;
        while (matcher.find()) {
            String plain = normalized.substring(lastEnd, matcher.start());
            if (!plain.isEmpty()) {
                sb.append(renderPlainTextWithMarkdownTables(plain));
            }
            String idx = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            sb.append("<img ").append(CREATE_DOC_IMG_ATTR).append("=\"").append(idx).append("\">");
            lastEnd = matcher.end();
        }
        String tail = normalized.substring(lastEnd);
        if (!tail.isEmpty()) {
            sb.append(renderPlainTextWithMarkdownTables(tail));
        }
        return sb.toString();
    }

    /**
     * 일반 텍스트는 escape·줄바꿈 처리하고, 연속된 마크다운 파이프 표는 &lt;table&gt; HTML로 변환한다.
     */
    private static String renderPlainTextWithMarkdownTables(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder();
        List<String> tableLines = new ArrayList<>();

        for (String line : lines) {
            if (isMarkdownTableRow(line)) {
                tableLines.add(line);
                continue;
            }
            if (!tableLines.isEmpty()) {
                appendWithBr(out, renderMarkdownTableBlock(tableLines));
                tableLines.clear();
            }
            if (!line.isEmpty() || out.length() > 0) {
                appendWithBr(out, escapeHtml(line));
            }
        }
        if (!tableLines.isEmpty()) {
            appendWithBr(out, renderMarkdownTableBlock(tableLines));
        }
        return out.toString();
    }

    private static void appendWithBr(StringBuilder out, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append("<br/>");
        }
        out.append(chunk);
    }

    private static boolean isMarkdownTableRow(String line) {
        return line != null && MARKDOWN_TABLE_ROW_PATTERN.matcher(line).matches();
    }

    private static boolean isMarkdownSeparatorRow(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        return t.startsWith("|") && t.contains("-") && t.matches("^\\|?[\\s|:\\-]+\\|?$");
    }

    private static String[] parseMarkdownTableCells(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        String[] parts = t.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static String renderMarkdownTableBlock(List<String> lines) {
        List<String> bodyLines = new ArrayList<>();
        boolean hasSeparator = false;
        for (String line : lines) {
            if (isMarkdownSeparatorRow(line)) {
                hasSeparator = true;
                continue;
            }
            bodyLines.add(line);
        }
        if (bodyLines.isEmpty()) {
            return escapeHtml(String.join("\n", lines)).replace("\n", "<br/>");
        }
        StringBuilder table = new StringBuilder("<table><tbody>");
        for (int i = 0; i < bodyLines.size(); i++) {
            boolean headerRow = hasSeparator && i == 0;
            String[] cells = parseMarkdownTableCells(bodyLines.get(i));
            table.append("<tr>");
            for (String cell : cells) {
                if (headerRow) {
                    table.append("<th>").append(escapeHtml(cell)).append("</th>");
                } else {
                    table.append("<td>").append(escapeHtml(cell)).append("</td>");
                }
            }
            table.append("</tr>");
        }
        table.append("</tbody></table>");
        return table.toString();
    }

    /** 템플릿 렌더링 결과를 HTML 문맥에 안전하게 삽입하기 위한 최소 escape. */
    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
