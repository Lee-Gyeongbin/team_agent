package kr.teamagent.meeting.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * 회의록 양식 — 시스템 프롬프트 조립 + 회의록 JSON → 에디터 HTML 렌더링 (CMB MinutesTemplate 이식)
 *
 * - 프롬프트 문구는 코드에 두지 않는다. 조각(공통 작성 원칙 · 양식 블록 · 문체 · 분량 · 발언자 표시)은 DB 에서 받아
 *   CMB 와 같은 순서·구분자로 이어 붙이기만 한다. (공통 + "\n\n" + 양식 + "\n\n" + "[작성 옵션]\n…")
 * - 요약 문장 수(회의 길이별)처럼 숫자로 정해지는 값은 여기서 계산해 양식 블록의 {{SUMMARY_RANGE}} 에 넣는다.
 * - 렌더링은 양식 코드(standard / report / agenda / verbatim)별 구성이 CMB 와 같다.
 *   HTML 은 에디터 · PDF · Word 가 그대로 읽도록 h1/h2/p/ul/ol/table 만 쓴다.
 */
final class MeetingMinutesTemplate {

    static final String DEFAULT_TEMPLATE = "standard";
    static final String DEFAULT_TONE = "bullet";
    static final String DEFAULT_LENGTH = "normal";

    static final List<String> TEMPLATES = Arrays.asList("standard", "report", "agenda", "verbatim");
    static final List<String> TONES = Arrays.asList("bullet", "plain", "polite");
    static final List<String> LENGTHS = Arrays.asList("normal", "long");

    /** 양식 블록에서 요약 문장 수가 들어갈 자리 */
    static final String SUMMARY_RANGE_PLACEHOLDER = "{{SUMMARY_RANGE}}";

    private MeetingMinutesTemplate() {
    }

    static String normalizeTemplate(String code) {
        return code != null && TEMPLATES.contains(code) ? code : DEFAULT_TEMPLATE;
    }

    static String normalizeTone(String code) {
        return code != null && TONES.contains(code) ? code : DEFAULT_TONE;
    }

    static String normalizeLength(String code) {
        return code != null && LENGTHS.contains(code) ? code : DEFAULT_LENGTH;
    }

    // ===================== 프롬프트 =====================

    /** 표준 양식 요약 문장 수 — 회의 길이별 (긴 회의에서 요약이 잘리지 않도록), 상세 분량이면 한 단계 늘림 */
    static String summaryRange(String length, double durationMin) {
        int level = durationMin <= 20 ? 1 : durationMin <= 60 ? 2 : 3;
        if ("long".equals(length)) level++;
        switch (level) {
            case 1: return "3~5";
            case 2: return "5~8";
            case 3: return "8~12";
            default: return "12~15";
        }
    }

    /**
     * 시스템 프롬프트 = 공통 작성 원칙 + 양식 블록 + 작성 옵션 (CMB 와 같은 구분자)
     *
     * @param commonText   공통 작성 원칙 (TB_PROMPT)
     * @param templateText 양식 블록 (TB_TMPL.LLM_PROMPT) — {{SUMMARY_RANGE}} 자리 포함 가능
     * @param toneText     "- 문체: …" (TB_PROMPT)
     * @param lengthText   "- 분량: …" (TB_PROMPT)
     * @param speakerText  "- 발언자 표시: …" (TB_PROMPT)
     * @param wantTitle    자동 제목이면 양식의 [응답 JSON 키] 바로 아래에 title 키를 끼워 넣음
     */
    static String buildSystemPrompt(String commonText, String templateText, String toneText, String lengthText,
                                    String speakerText, String length, boolean wantTitle, double durationMin) {
        // DB 문구는 DBeaver(Windows) 입력 시 \r\n — 줄바꿈을 \n 으로 통일해야 아래 title 키 삽입이 동작하고 CMB 원문과 같아짐
        commonText = lf(commonText);
        templateText = lf(templateText);
        toneText = lf(toneText);
        lengthText = lf(lengthText);
        speakerText = lf(speakerText);
        String tplText = templateText.replace(SUMMARY_RANGE_PLACEHOLDER, summaryRange(normalizeLength(length), durationMin));
        if (wantTitle) {
            tplText = tplText.replace("[응답 JSON 키]\n", "[응답 JSON 키]\n- title: 회의를 대표하는 제목 한 줄 (30자 이내)\n");
        }
        String option = "[작성 옵션]\n" + toneText.trim() + "\n" + lengthText.trim() + "\n" + speakerText.trim();
        return commonText.trim() + "\n\n" + tplText.trim() + "\n\n" + option;
    }

