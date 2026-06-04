package kr.teamagent.mail.web;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.web.BaseController;
import kr.teamagent.mail.service.MailDto;
import kr.teamagent.mail.service.impl.MailServiceImpl;

@Controller
@RequestMapping("/mail")
public class MailController extends BaseController<Object> {

    private static final String SESSION_KEY_EMAIL    = "mail.email";
    private static final String SESSION_KEY_PASSWORD = "mail.password";
    private static final String SESSION_KEY_START_DATE = "mail.startDate";
    private static final String SESSION_KEY_END_DATE = "mail.endDate";

    private static final String DEFAULT_CHAT_SYSTEM_PROMPT =
        "당신은 사용자의 메일함을 분석하는 AI 비서입니다.\n"
            + "주어진 메일 목록 컨텍스트와 대화 이력을 바탕으로 사용자 질문에 답변하세요.\n\n"
            + "규칙:\n"
            + "1) 답변은 한국어로, 간결하고 실용적으로 작성합니다. (기본 3~6문장)\n"
            + "2) 반드시 제공된 메일 컨텍스트 안의 정보만 사용합니다. 추측/지어내기 금지.\n"
            + "3) 요청이 모호하면 필요한 확인 질문 1개를 먼저 제시합니다.\n"
            + "4) 사용자가 '지금 해야 할 일'을 물으면 우선순위(긴급/이번 주/일반) 형태로 정리합니다.\n"
            + "5) 날짜/발신자/제목 등 근거를 가능한 한 포함합니다.\n"
            + "6) 민감 정보(비밀번호, 인증정보 등)는 절대 생성/요청/노출하지 않습니다.\n"
            + "7) 컨텍스트에 근거가 없으면 '메일 컨텍스트에서 확인되지 않습니다'라고 명확히 말합니다.\n"
            + "8) 불필요한 서론 없이 핵심부터 답합니다.\n\n"
            + "출력 형식:\n"
            + "- 일반 질문: 바로 답변 본문\n"
            + "- 액션 요청 질문: 아래 형식 우선\n"
            + "  [우선순위]\n"
            + "  - 긴급: ...\n"
            + "  - 이번 주: ...\n"
            + "  - 일반: ...\n"
            + "  [근거]\n"
            + "  - (메일 제목/발신자/날짜)";

    @Autowired
    private MailServiceImpl mailService;
    
    @Autowired
    private ChatbotServiceImpl chatbotService;

    // ─── 1. IMAP 로그인 인증 ─────────────────────────────────

