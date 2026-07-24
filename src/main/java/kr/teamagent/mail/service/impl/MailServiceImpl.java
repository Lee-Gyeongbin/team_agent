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
import javax.mail.search.ComparisonTerm;
import javax.mail.search.ReceivedDateTerm;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.util.KeyGenerate;
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

    @Autowired
    private MailDAO mailDAO;

    @Autowired
    private KeyGenerate keyGenerate;

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
        String aiResponse = chatbotService.callAiSummary(prompt, "mail_summary", null);

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
        String answer = chatbotService.callAiSummary(prompt, "mail_chat", null);

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
        String draft   = chatbotService.callAiSummary(prompt, "mail_followup_draft", null);
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

    // ─── A. 계정 저장 (인증 성공 시 TB_MAIL_ACCOUNT upsert) ────

    /**
     * 세션 만료 시 TB_MAIL_ACCOUNT 저장 자격증명으로 IMAP 재연결을 시도한다.
     * @return success=true 시 email/password/accountId 포함, 실패 시 success=false 및 code
     */
    public HashMap<String, Object> tryReconnectFromStoredAccount(String userId) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("success", false);

        if (isBlank(userId)) {
            result.put("code", "MAIL_AUTH_REQUIRED");
            return result;
        }

        try {
            MailVO.MailAccountVO account = mailDAO.selectMailAccountByUserId(userId);
            if (account == null) {
                result.put("code", "MAIL_AUTH_REQUIRED");
                return result;
            }
            if (account.getCredentialEnc() == null || account.getCredentialIv() == null) {
                result.put("code", "MAIL_AUTH_REQUIRED");
                return result;
            }

            String email    = account.getEmailAddr();
            String password = decryptCredential(account.getCredentialEnc(), account.getCredentialIv());
            if (!authImap(email, password)) {
                log.warn("저장 자격증명 IMAP 재연결 실패 [{}]", email);
                result.put("code", "MAIL_CREDENTIAL_INVALID");
                return result;
            }

            result.put("success",   true);
            result.put("email",     email);
            result.put("password",  password);
            result.put("accountId", account.getAccountId());
            result.put("userId",    userId);
            return result;
        } catch (Exception e) {
            log.warn("메일 자동 재연결 실패: {}", e.getMessage());
            result.put("code", "MAIL_CREDENTIAL_INVALID");
            return result;
        }
    }

    /**
     * IMAP 인증 성공 후 계정 정보를 TB_MAIL_ACCOUNT에 저장.
     * AES-128-CBC 암호화 적용. 추후 KMS 연동 시 encryptCredential/decryptCredential 메서드만 교체.
     */
    public String saveMailAccount(String userId, String email, String password) throws Exception {
        String host = PropertyUtil.getProperty("Globals.mail.imap.host");
        int    port = Integer.parseInt(PropertyUtil.getProperty("Globals.mail.imap.port"));

        Map<String, Object> checkParam = new HashMap<>();
        checkParam.put("userId",    userId);
        checkParam.put("emailAddr", email);

        MailVO.MailAccountVO existing = mailDAO.selectMailAccount(checkParam);

        byte[] iv = new byte[16];
        new java.security.SecureRandom().nextBytes(iv);
        byte[] encCredential = encryptCredential(password, iv);

        if (existing != null) {
            Map<String, Object> updateParam = new HashMap<>();
            updateParam.put("accountId",     existing.getAccountId());
            updateParam.put("credentialEnc", encCredential);
            updateParam.put("credentialIv",  iv);
            updateParam.put("keyVersion",    1);
            mailDAO.updateMailAccountCredential(updateParam);
            return existing.getAccountId();
        } else {
            String accountId = keyGenerate.generateTableKey("MA", "TB_MAIL_ACCOUNT", "ACCOUNT_ID");
            MailVO.MailAccountVO newAccount = new MailVO.MailAccountVO();
            newAccount.setAccountId(accountId);
            newAccount.setUserId(userId);
            newAccount.setEmailAddr(email);
            newAccount.setImapHost(host);
            newAccount.setImapPort(port);
            newAccount.setAuthTypeCd("001"); // PASSWORD
            newAccount.setCredentialEnc(encCredential);
            newAccount.setCredentialIv(iv);
            newAccount.setKeyVersion(1);
            mailDAO.insertMailAccount(newAccount);
            return accountId;
        }
    }

    private byte[] getCredentialKeyBytes() throws Exception {
        String keyStr = PropertyUtil.getProperty("Globals.mail.credential.key");
        if (keyStr == null || keyStr.length() < 16) {
            keyStr = "teamagentmailkey";
        }
        return keyStr.substring(0, 16).getBytes("UTF-8");
    }

    private byte[] encryptCredential(String plainText, byte[] iv) {
        try {
            javax.crypto.SecretKey secretKey = new javax.crypto.spec.SecretKeySpec(getCredentialKeyBytes(), "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, new javax.crypto.spec.IvParameterSpec(iv));
            return cipher.doFinal(plainText.getBytes("UTF-8"));
        } catch (Exception e) {
            log.error("자격증명 암호화 실패: {}", e.getMessage(), e);
            throw new RuntimeException("CREDENTIAL_ENCRYPT_FAILED");
        }
    }

    private String decryptCredential(byte[] encCredential, byte[] iv) {
        try {
            javax.crypto.SecretKey secretKey = new javax.crypto.spec.SecretKeySpec(getCredentialKeyBytes(), "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, new javax.crypto.spec.IvParameterSpec(iv));
            return new String(cipher.doFinal(encCredential), "UTF-8");
        } catch (Exception e) {
            log.error("자격증명 복호화 실패: {}", e.getMessage(), e);
            throw new RuntimeException("CREDENTIAL_DECRYPT_FAILED");
        }
    }

    // ─── B. IMAP 동기화 (TB_MAIL_MSG + TB_MAIL_SYNC_STATE) ────

    /**
     * IMAP에서 신규 메일을 증분 동기화하여 TB_MAIL_MSG에 저장.
     * UID_VALIDITY 비교 → 변경 시 전체 재동기화, 같으면 LAST_SYNC_UID 이후만 조회.
     * @return 동기화된 신규 mailId 목록 (AI 분류 파이프라인으로 전달)
     */
    public List<String> syncMailMessages(String accountId, String email, String password, String folderKey) throws Exception {
        String host      = PropertyUtil.getProperty("Globals.mail.imap.host");
        int    port      = Integer.parseInt(PropertyUtil.getProperty("Globals.mail.imap.port"));
        String folderCd  = "SENT".equals(folderKey) ? "002" : "001"; // 001=INBOX, 002=SENT
        String folderName = "SENT".equals(folderKey) ? "SENT" : "INBOX";

        List<String> newMailIds = new ArrayList<>();

        Session session = Session.getInstance(buildImapProperties(host, port));
        Store  store  = null;
        Folder folder = null;
        try {
            store = session.getStore("imaps");
            store.connect(host, port, email, password);

            folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);

            com.sun.mail.imap.IMAPFolder imapFolder = (com.sun.mail.imap.IMAPFolder) folder;
            long currentUidValidity = imapFolder.getUIDValidity();

            // TB_MAIL_SYNC_STATE 조회
            Map<String, Object> stateParam = new HashMap<>();
            stateParam.put("accountId", accountId);
            stateParam.put("folderCd",  folderCd);
            MailVO.MailSyncStateVO syncState = mailDAO.selectMailSyncState(stateParam);

            long    lastSyncUid = 0L;
            boolean isFirstSync = (syncState == null);
            boolean fullSync    = false;

            if (syncState == null) {
                // 최초 동기화 — SINCE 14일 조건으로 제한 (isFirstSync 플래그로 분기)
            } else if (syncState.getUidValidity() != currentUidValidity) {
                fullSync = true;
                log.info("UID_VALIDITY 변경 감지 (account={}, folder={}): {} → {}",
                    accountId, folderName, syncState.getUidValidity(), currentUidValidity);
            } else {
                // TB_MAIL_SYNC_STATE에서 LAST_SYNC_UID
                lastSyncUid = syncState.getLastSyncUid();
            }

            long maxUid = lastSyncUid;
            int  total  = folder.getMessageCount();

            if (total == 0) {
                // 빈 폴더도 sync state 기록
                MailVO.MailSyncStateVO newState = new MailVO.MailSyncStateVO();
                newState.setAccountId(accountId);
                newState.setFolderCd(folderCd);
                newState.setUidValidity(currentUidValidity);
                newState.setLastSyncUid(lastSyncUid);
                mailDAO.upsertMailSyncState(newState);
                return newMailIds;
            }

            // 메시지 목록 취득
            Message[] allMessages;
            if (isFirstSync) {
                // 최초 동기화: 최근 14일치만 (IMAP SEARCH SINCE)
                Date since = Date.from(LocalDate.now().minusDays(14).atStartOfDay(ZoneId.systemDefault()).toInstant());
                allMessages = folder.search(new ReceivedDateTerm(ComparisonTerm.GE, since));
                log.info("최초 동기화 — SINCE 14일 적용 (account={}, folder={})", accountId, folderName);
            } else if (fullSync) {
                // UID_VALIDITY 변경 시 재동기화: 최근 200건
                int startIdx = Math.max(1, total - 199);
                allMessages = folder.getMessages(startIdx, total);
            } else {
                // 증분: 전체 메시지 순회 후 UID > lastSyncUid 필터링
                allMessages = folder.getMessages();
            }

            // IMAP 폴더 메일 순회 (allMessages)
            for (Message msg : allMessages) {
                long uid = imapFolder.getUID(msg);

                // 증분 동기화 시 이미 처리된 UID 스킵
                if (!fullSync && uid <= lastSyncUid) continue;

                //각 메일 uid가 LAST_SYNC_UID보다 크면 → 가져올 후보 (maxUid 갱신)
                if (uid > maxUid) maxUid = uid;

                String imapUidStr = String.valueOf(uid);

                // 중복 체크
                Map<String, Object> dupParam = new HashMap<>();
                dupParam.put("accountId", accountId);
                dupParam.put("folderCd",  folderCd);
                dupParam.put("imapUid",   imapUidStr);
                // TB_MAIL_MSG에 같은 IMAP_UID가 이미 있으면 → INSERT 스킵
                MailVO.MailMsgVO existingMsg = mailDAO.selectMailMsgByImapUid(dupParam);
                if (existingMsg != null) continue;

                try {
                    MailVO imap    = toVO(msg);
                    String mailId  = keyGenerate.generateTableKey("MS", "TB_MAIL_MSG", "MAIL_ID");

                    MailVO.MailMsgVO msgVO = new MailVO.MailMsgVO();
                    msgVO.setMailId(mailId);
                    msgVO.setAccountId(accountId);
                    msgVO.setFolderCd(folderCd);
                    msgVO.setImapUid(imapUidStr);
                    msgVO.setMsgIdHeader(imap.getMessageId());

                    // In-Reply-To 헤더
                    try {
                        String[] inReplyToArr = msg.getHeader("In-Reply-To");
                        if (inReplyToArr != null && inReplyToArr.length > 0) {
                            msgVO.setInReplyTo(inReplyToArr[0].trim());
                        }
                    } catch (Exception ignored) {}

                    msgVO.setSubject(imap.getSubject());
                    msgVO.setFromAddr(imap.getFrom());
                    msgVO.setFromName(imap.getFromName());

                    // TO_ADDR_JSON: 첫 번째 수신자만 JSON 배열로 저장
                    if (imap.getTo() != null) {
                        msgVO.setToAddrJson("[\"" + imap.getTo().replace("\"", "\\\"") + "\"]");
                    }

                    Date mailDate = imap.getReceivedDate() != null ? imap.getReceivedDate() : imap.getSentDate();
                    msgVO.setMailDt(mailDate);

                    // 본문: 최대 5000자 (AI 분석/미리보기용)
                    String bodyText = imap.getBody();
                    if (bodyText != null && bodyText.length() > 5000) bodyText = bodyText.substring(0, 5000);
                    msgVO.setBodyText(bodyText);

                    // 첨부 여부 판단
                    msgVO.setHasAttachYn(hasAttachment(msg) ? "Y" : "N");

                    mailDAO.insertMailMsg(msgVO);
                    newMailIds.add(mailId);

                } catch (Exception e) {
                    log.warn("메일 DB 저장 오류 (uid={}): {}", uid, e.getMessage());
                }
            }

            // 체크포인트 업데이트(sync 끝나면 LAST_SYNC_UID = 이번에 본 uid 중 최대값(maxUid)으로 갱신)
            MailVO.MailSyncStateVO newState = new MailVO.MailSyncStateVO();
            newState.setAccountId(accountId);
            newState.setFolderCd(folderCd);
            newState.setUidValidity(currentUidValidity);
            newState.setLastSyncUid(maxUid);
            mailDAO.upsertMailSyncState(newState);

            log.info("동기화 완료 (account={}, folder={}, 신규={}건, maxUid={})",
                accountId, folderName, newMailIds.size(), maxUid);

        } finally {
            closeQuietly(folder, store);
        }

        return newMailIds;
    }

    /**
     * 날짜 범위 기준 IMAP INBOX 동기화 (기존 IMAP_UID는 스킵, LAST_SYNC_UID 갱신 없음).
     * 증분 동기화와 독립적으로 동작하며, 과거 기간 메일 보완용으로 사용.
     * @return 신규 저장된 mailId 목록
     */
    public List<String> syncMailMessagesByDateRange(String accountId, String email, String password,
                                                     String startDateStr, String endDateStr) throws Exception {
        String host = PropertyUtil.getProperty("Globals.mail.imap.host");
        int    port = Integer.parseInt(PropertyUtil.getProperty("Globals.mail.imap.port"));

        LocalDate startDate = LocalDate.parse(startDateStr);
        LocalDate endDate   = LocalDate.parse(endDateStr);
        Date since  = Date.from(startDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date before = Date.from(endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());

        List<String> newMailIds = new ArrayList<>();

        // INBOX + SENT 두 폴더 모두 동기화
        String[][] folders = {{"INBOX", "001"}, {"SENT", "002"}};

        Session session = Session.getInstance(buildImapProperties(host, port));
        Store store = null;
        try {
            store = session.getStore("imaps");
            store.connect(host, port, email, password);

            for (String[] folderDef : folders) {
                String folderName = folderDef[0];
                String folderCd   = folderDef[1];

                Folder folder = null;
                try {
                    folder = store.getFolder(folderName);
                    folder.open(Folder.READ_ONLY);

                    com.sun.mail.imap.IMAPFolder imapFolder = (com.sun.mail.imap.IMAPFolder) folder;

                    // IMAP BEFORE 커맨드는 일부 서버에서 미지원/오동작 → syncMailMessages와 동일하게
                    // GE(SINCE)만 IMAP에 요청하고, 상한선(before)은 Java에서 필터링
                    Message[] messages = folder.search(new ReceivedDateTerm(ComparisonTerm.GE, since));

                    log.info("날짜범위 동기화 시작 (account={}, folder={}, {} ~ {}, {}건 검색)",
                        accountId, folderName, startDateStr, endDateStr, messages.length);

                    for (Message msg : messages) {
                        // 상한선 Java 필터링: before(endDate+1) 이후 메일 스킵
                        Date msgDate = msg.getReceivedDate() != null ? msg.getReceivedDate() : msg.getSentDate();
                        if (msgDate != null && !msgDate.before(before)) continue;

                        long   uid        = imapFolder.getUID(msg);
                        String imapUidStr = String.valueOf(uid);

                        // 이미 DB에 있으면 스킵
                        Map<String, Object> dupParam = new HashMap<>();
                        dupParam.put("accountId", accountId);
                        dupParam.put("folderCd",  folderCd);
                        dupParam.put("imapUid",   imapUidStr);
                        if (mailDAO.selectMailMsgByImapUid(dupParam) != null) continue;

                        try {
                            MailVO imap   = toVO(msg);
                            String mailId = keyGenerate.generateTableKey("MS", "TB_MAIL_MSG", "MAIL_ID");

                            MailVO.MailMsgVO msgVO = new MailVO.MailMsgVO();
                            msgVO.setMailId(mailId);
                            msgVO.setAccountId(accountId);
                            msgVO.setFolderCd(folderCd);
                            msgVO.setImapUid(imapUidStr);
                            msgVO.setMsgIdHeader(imap.getMessageId());

                            try {
                                String[] inReplyToArr = msg.getHeader("In-Reply-To");
                                if (inReplyToArr != null && inReplyToArr.length > 0) {
                                    msgVO.setInReplyTo(inReplyToArr[0].trim());
                                }
                            } catch (Exception ignored) {}

                            msgVO.setSubject(imap.getSubject());
                            msgVO.setFromAddr(imap.getFrom());
                            msgVO.setFromName(imap.getFromName());

                            if (imap.getTo() != null) {
                                msgVO.setToAddrJson("[\"" + imap.getTo().replace("\"", "\\\"") + "\"]");
                            }

                            Date mailDate = imap.getReceivedDate() != null ? imap.getReceivedDate() : imap.getSentDate();
                            msgVO.setMailDt(mailDate);

                            String bodyText = imap.getBody();
                            if (bodyText != null && bodyText.length() > 5000) bodyText = bodyText.substring(0, 5000);
                            msgVO.setBodyText(bodyText);

                            msgVO.setHasAttachYn(hasAttachment(msg) ? "Y" : "N");

                            mailDAO.insertMailMsg(msgVO);
                            newMailIds.add(mailId);

                        } catch (Exception e) {
                            log.warn("날짜범위 동기화 — 저장 오류 (folder={}, uid={}): {}", folderName, uid, e.getMessage());
                        }
                    }

                    log.info("날짜범위 동기화 완료 (account={}, folder={}, {} ~ {}, 신규={}건)",
                        accountId, folderName, startDateStr, endDateStr, newMailIds.size());

                } finally {
                    closeQuietly(folder, null);
                }
            }

        } finally {
            closeQuietly(null, store);
        }

        return newMailIds;
    }

    private boolean hasAttachment(Message msg) {
        try {
            Object content = msg.getContent();
            if (content instanceof Multipart) {
                Multipart mp = (Multipart) content;
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart bp = mp.getBodyPart(i);
                    if (Part.ATTACHMENT.equalsIgnoreCase(bp.getDisposition())) return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── C. AI 분류 파이프라인 ───────────────────────────────

    /**
     * 신규 동기화된 mailId 목록에 대해 AI 분류 수행 → TB_MAIL_AI_ANALYSIS upsert
     */
    public void classifyMails(List<String> mailIds) throws Exception {
        if (mailIds == null || mailIds.isEmpty()) return;

        // 업무영역 코드 동적 로드 (하드코딩 금지)
        List<Map<String, Object>> workCategories = mailDAO.selectWorkCategoryList();
        StringBuilder categoryPromptPart = new StringBuilder();
        for (Map<String, Object> cat : workCategories) {
            categoryPromptPart.append("  - ").append(cat.get("CODE_ID"))
                .append(": ").append(cat.get("CODE_NM")).append("\n");
        }

        for (String mailId : mailIds) {
            try {
                MailVO.MailMsgVO msg = mailDAO.selectMailMsgById(mailId);
                if (msg == null) continue;

                // 이미 분류된 메일은 LLM 호출 자체를 건너뜀 (TB_API_CALL_LOG 미적재)
                if (mailDAO.selectMailAiAnalysis(mailId) != null) {
                    log.debug("AI 분류 스킵 — 이미 분석됨 (mailId={})", mailId);
                    continue;
                }

                String prompt     = buildClassificationPrompt(msg, categoryPromptPart.toString());
                String aiResponse = chatbotService.callAiSummary(prompt, "mail_classify", null);

                if (isBlank(aiResponse)) {
                    log.warn("AI 분류 응답 없음 (mailId={})", mailId);
                    continue;
                }

                MailVO.MailAiAnalysisVO analysis = parseClassificationResponse(aiResponse);
                analysis.setMailId(mailId);
                mailDAO.insertMailAiAnalysis(analysis);

            } catch (Exception e) {
                log.error("AI 분류 실패 (mailId={}): {}", mailId, e.getMessage());
            }
        }
    }

    private String buildClassificationPrompt(MailVO.MailMsgVO msg, String categoryList) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        boolean isSent = "002".equals(msg.getFolderCd()); // 보낸메일함 여부
        StringBuilder sb = new StringBuilder();
        sb.append("다음 이메일을 분석하여 아래 형식의 JSON으로만 응답해주세요.\n\n");
        sb.append("응답 형식:\n");
        sb.append("{\n");
        sb.append("  \"mailPurposeCd\": \"(메일목적 코드)\",\n");
        sb.append("  \"actionRequiredCd\": \"(필요조치 코드)\",\n");
        sb.append("  \"urgencyCd\": \"(긴급도 코드)\",\n");
        sb.append("  \"importanceCd\": \"(중요도 코드)\",\n");
        sb.append("  \"workCategoryCd\": \"(업무영역 코드)\",\n");
        sb.append("  \"dueDt\": \"YYYY-MM-DD HH:mm 또는 null\",\n");
        sb.append("  \"summary\": \"메일 내용 1~2문장 요약\",\n");
        sb.append("  \"replyExpectedYn\": \"Y 또는 N\",\n");
        sb.append("  \"expectedReplyDueDt\": \"YYYY-MM-DD HH:mm:ss 또는 null\"\n");
        sb.append("}\n\n");
        sb.append("코드 기준:\n");
        sb.append("메일목적(mailPurposeCd): 001=업무요청, 002=보고, 003=문의, 004=일정, 005=정보공유\n");
        sb.append("필요조치(actionRequiredCd): 001=To-Do, 002=회신, 003=승인, 004=일정등록, 005=조치없음\n");
        sb.append("긴급도(urgencyCd): 001=긴급, 002=높음, 003=보통, 004=낮음\n");
        sb.append("중요도(importanceCd): 001=핵심, 002=중요, 003=일반, 004=낮음\n");
        sb.append("업무영역(workCategoryCd) - 아래 목록에서 가장 적합한 코드 선택:\n");
        sb.append(categoryList);
        sb.append("\n기한(dueDt): 메일 본문에서 명시적 기한이 있으면 추출, 없으면 null\n");
        if (isSent) {
            sb.append("회신기대여부(replyExpectedYn): 이 발신 메일이 수신자의 회신을 기대하는 내용이면 Y, 단순 공유/안내/FYI면 N\n");
            sb.append("예상 회신기한(expectedReplyDueDt): 메일 본문에 명시된 회신 요청 기한이 있으면 추출, 없으면 null\n");
        } else {
            sb.append("회신기대여부(replyExpectedYn): N (받은 메일이므로 항상 N)\n");
            sb.append("예상 회신기한(expectedReplyDueDt): null\n");
        }
        sb.append("\n이메일:\n");
        sb.append("제목: ").append(msg.getSubject()).append("\n");
        sb.append("발신자: ").append(msg.getFromName()).append(" <").append(msg.getFromAddr()).append(">\n");
        sb.append("날짜: ").append(msg.getMailDt() != null ? sdf.format(msg.getMailDt()) : "").append("\n");
        if (!isBlank(msg.getBodyText())) {
            String preview = msg.getBodyText().length() > 800
                ? msg.getBodyText().substring(0, 800) + "..."
                : msg.getBodyText();
            sb.append("본문:\n").append(preview).append("\n");
        }
        return sb.toString();
    }

    // AI 분류 응답 파싱
    @SuppressWarnings("unchecked")
    private MailVO.MailAiAnalysisVO parseClassificationResponse(String aiResponse) {
        MailVO.MailAiAnalysisVO vo = new MailVO.MailAiAnalysisVO();
        try {
            String jsonStr = aiResponse;
            int start = aiResponse.indexOf('{');
            int end   = aiResponse.lastIndexOf('}');
            if (start >= 0 && end > start) jsonStr = aiResponse.substring(start, end + 1);

            JSONParser parser = new JSONParser();
            JSONObject parsed = (JSONObject) parser.parse(jsonStr);

            vo.setMailPurposeCd(toStr(parsed.get("mailPurposeCd")));
            vo.setActionRequiredCd(toStr(parsed.get("actionRequiredCd")));
            vo.setUrgencyCd(toStr(parsed.get("urgencyCd")));
            vo.setImportanceCd(toStr(parsed.get("importanceCd")));
            vo.setWorkCategoryCd(toStr(parsed.get("workCategoryCd")));
            vo.setSummary(toStr(parsed.get("summary")));

            // 기한 파싱
            String dueDtStr = toStr(parsed.get("dueDt"));
            if (!isBlank(dueDtStr) && !"null".equals(dueDtStr)) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                    vo.setDueDt(sdf.parse(dueDtStr));
                } catch (Exception e) {
                    log.debug("기한 날짜 파싱 실패: {}", dueDtStr);
                }
            }

            // 회신기대여부 (기본값 N)
            String replyExpectedYn = toStr(parsed.get("replyExpectedYn"));
            vo.setReplyExpectedYn("Y".equalsIgnoreCase(replyExpectedYn) ? "Y" : "N");

            // 예상 회신기한 파싱
            String expectedReplyDueDtStr = toStr(parsed.get("expectedReplyDueDt"));
            if (!isBlank(expectedReplyDueDtStr) && !"null".equals(expectedReplyDueDtStr)) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    vo.setExpectedReplyDueDt(sdf.parse(expectedReplyDueDtStr));
                } catch (Exception e1) {
                    try {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                        vo.setExpectedReplyDueDt(sdf.parse(expectedReplyDueDtStr));
                    } catch (Exception e2) {
                        log.debug("예상 회신기한 날짜 파싱 실패: {}", expectedReplyDueDtStr);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("AI 분류 응답 파싱 실패: {}", e.getMessage());
        }
        return vo;
    }

    // ─── D. 회신메일 자동 매칭 팔로업 ───────────────────────

    /**
     * INBOX의 IN_REPLY_TO 헤더 기반으로 TB_MAIL_FOLLOWUP 자동 매칭
     * 회신메일 자동 매칭 팔로업
     */
    public void matchFollowupReplies(String accountId) throws Exception {
        List<MailVO.MailFollowupVO> waitingList = mailDAO.selectWaitingFollowupList(accountId);
        if (waitingList.isEmpty()) return;

        List<MailVO.MailMsgVO> inboxWithReply = mailDAO.selectInboxMsgWithInReplyTo(accountId);
        if (inboxWithReply.isEmpty()) return;

        for (MailVO.MailMsgVO inbox : inboxWithReply) {
            String inReplyTo = inbox.getInReplyTo();
            if (isBlank(inReplyTo)) continue;

            for (MailVO.MailFollowupVO fup : waitingList) {
                boolean matched = false;

                // 1순위: IN_REPLY_TO ↔ MSG_ID_HEADER 정밀 매칭
                if (!isBlank(fup.getMsgIdHeader())
                        && inReplyTo.equalsIgnoreCase(fup.getMsgIdHeader())) {
                    matched = true;
                }
                // 2순위: 발신자 이메일 fallback (MSG_ID_HEADER 없거나 불일치 시)
                else if (!isBlank(fup.getRecipientAddr())
                        && fup.getRecipientAddr().equalsIgnoreCase(inbox.getFromAddr())) {
                    matched = true;
                }

                if (matched) {
                    Map<String, Object> matchParam = new HashMap<>();
                    matchParam.put("followupId",    fup.getFollowupId());
                    matchParam.put("repliedMailId", inbox.getMailId());
                    mailDAO.updateFollowupMatched(matchParam);
                    log.info("팔로업 자동 매칭 (followupId={}, repliedMailId={})",
                        fup.getFollowupId(), inbox.getMailId());
                    break;
                }
            }
        }
    }

    // ─── E. KPI 조회 ───────────────────────────────────────

    public HashMap<String, Object> getMailKpi(String accountId, String startDate, String endDate) throws Exception {
        MailVO.MailListParamVO param = new MailVO.MailListParamVO();
        param.setAccountId(accountId);
        param.setStartDate(startDate);
        param.setEndDate(endDate);
        MailVO.MailKpiVO kpi = mailDAO.selectMailKpi(param);
        HashMap<String, Object> result = new HashMap<>();
        if (kpi != null) {
            result.put("totalCount",         kpi.getTotalCount());
            result.put("replyRequiredCount", kpi.getReplyRequiredCount());
            result.put("urgentCount",        kpi.getUrgentCount());
            result.put("todayDueCount",      kpi.getTodayDueCount());
        } else {
            result.put("totalCount",         0);
            result.put("replyRequiredCount", 0);
            result.put("urgentCount",        0);
            result.put("todayDueCount",      0);
        }
        // INBOX 폴더의 UID_VALIDITY (그룹웨어 boxnameSeq에 해당)
        java.util.Map<String, Object> syncParam = new HashMap<>();
        syncParam.put("accountId", accountId);
        syncParam.put("folderCd", "INBOX");
        MailVO.MailSyncStateVO syncState = mailDAO.selectMailSyncState(syncParam);
        result.put("inboxUidValidity", syncState != null ? syncState.getUidValidity() : null);
        return result;
    }

    // ─── F. 분류된 메일함 목록 ─────────────────────────────

    public HashMap<String, Object> getInboxClassified(MailVO.MailListParamVO param) throws Exception {
        // pageNum은 1-based (프론트에서 1부터 전송), SQL OFFSET은 0-based이므로 변환
        param.setPageNum((param.getPageNum() - 1) * param.getPageSize());
        List<MailVO.ClassifiedMailVO> list = mailDAO.selectClassifiedMailList(param);
        int totalCount = mailDAO.selectClassifiedMailCount(param);

        // 탭별 건수 (날짜 범위 동일하게 적용)
        List<Map<String, Object>> tabCounts = mailDAO.selectTabCounts(param);
        Map<String, Integer> tabCountMap = new HashMap<>();
        for (Map<String, Object> row : tabCounts) {
            String tabType = (String) row.get("tabType");
            Object cnt     = row.get("cnt");
            int    cntInt  = cnt instanceof Number ? ((Number) cnt).intValue() : 0;
            tabCountMap.put(tabType, cntInt);
        }

        HashMap<String, Object> result = new HashMap<>();
        result.put("list",       list);
        result.put("totalCount", totalCount);
        result.put("tabCounts",  tabCountMap);
        return result;
    }

    // ─── F-2. 분류된 받은메일함 AI 요약 ───────────────────────

    public HashMap<String, Object> getInboxSummary(MailVO.MailListParamVO param) throws Exception {
        // 요약 대상: 최대 20건 (AI 컨텍스트 길이 제한)
        param.setPageNum(0);
        param.setPageSize(20);

        List<MailVO.ClassifiedMailVO> list = mailDAO.selectClassifiedMailList(param);

        HashMap<String, Object> result = new HashMap<>();
        if (list == null || list.isEmpty()) {
            result.put("summary", "");
            return result;
        }

        String prompt     = buildInboxSummaryPrompt(list);
        String aiResponse = chatbotService.callAiSummary(prompt, "mail_inbox_summary", null);

        if (isBlank(aiResponse)) throw new RuntimeException("AI_FAILED");

        result.put("summary", aiResponse.trim());
        return result;
    }

    private String buildInboxSummaryPrompt(List<MailVO.ClassifiedMailVO> mails) {
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm");
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 이메일 비서입니다.\n");
        sb.append("아래 메일 목록을 분석하여 사용자가 현황을 빠르게 파악할 수 있도록 한국어로 마크다운 형식으로 요약해주세요.\n\n");
        sb.append("출력 형식 (아래 구조만 사용, 절대 변형 금지):\n\n");
        sb.append("전체 흐름을 2~3문장으로 요약. 긴급하거나 중요한 내용은 **굵게** 표시.\n\n");
        sb.append("처리 사항이 있으면 아래처럼 라벨별로 묶어서 출력:\n\n");
        sb.append("**[긴급]**\n");
        sb.append("- 긴급 처리 항목\n\n");
        sb.append("**[회신 필요]**\n");
        sb.append("- 회신이 필요한 항목\n\n");
        sb.append("**[To-Do]**\n");
        sb.append("- 기한 내 처리가 필요한 항목\n\n");
        sb.append("규칙:\n");
        sb.append("- 해당 라벨에 속하는 항목이 없으면 그 섹션 전체 생략\n");
        sb.append("- 각 항목은 동사로 끝나는 한 문장. 마감일이 있으면 괄호로 표기\n");
        sb.append("- 처리 사항이 아예 없으면 요약 단락만 출력\n");
        sb.append("- 위 3가지 라벨 외 다른 라벨 사용 금지. 헤딩(#) 사용 금지. 코드블록 사용 금지\n\n");
        sb.append("메일 목록 (").append(mails.size()).append("건):\n");

        for (int i = 0; i < mails.size(); i++) {
            MailVO.ClassifiedMailVO m = mails.get(i);
            sb.append("\n[").append(i + 1).append("]\n");
            sb.append("  제목: ").append(m.getSubject() != null ? m.getSubject() : "(없음)").append("\n");
            sb.append("  발신: ").append(m.getFromName() != null && !m.getFromName().trim().isEmpty()
                    ? m.getFromName() : m.getFromAddr()).append("\n");
            if (m.getMailDt() != null) {
                sb.append("  날짜: ").append(sdf.format(m.getMailDt())).append("\n");
            }
            if (!isBlank(m.getUrgencyNm())) {
                sb.append("  긴급도: ").append(m.getUrgencyNm()).append("\n");
            }
            if (!isBlank(m.getActionRequiredNm())) {
                sb.append("  필요조치: ").append(m.getActionRequiredNm()).append("\n");
            }
            if (!isBlank(m.getSummary())) {
                sb.append("  AI요약: ").append(m.getSummary()).append("\n");
            }
        }
        return sb.toString();
    }

    // ─── G. 메일 상세 + AI 분석결과 ────────────────────────

    public HashMap<String, Object> getMailDetail(String mailId) throws Exception {
        MailVO.MailMsgVO msg = mailDAO.selectMailMsgById(mailId);
        if (msg == null) throw new IllegalArgumentException("MAIL_NOT_FOUND");

        MailVO.MailAiAnalysisVO analysis = mailDAO.selectMailAiAnalysis(mailId);

        HashMap<String, Object> result = new HashMap<>();
        result.put("mail",     msg);
        result.put("analysis", analysis);
        return result;
    }

    // ─── H. 회신 초안 생성 ─────────────────────────────────

    public HashMap<String, Object> createReplyDraft(String mailId) throws Exception {
        MailVO.MailMsgVO msg = mailDAO.selectMailMsgById(mailId);
        if (msg == null) throw new IllegalArgumentException("MAIL_NOT_FOUND");

        MailVO.MailAiAnalysisVO analysis = mailDAO.selectMailAiAnalysis(mailId);

        String prompt = buildReplyDraftPrompt(msg, analysis);
        String draft  = chatbotService.callAiSummary(prompt, "mail_reply_draft", null);

        if (isBlank(draft)) throw new RuntimeException("AI_FAILED");

        // TB_MAIL_REPLY_DRAFT 저장
        String draftId = keyGenerate.generateTableKey("DR", "TB_MAIL_REPLY_DRAFT", "DRAFT_ID");
        MailVO.MailReplyDraftVO draftVO = new MailVO.MailReplyDraftVO();
        draftVO.setDraftId(draftId);
        draftVO.setMailId(mailId);
        draftVO.setDraftContent(draft);
        mailDAO.insertMailReplyDraft(draftVO);

        HashMap<String, Object> result = new HashMap<>();
        result.put("draftId",      draftId);
        result.put("draftContent", draft);
        return result;
    }

    private String buildReplyDraftPrompt(MailVO.MailMsgVO msg, MailVO.MailAiAnalysisVO analysis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 이메일 회신 작성을 도와주는 비서입니다.\n");
        sb.append("아래 이메일에 대한 자연스럽고 전문적인 한국어 회신 초안을 작성해주세요.\n\n");
        sb.append("원본 메일:\n");
        sb.append("제목: ").append(msg.getSubject()).append("\n");
        sb.append("발신자: ").append(msg.getFromName()).append(" <").append(msg.getFromAddr()).append(">\n");
        sb.append("날짜: ").append(msg.getMailDt() != null ? sdf.format(msg.getMailDt()) : "").append("\n");
        if (analysis != null && !isBlank(analysis.getSummary())) {
            sb.append("AI 요약: ").append(analysis.getSummary()).append("\n");
        }
        if (!isBlank(msg.getBodyText())) {
            String preview = msg.getBodyText().length() > 600
                ? msg.getBodyText().substring(0, 600) + "..."
                : msg.getBodyText();
            sb.append("본문:\n").append(preview).append("\n");
        }
        sb.append("\n요청사항:\n");
        sb.append("- 정중하고 전문적인 어투로 회신 초안 작성\n");
        sb.append("- 제목(Re: 형태)과 본문 구분\n");
        sb.append("- 수신자: ").append(msg.getFromName()).append("\n");
        sb.append("형식:\n제목: [제목]\n\n[본문]");
        return sb.toString();
    }

    // ─── I. 액션 완료 토글 ──────────────────────────────────

    public void toggleActionComplete(String mailId, String currentYn) throws Exception {
        String newYn = "Y".equals(currentYn) ? "N" : "Y";
        Map<String, Object> param = new HashMap<>();
        param.put("mailId",           mailId);
        param.put("actionCompleteYn", newYn);
        mailDAO.updateActionComplete(param);
    }

    // ─── J. 팔로업 등록 ────────────────────────────────────

    public HashMap<String, Object> registerFollowup(String mailId, String recipientAddr, Date expectedReplyDt) throws Exception {
        return insertFollowupWithStatus(mailId, recipientAddr, expectedReplyDt, "001");
    }

    public HashMap<String, Object> dismissFollowup(String mailId) throws Exception {
        return insertFollowupWithStatus(mailId, null, null, "003");
    }

    private HashMap<String, Object> insertFollowupWithStatus(String mailId, String recipientAddr,
            Date expectedReplyDt, String statusCd) throws Exception {
        MailVO.MailMsgVO msg = mailDAO.selectMailMsgById(mailId);
        if (msg == null) throw new IllegalArgumentException("MAIL_NOT_FOUND");

        String followupId = keyGenerate.generateTableKey("FU", "TB_MAIL_FOLLOWUP", "FOLLOWUP_ID");
        MailVO.MailFollowupVO vo = new MailVO.MailFollowupVO();
        vo.setFollowupId(followupId);
        vo.setSentMailId(mailId);
        vo.setRecipientAddr(!isBlank(recipientAddr) ? recipientAddr : msg.getFromAddr());
        vo.setExpectedReplyDt(expectedReplyDt);
        vo.setStatusCd(statusCd);
        mailDAO.insertMailFollowup(vo);

        HashMap<String, Object> result = new HashMap<>();
        result.put("followupId", followupId);
        return result;
    }

    // ─── K. 팔로업 목록 조회 ───────────────────────────────

    public HashMap<String, Object> getFollowupList(String accountId) throws Exception {
        List<MailVO.MailFollowupVO> list = mailDAO.selectMailFollowupList(accountId);
        HashMap<String, Object> result = new HashMap<>();
        result.put("list", list);
        return result;
    }

    // ─── L. 팔로업 상태 변경 ───────────────────────────────

    public void updateFollowupStatus(String followupId, String statusCd) throws Exception {
        Map<String, Object> param = new HashMap<>();
        param.put("followupId", followupId);
        param.put("statusCd",   statusCd);
        mailDAO.updateMailFollowupStatus(param);
    }

    // ─── L-2. 팔로업 취소(삭제) ────────────────────────────

    public void cancelFollowup(String followupId) throws Exception {
        mailDAO.deleteMailFollowup(followupId);
    }

    // ─── M. 보낸메일함 분류 목록 조회 (LLM 기반) ───────────────

    public HashMap<String, Object> getSentClassified(MailVO.SentListParamVO param) throws Exception {
        // pageNum은 1-based → 0-based OFFSET 변환
        param.setPageNum((param.getPageNum() - 1) * param.getPageSize());
        List<MailVO.SentClassifiedItemVO> list = mailDAO.selectSentClassifiedList(param);

        // toAddrRaw 파싱 → toName / toAddr 채우기
        for (MailVO.SentClassifiedItemVO item : list) {
            parseToAddr(item);
        }

        // 건수/탭카운트용 파라미터 (페이징 무관)
        MailVO.SentListParamVO countParam = new MailVO.SentListParamVO();
        countParam.setAccountId(param.getAccountId());
        countParam.setTabType(param.getTabType());
        countParam.setStartDate(param.getStartDate());
        countParam.setEndDate(param.getEndDate());
        countParam.setPageNum(0);
        countParam.setPageSize(Integer.MAX_VALUE);
        int totalCount = mailDAO.selectSentClassifiedCount(countParam);

        // 탭별 건수
        List<Map<String, Object>> tabCountRows = mailDAO.selectSentTabCounts(countParam);
        Map<String, Integer> tabCounts = new HashMap<>();
        for (Map<String, Object> row : tabCountRows) {
            String tabType = (String) row.get("tabType");
            Object cnt     = row.get("cnt");
            tabCounts.put(tabType, cnt instanceof Number ? ((Number) cnt).intValue() : 0);
        }

        HashMap<String, Object> result = new HashMap<>();
        result.put("list",       list);
        result.put("totalCount", totalCount);
        result.put("tabCounts",  tabCounts);
        return result;
    }

    // ─── N. 답장 대기 많은 상대 조회 ────────────────────────────

    public HashMap<String, Object> getTopPendingRecipients(MailVO.SentListParamVO param) throws Exception {
        List<MailVO.TopPendingRecipientVO> list = mailDAO.selectTopPendingRecipients(param);
        for (MailVO.TopPendingRecipientVO item : list) {
            String raw = item.getToAddrRaw();
            item.setToName(extractPersonalNameFromRaw(raw));
            item.setToAddr(extractEmailFromRaw(raw));
        }
        HashMap<String, Object> result = new HashMap<>();
        result.put("list", list);
        return result;
    }

    // ─── O. 이번 주 / 전주 회신 통계 ────────────────────────────

    public HashMap<String, Object> getSentWeeklyStats(String accountId) throws Exception {
        Map<String, Object> row = mailDAO.selectSentWeeklyStats(accountId);
        HashMap<String, Object> result = new HashMap<>();
        if (row == null) {
            result.put("avgReplyDays",     0.0);
            result.put("prevAvgReplyDays", 0.0);
            result.put("replyRate",        0);
            result.put("prevReplyRate",    0);
            result.put("pendingCount",     0);
            result.put("doneCount",        0);
            return result;
        }

        int thisWeekDone     = toInt(row.get("thisWeekDone"));
        int thisWeekExpected = toInt(row.get("thisWeekExpected"));
        int prevWeekDone     = toInt(row.get("prevWeekDone"));
        int prevWeekExpected = toInt(row.get("prevWeekExpected"));

        double thisAvg = toDouble(row.get("thisWeekAvgDays"));
        double prevAvg = toDouble(row.get("prevWeekAvgDays"));

        int thisRate = thisWeekExpected > 0 ? (int) Math.round(thisWeekDone * 100.0 / thisWeekExpected) : 0;
        int prevRate = prevWeekExpected > 0 ? (int) Math.round(prevWeekDone * 100.0 / prevWeekExpected) : 0;

        result.put("avgReplyDays",     Math.round(thisAvg * 10.0) / 10.0);
        result.put("prevAvgReplyDays", Math.round(prevAvg * 10.0) / 10.0);
        result.put("replyRate",        thisRate);
        result.put("prevReplyRate",    prevRate);
        result.put("pendingCount",     thisWeekExpected - thisWeekDone);
        result.put("doneCount",        thisWeekDone);
        return result;
    }

    // ─── 내부: 수신자 주소 파싱 ──────────────────────────────────

    private void parseToAddr(MailVO.SentClassifiedItemVO item) {
        String raw = item.getToAddrRaw();
        item.setToName(extractPersonalNameFromRaw(raw));
        item.setToAddr(extractEmailFromRaw(raw));
    }

    private String extractPersonalNameFromRaw(String raw) {
        if (isBlank(raw)) return "";
        int ltIdx = raw.indexOf('<');
        if (ltIdx > 0) return raw.substring(0, ltIdx).trim().replaceAll("^\"|\"$", "");
        if (raw.contains("@")) return raw.trim();
        return raw.trim();
    }

    private String extractEmailFromRaw(String raw) {
        return extractEmail(raw);
    }

    private int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return 0; }
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return 0.0; }
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
