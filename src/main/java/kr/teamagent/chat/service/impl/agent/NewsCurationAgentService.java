package kr.teamagent.chat.service.impl.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.chat.service.ChatbotVO.RssArticleRow;
import kr.teamagent.chat.socket.ChatbotWebSocketHandler;
import kr.teamagent.chat.service.impl.ChatbotAgentSupport;
import kr.teamagent.chat.service.impl.ChatbotDAO;
import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.NewsRssUtil;
import kr.teamagent.common.util.RestApiManager;

/**
 * 뉴스 큐레이션 에이전트 전용 서비스.
 * 관심 카테고리마다 RSS 수집 → AI 호출 → 서버에서 JSON 배열 병합.
 */
@Service
public class NewsCurationAgentService {

    private static final Logger logger = LoggerFactory.getLogger(NewsCurationAgentService.class);

    @Autowired
    private ChatbotAgentSupport agentSupport;

    @Autowired
    private ChatbotDAO chatbotDAO;

    @Autowired
    private RestApiManager restApiManager;

    /** ChatbotServiceImpl의 doInsertAiLog / updateChatRoomLastChatDt 호출용 (순환 의존 방지 위해 @Lazy) */
    @Autowired
    @Lazy
    private ChatbotServiceImpl chatbotService;

    // ── 뉴스 큐레이션 메인 메서드 ─────────────────────────────────────────────