    /**
     * IMAP 인증 시도 후 성공 시 세션에 자격증명 저장.
     * POST /mail/auth.do
     * Body: { email, password }
     */
    @SuppressWarnings("unchecked")
    @RequestMapping(value = "/auth.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView auth(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String email    = body.get("email");
        String password = body.get("password");

        if (email == null || email.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            return makeFailJsonData("이메일과 비밀번호를 입력해주세요.");
        }

        boolean ok = mailService.authImap(email.trim(), password);
        if (!ok) {
            return makeFailJsonData("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        // 세션에 자격증명 저장 (절대 클라이언트에 반환하지 않음)
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_KEY_EMAIL,    email.trim());
        session.setAttribute(SESSION_KEY_PASSWORD, password);

        return makeSuccessJsonData();
    }

    // ─── 2. 메일 목록 조회 ───────────────────────────────────

    /**
     * 세션 자격증명으로 기간 내 INBOX 메일 조회.
     * GET /mail/list.do?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
     */
    @SuppressWarnings("unchecked")
    @RequestMapping(value = "/list.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView list(@RequestParam(value = "startDate", required = false) String startDateStr,
                             @RequestParam(value = "endDate", required = false) String endDateStr,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        try {
            String email    = getMailSession(request, SESSION_KEY_EMAIL);
            String password = getMailSession(request, SESSION_KEY_PASSWORD);

            if (email == null || password == null) {
                return makeFailJsonData(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다. 로그인 후 다시 시도해주세요.", "MAIL_AUTH_REQUIRED");
            }

            DateRange dateRange = resolveDateRange(startDateStr, endDateStr);
            if (!dateRange.isValid()) {
                return makeFailJsonData(response, HttpServletResponse.SC_BAD_REQUEST,
                    "조회 기간이 올바르지 않습니다. 시작일과 종료일을 확인해 주세요.", "MAIL_INVALID_DATE_RANGE");
            }

            saveDateRangeToSession(request, dateRange.getStartDateStr(), dateRange.getEndDateStr());
            List<MailDto> mails = mailService.getRecentMails(email, password, dateRange.getStartDate(), dateRange.getEndExclusive());

            // 미읽음 수 계산
            long unreadCount = mails.stream().filter(m -> !m.isRead()).count();

            // 오늘 수신 수 계산
            String today = new SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
            long todayCount = mails.stream()
                    .filter(m -> m.getReceivedDate() != null &&
                            new SimpleDateFormat("yyyyMMdd").format(m.getReceivedDate()).equals(today))
                    .count();

            HashMap<String, Object> resultMap = new HashMap<>();
            resultMap.put("mails",        mails);
            resultMap.put("totalCount",   mails.size());
            resultMap.put("unreadCount",  unreadCount);
            resultMap.put("todayCount",   todayCount);
            return makeSuccessJsonData(resultMap);

        } catch (Exception e) {
            log.error("메일 목록 조회 실패: {}", e.getMessage(), e);
            return makeFailJsonData(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "메일 조회 중 오류가 발생했습니다.", "MAIL_LIST_FAILED");
        }
    }

    // ─── 3. 메일 AI 요약 ─────────────────────────────────────

    /**
     * 세션 자격증명으로 메일을 조회하고 AI 요약을 생성한다.
     * POST /mail/summary.do
     */
    @SuppressWarnings("unchecked")
    @RequestMapping(value = "/summary.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView summary(@RequestBody(required = false) Map<String, String> body,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        try {
            String email    = getMailSession(request, SESSION_KEY_EMAIL);
            String password = getMailSession(request, SESSION_KEY_PASSWORD);

            if (email == null || password == null) {
                return makeFailJsonData(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다. 로그인 후 다시 시도해주세요.", "MAIL_AUTH_REQUIRED");
            }

            String startDateStr = body != null ? body.get("startDate") : null;
            String endDateStr = body != null ? body.get("endDate") : null;
            if (isBlank(startDateStr) || isBlank(endDateStr)) {
                startDateStr = getMailSession(request, SESSION_KEY_START_DATE);
                endDateStr = getMailSession(request, SESSION_KEY_END_DATE);
            }
            DateRange dateRange = resolveDateRange(startDateStr, endDateStr);
            if (!dateRange.isValid()) {
                return makeFailJsonData(response, HttpServletResponse.SC_BAD_REQUEST,
                    "조회 기간이 올바르지 않습니다. 시작일과 종료일을 확인해 주세요.", "MAIL_INVALID_DATE_RANGE");
            }
            saveDateRangeToSession(request, dateRange.getStartDateStr(), dateRange.getEndDateStr());
            List<MailDto> mails = mailService.getRecentMails(
                email,
                password,
                dateRange.getStartDate(),
                dateRange.getEndExclusive()
            );

            if (mails.isEmpty()) {
                HashMap<String, Object> empty = new HashMap<>();
                List<String> emptyBriefing = new ArrayList<>();
                emptyBriefing.add("조회된 메일이 없습니다.");
                empty.put("briefing", emptyBriefing);
                empty.put("actionItems", new ArrayList<>());
                return makeSuccessJsonData(empty);
            }

            String prompt = buildSummaryPrompt(mails);
            String aiResponse = chatbotService.callAiSummary(prompt, "mail_summary");

            if (aiResponse == null) {
                return makeFailJsonData(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "AI 요약 생성에 실패했습니다.", "MAIL_SUMMARY_AI_FAILED");
            }

            // AI 응답 파싱 시도
            HashMap<String, Object> parsed = parseAiResponse(aiResponse);
            return makeSuccessJsonData(parsed);

        } catch (Exception e) {
            log.error("메일 AI 요약 실패: {}", e.getMessage(), e);
            return makeFailJsonData(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "AI 요약 처리 중 오류가 발생했습니다.", "MAIL_SUMMARY_FAILED");
        }
    }

    // ─── 4. 메일 컨텍스트 AI 채팅 ──────────────────────────────

    /**
     * 메일 컨텍스트 기반 AI 채팅 응답 생성.
     * POST /mail/chat.do
     * Body: { message, mailContext, chatHistory: [{role, content}] }
     */
    @SuppressWarnings("unchecked")
    @RequestMapping(value = "/chat.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView chat(@RequestBody(required = false) Map<String, Object> body,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        try {
            String email = getMailSession(request, SESSION_KEY_EMAIL);
            String password = getMailSession(request, SESSION_KEY_PASSWORD);
            if (email == null || password == null) {
                return makeFailJsonData(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다. 로그인 후 다시 시도해주세요.", "MAIL_AUTH_REQUIRED");
            }

            Map<String, Object> requestBody = body != null ? body : new HashMap<>();
            String message = getStringValue(requestBody.get("message"));
            String mailContext = getStringValue(requestBody.get("mailContext"));
            if (isBlank(message)) {
                return makeFailJsonData(response, HttpServletResponse.SC_BAD_REQUEST,
                    "message는 필수입니다.", "MAIL_CHAT_INVALID_REQUEST");
            }

            List<Map<String, Object>> chatHistory = new ArrayList<>();
            Object rawHistory = requestBody.get("chatHistory");
            if (rawHistory instanceof List) {
                List<?> historyList = (List<?>) rawHistory;
                int start = Math.max(0, historyList.size() - 10);
                for (int i = start; i < historyList.size(); i++) {
                    Object item = historyList.get(i);
                    if (item instanceof Map) {
                        chatHistory.add((Map<String, Object>) item);
                    }
                }
            }

            String prompt = buildMailChatPrompt(message, mailContext, chatHistory);
            String answer = chatbotService.callAiSummary(prompt, "mail_chat");
            if (isBlank(answer)) {
                return makeFailJsonData(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "메일 AI 채팅 응답 생성에 실패했습니다.", "MAIL_CHAT_AI_FAILED");
            }

            HashMap<String, Object> result = new HashMap<>();
            result.put("answer", answer);
            return makeSuccessJsonData(result);
        } catch (Exception e) {
            log.error("메일 AI 채팅 실패: {}", e.getMessage(), e);
            return makeFailJsonData(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "메일 AI 채팅 처리 중 오류가 발생했습니다.", "MAIL_CHAT_FAILED");
        }
    }

    // ─── 내부 헬퍼 ───────────────────────────────────────────

    private String getMailSession(HttpServletRequest request, String key) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object val = session.getAttribute(key);
        return val != null ? val.toString() : null;
    }

    private void saveDateRangeToSession(HttpServletRequest request, String startDate, String endDate) {
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_KEY_START_DATE, startDate);
        session.setAttribute(SESSION_KEY_END_DATE, endDate);
    }

