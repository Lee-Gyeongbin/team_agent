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

    /**
     * TB_TMPL.TMPL_HTML의 {{jsonKey}} 자리에 LLM JSON 값을 채운다.
     */
    public String renderTemplateHtml(String tmplHtml, JSONObject json, List<LibraryVO.TmplFieldItem> tmplFieldList) throws Exception {
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
            if ("table".equalsIgnoreCase(stringValue(field.getLayoutType()))) {
                html = renderTableRows(html, key, json.get(key), "Y".equals(field.getMultilineYn()));
                continue;
            }
            String value = renderTemplateValue(json.get(key), "Y".equals(field.getMultilineYn()));
            html = html.replace("{{" + key + "}}", value);
        }
        return html;
    }

    /**
     * LAYOUT_TYPE=table 필드는 {{jsonKey}}가 포함된 tr을 배열 길이만큼 복제한다.
     */
    private String renderTableRows(String html, String key, Object value, boolean multiline) {
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
            if (countTemplatePlaceholders(tr) == 1) {
                List<String> rows = extractTemplateRowValues(value);
                if (rows.isEmpty()) {
                    out.append(tr.replace(placeholder, ""));
                } else {
                    for (String row : rows) {
                        String cellValue = escapeHtml(row).replace("\r\n", "\n").replace("\n", "<br/>");
                        out.append(tr.replace(placeholder, cellValue));
                    }
                }
            } else {
                String singleValue = renderTemplateValue(value, multiline);
                out.append(tr.replace(placeholder, singleValue));
            }
            lastEnd = matcher.end();
        }
        out.append(html.substring(lastEnd));

        if (!matchedRow) {
            String fallback = renderTemplateValue(value, multiline);
            return html.replace(placeholder, fallback);
        }
        return out.toString();
    }

    /**
     * HTML 템플릿 내부에 들어갈 값으로 변환한다.
     */
    private String renderTemplateValue(Object value, boolean multiline) {
        if (value == null) {
            return "";
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            if (multiline) {
                return renderTemplateListValue(arr);
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
        String text = stringValue(value);
        if (multiline && text.startsWith("[") && text.endsWith("]")) {
            try {
                Object parsed = new JSONParser().parse(text);
                if (parsed instanceof JSONArray) {
                    return renderTemplateListValue((JSONArray) parsed);
                }
            } catch (Exception ignore) {
                // 배열 문자열이 아니면 일반 문자열로 처리한다.
            }
        }
        return escapeHtml(text).replace("\r\n", "\n").replace("\n", multiline ? "<br/>" : " ");
    }

    /** MULTILINE_YN=Y 배열 값을 리스트 형태로 렌더링 */
    private String renderTemplateListValue(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Object row : arr) {
            String item = stringValue(row);
            if (item.isEmpty()) {
                continue;
            }

            String[] lines = item.replace("\r\n", "\n").replace('\r', '\n').split("\n");
            for (String line : lines) {
                String normalized = line == null ? "" : line.trim();
                if (normalized.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("<br/>");
                }
                sb.append(escapeHtml(normalizeListMarker(normalized)));
            }
        }
        return sb.toString();
    }

    /** 리스트 마커가 없으면 기본 '-' 마커를 붙인다. */
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

    /** 템플릿 행 내부 placeholder({{...}}) 개수 */
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
