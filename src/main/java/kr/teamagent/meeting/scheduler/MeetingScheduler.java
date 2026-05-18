package kr.teamagent.meeting.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.teamagent.meeting.service.impl.MeetingServiceImpl;

/**
 * 회의 비정상종료 감지 스케줄러
 *
 * 3분마다 실행:
 * STATUS = '001'(진행중) 이고 LAST_HEARTBEAT_DT < NOW() - 3분 인 회의를
 * STATUS = '003'(취소), ABNORMAL_YN = 'Y' 로 일괄 처리
 */
@Component
public class MeetingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MeetingScheduler.class);

    @Autowired
    private MeetingServiceImpl meetingService;

    /**
     * 비정상종료 회의 감지 및 처리
     * fixedDelay: 이전 실행 완료 후 3분 대기 (초기 지연 1분)
     */
    @Scheduled(initialDelay = 60000, fixedDelay = 180000)
    public void detectAbnormalMeetings() {
        try {
            int count = meetingService.detectAbnormalMeetings();
            if (count > 0) {
                logger.info("[MeetingScheduler] 비정상종료 처리 완료 - {}건", count);
            }
        } catch (Exception e) {
            logger.error("[MeetingScheduler] 비정상종료 감지 오류", e);
        }
    }
}
