package kr.teamagent.mail.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.mail.service.impl.MailServiceImpl;

@Controller
@RequestMapping("/mail")
public class MailController extends BaseController<Object> {

    private static final String SESSION_KEY_EMAIL      = "mail.email";
    private static final String SESSION_KEY_PASSWORD   = "mail.password";
    private static final String SESSION_KEY_START_DATE = "mail.startDate";
    private static final String SESSION_KEY_END_DATE   = "mail.endDate";

    @Autowired
    private MailServiceImpl mailService;

    // ─── 0. 인증 상태 확인 ───────────────────────────────────

    /**
     * GET /mail/auth-check.do
     * 서버 세션에 메일 자격증명이 저장되어 있으면 SUCCESS, 없으면 FAIL 반환.
     * 로그인 모달 표시 여부 판단용 — IMAP 재연결 없이 세션만 확인.
     */
    @RequestMapping(value = "/auth-check.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView authCheck(HttpServletRequest request) {
        String email    = getMailSession(request, SESSION_KEY_EMAIL);
        String password = getMailSession(request, SESSION_KEY_PASSWORD);
        if (email != null && password != null) {
            return makeSuccessJsonData();
        }
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("message", "인증 정보가 없습니다.");
        return makeFailJsonData(resultMap);
    }

    // ─── 1. IMAP 로그인 인증 ─────────────────────────────────