    private DateRange resolveDateRange(String startDateStr, String endDateStr) {
        String startValue = startDateStr;
        String endValue = endDateStr;
        if (isBlank(startValue) || isBlank(endValue)) {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(7);
            startValue = start.format(DateTimeFormatter.ISO_LOCAL_DATE);
            endValue = end.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        try {
            LocalDate startDate = LocalDate.parse(startValue, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate endDate = LocalDate.parse(endValue, DateTimeFormatter.ISO_LOCAL_DATE);
            if (startDate.isAfter(endDate)) {
                return DateRange.invalid();
            }
            Date start = Date.from(startDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
            Date endExclusive = Date.from(endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
            return DateRange.of(startValue, endValue, start, endExclusive);
        } catch (DateTimeParseException e) {
            return DateRange.invalid();
        }
    }

    private ModelAndView makeFailJsonData(HttpServletResponse response, int status, String message, String code) {
        response.setStatus(status);
        HashMap<String, Object> result = new HashMap<>();
        result.put("message", message);
        result.put("msg", message);
        result.put("code", code);
        return makeFailJsonData(result);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String getStringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 메일 목록으로 AI 요약 프롬프트 생성 */
    private String buildSummaryPrompt(List<MailDto> mails) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음은 수신 이메일 목록입니다. 아래 형식의 JSON으로만 응답해주세요.\n\n");
        sb.append("응답 형식:\n");
        sb.append("{\n");
        sb.append("  \"briefing\": [\"요약 항목1\", \"요약 항목2\", \"요약 항목3\"],\n");
        sb.append("  \"actionItems\": [\n");
        sb.append("    {\"text\": \"액션 내용\", \"priority\": \"urgent|this_week|normal\", \"from\": \"발신자\", \"time\": \"시간\"}\n");
        sb.append("  ]\n");
        sb.append("}\n\n");
        sb.append("briefing은 반드시 문자열 배열로 작성하고, 각 원소는 한 줄 요약 문장으로 작성하세요.\n");
        sb.append("각 항목은 메일 제목/발신자/수신일시/핵심 내용을 포함한 리스트 형태로 정리하세요.\n\n");
        sb.append("priority 기준: urgent=즉시 처리 필요, this_week=이번 주 내, normal=일반\n\n");
        sb.append("이메일 목록:\n");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        int i = 1;
        for (MailDto mail : mails) {
            sb.append(i++).append(". 제목: ").append(mail.getSubject()).append("\n");
            sb.append("   발신자: ").append(mail.getFromName()).append("\n");
            sb.append("   수신: ").append(mail.getReceivedDate() != null ? sdf.format(mail.getReceivedDate()) : "").append("\n");
            if (mail.getBody() != null && !mail.getBody().isEmpty()) {
                String preview = mail.getBody().length() > 200 ? mail.getBody().substring(0, 200) + "..." : mail.getBody();
                sb.append("   내용: ").append(preview).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** AI 응답(JSON 문자열)을 파싱하여 Map으로 변환 */
    @SuppressWarnings("unchecked")
    private HashMap<String, Object> parseAiResponse(String aiResponse) {
        HashMap<String, Object> result = new HashMap<>();
        try {
            // AI 응답에서 JSON 블록 추출
            String jsonStr = aiResponse;
            int start = aiResponse.indexOf('{');
            int end   = aiResponse.lastIndexOf('}');
            if (start >= 0 && end > start) {
                jsonStr = aiResponse.substring(start, end + 1);
            }

            JSONParser parser = new JSONParser();
            JSONObject parsed = (JSONObject) parser.parse(jsonStr);
            Object briefingValue = parsed.get("briefing");
            if (briefingValue instanceof JSONArray) {
                result.put("briefing", briefingValue);
            } else if (briefingValue instanceof List) {
                result.put("briefing", briefingValue);
            } else if (briefingValue instanceof String) {
                List<String> briefingList = new ArrayList<>();
                briefingList.add((String) briefingValue);
                result.put("briefing", briefingList);
            } else {
                List<String> fallbackBriefing = new ArrayList<>();
                fallbackBriefing.add(aiResponse);
                result.put("briefing", fallbackBriefing);
            }
            result.put("actionItems", parsed.getOrDefault("actionItems", new JSONArray()));
        } catch (Exception e) {
            // 파싱 실패 시 원문을 briefing으로 반환
            log.warn("AI 응답 JSON 파싱 실패, 원문 반환: {}", e.getMessage());
            List<String> fallbackBriefing = new ArrayList<>();
            fallbackBriefing.add(aiResponse);
            result.put("briefing", fallbackBriefing);
            result.put("actionItems", new ArrayList<>());
        }
        return result;
    }

    private String buildMailChatPrompt(String message, String mailContext, List<Map<String, Object>> chatHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("[System]\n");
        sb.append(DEFAULT_CHAT_SYSTEM_PROMPT).append("\n\n");
        sb.append("[Context]\n");
        sb.append("메일 컨텍스트:\n");
        sb.append(isBlank(mailContext) ? "조회된 메일 컨텍스트가 없습니다." : mailContext).append("\n\n");
        sb.append("최근 대화 이력(최대 10턴):\n");
        if (chatHistory == null || chatHistory.isEmpty()) {
            sb.append("(없음)\n");
        } else {
            for (Map<String, Object> item : chatHistory) {
                String role = getStringValue(item.get("role"));
                String content = getStringValue(item.get("content"));
                if (!isBlank(content)) {
                    sb.append("- ").append(isBlank(role) ? "assistant" : role).append(": ").append(content).append("\n");
                }
            }
        }
        sb.append("\n[User]\n");
        sb.append(message);
        return sb.toString();
    }

    private static final class DateRange {
        private final String startDateStr;
        private final String endDateStr;
        private final Date startDate;
        private final Date endExclusive;
        private final boolean valid;

        private DateRange(String startDateStr, String endDateStr, Date startDate, Date endExclusive, boolean valid) {
            this.startDateStr = startDateStr;
            this.endDateStr = endDateStr;
            this.startDate = startDate;
            this.endExclusive = endExclusive;
            this.valid = valid;
        }

        private static DateRange of(String startDateStr, String endDateStr, Date startDate, Date endExclusive) {
            return new DateRange(startDateStr, endDateStr, startDate, endExclusive, true);
        }

        private static DateRange invalid() {
            return new DateRange(null, null, null, null, false);
        }

        private boolean isValid() {
            return valid;
        }

        private String getStartDateStr() {
            return startDateStr;
        }

        private String getEndDateStr() {
            return endDateStr;
        }

        private Date getStartDate() {
            return startDate;
        }

        private Date getEndExclusive() {
            return endExclusive;
        }
    }
}
