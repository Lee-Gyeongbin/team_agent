package kr.teamagent.dashboard.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.dashboard.service.DashBoardVO;
import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.util.CommonUtil;

@Service
public class DashBoardServiceImpl extends EgovAbstractServiceImpl {
    private static final Logger logger = LoggerFactory.getLogger(DashBoardServiceImpl.class);

    @Autowired
    DashBoardDAO dashBoardDAO;

    @Autowired
    ChatbotServiceImpl chatbotServiceImpl;

    /**
     * 대시보드 상단 통계 카드 조회
     */
    public DashBoardVO.StatSummary selectStatSummary() throws Exception {
        return dashBoardDAO.selectStatSummary();
    }

    /**
     * 대시보드 질의 비율 조회
     */
    public DashBoardVO.QueryRatio selectQueryRatio(String ym) throws Exception {
        return dashBoardDAO.selectQueryRatio(ym);
    }

    /**
     * 대시보드 공지 요약 목록 조회
     */
    public List<DashBoardVO.NoticeItem> selectDashboardNoticeList() throws Exception {
        return dashBoardDAO.selectDashboardNoticeList();
    }

    /**
     * 대시보드 토큰 사용량 조회
     */
    public List<DashBoardVO.TokenUsage> selectTokenUsage(String ym) throws Exception {
        return dashBoardDAO.selectTokenUsageList(ym);
    }

    /**
     * 대시보드 사용자(방문) 추이 조회
     */
    public List<DashBoardVO.VisitorTrend> selectVisitorTrend() throws Exception {
        return dashBoardDAO.selectVisitorTrendList();
    }

    /**
     * 대시보드 키워드 추이 조회
     */
    public List<DashBoardVO.KeywordTrend> selectKeywordTrend(Integer dayCnt) throws Exception {
        int days = (dayCnt == null || dayCnt < 1) ? 7 : dayCnt;
        return selectMergedKeywordTrend(days);
    }

