package kr.teamagent.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring @Scheduled 애노테이션 활성화 설정
 * Quartz(root-context.xml)와 별개로 @Scheduled 기반 경량 스케줄러 지원
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
