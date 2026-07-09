package kr.teamagent.chat.service.impl.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.chat.service.impl.ChatbotAgentSupport;

/**
 * 설문·심리검사 에이전트 전용 서비스.
 * <ul>
 *   <li>방사형 차트 데이터 생성 ({@link #getPsychologyChartData})</li>
 * </ul>
 */
@Service
public class SurveyAgentService {

    private static final Logger logger = LoggerFactory.getLogger(SurveyAgentService.class);

    @Autowired
    private ChatbotAgentSupport agentSupport;

    // ── 방사형 차트 데이터 ─────────────────────────────────────────────────────

    /**
     * 외부에서 prompt를 받아 AI 요약 API를 호출하고 방사형 차트용 JSON 문자열을 반환한다.
     *
     * @param prompt AI에 전달할 완성형 프롬프트
     * @return 방사형 차트 데이터 JSON 문자열, 실패 시 null
     */
    public String getPsychologyChartData(String prompt) {
        return agentSupport.callAiSummary(prompt, "방사형 차트 데이터", null);
    }
}
