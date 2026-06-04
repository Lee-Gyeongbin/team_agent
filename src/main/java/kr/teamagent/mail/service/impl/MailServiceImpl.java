package kr.teamagent.mail.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.mail.service.MailDto;
import kr.teamagent.mail.service.MailService;

@Service
public class MailServiceImpl implements MailService {

    private static final Logger log = LoggerFactory.getLogger(MailServiceImpl.class);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s{2,}");

    @Override
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
    @Override
    public List<MailDto> getRecentMails(String email, String password, Date startDate, Date endExclusive) throws Exception {
        String host = PropertyUtil.getProperty("Globals.mail.imap.host");
        int    port = Integer.parseInt(PropertyUtil.getProperty("Globals.mail.imap.port"));
    
        Session session = Session.getInstance(buildImapProperties(host, port));
    
        Store store = null;
        Folder inbox = null;
        List<MailDto> result = new ArrayList<>();
    
        try {
            store = session.getStore("imaps");
            store.connect(host, port, email, password);
    
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
    
            int total = inbox.getMessageCount();
            if (total == 0) {
                return result;
            }
    
            for (int messageNumber = total; messageNumber >= 1; messageNumber--) {
                Message msg = inbox.getMessage(messageNumber);
                try {
                    Date receivedDate = safeReceivedDate(msg);
                    Date msgDate = receivedDate != null ? receivedDate : safeSentDate(msg);
                    if (msgDate == null) {
                        continue;
                    }
    
                    // startDate 이전 메일 나오면 조기 중단
                    if (receivedDate != null && receivedDate.before(startDate)) {
                        break;
                    }
    
                    if (msgDate.before(startDate)) {
                        continue;
                    }
                    if (!msgDate.before(endExclusive)) {
                        continue;
                    }
    
                    result.add(toDto(msg));
                } catch (Exception e) {
                    log.warn("메일 파싱 오류 (messageNumber={}): {}", msg.getMessageNumber(), e.getMessage());
                }
            }
        } finally {
            closeQuietly(inbox, store);
        }
        return result;
    }

    // ─── 내부 헬퍼 ───────────────────────────────────────────

    private Properties buildImapProperties(String host, int port) {
        Properties props = new Properties();
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        props.put("mail.imaps.socketFactory.fallback", "false");
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "10000");
        // Java 11+ TLS 1.3의 PSK(Pre-Shared Key) 세션 재개 문제 방지 → TLS 1.2 강제
        props.put("mail.imaps.ssl.protocols", "TLSv1.2");
        return props;
    }

    private MailDto toDto(Message msg) throws MessagingException, IOException {
        MailDto dto = new MailDto();

        // 제목
        String rawSubject = msg.getSubject();
        dto.setSubject(rawSubject != null ? decodeText(rawSubject) : "(제목 없음)");

        // 발신자
        javax.mail.Address[] froms = msg.getFrom();
        if (froms != null && froms.length > 0) {
            String decoded = decodeText(froms[0].toString());
            dto.setFrom(decoded);
            dto.setFromName(extractFromName(froms[0], decoded));
        } else {
            dto.setFrom("");
            dto.setFromName("");
        }

        // 수신 일시
        dto.setReceivedDate(msg.getReceivedDate());

        // 읽음 여부
        dto.setRead(msg.isSet(Flags.Flag.SEEN));

        // 본문
        dto.setBody(extractBody(msg));

        return dto;
    }

    /** 발신자 주소에서 표시 이름 추출 */
    private String extractFromName(javax.mail.Address address, String decoded) {
        try {
            if (address instanceof InternetAddress) {
                InternetAddress ia = (InternetAddress) address;
                String personal = ia.getPersonal();
                if (personal != null && !personal.isEmpty()) {
                    return decodeText(personal);
                }
                return ia.getAddress();
            }
        } catch (Exception ignored) {}
        // fallback: <email@domain.com> 제거하고 이름만
        return decoded.replaceAll("<[^>]+>", "").trim();
    }

    /**
     * 파트에서 본문 추출. text/plain 우선, 없으면 text/html 태그 제거.
     */
    private String extractBody(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        }

        if (part.isMimeType("text/html")) {
            return stripHtml((String) part.getContent());
        }

        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            String textBody = null;
            String htmlBody = null;

            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);

                // 첨부파일 건너뜀
                String disposition = bp.getDisposition();
                if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
                    continue;
                }

                if (bp.isMimeType("text/plain") && textBody == null) {
                    textBody = (String) bp.getContent();
                } else if (bp.isMimeType("text/html") && htmlBody == null) {
                    htmlBody = stripHtml((String) bp.getContent());
                } else if (bp.isMimeType("multipart/*")) {
                    String nested = extractBody(bp);
                    if (nested != null && !nested.isEmpty() && textBody == null) {
                        textBody = nested;
                    }
                }
            }

            if (textBody != null) return textBody;
            if (htmlBody  != null) return htmlBody;
        }

        return "";
    }

    /** HTML 태그 제거 후 공백 정규화 */
    private String stripHtml(String html) {
        if (html == null) return "";
        String stripped = HTML_TAG.matcher(html).replaceAll(" ");
        stripped = WHITESPACE.matcher(stripped).replaceAll(" ");
        return stripped.trim();
    }

    private String decodeText(String text) {
        try {
            return MimeUtility.decodeText(text);
        } catch (Exception e) {
            return text;
        }
    }

    private Date safeReceivedDate(Message message) {
        try {
            return message.getReceivedDate();
        } catch (MessagingException e) {
            return null;
        }
    }

    private Date safeSentDate(Message message) {
        try {
            return message.getSentDate();
        } catch (MessagingException e) {
            return null;
        }
    }

    private void closeStore(Store store) {
        try {
            if (store != null && store.isConnected()) store.close();
        } catch (Exception ignored) {}
    }

    private void closeQuietly(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) folder.close(false);
        } catch (Exception ignored) {}
        closeStore(store);
    }
}
