package kr.teamagent.idea.service.impl;

import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

final class IdeaRichTextSanitizer {
    private static final String[] TAGS = {
        "b", "strong", "i", "em", "u", "s", "strike", "p", "br",
        "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "a", "span", "mark",
        "pre", "code", "hr", "table", "colgroup", "col", "thead", "tbody", "tfoot", "tr", "th", "td"
    };
    private static final Safelist ALLOWED_HTML = new Safelist()
            .addTags(TAGS)
            .addAttributes("ol", "start")
            .addAttributes(":all", "style")
            .addAttributes("th", "colspan", "rowspan", "data-colwidth")
            .addAttributes("td", "colspan", "rowspan", "data-colwidth")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "target", "_blank")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer");
    private static final Pattern HTML_TAG = Pattern.compile("<[/]?[a-z][\\w-]*(?=[\\s/>])[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_ESCAPED_TAG = Pattern.compile(
            "&lt;/?(?:" + String.join("|", TAGS) + ")(?=\\s|/|&gt;|>)(?:(?!&lt;|&gt;)[^<>])*(?:&gt;|>)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPED_OPEN = Pattern.compile("&lt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPED_CLOSE = Pattern.compile("&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern EDITOR_STYLE = Pattern.compile(
            "(?:text-align\\s*:\\s*(?:left|center|right|justify)"
            + "|(?:font-size|width|min-width)\\s*:\\s*\\d{1,4}(?:\\.\\d{1,2})?px"
            + "|(?:color|background-color)\\s*:\\s*(?:#[0-9a-f]{3,8}|rgba?\\([0-9.,% ]{1,40}\\)))",
            Pattern.CASE_INSENSITIVE);

    private IdeaRichTextSanitizer() {
    }

    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String html = HTML_TAG.matcher(value).find() ? value : value.replaceAll("\\r\\n?|\\n", "<br>");
        Document cleaned = Jsoup.parseBodyFragment(Jsoup.clean(
                html, "", ALLOWED_HTML, new Document.OutputSettings().prettyPrint(false)));
        cleaned.outputSettings().prettyPrint(false);
        for (org.jsoup.nodes.Element element : cleaned.select("[style]")) {
            String safeStyle = java.util.Arrays.stream(element.attr("style").split(";"))
                    .map(String::trim)
                    .filter(declaration -> EDITOR_STYLE.matcher(declaration).matches())
                    .collect(java.util.stream.Collectors.joining("; "));
            if (safeStyle.isEmpty()) {
                element.removeAttr("style");
            } else {
                element.attr("style", safeStyle);
            }
        }
        return cleaned.body().html().replace("&lt;", "&#60;");
    }

    static String cleanStored(String value) {
        if (value == null) {
            return null;
        }

        String restored = LEGACY_ESCAPED_TAG.matcher(value).replaceAll(match -> {
            String tag = ESCAPED_OPEN.matcher(match.group()).replaceFirst("<");
            return java.util.regex.Matcher.quoteReplacement(ESCAPED_CLOSE.matcher(tag).replaceFirst(">"));
        });
        return clean(restored);
    }
}
