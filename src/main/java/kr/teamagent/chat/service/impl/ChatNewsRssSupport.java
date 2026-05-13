package kr.teamagent.chat.service.impl;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.RestApiManager;

final class ChatNewsRssSupport {

    private static final String YONHAP_LABEL = "연합뉴스";
    private static final int MAX_ITEMS_PER_FEED = 14;
    private static final Pattern MEDIA_CONTENT_URL_THEN_JPEG = Pattern.compile(
            "(?i)<media:content\\s+url=[\"']([^\"']+)[\"']\\s+type=[\"']image/jpeg[\"']");

    private ChatNewsRssSupport() {
    }

    private static final class FeedSpec {
        final String propKey;
        final String rssCategory;

        FeedSpec(String propKey, String rssCategory) {
            this.propKey = propKey;
            this.rssCategory = rssCategory;
        }
    }

    /**
     * 사용자 관심 표준 카테고리별 연합뉴스 RSS. 알 수 없는 값은 무시한다.
     */
    private static List<FeedSpec> feedsForInterest(String interest) {
        if (interest == null) {
            return Collections.emptyList();
        }
        String cat = interest.trim();
        switch (cat) {
        case "정치":
            return Collections.singletonList(new FeedSpec("Globals.news.rss.yna.politics", "정치"));
        case "경제":
            return Collections.singletonList(new FeedSpec("Globals.news.rss.yna.economy", "경제"));
        case "사회":
            return Collections.singletonList(new FeedSpec("Globals.news.rss.yna.society", "사회"));
        case "생활/문화":
            List<FeedSpec> life = new ArrayList<>();
            life.add(new FeedSpec("Globals.news.rss.yna.health", "생활/문화"));
            life.add(new FeedSpec("Globals.news.rss.yna.sports", "생활/문화"));
            life.add(new FeedSpec("Globals.news.rss.yna.culture", "생활/문화"));
            life.add(new FeedSpec("Globals.news.rss.yna.entertainment", "생활/문화"));
            return life;
        case "산업":
            return Collections.singletonList(new FeedSpec("Globals.news.rss.yna.industry", "산업"));
        default:
            return Collections.emptyList();
        }
    }

