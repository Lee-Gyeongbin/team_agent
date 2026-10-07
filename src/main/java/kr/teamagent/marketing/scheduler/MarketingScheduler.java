package kr.teamagent.marketing.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.teamagent.marketing.service.impl.MarketingServiceImpl;

/**
 * 마케팅 상태 스케줄러
 *
 * 1시간마다: 예약 시각이 지난 콘텐츠를 발행완료(006)로 전환.
 */
@Component
public class MarketingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MarketingScheduler.class);

    @Autowired
    private MarketingServiceImpl marketingService;

    /**
     * 예약 시각 경과 콘텐츠 발행완료 전환
     * fixedDelay: 이전 실행 완료 후 1시간 대기 (초기 지연 1분)
     */
    @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
    public void advanceScheduledMarketing() {
        try {
            int count = marketingService.advanceScheduledMarketingToPublished();
            if (count > 0) {
                logger.info("[MarketingScheduler] 예약 콘텐츠 발행완료 전환 완료 - {}건", count);
            }
        } catch (Exception e) {
            logger.error("[MarketingScheduler] 예약 콘텐츠 발행완료 전환 오류", e);
        }
    }
}