    /**
     * POST /mail/auth.do
     * Body: { email, password }
     */
    @RequestMapping(value = "/auth.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView auth(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String email    = body.get("email");
        String password = body.get("password");

        if (isBlank(email) || isBlank(password)) {
            return makeFailJsonData("이메일과 비밀번호를 입력해주세요.");
        }

        boolean ok = mailService.authImap(email.trim(), password);
        if (!ok) {
            return makeFailJsonData("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_KEY_EMAIL,    email.trim());
        session.setAttribute(SESSION_KEY_PASSWORD, password);

        return makeSuccessJsonData();
    }

    // ─── 2. 받은 메일 목록 조회 ───────────────────────────────

    /**
     * GET /mail/list.do?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
     */
    @RequestMapping(value = "/list.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView list(@RequestParam(value = "startDate", required = false) String startDate,
                             @RequestParam(value = "endDate",   required = false) String endDate,
                             HttpServletRequest request, HttpServletResponse response) {
        try {
            String email    = getMailSession(request, SESSION_KEY_EMAIL);
            String password = getMailSession(request, SESSION_KEY_PASSWORD);
            if (email == null || password == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다. 로그인 후 다시 시도해주세요.", "MAIL_AUTH_REQUIRED");
            }

            HashMap<String, Object> result = mailService.getInboxResult(email, password, startDate, endDate);
            saveDateRangeToSession(request, result);
            return makeSuccessJsonData(result);

        } catch (IllegalArgumentException e) {
            return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                "조회 기간이 올바르지 않습니다. 시작일과 종료일을 확인해 주세요.", "MAIL_INVALID_DATE_RANGE");
        } catch (Exception e) {
            log.error("받은 메일 목록 조회 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "메일 조회 중 오류가 발생했습니다.", "MAIL_LIST_FAILED");
        }
    }

    // ─── 3. 보낸 메일 목록 조회 ─────────────────────────────

    /**
     * GET /mail/sent.do?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
     */
    @RequestMapping(value = "/sent.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView sent(@RequestParam(value = "startDate", required = false) String startDate,
                             @RequestParam(value = "endDate",   required = false) String endDate,
                             HttpServletRequest request, HttpServletResponse response) {
        try {
            String email    = getMailSession(request, SESSION_KEY_EMAIL);
            String password = getMailSession(request, SESSION_KEY_PASSWORD);
            if (email == null || password == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다. 로그인 후 다시 시도해주세요.", "MAIL_AUTH_REQUIRED");
            }

            HashMap<String, Object> result = mailService.getSentResult(email, password, startDate, endDate);
            return makeSuccessJsonData(result);

        } catch (IllegalArgumentException e) {
            return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                "조회 기간이 올바르지 않습니다. 시작일과 종료일을 확인해 주세요.", "MAIL_INVALID_DATE_RANGE");
        } catch (Exception e) {
            log.error("보낸 메일 목록 조회 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "보낸 메일 조회 중 오류가 발생했습니다.", "MAIL_SENT_LIST_FAILED");
        }
    }

    // ─── 4. 메일 AI 요약 ─────────────────────────────────────

    /**
     * POST /mail/summary.do
     * Body: { startDate?, endDate? }  — 없으면 세션 날짜 사용
     */
    @RequestMapping(value = "/summary.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView summary(@RequestBody(required = false) Map<String, String> body,
                                HttpServletRequest request, HttpServletResponse response) {
        try {
            String email    = getMailSession(request, SESSION_KEY_EMAIL);
            String password = getMailSession(request, SESSION_KEY_PASSWORD);
            if (email == null || password == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다. 로그인 후 다시 시도해주세요.", "MAIL_AUTH_REQUIRED");
            }

            // 날짜가 없으면 세션 날짜 사용
            String startDate = body != null ? body.get("startDate") : null;
            String endDate   = body != null ? body.get("endDate")   : null;
            if (isBlank(startDate) || isBlank(endDate)) {
                startDate = getMailSession(request, SESSION_KEY_START_DATE);
                endDate   = getMailSession(request, SESSION_KEY_END_DATE);
            }

            HashMap<String, Object> result = mailService.getSummaryResult(email, password, startDate, endDate);
            return makeSuccessJsonData(result);

        } catch (IllegalArgumentException e) {
            return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                "조회 기간이 올바르지 않습니다. 시작일과 종료일을 확인해 주세요.", "MAIL_INVALID_DATE_RANGE");
        } catch (RuntimeException e) {
            if ("AI_FAILED".equals(e.getMessage())) {
                return failResponse(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "AI 요약 생성에 실패했습니다.", "MAIL_SUMMARY_AI_FAILED");
            }
            log.error("메일 AI 요약 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "AI 요약 처리 중 오류가 발생했습니다.", "MAIL_SUMMARY_FAILED");
        } catch (Exception e) {
            log.error("메일 AI 요약 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "AI 요약 처리 중 오류가 발생했습니다.", "MAIL_SUMMARY_FAILED");
        }
    }

    // ─── 5. 메일 컨텍스트 AI 채팅 ────────────────────────────

    /**
     * POST /mail/chat.do
     * Body: { message, mailContext, chatHistory: [{role, content}] }
     */
    @SuppressWarnings("unchecked")
    @RequestMapping(value = "/chat.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView chat(@RequestBody(required = false) Map<String, Object> body,
                             HttpServletRequest request, HttpServletResponse response) {
        try {
            String email    = getMailSession(request, SESSION_KEY_EMAIL);
            String password = getMailSession(request, SESSION_KEY_PASSWORD);
            if (email == null || password == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다. 로그인 후 다시 시도해주세요.", "MAIL_AUTH_REQUIRED");
            }

            Map<String, Object> requestBody = body != null ? body : new HashMap<>();
            String message     = toStr(requestBody.get("message"));
            String mailContext = toStr(requestBody.get("mailContext"));

            if (isBlank(message)) {
                return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    "message는 필수입니다.", "MAIL_CHAT_INVALID_REQUEST");
            }

            List<Map<String, Object>> chatHistory = new ArrayList<>();
            Object rawHistory = requestBody.get("chatHistory");
            if (rawHistory instanceof List) {
                for (Object item : (List<?>) rawHistory) {
                    if (item instanceof Map) chatHistory.add((Map<String, Object>) item);
                }
            }

            String answer = mailService.getChatResult(message, mailContext, chatHistory);
            HashMap<String, Object> result = new HashMap<>();
            result.put("answer", answer);
            return makeSuccessJsonData(result);

        } catch (RuntimeException e) {
            if ("AI_FAILED".equals(e.getMessage())) {
                return failResponse(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "메일 AI 채팅 응답 생성에 실패했습니다.", "MAIL_CHAT_AI_FAILED");
            }
            log.error("메일 AI 채팅 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "메일 AI 채팅 처리 중 오류가 발생했습니다.", "MAIL_CHAT_FAILED");
        } catch (Exception e) {
            log.error("메일 AI 채팅 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "메일 AI 채팅 처리 중 오류가 발생했습니다.", "MAIL_CHAT_FAILED");
        }
    }

    // ─── 6. 보낸 메일 목록 조회 (팔로업 트래커용) ──────────────────

    /**
     * GET /mail/sent-list.do?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
     */
    @RequestMapping(value = "/sent-list.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView sentList(@RequestParam(value = "startDate", required = false) String startDate,
                                 @RequestParam(value = "endDate",   required = false) String endDate,
                                 HttpServletRequest request, HttpServletResponse response) {
        try {
            String email    = getMailSession(request, SESSION_KEY_EMAIL);
            String password = getMailSession(request, SESSION_KEY_PASSWORD);
            if (email == null || password == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다. 로그인 후 다시 시도해주세요.", "MAIL_AUTH_REQUIRED");
            }

            HashMap<String, Object> result = mailService.getSentListResult(email, password, startDate, endDate);
            return makeSuccessJsonData(result);

        } catch (IllegalArgumentException e) {
            return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                "조회 기간이 올바르지 않습니다. 시작일과 종료일을 확인해 주세요.", "MAIL_INVALID_DATE_RANGE");
        } catch (Exception e) {
            log.error("보낸 메일 목록(팔로업) 조회 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "보낸 메일 조회 중 오류가 발생했습니다.", "MAIL_SENT_LIST_FAILED");
        }
    }

    // ─── 7. 팔로업 상태 조회 ─────────────────────────────────────────

    /**
     * GET /mail/followup-status.do?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
     */
    @RequestMapping(value = "/followup-status.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView followupStatus(@RequestParam(value = "startDate", required = false) String startDate,
                                       @RequestParam(value = "endDate",   required = false) String endDate,
                                       HttpServletRequest request, HttpServletResponse response) {
        try {
            String email    = getMailSession(request, SESSION_KEY_EMAIL);
            String password = getMailSession(request, SESSION_KEY_PASSWORD);
            if (email == null || password == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다. 로그인 후 다시 시도해주세요.", "MAIL_AUTH_REQUIRED");
            }

            HashMap<String, Object> result = mailService.getFollowupStatus(email, password, startDate, endDate);
            return makeSuccessJsonData(result);

        } catch (IllegalArgumentException e) {
            return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                "조회 기간이 올바르지 않습니다. 시작일과 종료일을 확인해 주세요.", "MAIL_INVALID_DATE_RANGE");
        } catch (Exception e) {
            log.error("팔로업 상태 조회 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "팔로업 상태 조회 중 오류가 발생했습니다.", "MAIL_FOLLOWUP_STATUS_FAILED");
        }
    }

    // ─── 8. AI 독촉 메일 초안 생성 ───────────────────────────────────

    /**
     * POST /mail/followup-draft.do
     * Body: { to, subject, originalDate }
     */
    @RequestMapping(value = "/followup-draft.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView followupDraft(@RequestBody Map<String, String> body,
                                      HttpServletRequest request, HttpServletResponse response) {
        try {
            String email    = getMailSession(request, SESSION_KEY_EMAIL);
            String password = getMailSession(request, SESSION_KEY_PASSWORD);
            if (email == null || password == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다. 로그인 후 다시 시도해주세요.", "MAIL_AUTH_REQUIRED");
            }

            String to           = body.get("to");
            String subject      = body.get("subject");
            String originalDate = body.get("originalDate");

            HashMap<String, Object> result = mailService.getFollowupDraft(to, subject, originalDate);
            return makeSuccessJsonData(result);

        } catch (RuntimeException e) {
            if ("AI_FAILED".equals(e.getMessage())) {
                return failResponse(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "독촉 메일 초안 생성에 실패했습니다.", "MAIL_FOLLOWUP_DRAFT_AI_FAILED");
            }
            log.error("독촉 메일 초안 생성 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "독촉 메일 초안 생성 중 오류가 발생했습니다.", "MAIL_FOLLOWUP_DRAFT_FAILED");
        } catch (Exception e) {
            log.error("독촉 메일 초안 생성 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "독촉 메일 초안 생성 중 오류가 발생했습니다.", "MAIL_FOLLOWUP_DRAFT_FAILED");
        }
    }

    // ─── 내부 헬퍼 ───────────────────────────────────────────

    private String getMailSession(HttpServletRequest request, String key) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object val = session.getAttribute(key);
        return val != null ? val.toString() : null;
    }

    /** 서비스에서 반환한 resolvedStartDate/resolvedEndDate를 세션에 저장하고 맵에서 제거한다. */
    private void saveDateRangeToSession(HttpServletRequest request, HashMap<String, Object> result) {
        String startDate = (String) result.remove("resolvedStartDate");
        String endDate   = (String) result.remove("resolvedEndDate");
        if (startDate != null && endDate != null) {
            HttpSession session = request.getSession(true);
            session.setAttribute(SESSION_KEY_START_DATE, startDate);
            session.setAttribute(SESSION_KEY_END_DATE,   endDate);
        }
    }

    private ModelAndView failResponse(HttpServletResponse response, int status, String message, String code) {
        response.setStatus(status);
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("message", message);
        resultMap.put("msg",     message);
        resultMap.put("code",    code);
        return makeFailJsonData(resultMap);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String toStr(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
