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
            // table 레이아웃은 동일 row 복제 규칙이 필요하므로 전용 처리.
            if ("table".equalsIgnoreCase(stringValue(field.getLayoutType()))) {
                html = renderTableRows(html, key, json.get(key), "Y".equals(field.getMultilineYn()));
                continue;
            }
            // table 외 레이아웃은 placeholder 1:1 치환.
            String value = renderTemplateValue(json.get(key), "Y".equals(field.getMultilineYn()));
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
                        String cellValue = escapeHtml(rows.get(rowIdx)).replace("\r\n", "\n").replace("\n", "<br/>");
                        out.append(tr.replace(placeholder, cellValue)
                                     .replace("{{no}}", String.valueOf(rowIdx + 1)));
                    }
                }
            } else {
                // 같은 row에 placeholder가 여러 개 섞여 있으면 복제 로직보다 단일 치환이 안전하다.
                String singleValue = renderTemplateValue(value, multiline);
                out.append(tr.replace(placeholder, singleValue).replace("{{no}}", ""));
            }
            lastEnd = matcher.end();
        }
        out.append(html.substring(lastEnd));

        if (!matchedRow) {
            String fallback = renderTemplateValue(value, multiline);
            return html.replace(placeholder, fallback).replace("{{no}}", "");
        }
        return out.toString();
    }

    /**
     * HTML 템플릿 내부에 들어갈 값으로 변환한다.
     * 배열/문자열/null을 공통 규칙으로 정규화하고, 최종적으로 XSS 방지를 위해 HTML escape 처리한다.
     */
    private String renderTemplateValue(Object value, boolean multiline) {
        if (value == null) {
            return "";
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            if (multiline) {
                // 멀티라인 배열은 목록 형태로 출력(줄 구분 유지).
                return renderTemplateListValue(arr);
            }
            // 일반 배열은 콤마 구분 단일 라인으로 출력.
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
                // 문자열로 들어온 JSON 배열도 동일 규칙으로 처리.
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

    /**
     * MULTILINE_YN=Y 배열 값을 리스트 형태의 HTML 문자열로 렌더링한다.
     * 각 원소는 줄 단위로 분해한 뒤 빈 줄을 제거한다.
     * 리스트 마커가 없으면 기본 "- "를 붙이고, 항목 사이는 "<br/>"로 구분한다.
     */
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
                // 마커 정규화 후 escape를 적용해 템플릿 삽입 시 안전성을 확보한다.
                sb.append(escapeHtml(normalizeListMarker(normalized)));
            }
        }
        return sb.toString();
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