    private List<DashBoardVO.KeywordTrend> selectMergedKeywordTrend(int dayCnt) throws Exception {
        List<DashBoardVO.KeywordTrend> rawList = dashBoardDAO.selectKeywordTrend(dayCnt);
        Map<String, Integer> keywordCountMap = new HashMap<>();
        Map<String, String> keywordLabelMap = new HashMap<>();

        for (DashBoardVO.KeywordTrend item : rawList) {
            if (item.getLlmKeyword() == null || item.getKeywordCnt() == null) {
                continue;
            }
            String llmKeyword = item.getLlmKeyword().trim();
            if (llmKeyword.isEmpty() || "기타".equals(llmKeyword)) {
                continue;
            }
            String aggKey = llmKeyword.toLowerCase();
            keywordLabelMap.putIfAbsent(aggKey, llmKeyword);
            Integer currentCount = keywordCountMap.get(aggKey);
            keywordCountMap.put(aggKey, currentCount == null ? item.getKeywordCnt() : currentCount + item.getKeywordCnt());
        }

        List<Map.Entry<String, Integer>> sortedEntries = new ArrayList<>(keywordCountMap.entrySet());
        sortedEntries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        List<DashBoardVO.KeywordTrend> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : sortedEntries) {
            if (result.size() >= 5) {
                break;
            }
            if ("기타".equals(entry.getKey())) {
                continue;
            }
            DashBoardVO.KeywordTrend trend = new DashBoardVO.KeywordTrend();
            String label = keywordLabelMap.getOrDefault(entry.getKey(), entry.getKey());
            trend.setLlmKeyword(label);
            trend.setKeywordCnt(entry.getValue());
            trend.setDayCnt(dayCnt);
            result.add(trend);
        }
        return result;
    }

    /**
     * 배치용 전날(어제) 채팅 키워드 통계 생성
     */
    @Transactional(rollbackFor = Exception.class)
    public int buildYesterdayKeywordDailyStat() throws Exception {
        logger.info("buildYesterdayKeywordDailyStat 배치 시작");

        try {
            List<DashBoardVO.KeywordChatLog> chatLogList = dashBoardDAO.selectYesterdayKeywordChatLogList();
            Map<String, Integer> keywordCountMap = new HashMap<>();
            Map<String, String> keywordLabelMap = new HashMap<>();
            String baseKeywordText = resolveBaseKeywordText();

            for (DashBoardVO.KeywordChatLog chatLog : chatLogList) {
                DashBoardVO.KeywordDailyStat keywordStat = generateKeywordDailyStat(
                        chatLog.getQContent(), chatLog.getRContent(), baseKeywordText);
                String llmKeyword = keywordStat.getLlmKeyword().trim();
                String aggKey = llmKeyword.toLowerCase();
                keywordLabelMap.putIfAbsent(aggKey, llmKeyword);
                Integer currentCount = keywordCountMap.get(aggKey);
                keywordCountMap.put(aggKey, currentCount == null ? 1 : currentCount + 1);
            }

            keywordCountMap = mergeKeywordCountMap(keywordCountMap);

            dashBoardDAO.deleteYesterdayKeywordDailyStat();

            for (Map.Entry<String, Integer> entry : keywordCountMap.entrySet()) {
                DashBoardVO.KeywordDailyStat saveVO = new DashBoardVO.KeywordDailyStat();
                saveVO.setLlmKeyword(keywordLabelMap.get(entry.getKey()));
                saveVO.setKeywordCnt(entry.getValue());
                dashBoardDAO.insertYesterdayKeywordDailyStat(saveVO);
            }

            logger.info("buildYesterdayKeywordDailyStat 배치 완료. 처리 건수={}, 키워드별 건수={}", chatLogList.size(), keywordCountMap);
            return chatLogList.size();
        } catch (Exception e) {
            logger.error("buildYesterdayKeywordDailyStat 배치 실패.", e);
            throw e;
        }
    }

    private Map<String, Integer> mergeKeywordCountMap(Map<String, Integer> keywordCountMap) {
        Map<String, String> redirectMap = new HashMap<>();
        List<String> keywordKeys = new ArrayList<>(keywordCountMap.keySet());

        for (String keywordKey : keywordKeys) {
            String mergeTarget = null;
            int mergeTargetLength = 0;

            for (String candidateKey : keywordKeys) {
                if (keywordKey.equals(candidateKey)) {
                    continue;
                }
                if (keywordKey.contains(candidateKey) && candidateKey.length() > mergeTargetLength) {
                    mergeTarget = candidateKey;
                    mergeTargetLength = candidateKey.length();
                }
            }

            if (mergeTarget != null) {
                redirectMap.put(keywordKey, mergeTarget);
            }
        }

        Map<String, Integer> mergedCountMap = new HashMap<>();
        for (Map.Entry<String, Integer> entry : keywordCountMap.entrySet()) {
            String finalKey = entry.getKey();
            while (redirectMap.containsKey(finalKey)) {
                finalKey = redirectMap.get(finalKey);
            }
            Integer currentCount = mergedCountMap.get(finalKey);
            mergedCountMap.put(finalKey, currentCount == null ? entry.getValue() : currentCount + entry.getValue());
        }
        return mergedCountMap;
    }

    private String resolveBaseKeywordText() throws Exception {
        for (int daysAgo = 2; daysAgo <= 31; daysAgo++) {
            List<DashBoardVO.KeywordDailyStat> keywordList = dashBoardDAO.selectKeywordDailyStatListByDaysAgo(daysAgo);
            if (keywordList == null || keywordList.isEmpty()) {
                continue;
            }
            return formatBaseKeywordText(keywordList);
        }
        return null;
    }

    private String formatBaseKeywordText(List<DashBoardVO.KeywordDailyStat> keywordList) {
        StringBuilder sb = new StringBuilder();
        for (DashBoardVO.KeywordDailyStat keywordStat : keywordList) {
            if (keywordStat.getLlmKeyword() == null || keywordStat.getKeywordCnt() == null) {
                continue;
            }
            String llmKeyword = keywordStat.getLlmKeyword().trim();
            if (llmKeyword.isEmpty()) {
                continue;
            }
            sb.append(llmKeyword).append(" (").append(keywordStat.getKeywordCnt()).append(")\n");
        }
        return sb.toString().trim();
    }

    private DashBoardVO.KeywordDailyStat generateKeywordDailyStat(String qContent, String rContent, String baseKeywordText) {
        DashBoardVO.KeywordDailyStat fallback = new DashBoardVO.KeywordDailyStat();
        fallback.setLlmKeyword("기타");

        if (CommonUtil.isEmpty(qContent)) {
            return fallback;
        }

        String prompt = buildKeywordPrompt(qContent, rContent, baseKeywordText);

        String result = chatbotServiceImpl.callAiSummary(prompt, "keyword");
        JSONObject aiJson = parseAiJson(result);
        if (aiJson == null) {
            return fallback;
        }

        String llmKeyword = CommonUtil.nullToBlank((String) aiJson.get("llmKeyword")).trim();
        if (CommonUtil.isEmpty(llmKeyword)) {
            llmKeyword = "기타";
        }
        if (llmKeyword.length() > 100) {
            llmKeyword = llmKeyword.substring(0, 100);
        }

        DashBoardVO.KeywordDailyStat keywordStat = new DashBoardVO.KeywordDailyStat();
        keywordStat.setLlmKeyword(llmKeyword);
        return keywordStat;
    }

    private String buildKeywordPrompt(String qContent, String rContent, String baseKeywordText) {
        String baseKeywords = (baseKeywordText == null || baseKeywordText.trim().isEmpty())
                ? "없음"
                : baseKeywordText.trim();

        String prompt = "사용자 질문에서 대시보드 집계용 핵심 키워드 1개만 추출하세요.\n\n"
                + "원칙:\n"
                + "- 반드시 JSON만 출력하세요. llmKeyword는 명사형 대표 키워드 1개만 출력하세요.\n"
                + "- 명사형 대표 키워드는 단어 2개 조합을 넘어가지 마세요.\n"
                + "- [사용자 질문]과 [답변 내용]을 함께 확인하여 핵심 대상을 찾으세요.\n"
                + "- 질문에 핵심 대상이 명확하면 질문을 우선하고, 질문만으로 판단이 어려우면 답변 내용에서 핵심 대상을 확인하세요.\n"
                + "- 집계에 의미 있는 핵심 대상이 확인되면 \"기타\"보다 해당 대상을 선택하세요.\n"
                + "- \"기타\"는 인사말, 테스트, 의미 없는 입력, 질문 의도어만 남고 질문·답변 모두에서 핵심 대상을 찾을 수 없는 경우에만 사용하세요.\n"
                + "- 답변의 부가 설명, 절차, 예시, 무관한 시스템명만 단독으로 선택하지 마세요. 질문이 불명확하면 답변의 핵심 주제는 참고할 수 있습니다.\n"
                + "- 개인정보가 포함되면 다른 규칙보다 우선하여 \"개인정보\"로 출력하세요.\n"
                + "- 질문·답변·기준 키워드에 없는 임의 키워드는 만들지 마세요.\n\n"
                + "질문 의도어:\n"
                + "- 조회, 추천, 확인, 분석, 방법은 키워드가 아닙니다. 제거 후 남는 핵심 대상을 찾으세요.\n\n"
                + "기준 키워드 매칭:\n"
                + "- 핵심 대상을 먼저 찾은 뒤, 기준 키워드 목록과 같은 의미이면 반드시 기준 키워드 표기를 그대로 사용하세요.\n"
                + "- 오타, 약어/풀네임, 한글/영문, 공백·하이픈·언더바 차이만 있으면 같은 키워드로 판단하세요.\n"
                + "- 부분어 관계는 하나의 기준 키워드로 명확하게 판단될 때만 기준 키워드를 사용하세요.\n"
                + "- 기준 키워드 목록에 같은 의미의 후보가 있으면 \"기타\"나 넓은 일반어보다 기준 키워드를 우선 사용하세요.\n"
                + "- 관련만 있고 같은 의미가 아니면 억지로 병합하지 마세요.\n"
                + "- 기준 키워드 목록의 \"기타\"는 매칭 대상으로 사용하지 마세요.\n\n"
                + "대표 키워드 선택:\n"
                + "- 구체적인 업무명, 부품명, 공정명, 시스템명, 지표명, 상품명, 문서명, 고유 용어가 있으면 그 대상을 선택하세요.\n"
                + "- 공정, 시스템, 데이터, 파일, 프로젝트, 라인, 커뮤니티 같은 넓은 일반어는 더 구체적인 핵심 대상이 없을 때만 선택하세요.\n"
                + "- 상태어, 속성어, 행위어, 판정어, 수량어만 남기지 말고 핵심 대상을 찾으세요.\n"
                + "- 파일명, 확장자, 경로, 괄호 안 부가정보는 제외하고 핵심 대상만 남기세요.\n"
                + "- 고유 용어, 영문 약어, 영문+숫자 조합은 임의로 번역·축약·수정하지 마세요.\n"
                + "- llmKeyword에는 공백을 사용하지 마세요. 단, 기준 키워드의 언더바·하이픈은 유지하세요.\n\n"
                + "출력 형식:\n"
                + "{\n"
                + "  \"llmKeyword\": \"\"\n"
                + "}\n\n"
                + "[기준 키워드 목록]\n"
                + baseKeywords + "\n\n"
                + "[사용자 질문]\n"
                + qContent;

        if (CommonUtil.isNotEmpty(rContent)) {
            prompt += "\n\n[답변 내용]\n" + truncateKeywordContent(rContent, 500);
        }

        return prompt;
    }

    private String truncateKeywordContent(String text, int maxLength) {
        if (CommonUtil.isEmpty(text)) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private JSONObject parseAiJson(String answer) {
        if (CommonUtil.isEmpty(answer)) {
            return null;
        }
        String jsonStr = answer.replace("```json", "").replace("```", "").trim();
        if (jsonStr.isEmpty()) {
            return null;
        }
        try {
            Object parsed = new JSONParser().parse(jsonStr);
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            }
        } catch (Exception e) {
            logger.warn("키워드 추출 AI 응답 JSON 파싱 실패", e);
        }
        return null;
    }
}
