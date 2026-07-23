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

import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.web.BaseController;
import kr.teamagent.mail.service.MailVO;
import kr.teamagent.mail.service.impl.MailServiceImpl;

@Controller
@RequestMapping("/mail")
public class MailController extends BaseController<Object> {

    private static final String SESSION_KEY_EMAIL      = "mail.email";
    private static final String SESSION_KEY_PASSWORD   = "mail.password";
    private static final String SESSION_KEY_START_DATE = "mail.startDate";
    private static final String SESSION_KEY_END_DATE   = "mail.endDate";
    private static final String SESSION_KEY_ACCOUNT_ID = "mail.accountId";
    private static final String SESSION_KEY_USER_ID    = "mail.userId";

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

    // ─── 1. IMAP 로그인 인증 (수정: TB_MAIL_ACCOUNT 저장 추가) ──

    /**
     * POST /mail/auth.do
     * Body: { email, password, userId? }
     */
    @RequestMapping(value = "/auth.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView auth(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String email    = body.get("email");
        String password = body.get("password");
        // 시스템 로그인 세션에서 userId 획득 (프론트에서 별도 전송 불필요)
        String userId   = SessionUtil.getUserId();

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

        // TB_MAIL_ACCOUNT upsert (실패해도 로그인은 성공 처리)
        String accountId = null;
        try {
            if (!isBlank(userId)) {
                accountId = mailService.saveMailAccount(userId, email.trim(), password);
                session.setAttribute(SESSION_KEY_ACCOUNT_ID, accountId);
                session.setAttribute(SESSION_KEY_USER_ID,    userId);
            } else {
                log.warn("시스템 세션에 userId 없음 — TB_MAIL_ACCOUNT 저장 건너뜀");
            }
        } catch (Exception e) {
            log.warn("TB_MAIL_ACCOUNT 저장 실패 (계속 진행): {}", e.getMessage());
        }

        HashMap<String, Object> result = new HashMap<>();
        if (accountId != null) result.put("accountId", accountId);
        return makeSuccessJsonData(result);
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

    // ─── 9. IMAP 동기화 + AI 분류 ────────────────────────────────

    /**
     * POST /mail/sync.do
     * IMAP → TB_MAIL_MSG 증분 동기화 → AI 분류 → 팔로업 자동 매칭
     */
    @RequestMapping(value = "/sync.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView sync(HttpServletRequest request, HttpServletResponse response) {
        try {
            String email     = getMailSession(request, SESSION_KEY_EMAIL);
            String password  = getMailSession(request, SESSION_KEY_PASSWORD);
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);

            if (email == null || password == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다.", "MAIL_AUTH_REQUIRED");
            }
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    "계정 ID가 없습니다. 다시 로그인해주세요.", "MAIL_ACCOUNT_ID_MISSING");
            }

            // INBOX 동기화
            List<String> newInboxIds = mailService.syncMailMessages(accountId, email, password, "INBOX");
            // SENT 동기화
            List<String> newSentIds  = mailService.syncMailMessages(accountId, email, password, "SENT");

            // AI 분류 (INBOX + SENT 신규 메일)
            if (!newInboxIds.isEmpty()) {
                mailService.classifyMails(newInboxIds);
            }
            if (!newSentIds.isEmpty()) {
                mailService.classifyMails(newSentIds);
            }

            // 팔로업 자동 매칭
            mailService.matchFollowupReplies(accountId);

