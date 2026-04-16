package kr.teamagent.dashboard.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
