package kr.teamagent.common.util;

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

/**
 * 연합뉴스 RSS 수집·뉴스 큐레이션 프롬프트용 매핑 유틸.
 */
public final class NewsRssUtil {

    private static final String YONHAP_LABEL = "연합뉴스";
    private static final Pattern MEDIA_CONTENT_URL_THEN_JPEG = Pattern.compile(
            "(?i)<media:content\\s+url=[\"']([^\"']+)[\"']\\s+type=[\"']image/jpeg[\"']");

    private NewsRssUtil() {
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
     * NC000001 {@code CODE_ID} → 연합뉴스 RSS
     */
    private static final Map<String, List<FeedSpec>> FEED_MAP = Map.ofEntries(
            Map.entry("001", List.of(new FeedSpec("Globals.news.rss.yna.politics", "정치"))),
            Map.entry("002", List.of(new FeedSpec("Globals.news.rss.yna.economy", "경제"))),
            Map.entry("003", List.of(new FeedSpec("Globals.news.rss.yna.society", "사회"))),
            Map.entry("004", List.of(new FeedSpec("Globals.news.rss.yna.industry", "산업"))),
            Map.entry("005", List.of(new FeedSpec("Globals.news.rss.yna.culture", "문화"))),
            Map.entry("006", List.of(new FeedSpec("Globals.news.rss.yna.international", "세계"))),
            Map.entry("007", List.of(new FeedSpec("Globals.news.rss.yna.health", "건강"))),
            Map.entry("008", List.of(new FeedSpec("Globals.news.rss.yna.entertainment", "연예"))),
            Map.entry("009", List.of(new FeedSpec("Globals.news.rss.yna.sports", "스포츠"))),
            Map.entry("010", List.of(new FeedSpec("Globals.news.rss.yna.market", "주식"))));

    private static List<FeedSpec> feedsForCodeId(String codeId) {
        if (codeId == null) {
            return Collections.emptyList();
        }
        String key = codeId.trim();
        if (key.isEmpty()) {
            return Collections.emptyList();
        }
        List<FeedSpec> specs = FEED_MAP.get(key);
        return specs != null ? specs : Collections.emptyList();
    }

    /**
     * 관심 카테고리 1개에 대한 RSS 후보 기사 수집.
     *
     * @param codeId NC000001 {@code CODE_ID} (예: 001)
     */
    public static List<ChatbotVO.RssArticleRow> collectCandidatesForCodeId(RestApiManager restApiManager, Logger log,
            String codeId) {
        if (codeId == null || codeId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return collectCandidates(restApiManager, log, Collections.singletonList(codeId.trim()));
    }

    /**
     * @param codeIds NC000001 {@code CODE_ID} 목록 (예: 001, 002)
     */
    public static List<ChatbotVO.RssArticleRow> collectCandidates(RestApiManager restApiManager, Logger log,
            List<String> codeIds) {
        List<ChatbotVO.RssArticleRow> out = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", "Mozilla/5.0 (compatible; TeamAgent/1.0; +https://example.invalid/news-bot)");

        List<String> ids = codeIds != null ? codeIds : Collections.emptyList();
        for (String codeId : ids) {
            if (codeId == null || codeId.trim().isEmpty()) {
                continue;
            }
            List<FeedSpec> specs = feedsForCodeId(codeId);
            if (specs.isEmpty()) {
                log.warn("뉴스 RSS: 알 수 없는 관심 카테고리 CODE_ID 무시: {}", codeId);
                continue;
            }
            for (FeedSpec feedSpec : specs) {
                addFeed(out, seenLinks, restApiManager, header, feedSpec.propKey, feedSpec.rssCategory, log);
            }
        }

        int sequentialArticleId = 0;
        for (ChatbotVO.RssArticleRow row : out) {
            row.setId(sequentialArticleId++);
        }
        return out;
    }

    private static void addFeed(List<ChatbotVO.RssArticleRow> sink, Set<String> seenLinks,
            RestApiManager restApiManager, Map<String, String> header, String propKey, String rssCategory, Logger log) {
        String feedUrl = PropertyUtil.getProperty(propKey);
        if (feedUrl == null || feedUrl.isEmpty()) {
            log.warn("RSS 수집 생략: properties에 URL 없음 propKey={}", propKey);
            return;
        }
        try {
            String xml = restApiManager.getResponseString(feedUrl, header);
            if (xml == null || xml.trim().isEmpty()) {
                return;
            }
            List<ChatbotVO.RssArticleRow> parsed = parseYonhapRssFeed(xml, rssCategory);
            for (ChatbotVO.RssArticleRow row : parsed) {
                String articleUrl = row.getLink() != null ? row.getLink().trim() : "";
                if (!articleUrl.isEmpty() && !seenLinks.add(articleUrl)) {
                    continue;
                }
                sink.add(row);
            }
        } catch (Exception e) {
            log.warn("RSS 수집 실패 {} {}: {}", YONHAP_LABEL, feedUrl, e.getMessage());
        }
    }

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
        Matcher jpegUrlMatcher = MEDIA_CONTENT_URL_THEN_JPEG.matcher(itemXml);
        if (jpegUrlMatcher.find()) {
            return jpegUrlMatcher.group(1).trim();
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

    /** 큐레이터 프롬프트용 기사 객체(sourceUrl, imageUrl, rssCategory 등). */
    public static Map<String, Object> curatorPromptArticleMap(ChatbotVO.RssArticleRow articleRow) {
        Map<String, Object> curatorFields = new HashMap<>();
        curatorFields.put("id", articleRow.getId());
        curatorFields.put("source", articleRow.getPressLabel());
        curatorFields.put("title", articleRow.getTitle());
        curatorFields.put("snippet", articleRow.getSnippet());
        curatorFields.put("rssCategory", articleRow.getRssCategory() != null ? articleRow.getRssCategory() : "");
        curatorFields.put("sourceUrl", articleRow.getLink());
        curatorFields.put("imageUrl", CommonUtil.nullToBlank(articleRow.getImageUrl()));
        return curatorFields;
    }
}
