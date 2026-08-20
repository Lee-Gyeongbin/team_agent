package kr.teamagent.marketing.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.teamagent.marketing.service.impl.MarketingServiceImpl;

/**
 * 마케팅 프로젝트 상태 스케줄러
 *
 * 1시간마다 실행:
 * 마감일(DUE_DT)이 지났는데 아직 STATUS_CD='001'(작성중)인 프로젝트를 '002'(검수중)로 일괄 전환.
 * 003(완료)/004(보류)는 사용자가 프로젝트 수정 화면에서 직접 지정한다.
 */
@Component
public class MarketingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MarketingScheduler.class);

    @Autowired
    private MarketingServiceImpl marketingService;

    /**
     * 마감일 경과 프로젝트 검수중 전환
     * fixedDelay: 이전 실행 완료 후 1시간 대기 (초기 지연 1분)
     */
    @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
    public void advanceOverdueProjects() {
        try {
            int count = marketingService.advanceOverdueProjectsToReview();
            if (count > 0) {
                logger.info("[MarketingScheduler] 마감일 경과 프로젝트 검수중 전환 완료 - {}건", count);
            }
        } catch (Exception e) {
            logger.error("[MarketingScheduler] 마감일 경과 프로젝트 전환 오류", e);
        }
    }
}
