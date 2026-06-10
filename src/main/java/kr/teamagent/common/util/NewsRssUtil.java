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

    private static final String DEFAULT_PRESS_LABEL = "연합뉴스";
    private static final int DEFAULT_SNIPPET_MAX_LENGTH = 400;
    private static final Pattern MEDIA_CONTENT_URL_THEN_JPEG = Pattern.compile(
            "(?i)<media:content\\s+url=[\"']([^\"']+)[\"']\\s+type=[\"']image/jpeg[\"']");

    private NewsRssUtil() {
    }

    /**
     * NC000001 {@code CODE_ID}에 매핑되는 RSS 피드 1건 (feedUrl + 카테고리명).
     */
    public static final class FeedSpec {
        final String feedUrl;
        final String rssCategory;

        public FeedSpec(String feedUrl, String rssCategory) {
            this.feedUrl = feedUrl;
            this.rssCategory = rssCategory;
        }
    }

    /**
     * ADDITIONAL_CONFIG의 {@code candidateSources}(codeId/rssCategory/feedUrl 목록)로부터
     * NC000001 {@code CODE_ID} → RSS 피드 매핑을 생성한다.
     */
    public static Map<String, List<FeedSpec>> buildFeedMap(List<Map<String, Object>> candidateSources) {
        if (candidateSources == null || candidateSources.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<FeedSpec>> feedMap = new HashMap<>();
        for (Map<String, Object> source : candidateSources) {
            if (source == null) {
                continue;
            }
            String codeId = String.valueOf(source.getOrDefault("codeId", "")).trim();
            String feedUrl = String.valueOf(source.getOrDefault("feedUrl", "")).trim();
            String rssCategory = String.valueOf(source.getOrDefault("rssCategory", "")).trim();
            if (codeId.isEmpty() || feedUrl.isEmpty()) {
                continue;
            }
            feedMap.computeIfAbsent(codeId, k -> new ArrayList<>()).add(new FeedSpec(feedUrl, rssCategory));
        }
        return feedMap;
    }

    private static List<FeedSpec> feedsForCodeId(Map<String, List<FeedSpec>> feedMap, String codeId) {
        if (feedMap == null || codeId == null) {
            return Collections.emptyList();
        }
        String key = codeId.trim();
        if (key.isEmpty()) {
            return Collections.emptyList();
        }
        List<FeedSpec> specs = feedMap.get(key);
        return specs != null ? specs : Collections.emptyList();
    }

    /**
     * 관심 카테고리 1개에 대한 RSS 후보 기사 수집.
     *
     * @param codeId NC000001 {@code CODE_ID} (예: 001)
     * @param feedMap {@link #buildFeedMap(List)}로 생성한 CODE_ID → RSS 피드 매핑
     * @param pressLabel 기사 출처 라벨 (예: 연합뉴스)
     * @param snippetMaxLength 기사 요약 최대 길이
     */
    public static List<ChatbotVO.RssArticleRow> collectCandidatesForCodeId(RestApiManager restApiManager, Logger log,
            String codeId, Map<String, List<FeedSpec>> feedMap, String pressLabel, int snippetMaxLength) {
        if (codeId == null || codeId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return collectCandidates(restApiManager, log, Collections.singletonList(codeId.trim()), feedMap, pressLabel,
                snippetMaxLength);
    }

    /**
     * @param codeIds NC000001 {@code CODE_ID} 목록 (예: 001, 002)
     * @param feedMap {@link #buildFeedMap(List)}로 생성한 CODE_ID → RSS 피드 매핑
     * @param pressLabel 기사 출처 라벨 (예: 연합뉴스)
     * @param snippetMaxLength 기사 요약 최대 길이
     */
    public static List<ChatbotVO.RssArticleRow> collectCandidates(RestApiManager restApiManager, Logger log,
            List<String> codeIds, Map<String, List<FeedSpec>> feedMap, String pressLabel, int snippetMaxLength) {
        List<ChatbotVO.RssArticleRow> out = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", "Mozilla/5.0 (compatible; TeamAgent/1.0; +https://example.invalid/news-bot)");

        String resolvedPressLabel = (pressLabel != null && !pressLabel.trim().isEmpty()) ? pressLabel
                : DEFAULT_PRESS_LABEL;
        int resolvedSnippetMaxLength = snippetMaxLength > 0 ? snippetMaxLength : DEFAULT_SNIPPET_MAX_LENGTH;

        List<String> ids = codeIds != null ? codeIds : Collections.emptyList();
        for (String codeId : ids) {
            if (codeId == null || codeId.trim().isEmpty()) {
                continue;
            }
            List<FeedSpec> specs = feedsForCodeId(feedMap, codeId);
            if (specs.isEmpty()) {
                log.warn("뉴스 RSS: 알 수 없는 관심 카테고리 CODE_ID 무시: {}", codeId);
                continue;
            }
            for (FeedSpec feedSpec : specs) {
                addFeed(out, seenLinks, restApiManager, header, feedSpec.feedUrl, feedSpec.rssCategory,
                        resolvedPressLabel, resolvedSnippetMaxLength, log);
            }
        }

        int sequentialArticleId = 0;
        for (ChatbotVO.RssArticleRow row : out) {
            row.setId(sequentialArticleId++);
        }
        return out;
    }

    private static void addFeed(List<ChatbotVO.RssArticleRow> sink, Set<String> seenLinks,
            RestApiManager restApiManager, Map<String, String> header, String feedUrl, String rssCategory,
            String pressLabel, int snippetMaxLength, Logger log) {
        if (feedUrl == null || feedUrl.isEmpty()) {
            log.warn("RSS 수집 생략: feedUrl 없음 rssCategory={}", rssCategory);
            return;
        }
        try {
            String xml = restApiManager.getResponseString(feedUrl, header);
            if (xml == null || xml.trim().isEmpty()) {
                return;
            }
            List<ChatbotVO.RssArticleRow> parsed = parseYonhapRssFeed(xml, rssCategory, pressLabel, snippetMaxLength);
            for (ChatbotVO.RssArticleRow row : parsed) {
                String articleUrl = row.getLink() != null ? row.getLink().trim() : "";
                if (!articleUrl.isEmpty() && !seenLinks.add(articleUrl)) {
                    continue;
                }
                sink.add(row);
            }
        } catch (Exception e) {
            log.warn("RSS 수집 실패 {} {}: {}", pressLabel, feedUrl, e.getMessage());
        }
    }

    private static List<ChatbotVO.RssArticleRow> parseYonhapRssFeed(String xml, String rssCategory, String pressLabel,
            int snippetMaxLength) throws Exception {
        SyndFeedInput input = new SyndFeedInput();
        input.setPreserveWireFeed(true);
        List<ChatbotVO.RssArticleRow> rows = new ArrayList<>();
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))) {
            SyndFeed feed = input.build(reader);
            for (SyndEntry entry : feed.getEntries()) {
                String title = entry.getTitle() != null ? entry.getTitle().trim() : "";
                String link = resolveLink(entry);
                String descriptionHtml = resolveDescriptionHtml(entry);
                String snippet = shorten(stripHtml(descriptionHtml), snippetMaxLength);
                String imageUrl = yonhapMediaContentJpegInRawItemXml(xml, link);

                if (title.isEmpty() && link.isEmpty()) {
                    continue;
                }
                ChatbotVO.RssArticleRow row = new ChatbotVO.RssArticleRow();
                row.setPressLabel(pressLabel);
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