    /** 줄바꿈 통일 (\r\n, \r → \n) */
    private static String lf(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 이 양식으로 만든 회의록 JSON 인지 — 이전(TB_TMPL 필드 기반) 회의록은 렌더러로 다시 그리면 비므로 구분한다 */
    static boolean isTemplateJson(String template, JSONObject json) {
        if (json == null) return false;
        switch (normalizeTemplate(template)) {
            case "report":   return json.containsKey("results") || json.containsKey("background");
            case "agenda":   return json.containsKey("agendas");
            case "verbatim": return json.containsKey("timeline");
            default:         return json.containsKey("discussions") || json.containsKey("summary");
        }
    }

    /** DB 일시 문자열(yyyy-MM-dd HH:mm:ss[.S]) → yyyy.MM.dd HH:mm, 형식이 다르면 원문 그대로 */
    static String formatDateTime(String dt) {
        if (dt == null || dt.trim().isEmpty()) return "";
        Matcher m = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})").matcher(dt.trim());
        return m.find() ? m.group(1) + "." + m.group(2) + "." + m.group(3) + " " + m.group(4) + ":" + m.group(5) : dt.trim();
    }

    // ===================== 렌더러 (회의록 JSON → 에디터 HTML) =====================

    static String render(String template, JSONObject json, String title, String startDt, List<String> attendeeNames) {
        String tpl = normalizeTemplate(template);
        StringBuilder html = new StringBuilder();
        String t = esc(nvl(title, "회의록"));
        String people = attendeeNames == null || attendeeNames.isEmpty() ? "-" : String.join(", ", attendeeNames);

        switch (tpl) {
            case "report":
                html.append("<h1>").append(t).append("</h1>");
                html.append("<h2>1. 개요</h2>")
                    .append(rp(2, "일시 : " + nvl(startDt, "-")))
                    .append(rp(2, "참석 : " + people));
                html.append("<h2>2. 추진 배경</h2>").append(rpList(1, json.get("background")));
                html.append("<h2>3. 논의 결과</h2>");
                JSONArray results = array(json.get("results"));
                if (results.isEmpty()) html.append("<p>-</p>");
                for (Object obj : results) {
                    JSONObject r = object(obj);
                    if (r == null) { html.append(rp(1, str(obj))); continue; }
                    html.append("<p><strong>□ ").append(esc(str(r.get("topic")))).append("</strong></p>");
                    for (Object d : array(r.get("details"))) html.append(rp(2, str(d)));
                    for (Object d : array(r.get("decisions"))) html.append(rp(3, "(결정) " + str(d)));
                }
                html.append("<h2>4. 향후 계획</h2>");
                JSONArray plans = array(json.get("plans"));
                if (plans.isEmpty()) html.append("<p>-</p>");
                for (Object obj : plans) {
                    JSONObject p = object(obj);
                    if (p == null) { html.append(rp(2, str(obj))); continue; }
                    String who = joinNonEmpty(str(p.get("owner")), str(p.get("due")).isEmpty() ? "" : "~" + str(p.get("due")));
                    html.append(rp(2, str(p.get("task")) + (who.isEmpty() ? "" : " (" + who + ")")));
                }
                html.append("<h2>5. 미결 사항</h2>").append(rpList(2, json.get("open_issues")));
                break;
            case "agenda":
                html.append("<h1>").append(t).append("</h1>").append(overview(startDt, people));
                JSONArray agendas = array(json.get("agendas"));
                int no = 0;
                for (Object obj : agendas) {
                    JSONObject a = object(obj);
                    no++;
                    if (a == null) { html.append("<h2>안건 ").append(no).append(". ").append(esc(str(obj))).append("</h2>"); continue; }
                    html.append("<h2>안건 ").append(no).append(". ").append(esc(str(a.get("topic")))).append("</h2>")
                        .append("<table><tr><th colwidth=\"80\" style=\"width:80px\">논의</th><td>").append(list("ul", a.get("discussions"))).append("</td></tr>")
                        .append("<tr><th>결정</th><td>").append(list("ul", a.get("decisions"))).append("</td></tr>")
                        .append("<tr><th>후속</th><td>").append(actionLines(a.get("actions"))).append("</td></tr></table>");
                }
                if (agendas.isEmpty()) html.append("<h2>안건</h2><p>-</p>");
                html.append("<h2>미결 사항</h2>").append(list("ul", json.get("open_issues")));
                break;
            case "verbatim":
                html.append("<h1>").append(t).append("</h1>").append(overview(startDt, people));
                html.append("<h2>1. 시간순 발언 요지</h2>");
                JSONArray timeline = array(json.get("timeline"));
                if (timeline.isEmpty()) {
                    html.append("<p>-</p>");
                } else {
                    html.append("<table><tr><th colwidth=\"70\" style=\"width:70px\">시각</th><th colwidth=\"90\" style=\"width:90px\">발언자</th><th>발언 요지</th></tr>");
                    for (Object obj : timeline) {
                        JSONObject x = object(obj);
                        if (x == null) { html.append("<tr><td></td><td></td><td>").append(esc(str(obj))).append("</td></tr>"); continue; }
                        html.append("<tr><td>").append(esc(str(x.get("time")))).append("</td><td>").append(esc(str(x.get("speaker"))))
                            .append("</td><td>").append(esc(str(x.get("point")))).append("</td></tr>");
                    }
                    html.append("</table>");
                }
                html.append("<h2>2. 결정 사항</h2>").append(list("ol", json.get("decisions")));
                html.append("<h2>3. 후속 조치</h2>").append(actionTable(json.get("action_items")));
                break;
            default:
                html.append("<h1>").append(t).append("</h1>").append(overview(startDt, people));
                html.append("<h2>1. 회의 요약</h2>").append(summaryParagraphs(json.get("summary")));
                html.append("<h2>2. 주요 논의 내용</h2>").append(discussions(json.get("discussions")));
                html.append("<h2>3. 결정 사항</h2>").append(list("ol", collectDecisions(json)));
                html.append("<h2>4. 미결·검토 사항</h2>").append(list("ul", json.get("open_issues")));
                html.append("<h2>5. 후속 조치</h2>").append(actionTable(json.get("action_items")));
                break;
        }
        return html.toString();
    }

    private static String overview(String startDt, String people) {
        return "<table><tr><th colwidth=\"110\" style=\"width:110px\">일시</th><td>" + esc(nvl(startDt, "")) + "</td></tr>"
            + "<tr><th>참석자</th><td>" + esc(people) + "</td></tr></table>";
    }

    /** 회의 요약 — 한 문장씩 문단으로 (배열이면 원소마다, 문자열이면 마침표 기준으로 나눔) */
    private static String summaryParagraphs(Object value) {
        List<String> lines = new ArrayList<>();
        if (value instanceof JSONArray) {
            for (Object item : (JSONArray) value) lines.add(str(item));
        } else {
            lines.addAll(Arrays.asList(str(value).split("(?<=[.!?])\\s+|\\n+")));
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!line.trim().isEmpty()) sb.append("<p>").append(esc(line.trim())).append("</p>");
        }
        return sb.length() == 0 ? "<p>-</p>" : sb.toString();
    }

    /** 안건별 논의 — {topic, details[]} 는 안건 아래 세부 목록으로 (안건별 decisions 는 결정 사항 목차에만) */
    private static String discussions(Object value) {
        JSONArray arr = array(value);
        if (arr.isEmpty()) return "<p>-</p>";
        StringBuilder sb = new StringBuilder("<ul>");
        for (Object item : arr) {
            JSONObject d = object(item);
            if (d == null) {
                sb.append("<li>").append(esc(str(item))).append("</li>");
                continue;
            }
            sb.append("<li><strong>").append(esc(str(d.get("topic")))).append("</strong>");
            JSONArray details = array(d.get("details"));
            if (!details.isEmpty()) {
                sb.append("<ul>");
                for (Object x : details) sb.append("<li>").append(esc(detailLine(x))).append("</li>");
                sb.append("</ul>");
            }
            sb.append("</li>");
        }
        return sb.append("</ul>").toString();
    }

    /** point 가 이 어미 · 조사로 끝나면 result 로 이어지는 문장, 아니면 명사형 소제목 */
    private static final Pattern CONNECTIVE_END =
        Pattern.compile("(해|서|고|며|나|데|면|니|어|아|여|져|워|와|켜|쳐|라|도|만|은|는|로|을|를|에|결과|뒤|후)$");

    /** 논의 항목 한 줄 — 문자열이면 그대로, {point, result} 는 이어서 한 문장으로 */
    private static String detailLine(Object item) {
        JSONObject x = object(item);
        if (x == null) return str(item);
        return joinWords(str(x.get("point")), str(x.get("result")));
    }

    private static String joinWords(String a, String b) {
        a = a.replaceAll("\\s*[:：]+$", "");
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + (CONNECTIVE_END.matcher(a).find() ? " " : ": ") + b;
    }

    /** 표준 양식 결정 사항 목차 — 안건별 decisions 를 순서대로 모음 (없으면 최상위 decisions) */
    @SuppressWarnings("unchecked")
    private static JSONArray collectDecisions(JSONObject json) {
        JSONArray all = new JSONArray();
        for (Object item : array(json.get("discussions"))) {
            JSONObject d = object(item);
            if (d != null) all.addAll(array(d.get("decisions")));
        }
        return all.isEmpty() ? array(json.get("decisions")) : all;
    }

    private static String list(String tag, Object value) {
        JSONArray arr = array(value);
        if (arr.isEmpty()) return "<p>-</p>";
        StringBuilder sb = new StringBuilder("<").append(tag).append('>');
        for (Object item : arr) sb.append("<li>").append(esc(str(item))).append("</li>");
        return sb.append("</").append(tag).append('>').toString();
    }

    private static String actionTable(Object value) {
        JSONArray arr = array(value);
        if (arr.isEmpty()) return "<p>-</p>";
        // 담당 · 기한 폭은 colwidth 로 고정 (에디터 Tiptap 은 style 폭을 버리고 colwidth 만 읽음), 남는 폭은 항목
        StringBuilder sb = new StringBuilder("<table><tr><th>항목</th><th colwidth=\"170\" style=\"width:170px\">담당</th><th colwidth=\"170\" style=\"width:170px\">기한</th></tr>");
        for (Object obj : arr) {
            JSONObject item = object(obj);
            if (item == null) {
                sb.append("<tr><td>").append(esc(str(obj))).append("</td><td></td><td></td></tr>");
            } else {
                sb.append("<tr><td>").append(esc(str(item.get("task")))).append("</td>")
                  .append("<td>").append(esc(str(item.get("owner")))).append("</td>")
                  .append("<td>").append(esc(str(item.get("due")))).append("</td></tr>");
            }
        }
        return sb.append("</table>").toString();
    }

    /** 안건별 후속 조치 — 표 칸 안이라 한 줄씩 (할 일 — 담당 / 기한) */
    private static String actionLines(Object value) {
        JSONArray arr = array(value);
        if (arr.isEmpty()) return "-";
        List<String> lines = new ArrayList<>();
        for (Object obj : arr) {
            JSONObject item = object(obj);
            if (item == null) { lines.add(esc(str(obj))); continue; }
            String who = joinNonEmpty(str(item.get("owner")), str(item.get("due")));
            lines.add(esc(str(item.get("task")) + (who.isEmpty() ? "" : " — " + who)));
        }
        return String.join("<br>", lines);
    }

    /** 보고서형 계층 문단 — 1: □, 2: ○, 3: - (Word·PDF 에서도 같게 보이도록 기호와 공백을 글자로 넣음) */
    private static String rp(int level, String text) {
        String prefix = level == 1 ? "□ " : level == 2 ? "&nbsp;&nbsp;○ " : "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;- ";
        return "<p>" + prefix + esc(text) + "</p>";
    }

    private static String rpList(int level, Object value) {
        JSONArray arr = array(value);
        if (arr.isEmpty()) return "<p>-</p>";
        StringBuilder sb = new StringBuilder();
        for (Object item : arr) sb.append(rp(level, str(item)));
        return sb.toString();
    }

    private static String joinNonEmpty(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " / " + b;
    }

    private static JSONArray array(Object value) {
        return value instanceof JSONArray ? (JSONArray) value : new JSONArray();
    }

    private static JSONObject object(Object value) {
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    private static String str(Object obj) {
        return obj == null ? "" : String.valueOf(obj).trim();
    }

    private static String nvl(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
