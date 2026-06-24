package kr.teamagent.dashboard.service.impl;

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
     * 대시보드 카테고리 추이 조회
     */
    public List<DashBoardVO.CategoryTrend> selectCategoryTrend(Integer dayCnt) throws Exception {
        int days = (dayCnt == null || dayCnt < 1) ? 7 : dayCnt;
        return dashBoardDAO.selectCategoryTrend(days);
    }

    /**
     * 대시보드 카테고리 분류 결과 저장
     */
    public void insertCategoryTrend(String qContent) throws Exception {
        String categoryCd = generateCategoryTrend(qContent);
        DashBoardVO.CategoryTrend saveVO = new DashBoardVO.CategoryTrend();
        saveVO.setCategoryCd(categoryCd);
        saveVO.setCategoryCnt(1);
        dashBoardDAO.insertCategoryTrend(saveVO);
    }

    /**
     * 배치용 어제 카테고리 통계 생성
     */
    @Transactional(rollbackFor = Exception.class)
    public int buildYesterdayCategoryTrend() throws Exception {
        logger.info("buildYesterdayCategoryTrend 배치 시작");
        List<String> qContentList = dashBoardDAO.selectYesterdayQContentList();
        Map<String, Integer> categoryCountMap = new HashMap<>();

        try {
            for (String qContent : qContentList) {
                String categoryCd = generateCategoryTrend(qContent);
                Integer currentCount = categoryCountMap.get(categoryCd);
                categoryCountMap.put(categoryCd, currentCount == null ? 1 : currentCount + 1);
            }

            dashBoardDAO.deleteYesterdayCategoryTrend();

            for (Map.Entry<String, Integer> entry : categoryCountMap.entrySet()) {
                DashBoardVO.CategoryTrend saveVO = new DashBoardVO.CategoryTrend();
                saveVO.setCategoryCd(entry.getKey());
                saveVO.setCategoryCnt(entry.getValue());
                dashBoardDAO.insertCategoryTrend(saveVO);
            }

            logger.info("buildYesterdayCategoryTrend 배치 완료. 처리 건수={}, 카테고리별 건수={}", qContentList.size(), categoryCountMap);
            return qContentList.size();
        } catch (Exception e) {
            logger.error("buildYesterdayCategoryTrend 배치 실패.", e);
            throw e;
        }
    }

    /* 
     * 카테고리 -> 키워드 교체 예정
     * 배치용 전날(어제) 채팅 키워드 통계 생성
     */
    @Transactional(rollbackFor = Exception.class)
    public int buildYesterdayKeywordDailyStat() throws Exception {
        logger.info("buildYesterdayKeywordDailyStat 배치 시작");
        List<String> qContentList = dashBoardDAO.selectYesterdayQContentList();
        Map<String, Integer> keywordCountMap = new HashMap<>();
        Map<String, String> keywordLabelMap = new HashMap<>();

        try {
            for (String qContent : qContentList) {
                DashBoardVO.KeywordDailyStat keywordStat = generateKeywordDailyStat(qContent);
                String llmKeyword = keywordStat.getLlmKeyword().trim();
                String aggKey = llmKeyword.toLowerCase();
                keywordLabelMap.putIfAbsent(aggKey, llmKeyword);
                Integer currentCount = keywordCountMap.get(aggKey);
                keywordCountMap.put(aggKey, currentCount == null ? 1 : currentCount + 1);
            }

            dashBoardDAO.deleteYesterdayKeywordDailyStat();

            for (Map.Entry<String, Integer> entry : keywordCountMap.entrySet()) {
                DashBoardVO.KeywordDailyStat saveVO = new DashBoardVO.KeywordDailyStat();
                saveVO.setLlmKeyword(keywordLabelMap.get(entry.getKey()));
                saveVO.setKeywordCnt(entry.getValue());
                dashBoardDAO.insertKeywordDailyStat(saveVO);
            }

            logger.info("buildYesterdayKeywordDailyStat 배치 완료. 처리 건수={}, 키워드별 건수={}", qContentList.size(), keywordCountMap);
            return qContentList.size();
        } catch (Exception e) {
            logger.error("buildYesterdayKeywordDailyStat 배치 실패.", e);
            throw e;
        }
    }

    private DashBoardVO.KeywordDailyStat generateKeywordDailyStat(String qContent) {
        DashBoardVO.KeywordDailyStat fallback = new DashBoardVO.KeywordDailyStat();
        fallback.setLlmKeyword("기타");

        if (CommonUtil.isEmpty(qContent)) {
            return fallback;
        }

        String prompt = buildKeywordPrompt(qContent);

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

    private String buildKeywordPrompt(String qContent) {
        return "다음 사용자 질문에서 대시보드 날짜별 집계에 사용할 대표 키워드 1개를 추출하세요.\n\n"
                + "목표:\n"
                + "- 사용자 질문을 집계용 대표 키워드 1개로 정규화합니다.\n"
                + "- llmKeyword는 원문 그대로가 아니라 통계 집계에 사용할 대표 키워드입니다.\n"
                + "- 같은 의미의 질문은 항상 같은 llmKeyword로 출력하세요.\n"
                + "- 질문 의도(조회, 설명, 확인, 추천, 요청, 방법, 원인, 해결)는 키워드가 아닙니다.\n\n"
                + "출력 규칙:\n"
                + "1) 반드시 JSON만 출력하세요.\n"
                + "2) 설명, 마크다운, 코드블록은 출력하지 마세요.\n"
                + "3) llmKeyword는 짧은 명사형 대표 키워드 1개만 출력하세요.\n"
                + "4) llmKeyword가 비어 있으면 안 됩니다.\n"
                + "5) 판단이 어렵거나 핵심 대상이 없으면 \"기타\"로 출력하세요.\n"
                + "6) 개인정보가 포함된 질문이면 다른 규칙보다 우선하여 \"개인정보\"로 출력하세요.\n\n"
                + "정규화 우선순위:\n"
                + "1) 개인정보 포함 여부를 먼저 판단합니다.\n"
                + "2) 인사말, 테스트, 의미 없는 입력인지 판단합니다.\n"
                + "3) 질문에서 사용자가 궁금해하는 핵심 대상을 찾습니다.\n"
                + "4) 표기 통일이 필요한 영문 키워드는 표준 표기로 변환합니다.\n"
                + "5) 절대 보호 대상이면 해당 명칭을 유지합니다.\n"
                + "6) 절대 보호 대상이 아니면 접두어·접미어·상태 표현을 제거합니다.\n"
                + "7) 복합어 또는 대상+수식어 조합은 가능한 한 상위 개념 1개로 강하게 통합합니다.\n"
                + "8) 판단이 어렵거나 핵심 대상이 남지 않으면 \"기타\"로 출력합니다.\n\n"
                + "규칙 1. 개인정보\n"
                + "- 주민등록번호, 전화번호, 휴대폰번호, 계좌번호, 이메일 주소가 포함되면 llmKeyword는 \"개인정보\"입니다.\n"
                + "- 개인정보와 다른 키워드가 함께 있어도 \"개인정보\"를 우선합니다.\n\n"
                + "규칙 2. 기타\n"
                + "- 인사말, 테스트, 의미 없는 입력, 단순 감탄, 핵심 대상이 없는 요청은 \"기타\"로 출력하세요.\n"
                + "- 예: 안녕, 테스트, 뭐야, 알려줘, 대책 알려줘, 확인해줘\n\n"
                + "규칙 3. 핵심 대상 추출\n"
                + "- 사용자가 실제로 궁금해하는 대상·지표·부품·업무·주제 명사 1개만 선택하세요.\n"
                + "- 여러 대상이 있으면 가장 중요도가 높은 대상을 선택하세요.\n"
                + "- 중요도가 비슷하면 질문에서 먼저 등장한 핵심 대상을 선택하세요.\n"
                + "- 조회, 설명, 방법, 원인, 해결, 확인, 추천, 요청은 핵심 키워드가 아닙니다.\n\n"
                + "규칙 4. 절대 보호 대상\n"
                + "- 아래 유형은 상위 개념으로 축약하지 말고 명칭을 유지하세요.\n"
                + "- 영문 업무 약어·부품명·공정명·지표명: PTO, DType, Terminal, CNC연삭, RPM, CycleTime, LotTraceability\n"
                + "- 복합 부품·고유 부품명: 스윙방지핀, 리드핀\n"
                + "- CycleTime은 CycleTime, Cycle Time, 사이클타임처럼 명확히 표현된 경우에만 출력하세요.\n"
                + "- 공정, 시작시간, 시간이라는 단어만으로 CycleTime을 추론하지 마세요.\n\n"
                + "규칙 5. 상위 개념 강제 통합\n"
                + "- 절대 보호 대상이 아닌 복합어, 대상+수식어 조합, 업무 기능명은 더 넓은 상위 개념 1개로 통합하세요.\n"
                + "- 질문에 구체 표현이 있더라도 집계 기준에서는 더 포괄적인 대표 키워드를 우선하세요.\n"
                + "- 접미어, 기능명, 행위명, 화면명, AGENT성 표현은 제거하고 핵심 상위 대상만 남기세요.\n"
                + "상위 개념 통합 예시:\n"
                + "- 뉴스큐레이션 → 뉴스\n"
                + "- 음악추천 → 음악\n"
                + "- 금형수정 → 금형\n"
                + "- 주가상승 → 주가\n"
                + "- 사용자 가입자수 → 사용자\n"
                + "- 가입자수 → 가입자\n"
                + "- 매출액 조회 → 매출\n"
                + "- 영업이익률 확인 → 영업이익\n"
                + "- 데이터인터페이스 → 데이터\n"
                + "- 리스크진단 → 리스크\n"
                + "- 필드클레임 → 클레임\n"
                + "- 레인보우플랫폼 → 플랫폼\n"
                + "- 케이블플랫폼 → 플랫폼\n"
                + "- Terminal안착성 → Terminal\n"
                + "- PTO오조립 → PTO\n"
                + "- 리드핀개선 → 리드핀\n"
                + "- 스윙방지핀수정 → 스윙방지핀\n\n"
                + "규칙 6. 접두어 제거\n"
                + "- 숫자, 모델코드, 장비번호, 관리번호처럼 앞에 붙은 코드·번호는 제거하고 핵심 명사만 남기세요.\n"
                + "- 예: 7516시동정지 → 시동정지\n\n"
                + "규칙 7. 접미어·상태 표현 제거\n"
                + "- 절대 보호 대상이 아닌 핵심 대상 뒤에 붙은 속성·행위·상태 표현은 제거하고 대상명만 남기세요.\n"
                + "- 제거 대상: 수정, 개선, 모드, 오조립, 안착성, 추천, 가입자, 매출액, 대책, 가입자수, 기간, 상승, 하락, 오류, 유형, 수익\n"
                + "- 단, 제거 후 핵심 대상이 남지 않으면 \"기타\"로 출력하세요.\n"
                + "예:\n"
                + "- API 오류 → API\n"
                + "- 대책 알려줘 → 기타\n"
                + "- 수익 알려줘 → 수익\n\n"
                + "규칙 8. 표기 통일\n"
                + "- 영문 키워드는 아래 표기만 사용하세요.\n"
                + "- PTO, DType, Terminal, CNC연삭, CycleTime, RPM, LotTraceability\n"
                + "- 소문자, 띄어쓰기, 변형 표기는 위 표기로 통일하세요.\n"
                + "예:\n"
                + "- pto → PTO\n"
                + "- terminal → Terminal\n"
                + "- cycle time → CycleTime\n"
                + "- 사이클타임 → CycleTime\n"
                + "- lot traceability → LotTraceability\n\n"
                + "출력 형식:\n"
                + "{\n"
                + "  \"llmKeyword\": \"\"\n"
                + "}\n\n"
                + "질문: " + qContent;
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

    private String generateCategoryTrend(String qContent) {
        if (CommonUtil.isEmpty(qContent)) {
            return "008";
        }

        String prompt = "다음 질문을 보고 질문을 분석해서 다음 카테고리 코드에 매핑해. 인사말, 무의미한 텍스트는 기타(008)로 분류하고, 카테고리 코드만 출력해."
                        + "001 : 지식·문서 기반 Q&A"
                        + "002 : 데이터·수치·리포트"
                        + "003 : 일반 대화·조언"
                        + "004 : 작성·편집·번역"
                        + "005 : 요약·비교·정리"
                        + "006 : 코드·기술 지원"
                        + "007 : 파일·첨부 기반 질의"
                        + "008 : 기타·미분류"
                + "질문: " + qContent;

        String result = chatbotServiceImpl.callAiSummary(prompt, "category");
        if (CommonUtil.isNotEmpty(result)) {
            String categoryCd = result.trim();
            if (categoryCd.matches("00[1-8]")) {
                return categoryCd;
            }
        }
        return "008";
    }
}