    static List<ChatbotVO.RssArticleRow> collectCandidates(RestApiManager restApiManager, Logger log,
            List<String> interests) {
        List<ChatbotVO.RssArticleRow> out = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", "Mozilla/5.0 (compatible; TeamAgent/1.0; +https://example.invalid/news-bot)");

        List<String> cats = interests != null ? interests : new ArrayList<>();
        for (String interest : cats) {
            if (interest == null || interest.trim().isEmpty()) {
                continue;
            }
            List<FeedSpec> specs = feedsForInterest(interest);
            if (specs.isEmpty()) {
                log.warn("뉴스 RSS: 알 수 없는 관심 카테고리 무시: {}", interest);
                continue;
            }
            for (FeedSpec fs : specs) {
                addFeed(out, seenLinks, restApiManager, header, fs.propKey, fs.rssCategory, log);
            }
        }

        int idx = 0;
        for (ChatbotVO.RssArticleRow row : out) {
            row.setId(idx++);
        }
        return out;
    }

    /** properties 비어 있으면 null */
    private static String feedUrlFromProperty(String propKey) {
        String v = PropertyUtil.getProperty(propKey);
        return (v != null && !v.trim().isEmpty()) ? v.trim() : null;
    }

    private static void addFeed(List<ChatbotVO.RssArticleRow> sink, Set<String> seenLinks,
            RestApiManager restApiManager, Map<String, String> header, String propKey, String rssCategory, Logger log) {
        String feedUrl = feedUrlFromProperty(propKey);
        if (feedUrl == null) {
            log.warn("RSS 수집 생략: properties에 URL 없음 propKey={}", propKey);
            return;
        }
        try {
            String xml = restApiManager.getResponseString(feedUrl, header);
            if (xml == null || xml.trim().isEmpty()) {
                return;
            }
            List<ChatbotVO.RssArticleRow> parsed = parseYonhapRssFeed(xml, rssCategory);
            int n = Math.min(MAX_ITEMS_PER_FEED, parsed.size());
            for (int i = 0; i < n; i++) {
                ChatbotVO.RssArticleRow row = parsed.get(i);
                String lk = row.getLink() != null ? row.getLink().trim() : "";
                if (!lk.isEmpty() && !seenLinks.add(lk)) {
                    continue;
                }
                sink.add(row);
            }
        } catch (Exception e) {
            log.warn("RSS 수집 실패 {} {}: {}", YONHAP_LABEL, feedUrl, e.getMessage());
        }
    }

    /** 연합뉴스 RSS XML → 후보 행. 썸네일: 원본 XML {@code <item>} 내 {@code media:content} JPEG. */
    private static List<ChatbotVO.RssArticleRow> parseYonhapRssFeed(String xml, String rssCategory) throws Exception {
        SyndFeedInput input = new SyndFeedInput();
        input.setPreserveWireFeed(true);
        List<ChatbotVO.RssArticleRow> rows = new ArrayList<>();
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))) {
            SyndFeed feed = input.build(reader);
            for (SyndEntry entry : feed.getEntries()) {
                String title = entry.getTitle() != null ? entry.getTitle().trim() : "";
                String link = resolveLink(entry);
                String descriptionHtml = resolveDescriptionHtml(entry);
                String snippet = shorten(stripHtml(descriptionHtml), 400);
                String imageUrl = yonhapMediaContentJpegInRawItemXml(xml, link);

                if (title.isEmpty() && link.isEmpty()) {
                    continue;
                }
                ChatbotVO.RssArticleRow row = new ChatbotVO.RssArticleRow();
                row.setPressLabel(YONHAP_LABEL);
                row.setRssCategory(rssCategory != null ? rssCategory : "");
                row.setTitle(title);
                row.setLink(link);
                row.setSnippet(snippet);
                row.setImageUrl(imageUrl);
                rows.add(row);
            }
        }
        return rows;
    }

    private static String resolveLink(SyndEntry entry) {
        if (entry.getLink() != null && !entry.getLink().trim().isEmpty()) {
            return entry.getLink().trim();
        }
        return "";
    }

    private static String resolveDescriptionHtml(SyndEntry entry) {
        SyndContent desc = entry.getDescription();
        if (desc != null && desc.getValue() != null) {
            return desc.getValue();
        }
        return "";
    }

    /**
     * 연합 RSS에서 {@code media:content}는 보통 description 밖에 있어 Rome 문자열 필드에는 안 들어온다.
     * 원본 XML에서 이 기사 {@code <link>}이 속한 {@code <item>} 블록만 잘라 {@code media:content} JPEG URL을 고른다.
     */
    private static String yonhapMediaContentJpegInRawItemXml(String fullXml, String itemLink) {
        if (itemLink == null || itemLink.trim().isEmpty()) {
            return "";
        }
        String needle = itemLink.trim();
        int linkPos = fullXml.indexOf(needle);
        if (linkPos < 0) {
            return "";
        }
        int itemStart = fullXml.lastIndexOf("<item>", linkPos);
        if (itemStart < 0) {
            return "";
        }
        int itemEnd = fullXml.indexOf("</item>", linkPos);
        if (itemEnd < 0) {
            return "";
        }
        String itemXml = fullXml.substring(itemStart, itemEnd + "</item>".length());
        Matcher m = MEDIA_CONTENT_URL_THEN_JPEG.matcher(itemXml);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    static Map<String, Object> candidateForPrompt(ChatbotVO.RssArticleRow r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("source", r.getPressLabel());
        m.put("title", r.getTitle());
        m.put("link", r.getLink());
        m.put("snippet", r.getSnippet());
        m.put("rssCategory", r.getRssCategory() != null ? r.getRssCategory() : "");
        return m;
    }
}