            HashMap<String, Object> result = new HashMap<>();
            result.put("newInboxCount", newInboxIds.size());
            result.put("newSentCount",  newSentIds.size());
            return makeSuccessJsonData(result);

        } catch (Exception e) {
            log.error("메일 동기화 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "메일 동기화 중 오류가 발생했습니다.", "MAIL_SYNC_FAILED");
        }
    }

    // ─── 9-1. 날짜 범위 동기화 ──────────────────────────────────

    /**
     * POST /mail/sync-range.do
     * Body: { startDate: "yyyy-MM-dd", endDate: "yyyy-MM-dd" }
     * 해당 날짜 범위의 IMAP 메일 중 DB에 없는 것만 동기화 + AI 분류 (LAST_SYNC_UID 갱신 없음)
     */
    @RequestMapping(value = "/sync-range.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView syncRange(@RequestBody Map<String, Object> body,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            String email     = getMailSession(request, SESSION_KEY_EMAIL);
            String password  = getMailSession(request, SESSION_KEY_PASSWORD);
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);

            if (email == null || password == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "메일 계정이 연결되어 있지 않습니다.", "MAIL_AUTH_REQUIRED");
            }
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    "계정 ID가 없습니다.", "MAIL_ACCOUNT_ID_MISSING");
            }

            String startDate = toStr(body.get("startDate"));
            String endDate   = toStr(body.get("endDate"));

            if (isBlank(startDate) || isBlank(endDate)) {
                return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    "날짜 범위가 필요합니다.", "MAIL_DATE_RANGE_REQUIRED");
            }

            List<String> newMailIds = mailService.syncMailMessagesByDateRange(
                accountId, email, password, startDate, endDate);

            if (!newMailIds.isEmpty()) {
                mailService.classifyMails(newMailIds);
            }

            HashMap<String, Object> result = new HashMap<>();
            result.put("newCount", newMailIds.size());
            return makeSuccessJsonData(result);

        } catch (Exception e) {
            log.error("날짜 범위 동기화 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "메일 동기화 중 오류가 발생했습니다.", "MAIL_SYNC_RANGE_FAILED");
        }
    }

    // ─── 10. KPI 집계 조회 ──────────────────────────────────────

    /**
     * GET /mail/kpi.do
     */
    @RequestMapping(value = "/kpi.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView kpi(HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }
            HashMap<String, Object> result = mailService.getMailKpi(accountId);
            return makeSuccessJsonData(result);
        } catch (Exception e) {
            log.error("KPI 조회 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "KPI 조회 중 오류가 발생했습니다.", "MAIL_KPI_FAILED");
        }
    }

    // ─── 11. 분류된 메일함 목록 조회 ───────────────────────────

    /**
     * GET /mail/inbox-classified.do
     *   ?tabType=all|action|reply
     *   &searchField=from|to|body
     *   &searchKeyword=...
     *   &purposeCds=001,002&actionCds=...&urgencyCds=...&importanceCds=...&categoryCds=...
     *   &pageNum=0&pageSize=20
     */
    @RequestMapping(value = "/inbox-classified.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView inboxClassified(
            @RequestParam(value = "tabType",       defaultValue = "all")  String tabType,
            @RequestParam(value = "searchField",   defaultValue = "from") String searchField,
            @RequestParam(value = "searchKeyword", required = false)      String searchKeyword,
            @RequestParam(value = "purposeCds",    required = false)      String purposeCdStr,
            @RequestParam(value = "actionCds",     required = false)      String actionCdStr,
            @RequestParam(value = "urgencyCds",    required = false)      String urgencyCdStr,
            @RequestParam(value = "importanceCds", required = false)      String importanceCdStr,
            @RequestParam(value = "categoryCds",   required = false)      String categoryCdStr,
            @RequestParam(value = "pageNum",       defaultValue = "0")    int pageNum,
            @RequestParam(value = "pageSize",      defaultValue = "50")   int pageSize,
            @RequestParam(value = "startDate",     required = false)      String startDate,
            @RequestParam(value = "endDate",       required = false)      String endDate,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }

            MailVO.MailListParamVO param = new MailVO.MailListParamVO();
            param.setAccountId(accountId);
            param.setTabType(tabType);
            param.setSearchField(searchField);
            param.setSearchKeyword(searchKeyword);
            param.setPageNum(pageNum);
            param.setPageSize(pageSize);
            param.setStartDate(startDate);
            param.setEndDate(endDate);
            if (!isBlank(purposeCdStr))    param.setPurposeCds(purposeCdStr.split(","));
            if (!isBlank(actionCdStr))     param.setActionCds(actionCdStr.split(","));
            if (!isBlank(urgencyCdStr))    param.setUrgencyCds(urgencyCdStr.split(","));
            if (!isBlank(importanceCdStr)) param.setImportanceCds(importanceCdStr.split(","));
            if (!isBlank(categoryCdStr))   param.setCategoryCds(categoryCdStr.split(","));

            HashMap<String, Object> result = mailService.getInboxClassified(param);
            return makeSuccessJsonData(result);

        } catch (Exception e) {
            log.error("분류된 메일함 조회 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "메일 목록 조회 중 오류가 발생했습니다.", "MAIL_CLASSIFIED_LIST_FAILED");
        }
    }

    // ─── 11-1. 분류된 받은메일함 AI 요약 ────────────────────────

    /**
     * POST /mail/inbox-summary.do
     * Body: { tabType, searchField, searchKeyword, purposeCds[], actionCds[], urgencyCds[], importanceCds[], categoryCds[] }
     * 현재 필터 조건에 해당하는 메일 목록을 AI로 요약하여 반환
     */
    @RequestMapping(value = "/inbox-summary.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView inboxSummary(@RequestBody Map<String, Object> body,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }

            MailVO.MailListParamVO param = new MailVO.MailListParamVO();
            param.setAccountId(accountId);
            param.setTabType(body.containsKey("tabType") ? toStr(body.get("tabType")) : "all");
            param.setSearchField(body.containsKey("searchField") ? toStr(body.get("searchField")) : "subject");
            param.setSearchKeyword(body.containsKey("searchKeyword") ? toStr(body.get("searchKeyword")) : null);

            String[] purposeCds    = parseCdList(body, "purposeCds");
            String[] actionCds     = parseCdList(body, "actionCds");
            String[] urgencyCds    = parseCdList(body, "urgencyCds");
            String[] importanceCds = parseCdList(body, "importanceCds");
            String[] categoryCds   = parseCdList(body, "categoryCds");
            if (purposeCds    != null) param.setPurposeCds(purposeCds);
            if (actionCds     != null) param.setActionCds(actionCds);
            if (urgencyCds    != null) param.setUrgencyCds(urgencyCds);
            if (importanceCds != null) param.setImportanceCds(importanceCds);
            if (categoryCds   != null) param.setCategoryCds(categoryCds);
            if (body.containsKey("startDate")) param.setStartDate(toStr(body.get("startDate")));
            if (body.containsKey("endDate"))   param.setEndDate(toStr(body.get("endDate")));

            HashMap<String, Object> result = mailService.getInboxSummary(param);
            return makeSuccessJsonData(result);

        } catch (RuntimeException e) {
            if ("AI_FAILED".equals(e.getMessage())) {
                return failResponse(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "AI 요약 생성에 실패했습니다.", "MAIL_INBOX_SUMMARY_AI_FAILED");
            }
            log.error("받은메일함 AI 요약 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "AI 요약 생성 중 오류가 발생했습니다.", "MAIL_INBOX_SUMMARY_FAILED");
        } catch (Exception e) {
            log.error("받은메일함 AI 요약 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "AI 요약 생성 중 오류가 발생했습니다.", "MAIL_INBOX_SUMMARY_FAILED");
        }
    }

    // ─── 12. 메일 상세 + AI 분석결과 조회 ─────────────────────

    /**
     * GET /mail/inbox-detail.do?mailId=MS000001
     */
    @RequestMapping(value = "/inbox-detail.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView inboxDetail(@RequestParam("mailId") String mailId,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }
            HashMap<String, Object> result = mailService.getMailDetail(mailId);
            return makeSuccessJsonData(result);

        } catch (IllegalArgumentException e) {
            return failResponse(response, HttpServletResponse.SC_NOT_FOUND,
                "메일을 찾을 수 없습니다.", "MAIL_NOT_FOUND");
        } catch (Exception e) {
            log.error("메일 상세 조회 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "메일 상세 조회 중 오류가 발생했습니다.", "MAIL_DETAIL_FAILED");
        }
    }

    // ─── 13. 회신 초안 생성 ─────────────────────────────────────

    /**
     * POST /mail/reply-draft.do
     * Body: { mailId }
     */
    @RequestMapping(value = "/reply-draft.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView replyDraft(@RequestBody Map<String, String> body,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }
            String mailId = body.get("mailId");
            if (isBlank(mailId)) {
                return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    "mailId는 필수입니다.", "MAIL_REPLY_DRAFT_INVALID");
            }
            HashMap<String, Object> result = mailService.createReplyDraft(mailId);
            return makeSuccessJsonData(result);

        } catch (IllegalArgumentException e) {
            return failResponse(response, HttpServletResponse.SC_NOT_FOUND,
                "메일을 찾을 수 없습니다.", "MAIL_NOT_FOUND");
        } catch (RuntimeException e) {
            if ("AI_FAILED".equals(e.getMessage())) {
                return failResponse(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "회신 초안 생성에 실패했습니다.", "MAIL_REPLY_DRAFT_AI_FAILED");
            }
            log.error("회신 초안 생성 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "회신 초안 생성 중 오류가 발생했습니다.", "MAIL_REPLY_DRAFT_FAILED");
        } catch (Exception e) {
            log.error("회신 초안 생성 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "회신 초안 생성 중 오류가 발생했습니다.", "MAIL_REPLY_DRAFT_FAILED");
        }
    }

    // ─── 14. 액션 완료 처리 ─────────────────────────────────────

    /**
     * POST /mail/action-complete.do
     * Body: { mailId, currentYn }
     */
    @RequestMapping(value = "/action-complete.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView actionComplete(@RequestBody Map<String, String> body,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }
            String mailId    = body.get("mailId");
            String currentYn = body.get("currentYn");
            if (isBlank(mailId)) {
                return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    "mailId는 필수입니다.", "MAIL_ACTION_COMPLETE_INVALID");
            }
            mailService.toggleActionComplete(mailId, currentYn);
            return makeSuccessJsonData();

        } catch (Exception e) {
            log.error("액션 완료 처리 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "액션 완료 처리 중 오류가 발생했습니다.", "MAIL_ACTION_COMPLETE_FAILED");
        }
    }

    // ─── 15. 팔로업 등록 ─────────────────────────────────────────

    /**
     * POST /mail/followup-register.do
     * Body: { mailId, recipientAddr?, expectedReplyDt? }
     */
    @RequestMapping(value = "/followup-register.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView followupRegister(@RequestBody Map<String, String> body,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }
            String mailId          = body.get("mailId");
            String recipientAddr   = body.get("recipientAddr");
            String expectedDateStr = body.get("expectedReplyDt");

            if (isBlank(mailId)) {
                return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    "mailId는 필수입니다.", "MAIL_FOLLOWUP_REGISTER_INVALID");
            }

            java.util.Date expectedDate = null;
            if (!isBlank(expectedDateStr)) {
                try {
                    expectedDate = new java.text.SimpleDateFormat("yyyy-MM-dd").parse(expectedDateStr);
                } catch (Exception ignored) {}
            }

            HashMap<String, Object> result = mailService.registerFollowup(mailId, recipientAddr, expectedDate);
            return makeSuccessJsonData(result);

        } catch (IllegalArgumentException e) {
            return failResponse(response, HttpServletResponse.SC_NOT_FOUND,
                "메일을 찾을 수 없습니다.", "MAIL_NOT_FOUND");
        } catch (Exception e) {
            log.error("팔로업 등록 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "팔로업 등록 중 오류가 발생했습니다.", "MAIL_FOLLOWUP_REGISTER_FAILED");
        }
    }

    // ─── 16. 팔로업 목록 조회 ───────────────────────────────────

    /**
     * GET /mail/followup-list.do
     */
    @RequestMapping(value = "/followup-list.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView followupList(HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }
            HashMap<String, Object> result = mailService.getFollowupList(accountId);
            return makeSuccessJsonData(result);
        } catch (Exception e) {
            log.error("팔로업 목록 조회 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "팔로업 목록 조회 중 오류가 발생했습니다.", "MAIL_FOLLOWUP_LIST_FAILED");
        }
    }

    // ─── 17-1. 보낸메일함 분류 목록 조회 (LLM 기반) ─────────────

    /**
     * GET /mail/sent-classified.do
     *   ?tabType=all|pending|done
     *   &startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
     *   &pageNum=1&pageSize=50
     */
    @RequestMapping(value = "/sent-classified.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView sentClassified(
            @RequestParam(value = "tabType",   defaultValue = "all") String tabType,
            @RequestParam(value = "startDate", required = false)     String startDate,
            @RequestParam(value = "endDate",   required = false)     String endDate,
            @RequestParam(value = "pageNum",   defaultValue = "1")   int pageNum,
            @RequestParam(value = "pageSize",  defaultValue = "50")  int pageSize,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }

            MailVO.SentListParamVO param = new MailVO.SentListParamVO();
            param.setAccountId(accountId);
            param.setTabType(tabType);
            param.setStartDate(startDate);
            param.setEndDate(endDate);
            param.setPageNum(pageNum);
            param.setPageSize(pageSize);

            HashMap<String, Object> result = mailService.getSentClassified(param);
            return makeSuccessJsonData(result);

        } catch (Exception e) {
            log.error("보낸메일함 분류 목록 조회 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "보낸메일함 조회 중 오류가 발생했습니다.", "MAIL_SENT_CLASSIFIED_FAILED");
        }
    }

    // ─── 17-2. 답장 대기 많은 상대 조회 ─────────────────────────

    /**
     * GET /mail/sent-top-recipients.do?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
     */
    @RequestMapping(value = "/sent-top-recipients.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView sentTopRecipients(
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate",   required = false) String endDate,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }

            MailVO.SentListParamVO param = new MailVO.SentListParamVO();
            param.setAccountId(accountId);
            param.setTabType("pending");
            param.setStartDate(startDate);
            param.setEndDate(endDate);
            param.setPageNum(0);
            param.setPageSize(5);

            HashMap<String, Object> result = mailService.getTopPendingRecipients(param);
            return makeSuccessJsonData(result);

        } catch (Exception e) {
            log.error("답장 대기 많은 상대 조회 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "조회 중 오류가 발생했습니다.", "MAIL_TOP_RECIPIENTS_FAILED");
        }
    }

    // ─── 17-3. 이번 주 회신 통계 조회 ───────────────────────────

    /**
     * GET /mail/sent-weekly-stats.do
     * 조회 기간 필터 무관, 캘린더 주 단위 고정 통계
     */
    @RequestMapping(value = "/sent-weekly-stats.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView sentWeeklyStats(HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }

            HashMap<String, Object> result = mailService.getSentWeeklyStats(accountId);
            return makeSuccessJsonData(result);

        } catch (Exception e) {
            log.error("회신 통계 조회 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "통계 조회 중 오류가 발생했습니다.", "MAIL_WEEKLY_STATS_FAILED");
        }
    }

    // ─── 17. 팔로업 상태 변경 ───────────────────────────────────

    /**
     * POST /mail/followup-status-update.do
     * Body: { followupId, statusCd }
     */
    @RequestMapping(value = "/followup-status-update.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView followupStatusUpdate(@RequestBody Map<String, String> body,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            String accountId = getMailSession(request, SESSION_KEY_ACCOUNT_ID);
            if (accountId == null) {
                return failResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "계정 정보가 없습니다.", "MAIL_AUTH_REQUIRED");
            }
            String followupId = body.get("followupId");
            String statusCd   = body.get("statusCd");
            if (isBlank(followupId) || isBlank(statusCd)) {
                return failResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    "followupId, statusCd는 필수입니다.", "MAIL_FOLLOWUP_STATUS_INVALID");
            }
            mailService.updateFollowupStatus(followupId, statusCd);
            return makeSuccessJsonData();
        } catch (Exception e) {
            log.error("팔로업 상태 변경 실패: {}", e.getMessage(), e);
            return failResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "팔로업 상태 변경 중 오류가 발생했습니다.", "MAIL_FOLLOWUP_STATUS_FAILED");
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

    /**
     * POST body의 JSON 배열 또는 CSV 문자열 → String[] 변환.
     * 빈 배열이면 null 반환 (필터 미적용과 동일하게 처리).
     */
    @SuppressWarnings("unchecked")
    private String[] parseCdList(Map<String, Object> body, String key) {
        Object val = body.get(key);
        if (val == null) return null;
        if (val instanceof List) {
            List<?> list = (List<?>) val;
            if (list.isEmpty()) return null;
            String[] arr = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = list.get(i) != null ? list.get(i).toString() : "";
            }
            return arr;
        }
        String str = val.toString().trim();
        return str.isEmpty() ? null : str.split(",");
    }

    private String toStr(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
