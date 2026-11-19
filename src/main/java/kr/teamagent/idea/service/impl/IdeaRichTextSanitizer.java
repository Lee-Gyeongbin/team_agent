package kr.teamagent.idea.service.impl;

import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

final class IdeaRichTextSanitizer {
    private static final String[] TAGS = {
        "b", "strong", "i", "em", "u", "s", "strike", "p", "br",
        "ul", "ol", "li", "h3", "blockquote", "a"
    };
    private static final Safelist ALLOWED_HTML = new Safelist()
            .addTags(TAGS)
            .addAttributes("ol", "start")
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

    private IdeaRichTextSanitizer() {
    }

    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String html = HTML_TAG.matcher(value).find() ? value : value.replaceAll("\\r\\n?|\\n", "<br>");
        return Jsoup.clean(html, "", ALLOWED_HTML, new Document.OutputSettings().prettyPrint(false))
                .replace("&lt;", "&#60;");
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