    /**
     * 뉴스 큐레이션 에이전트 전용.
     * 관심 카테고리마다 RSS 수집 → AI 호출 → 서버에서 JSON 배열 병합.
     */
    @SuppressWarnings("unchecked")
    public void deliverNewsRecommendationViaWebSocket(
            String query, String threadId, String userId, String svcTy,
            String modelId, String refId, String agentId, List<Long> attachmentFileIds,
            Map<String, Object> additionalConfig,
            ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {

        String rawQuery = query != null ? query : "";
        List<String> interestCategories = selectNewsInterestCategoryCodeIds(userId);
        if (interestCategories.isEmpty()) {
            callback.onError("뉴스 관심 카테고리가 설정되지 않았습니다.");
            return;
        }

        Map<String, Object> config = additionalConfig != null ? additionalConfig : Collections.emptyMap();
        List<Map<String, Object>> candidateSources = (List<Map<String, Object>>) config.get("candidateSources");
        Map<String, List<NewsRssUtil.FeedSpec>> feedMap = NewsRssUtil.buildFeedMap(candidateSources);

        String pressLabel = "연합뉴스";
        int snippetMaxLength = 400;
        Object engineObj = config.get("engine");
        if (engineObj instanceof Map) {
            Object candidateFetchObj = ((Map<String, Object>) engineObj).get("candidateFetch");
            if (candidateFetchObj instanceof Map) {
                Map<String, Object> candidateFetch = (Map<String, Object>) candidateFetchObj;
                Object pressLabelObj = candidateFetch.get("pressLabel");
                if (pressLabelObj != null && CommonUtil.isNotEmpty(String.valueOf(pressLabelObj))) {
                    pressLabel = String.valueOf(pressLabelObj);
                }
                Object snippetMaxLengthObj = candidateFetch.get("snippetMaxLength");
                if (snippetMaxLengthObj instanceof Number) {
                    snippetMaxLength = ((Number) snippetMaxLengthObj).intValue();
                }
            }
        }

        List<String> categoryJsonList = new ArrayList<>();
        for (String codeId : interestCategories) {
            List<RssArticleRow> categoryRows = NewsRssUtil.collectCandidatesForCodeId(
                    restApiManager, logger, codeId, feedMap, pressLabel, snippetMaxLength);
            if (categoryRows.isEmpty()) {
                continue;
            }
            String categoryLabel = categoryRows.get(0).getRssCategory();
            String curatorPrompt = appendNewsCandidateArticlesToCuratorQuery(
                    rawQuery, categoryRows, codeId, categoryLabel);
            String categoryAiJson = runNewsCuratorAi(curatorPrompt);
            if (CommonUtil.isEmpty(categoryAiJson)) {
                continue;
            }
            categoryJsonList.add(categoryAiJson.trim());
        }

        String curatorAiJson = categoryJsonList.isEmpty()
                ? "" : mergeNewsCuratorCategoryResponses(categoryJsonList);
        if (CommonUtil.isEmpty(curatorAiJson)) {
            String msg = "AI가 선정한 뉴스를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.";
            callback.onError(msg);
            return;
        }

        callback.onChunk(curatorAiJson, curatorAiJson, null);

        String qContentForDb = buildNewsCurationQContentForDb(rawQuery, interestCategories);
        String savedLogId = "";
        if (!"llmTest".equals(svcTy) && CommonUtil.isNotEmpty(threadId)) {
            try {
                savedLogId = chatbotService.doInsertAiLog(
                        threadId, agentId, qContentForDb, curatorAiJson,
                        0, 0, svcTy, modelId, refId, userId,
                        "", "", "", "", "", "",
                        new ArrayList<>(), "", "", "", "");
                chatbotService.updateChatRoomLastChatDt(threadId);
                if (CommonUtil.isNotEmpty(savedLogId)
                        && attachmentFileIds != null && !attachmentFileIds.isEmpty()) {
                    try {
                        ChatbotVO fileVO = new ChatbotVO();
                        fileVO.setChatFileIdList(attachmentFileIds);
                        fileVO.setLogId(Long.parseLong(savedLogId));
                        chatbotDAO.linkChatFilesToLog(fileVO);
                    } catch (Exception e) {
                        logger.warn("첨부파일 LOG_ID 연결 실패: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.warn("챗봇 로그 저장 실패: {}", e.getMessage());
            }
        }

        callback.onComplete(curatorAiJson, "", "", new ArrayList<>(),
                CommonUtil.nullToBlank(threadId),
                CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null,
                null, null, null);
    }

    // ── 내부 헬퍼 메서드 ─────────────────────────────────────────────────────

    /** AI 큐레이션 JSON 배열 응답. */
    private String runNewsCuratorAi(String curatorPrompt) {
        String curatorAiJson = agentSupport.callAiSummary(curatorPrompt, "news_curate", null);
        if (CommonUtil.isEmpty(curatorAiJson)) {
            return "";
        }
        return curatorAiJson.trim();
    }

    private static String appendNewsCandidateArticlesToCuratorQuery(
            String frontendTemplate, List<RssArticleRow> candidates,
            String codeId, String categoryLabel) {
        String block = buildNewsCandidateArticlesMetadataBlock(candidates, codeId, categoryLabel);
        int roleIdx = frontendTemplate.indexOf("[역할]");
        if (roleIdx >= 0) {
            return frontendTemplate.substring(0, roleIdx).trim()
                    + "\n" + block + "\n"
                    + frontendTemplate.substring(roleIdx);
        }
        return frontendTemplate.trim() + "\n\n" + block;
    }

    /** TB_CHAT_LOG.Q_CONTENT — 프롬프트 템플릿 + 관심 카테고리 CODE_ID 목록 */
    private static String buildNewsCurationQContentForDb(String rawQuery, List<String> categoryCodeIds) {
        String base = rawQuery != null ? rawQuery.trim() : "";
        String categoryLine = "- 선정 대상 카테고리: "
                + ChatbotAgentSupport.NEWS_CURATE_PROMPT_GSON.toJson(categoryCodeIds);
        if (CommonUtil.isEmpty(base)) {
            return categoryLine;
        }
        return base + "\n\n" + categoryLine;
    }

    private static String buildNewsCandidateArticlesMetadataBlock(
            List<RssArticleRow> candidates, String codeId, String categoryLabel) {
        List<Map<String, Object>> curatorInputArticles = new ArrayList<>(candidates.size());
        for (RssArticleRow candidateRow : candidates) {
            curatorInputArticles.add(NewsRssUtil.curatorPromptArticleMap(candidateRow));
        }
        String articlesJson = ChatbotAgentSupport.NEWS_CURATE_PROMPT_GSON.toJson(curatorInputArticles);
        String normalizedCodeId = codeId != null ? codeId.trim() : "";
        String normalizedCategoryLabel = categoryLabel != null ? categoryLabel.trim() : "";
        if (normalizedCategoryLabel.isEmpty()) {
            normalizedCategoryLabel = CommonUtil.isNotEmpty(normalizedCodeId) ? normalizedCodeId : "미분류";
        }
        StringBuilder block = new StringBuilder();
        block.append("- 선정 대상 카테고리: ").append(normalizedCategoryLabel)
                .append(" (CODE_ID: ").append(normalizedCodeId).append(")\n");
        block.append("- 후보 기사 목록(JSON 배열): ").append(articlesJson);
        return block.toString();
    }

    /**
     * 카테고리별 AI 응답 JSON 문자열을 파싱 없이 문자열 그대로 이어 붙여 하나의 JSON 배열로 만든다.
     */
    private static String mergeNewsCuratorCategoryResponses(List<String> categoryJsonList) {
        StringBuilder sb = new StringBuilder("[");
        boolean firstItem = true;
        for (String json : categoryJsonList) {
            String trimmed = json.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                String inner = trimmed.substring(1, trimmed.length() - 1).trim();
                if (inner.isEmpty()) continue;
                if (!firstItem) sb.append(",");
                sb.append(inner);
            } else {
                if (!firstItem) sb.append(",");
                sb.append(trimmed);
            }
            firstItem = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /** TB_USER_INTEREST_NEWS_CATEGORY.NEWS_CATEGORY_CD → RSS용 codeId 목록 */
    private List<String> selectNewsInterestCategoryCodeIds(String userId) throws Exception {
        if (CommonUtil.isEmpty(userId)) {
            return Collections.emptyList();
        }
        ChatbotVO param = new ChatbotVO();
        param.setUserId(userId);
        ChatbotVO saved = chatbotDAO.selectUserNewsInterestCategory(param);
        return parseNewsCategoryCdJson(saved != null ? saved.getNewsCategoryCd() : null);
    }

    private List<String> parseNewsCategoryCdJson(String newsCategoryCdJson) {
        if (CommonUtil.isEmpty(newsCategoryCdJson)) {
            return Collections.emptyList();
        }
        String trimmed = newsCategoryCdJson.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return Collections.emptyList();
        }
        try {
            Type listType = new TypeToken<List<String>>() {}.getType();
            List<String> parsed = ChatbotAgentSupport.NEWS_CURATE_PROMPT_GSON.fromJson(trimmed, listType);
            if (parsed == null || parsed.isEmpty()) {
                return Collections.emptyList();
            }
            return parsed.stream()
                    .filter(s -> s != null && !s.trim().isEmpty())
                    .map(String::trim)
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
