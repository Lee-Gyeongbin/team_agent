package kr.teamagent.mail.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.mail.service.MailVO;

@Repository
public class MailDAO extends EgovComAbstractDAO {

    // ────────────────────────────────────────────────────────────────────
    // TB_MAIL_ACCOUNT
    // ────────────────────────────────────────────────────────────────────

    /** userId + emailAddr 기준으로 계정 단건 조회 */
    public MailVO.MailAccountVO selectMailAccount(Map<String, Object> param) throws Exception {
        return selectOne("mail.selectMailAccount", param);
    }

    /** 계정 INSERT (ON DUPLICATE KEY UPDATE) */
    public void insertMailAccount(MailVO.MailAccountVO vo) throws Exception {
        insert("mail.insertMailAccount", vo);
    }

    /** LAST_LOGIN_DT / UPDATE_DT 갱신 */
    public void updateMailAccountLastLogin(Map<String, Object> param) throws Exception {
        update("mail.updateMailAccountLastLogin", param);
    }

    // ────────────────────────────────────────────────────────────────────
    // TB_MAIL_SYNC_STATE
    // ────────────────────────────────────────────────────────────────────

    /** accountId + folderCd 기준 동기화 상태 조회 */
    public MailVO.MailSyncStateVO selectMailSyncState(Map<String, Object> param) throws Exception {
        return selectOne("mail.selectMailSyncState", param);
    }

    /** 동기화 상태 UPSERT (INSERT ON DUPLICATE KEY UPDATE) */
    public void upsertMailSyncState(MailVO.MailSyncStateVO vo) throws Exception {
        insert("mail.upsertMailSyncState", vo);
    }

    // ────────────────────────────────────────────────────────────────────
    // TB_MAIL_MSG
    // ────────────────────────────────────────────────────────────────────

    /** IMAP_UID 기준 단건 조회 (중복 체크용) */
    public MailVO.MailMsgVO selectMailMsgByImapUid(Map<String, Object> param) throws Exception {
        return selectOne("mail.selectMailMsgByImapUid", param);
    }

    /** 메일 메시지 INSERT (ON DUPLICATE KEY UPDATE) */
    public void insertMailMsg(MailVO.MailMsgVO vo) throws Exception {
        insert("mail.insertMailMsg", vo);
    }

    /** MAIL_ID 기준 단건 조회 */
    public MailVO.MailMsgVO selectMailMsgById(String mailId) throws Exception {
        return selectOne("mail.selectMailMsgById", mailId);
    }

    // ────────────────────────────────────────────────────────────────────
    // TB_MAIL_AI_ANALYSIS
    // ────────────────────────────────────────────────────────────────────

    /** MAIL_ID 기준 AI 분석 결과 단건 조회 */
    public MailVO.MailAiAnalysisVO selectMailAiAnalysis(String mailId) throws Exception {
        return selectOne("mail.selectMailAiAnalysis", mailId);
    }

    /** AI 분석 결과 신규 INSERT */
    public void insertMailAiAnalysis(MailVO.MailAiAnalysisVO vo) throws Exception {
        insert("mail.insertMailAiAnalysis", vo);
    }

    /** AI 분석 결과 재분석 UPDATE */
    public void updateMailAiAnalysis(MailVO.MailAiAnalysisVO vo) throws Exception {
        update("mail.updateMailAiAnalysis", vo);
    }

    // ────────────────────────────────────────────────────────────────────
    // KPI
    // ────────────────────────────────────────────────────────────────────

    /** 받은메일함 KPI 집계 */
    public MailVO.MailKpiVO selectMailKpi(String accountId) throws Exception {
        return selectOne("mail.selectMailKpi", accountId);
    }

    // ────────────────────────────────────────────────────────────────────
    // 분류된 메일함 목록
    // ────────────────────────────────────────────────────────────────────

    /** 분류된 메일함 목록 조회 (MSG + AI_ANALYSIS JOIN) */
    public List<MailVO.ClassifiedMailVO> selectClassifiedMailList(MailVO.MailListParamVO param) throws Exception {
        return selectList("mail.selectClassifiedMailList", param);
    }

    /** 분류된 메일함 전체 건수 조회 */
    public int selectClassifiedMailCount(MailVO.MailListParamVO param) throws Exception {
        return (int) selectOne("mail.selectClassifiedMailCount", param);
    }

