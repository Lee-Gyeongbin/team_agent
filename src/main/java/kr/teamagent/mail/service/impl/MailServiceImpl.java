package kr.teamagent.mail.service.impl;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeUtility;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.mail.service.MailVO;

@Service
public class MailServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(MailServiceImpl.class);
    private static final Pattern HTML_TAG   = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s{2,}");

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
    private ChatbotServiceImpl chatbotService;

    // ─── 1. IMAP 인증 ────────────────────────────────────────

    public boolean authImap(String email, String password) {
        String host = PropertyUtil.getProperty("Globals.mail.imap.host");
        int    port = Integer.parseInt(PropertyUtil.getProperty("Globals.mail.imap.port"));

        Store store = null;
        try {
            Session session = Session.getInstance(buildImapProperties(host, port));
            store = session.getStore("imaps");
            store.connect(host, port, email, password);
            return true;
        } catch (Exception e) {
            log.warn("IMAP 인증 실패 [{}]: {}", email, e.getMessage());
            return false;
        } finally {
            closeStore(store);
        }
    }

    // ─── 2. 받은 메일 목록 조회 ──────────────────────────────

    /**
     * INBOX 메일을 날짜 범위로 조회하고, 미읽음/오늘 수신 수를 포함한 결과를 반환한다.
     * 반환 Map에 resolvedStartDate, resolvedEndDate 가 포함되어 있으며 컨트롤러에서 세션 저장 후 제거한다.
     */
    public HashMap<String, Object> getInboxResult(String email, String password, String startDateStr, String endDateStr) throws Exception {
        DateRange dateRange = resolveDateRange(startDateStr, endDateStr);
        if (!dateRange.isValid()) {
            throw new IllegalArgumentException("INVALID_DATE_RANGE");
        }

        List<MailVO> mails = fetchMailsFromFolder(email, password, dateRange.getStartDate(), dateRange.getEndExclusive(), "INBOX");

        long unreadCount = mails.stream().filter(m -> !m.isRead()).count();
        String today = new SimpleDateFormat("yyyyMMdd").format(new Date());
        long todayCount = mails.stream()
            .filter(m -> m.getReceivedDate() != null
                && new SimpleDateFormat("yyyyMMdd").format(m.getReceivedDate()).equals(today))
            .count();

        HashMap<String, Object> result = new HashMap<>();
        result.put("mails",       mails);
        result.put("totalCount",  mails.size());
        result.put("unreadCount", unreadCount);
        result.put("todayCount",  todayCount);
        result.put("resolvedStartDate", dateRange.getStartDateStr());
        result.put("resolvedEndDate",   dateRange.getEndDateStr());
        return result;
    }

    // ─── 3. 보낸 메일 목록 조회 ──────────────────────────────

    public HashMap<String, Object> getSentResult(String email, String password, String startDateStr, String endDateStr) throws Exception {
        DateRange dateRange = resolveDateRange(startDateStr, endDateStr);
        if (!dateRange.isValid()) {
            throw new IllegalArgumentException("INVALID_DATE_RANGE");
        }

        List<MailVO> mails = fetchMailsFromFolder(email, password, dateRange.getStartDate(), dateRange.getEndExclusive(), "SENT");

        HashMap<String, Object> result = new HashMap<>();
        result.put("mails",      mails);
        result.put("totalCount", mails.size());
        return result;
    }

    // ─── 4. AI 메일 요약 ─────────────────────────────────────

    public HashMap<String, Object> getSummaryResult(String email, String password, String startDateStr, String endDateStr) throws Exception {
        DateRange dateRange = resolveDateRange(startDateStr, endDateStr);
        if (!dateRange.isValid()) {
            throw new IllegalArgumentException("INVALID_DATE_RANGE");
        }

        List<MailVO> mails = fetchMailsFromFolder(email, password, dateRange.getStartDate(), dateRange.getEndExclusive(), "INBOX");

        if (mails.isEmpty()) {
            HashMap<String, Object> empty = new HashMap<>();
            List<String> emptyBriefing = new ArrayList<>();
            emptyBriefing.add("조회된 메일이 없습니다.");
            empty.put("briefing",     emptyBriefing);
            empty.put("actionItems",  new ArrayList<>());
            return empty;
        }

        String prompt     = buildSummaryPrompt(mails);
        String aiResponse = chatbotService.callAiSummary(prompt, "mail_summary");

        if (aiResponse == null) {
            throw new RuntimeException("AI_FAILED");
        }
        return parseAiResponse(aiResponse);
    }

    // ─── 5. AI 메일 채팅 ─────────────────────────────────────

    public String getChatResult(String message, String mailContext, List<Map<String, Object>> chatHistory) throws Exception {
        // 최근 10턴만 사용
        List<Map<String, Object>> recentHistory = chatHistory != null && chatHistory.size() > 10
            ? chatHistory.subList(chatHistory.size() - 10, chatHistory.size())
            : chatHistory;

        String prompt = buildMailChatPrompt(message, mailContext, recentHistory);
        String answer = chatbotService.callAiSummary(prompt, "mail_chat");

        if (isBlank(answer)) {
            throw new RuntimeException("AI_FAILED");
        }
        return answer;
    }

    // ─── 6. 보낸 메일 목록 조회 (팔로업 트래커용, 경량 버전) ──

    public HashMap<String, Object> getSentListResult(String email, String password, String startDateStr, String endDateStr) throws Exception {
        DateRange dateRange = resolveDateRange(startDateStr, endDateStr);
        if (!dateRange.isValid()) {
            throw new IllegalArgumentException("INVALID_DATE_RANGE");
        }

        List<MailVO> mails = fetchMailsFromFolder(email, password, dateRange.getStartDate(), dateRange.getEndExclusive(), "SENT");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        List<Map<String, Object>> sentMails = new ArrayList<>();
        for (MailVO mail : mails) {
            Map<String, Object> item = new HashMap<>();
            item.put("to",          mail.getToName());
            item.put("toEmail",     extractEmail(mail.getTo()));
            item.put("subject",     mail.getSubject());
            item.put("sentDate",    mail.getSentDate() != null ? sdf.format(mail.getSentDate()) : null);
            item.put("messageId",   mail.getMessageId() != null ? mail.getMessageId() : "");
            sentMails.add(item);
        }

        HashMap<String, Object> result = new HashMap<>();
        result.put("sentMails", sentMails);
        return result;
    }

    // ─── 7. 팔로업 상태 조회 (보낸 메일 vs 받은 메일 교차 분석) ─

    public HashMap<String, Object> getFollowupStatus(String email, String password, String startDateStr, String endDateStr) throws Exception {
        DateRange dateRange = resolveDateRange(startDateStr, endDateStr);
        if (!dateRange.isValid()) {
            throw new IllegalArgumentException("INVALID_DATE_RANGE");
        }

        List<MailVO> sentMails = fetchMailsFromFolder(email, password, dateRange.getStartDate(), dateRange.getEndExclusive(), "SENT");

        if (sentMails.isEmpty()) {
            Map<String, Object> emptyStats = new HashMap<>();
            emptyStats.put("pendingCount",       0);
            emptyStats.put("avgWaitDays",        0.0);
            emptyStats.put("completedThisWeek",  0);
            HashMap<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("pending",   new ArrayList<>());
            emptyResult.put("completed", new ArrayList<>());
            emptyResult.put("stats",     emptyStats);
            return emptyResult;
        }

        // 가장 이른 발송일 기준으로 받은 메일 조회 (답장 여부 확인용)
        Date earliestSent = sentMails.stream()
            .map(m -> m.getSentDate() != null ? m.getSentDate() : m.getReceivedDate())
            .filter(d -> d != null)
            .min(Date::compareTo)
            .orElse(dateRange.getStartDate());

        Date tomorrow = Date.from(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        List<MailVO> receivedMails = fetchMailsFromFolder(email, password, earliestSent, tomorrow, "INBOX");

        List<Map<String, Object>> pending   = new ArrayList<>();
        List<Map<String, Object>> completed = new ArrayList<>();

        SimpleDateFormat sdf  = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Date now              = new Date();
        Date weekStart        = Date.from(LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay(ZoneId.systemDefault()).toInstant());
        int  completedThisWeek = 0;

        for (MailVO sent : sentMails) {
            String toEmail  = extractEmail(sent.getTo());
            Date   sentDate = sent.getSentDate() != null ? sent.getSentDate() : sent.getReceivedDate();
            if (sentDate == null || isBlank(toEmail)) continue;

            // 답장을 기대하는 메일인지 키워드로 필터링
            if (!isReplyExpected(sent.getSubject(), sent.getBody())) continue;

            // 동일 발신자(toEmail)로부터 sentDate 이후 수신된 메일이 있는지 확인
            MailVO reply = null;
            for (MailVO received : receivedMails) {
                String fromEmail = extractEmail(received.getFrom());
                if (toEmail.equalsIgnoreCase(fromEmail)
                    && received.getReceivedDate() != null
                    && received.getReceivedDate().after(sentDate)) {
                    if (reply == null || received.getReceivedDate().before(reply.getReceivedDate())) {
                        reply = received;
                    }
                }
            }

            if (reply == null) {
                long daysElapsed = (now.getTime() - sentDate.getTime()) / (1000L * 60 * 60 * 24);
                Map<String, Object> item = new HashMap<>();
                item.put("to",          sent.getToName());
                item.put("toEmail",     toEmail);
                item.put("subject",     sent.getSubject());
                item.put("sentDate",    sdf.format(sentDate));
                item.put("daysElapsed", (int) daysElapsed);
                pending.add(item);
            } else {
                long daysElapsed = (reply.getReceivedDate().getTime() - sentDate.getTime()) / (1000L * 60 * 60 * 24);
                Map<String, Object> item = new HashMap<>();
                item.put("to",          sent.getToName());
                item.put("toEmail",     toEmail);
                item.put("subject",     sent.getSubject());
                item.put("sentDate",    sdf.format(sentDate));
                item.put("replyDate",   sdf.format(reply.getReceivedDate()));
                item.put("daysElapsed", (int) daysElapsed);
                completed.add(item);
                if (reply.getReceivedDate().after(weekStart)) {
                    completedThisWeek++;
                }
            }
        }

        double avgWaitDays = 0.0;
        if (!pending.isEmpty()) {
            long totalDays = 0;
            for (Map<String, Object> item : pending) {
                totalDays += (int) item.get("daysElapsed");
            }
            avgWaitDays = Math.round((double) totalDays / pending.size() * 10.0) / 10.0;
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("pendingCount",       pending.size());
        stats.put("avgWaitDays",        avgWaitDays);
        stats.put("completedThisWeek",  completedThisWeek);

        HashMap<String, Object> result = new HashMap<>();
        result.put("pending",   pending);
        result.put("completed", completed);
        result.put("stats",     stats);
        return result;
    }

    // ─── 8. AI 독촉 메일 초안 생성 ───────────────────────────

    public HashMap<String, Object> getFollowupDraft(String to, String subject, String originalDate) throws Exception {
        String prompt  = buildFollowupDraftPrompt(to, subject, originalDate);
        String draft   = chatbotService.callAiSummary(prompt, "mail_followup_draft");
        if (isBlank(draft)) {
            throw new RuntimeException("AI_FAILED");
        }
        HashMap<String, Object> result = new HashMap<>();
        result.put("draft", draft);
        return result;
    }

    private String buildFollowupDraftPrompt(String to, String subject, String originalDate) {
        return "당신은 이메일 작성을 도와주는 비서입니다.\n"
             + "아래 정보를 바탕으로 정중하고 자연스러운 답장 독촉 메일 초안을 작성하세요.\n\n"
             + "수신자: " + to + "\n"
             + "원본 메일 제목: " + subject + "\n"
             + "원본 발송일: " + originalDate + "\n\n"
             + "한국어로 작성하고, 제목과 본문을 구분하여 출력하세요.\n"
             + "형식:\n제목: [제목]\n\n[본문]";
    }

    // ─── 내부: IMAP 메일 조회 ────────────────────────────────

    private List<MailVO> fetchMailsFromFolder(String email, String password, Date startDate, Date endExclusive, String folderName) throws Exception {
        String host = PropertyUtil.getProperty("Globals.mail.imap.host");
        int    port = Integer.parseInt(PropertyUtil.getProperty("Globals.mail.imap.port"));

        Session session = Session.getInstance(buildImapProperties(host, port));
        Store store = null;
        Folder folder = null;
        List<MailVO> result = new ArrayList<>();

        try {
            store = session.getStore("imaps");
            store.connect(host, port, email, password);

            folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);

            int total = folder.getMessageCount();
            if (total == 0) return result;

            for (int messageNumber = total; messageNumber >= 1; messageNumber--) {
                Message msg = folder.getMessage(messageNumber);
                try {
                    Date receivedDate = safeReceivedDate(msg);
                    Date sentDate     = safeSentDate(msg);
                    Date msgDate      = receivedDate != null ? receivedDate : sentDate;
                    if (msgDate == null) continue;

                    // INBOX: receivedDate 기준 조기 중단 최적화
                    if (receivedDate != null && receivedDate.before(startDate)) break;

                    if (msgDate.before(startDate))      continue;
                    if (!msgDate.before(endExclusive))  continue;

                    result.add(toVO(msg));
                } catch (Exception e) {
                    log.warn("메일 파싱 오류 (folder={}, messageNumber={}): {}", folderName, msg.getMessageNumber(), e.getMessage());
                }
            }
        } finally {
            closeQuietly(folder, store);
        }
        return result;
    }

    // ─── 내부: 날짜 범위 처리 ────────────────────────────────

    DateRange resolveDateRange(String startDateStr, String endDateStr) {
        String startValue = startDateStr;
        String endValue   = endDateStr;

        if (isBlank(startValue) || isBlank(endValue)) {
            LocalDate end   = LocalDate.now();
            LocalDate start = end.minusDays(7);
            startValue = start.format(DateTimeFormatter.ISO_LOCAL_DATE);
            endValue   = end.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        try {
            LocalDate startDate = LocalDate.parse(startValue, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate endDate   = LocalDate.parse(endValue,   DateTimeFormatter.ISO_LOCAL_DATE);
            if (startDate.isAfter(endDate)) return DateRange.invalid();

            Date start       = Date.from(startDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
            Date endExclusive = Date.from(endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
            return DateRange.of(startValue, endValue, start, endExclusive);
        } catch (DateTimeParseException e) {
            return DateRange.invalid();
        }
    }

    static final class DateRange {
        private final String startDateStr;
        private final String endDateStr;
        private final Date   startDate;
        private final Date   endExclusive;
        private final boolean valid;

        private DateRange(String startDateStr, String endDateStr, Date startDate, Date endExclusive, boolean valid) {
            this.startDateStr = startDateStr;
            this.endDateStr   = endDateStr;
            this.startDate    = startDate;
            this.endExclusive = endExclusive;
            this.valid        = valid;
        }

        static DateRange of(String startDateStr, String endDateStr, Date startDate, Date endExclusive) {
            return new DateRange(startDateStr, endDateStr, startDate, endExclusive, true);
        }

        static DateRange invalid() {
            return new DateRange(null, null, null, null, false);
        }

        boolean isValid()          { return valid; }
        String  getStartDateStr()  { return startDateStr; }
        String  getEndDateStr()    { return endDateStr; }
        Date    getStartDate()     { return startDate; }
        Date    getEndExclusive()  { return endExclusive; }
    }

    // ─── 내부: AI 프롬프트 / 응답 처리 ──────────────────────

    private String buildSummaryPrompt(List<MailVO> mails) {
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
        for (MailVO mail : mails) {
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

    @SuppressWarnings("unchecked")
    private HashMap<String, Object> parseAiResponse(String aiResponse) {
        HashMap<String, Object> result = new HashMap<>();
        try {
            String jsonStr = aiResponse;
            int start = aiResponse.indexOf('{');
            int end   = aiResponse.lastIndexOf('}');
            if (start >= 0 && end > start) {
                jsonStr = aiResponse.substring(start, end + 1);
            }

            JSONParser parser = new JSONParser();
            JSONObject parsed = (JSONObject) parser.parse(jsonStr);
            Object briefingValue = parsed.get("briefing");

            if (briefingValue instanceof JSONArray || briefingValue instanceof List) {
                result.put("briefing", briefingValue);
            } else if (briefingValue instanceof String) {
                List<String> list = new ArrayList<>();
                list.add((String) briefingValue);
                result.put("briefing", list);
            } else {
                List<String> list = new ArrayList<>();
                list.add(aiResponse);
                result.put("briefing", list);
            }
            result.put("actionItems", parsed.getOrDefault("actionItems", new JSONArray()));
        } catch (Exception e) {
            log.warn("AI 응답 JSON 파싱 실패, 원문 반환: {}", e.getMessage());
            List<String> list = new ArrayList<>();
            list.add(aiResponse);
            result.put("briefing",    list);
            result.put("actionItems", new ArrayList<>());
        }
        return result;
    }

    private String buildMailChatPrompt(String message, String mailContext, List<Map<String, Object>> chatHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("[System]\n").append(DEFAULT_CHAT_SYSTEM_PROMPT).append("\n\n");
        sb.append("[Context]\n메일 컨텍스트:\n");
        sb.append(isBlank(mailContext) ? "조회된 메일 컨텍스트가 없습니다." : mailContext).append("\n\n");
        sb.append("최근 대화 이력(최대 10턴):\n");
        if (chatHistory == null || chatHistory.isEmpty()) {
            sb.append("(없음)\n");
        } else {
            for (Map<String, Object> item : chatHistory) {
                String role    = toStr(item.get("role"));
                String content = toStr(item.get("content"));
                if (!isBlank(content)) {
                    sb.append("- ").append(isBlank(role) ? "assistant" : role).append(": ").append(content).append("\n");
                }
            }
        }
        sb.append("\n[User]\n").append(message);
        return sb.toString();
    }

    // ─── 내부: IMAP VO 변환 ──────────────────────────────────

    private MailVO toVO(Message msg) throws MessagingException, IOException {
        MailVO vo = new MailVO();

        String rawSubject = msg.getSubject();
        vo.setSubject(rawSubject != null ? decodeText(rawSubject) : "(제목 없음)");

        javax.mail.Address[] froms = msg.getFrom();
        if (froms != null && froms.length > 0) {
            String decoded = decodeText(froms[0].toString());
            vo.setFrom(decoded);
            vo.setFromName(extractPersonalName(froms[0], decoded));
        } else {
            vo.setFrom("");
            vo.setFromName("");
        }

        javax.mail.Address[] recipients = msg.getRecipients(javax.mail.Message.RecipientType.TO);
        if (recipients != null && recipients.length > 0) {
            String decoded = decodeText(recipients[0].toString());
            vo.setTo(decoded);
            vo.setToName(extractPersonalName(recipients[0], decoded));
        }

        vo.setReceivedDate(msg.getReceivedDate());
        vo.setSentDate(safeSentDate(msg));
        vo.setRead(msg.isSet(Flags.Flag.SEEN));
        vo.setBody(extractBody(msg));

        try {
            String[] messageIds = msg.getHeader("Message-ID");
            vo.setMessageId(messageIds != null && messageIds.length > 0 ? messageIds[0] : "");
        } catch (Exception ignored) {
            vo.setMessageId("");
        }

        return vo;
    }

    private String extractPersonalName(javax.mail.Address address, String decoded) {
        try {
            if (address instanceof InternetAddress) {
                InternetAddress ia = (InternetAddress) address;
                String personal = ia.getPersonal();
                if (personal != null && !personal.isEmpty()) return decodeText(personal);
                return ia.getAddress();
            }
        } catch (Exception ignored) {}
        return decoded.replaceAll("<[^>]+>", "").trim();
    }

    private String extractBody(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) return (String) part.getContent();

        if (part.isMimeType("text/html")) return stripHtml((String) part.getContent());

        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            String textBody = null;
            String htmlBody = null;

            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(bp.getDisposition())) continue;

                if (bp.isMimeType("text/plain") && textBody == null) {
                    textBody = (String) bp.getContent();
                } else if (bp.isMimeType("text/html") && htmlBody == null) {
                    htmlBody = stripHtml((String) bp.getContent());
                } else if (bp.isMimeType("multipart/*")) {
                    String nested = extractBody(bp);
                    if (nested != null && !nested.isEmpty() && textBody == null) textBody = nested;
                }
            }
            if (textBody != null) return textBody;
            if (htmlBody  != null) return htmlBody;
        }
        return "";
    }

    // ─── 내부: 유틸 ──────────────────────────────────────────

    private Properties buildImapProperties(String host, int port) {
        Properties props = new Properties();
        props.put("mail.imaps.host",                   host);
        props.put("mail.imaps.port",                   String.valueOf(port));
        props.put("mail.imaps.ssl.enable",             "true");
        props.put("mail.imaps.socketFactory.class",    "javax.net.ssl.SSLSocketFactory");
        props.put("mail.imaps.socketFactory.fallback", "false");
        props.put("mail.imaps.connectiontimeout",      "10000");
        props.put("mail.imaps.timeout",                "10000");
        // Java 11+ TLS 1.3의 PSK(Pre-Shared Key) 세션 재개 문제 방지 → TLS 1.2 강제
        props.put("mail.imaps.ssl.protocols",          "TLSv1.2");
        return props;
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        // HTML 태그 제거
        String result = HTML_TAG.matcher(html).replaceAll(" ");
        // HTML 엔티티 디코딩
        result = result
            .replace("&nbsp;",  " ")
            .replace("&amp;",   "&")
            .replace("&lt;",    "<")
            .replace("&gt;",    ">")
            .replace("&quot;",  "\"")
            .replace("&#39;",   "'")
            .replace("&apos;",  "'");
        // 연속 공백 정규화, 빈 줄 최대 2줄로 제한
        result = WHITESPACE.matcher(result).replaceAll(" ");
        result = result.replaceAll("(?:\\s*\\n\\s*){3,}", "\n\n");
        return result.trim();
    }

    private String decodeText(String text) {
        try { return MimeUtility.decodeText(text); } catch (Exception e) { return text; }
    }

    private Date safeReceivedDate(Message msg) {
        try { return msg.getReceivedDate(); } catch (MessagingException e) { return null; }
    }

    private Date safeSentDate(Message msg) {
        try { return msg.getSentDate(); } catch (MessagingException e) { return null; }
    }

    /**
     * 보낸 메일의 제목·본문에 답장을 기대하는 키워드가 포함되어 있으면 true를 반환한다.
     * 단순 공유·알림·FYI 메일은 팔로업 대상에서 제외하기 위해 사용한다.
     */
    private static final List<String> REPLY_EXPECTED_KEYWORDS = java.util.Arrays.asList(
        // 한국어 — 확인/검토 요청
        "확인해주세요", "확인해 주세요", "확인 부탁", "확인 요청", "확인 후",
        "검토 부탁", "검토해주세요", "검토해 주세요", "검토 요청", "검토 후",
        // 한국어 — 회신/답변 요청
        "회신 부탁", "회신해주세요", "회신해 주세요", "회신 주시면", "회신 요청",
        "답변 부탁", "답변해주세요", "답변해 주세요", "답장 부탁", "답장 주시면",
        // 한국어 — 의견/피드백 요청
        "알려주세요", "알려 주세요", "의견 부탁", "의견 주시면", "의견을 주시면",
        "피드백 부탁", "피드백 주시면", "피드백을",
        // 한국어 — 승인/협조 요청
        "승인 부탁", "승인해주세요", "승인 요청",
        "협조 부탁", "협조해주세요",
        "요청드립니다", "부탁드립니다",
        // 영문 — reply / respond
        "please reply", "please respond", "kindly reply", "kindly respond",
        // 영문 — confirm / review
        "please confirm", "please review", "please check",
        // 영문 — let me know / awaiting
        "let me know", "please let me know", "awaiting your",
        "looking forward to your", "your feedback", "your response",
        "your approval", "your confirmation"
    );

    private boolean isReplyExpected(String subject, String body) {
        String haystack = ((subject != null ? subject : "") + " " + (body != null ? body : "")).toLowerCase();
        for (String keyword : REPLY_EXPECTED_KEYWORDS) {
            if (haystack.contains(keyword.toLowerCase())) return true;
        }
        return false;
    }

    private String extractEmail(String address) {
        if (isBlank(address)) return "";
        int start = address.lastIndexOf('<');
        int end   = address.lastIndexOf('>');
        if (start >= 0 && end > start) return address.substring(start + 1, end).trim();
        return address.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String toStr(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void closeStore(Store store) {
        try { if (store != null && store.isConnected()) store.close(); } catch (Exception ignored) {}
    }

    private void closeQuietly(Folder folder, Store store) {
        try { if (folder != null && folder.isOpen()) folder.close(false); } catch (Exception ignored) {}
        closeStore(store);
    }
}