    /** 탭별 건수 조회 (뱃지용: all / action / reply) */
    public List<Map<String, Object>> selectTabCounts(String accountId) throws Exception {
        return selectList("mail.selectTabCounts", accountId);
    }

    // ────────────────────────────────────────────────────────────────────
    // 액션 완료 처리
    // ────────────────────────────────────────────────────────────────────

    /** ACTION_COMPLETE_YN 토글 */
    public void updateActionComplete(Map<String, Object> param) throws Exception {
        update("mail.updateActionComplete", param);
    }

    // ────────────────────────────────────────────────────────────────────
    // TB_MAIL_REPLY_DRAFT
    // ────────────────────────────────────────────────────────────────────

    /** 회신 초안 INSERT */
    public void insertMailReplyDraft(MailVO.MailReplyDraftVO vo) throws Exception {
        insert("mail.insertMailReplyDraft", vo);
    }

    /** 최신 회신 초안 조회 */
    public MailVO.MailReplyDraftVO selectLatestReplyDraft(String mailId) throws Exception {
        return selectOne("mail.selectLatestReplyDraft", mailId);
    }

    // ────────────────────────────────────────────────────────────────────
    // TB_MAIL_FOLLOWUP
    // ────────────────────────────────────────────────────────────────────

    /** 팔로업 INSERT */
    public void insertMailFollowup(MailVO.MailFollowupVO vo) throws Exception {
        insert("mail.insertMailFollowup", vo);
    }

    /** 팔로업 목록 조회 (accountId 기준) */
    public List<MailVO.MailFollowupVO> selectMailFollowupList(String accountId) throws Exception {
        return selectList("mail.selectMailFollowupList", accountId);
    }

    /** 팔로업 상태 변경 */
    public void updateMailFollowupStatus(Map<String, Object> param) throws Exception {
        update("mail.updateMailFollowupStatus", param);
    }

    /** 팔로업 자동 매칭 (REPLIED_MAIL_ID + STATUS_CD 업데이트) */
    public void updateFollowupMatched(Map<String, Object> param) throws Exception {
        update("mail.updateFollowupMatched", param);
    }

    /** 팔로업 대기 목록 조회 (WAITING 상태, 자동 매칭용) */
    public List<MailVO.MailFollowupVO> selectWaitingFollowupList(String accountId) throws Exception {
        return selectList("mail.selectWaitingFollowupList", accountId);
    }

    /** IN_REPLY_TO 헤더가 있는 INBOX 메일 조회 (자동 매칭용) */
    public List<MailVO.MailMsgVO> selectInboxMsgWithInReplyTo(String accountId) throws Exception {
        return selectList("mail.selectInboxMsgWithInReplyTo", accountId);
    }

    // ────────────────────────────────────────────────────────────────────
    // 코드
    // ────────────────────────────────────────────────────────────────────

    /** 업무영역 코드 목록 조회 (ML000008, AI 프롬프트 동적 삽입용) */
    public List<Map<String, Object>> selectWorkCategoryList() throws Exception {
        return selectList("mail.selectWorkCategoryList");
    }

    // ────────────────────────────────────────────────────────────────────
    // 보낸메일함 분류 (LLM 기반)
    // ────────────────────────────────────────────────────────────────────

    /** 보낸메일함 분류 목록 조회 */
    public List<MailVO.SentClassifiedItemVO> selectSentClassifiedList(MailVO.SentListParamVO param) throws Exception {
        return selectList("mail.selectSentClassifiedList", param);
    }

    /** 보낸메일함 분류 건수 조회 */
    public int selectSentClassifiedCount(MailVO.SentListParamVO param) throws Exception {
        return (int) selectOne("mail.selectSentClassifiedCount", param);
    }

    /** 보낸메일함 탭별 건수 조회 */
    public List<Map<String, Object>> selectSentTabCounts(MailVO.SentListParamVO param) throws Exception {
        return selectList("mail.selectSentTabCounts", param);
    }

    /** 답장 대기 많은 상대 조회 */
    public List<MailVO.TopPendingRecipientVO> selectTopPendingRecipients(MailVO.SentListParamVO param) throws Exception {
        return selectList("mail.selectTopPendingRecipients", param);
    }

    /** 이번 주 / 전주 회신 통계 조회 */
    public Map<String, Object> selectSentWeeklyStats(String accountId) throws Exception {
        return selectOne("mail.selectSentWeeklyStats", accountId);
    }
}
