package kr.teamagent.mail.service;

import java.util.List;
import java.util.Date;

public interface MailService {

    /**
     * IMAP SSL 접속을 시도하여 인증이 유효한지 검사한다.
     *
     * @param email    이메일 주소
     * @param password 비밀번호
     * @return 인증 성공 여부
     */
    boolean authImap(String email, String password);

    /**
     * INBOX에서 날짜 범위 내 메일을 조회한다.
     *
     * @param email        이메일 주소 (세션에서 전달)
     * @param password     비밀번호 (세션에서 전달)
     * @param startDate    시작일(포함)
     * @param endExclusive 종료일 다음날(미포함)
     * @return 메일 DTO 목록 (최신순)
     * @throws Exception IMAP 접속/파싱 오류
     */
    List<MailDto> getRecentMails(String email, String password, Date startDate, Date endExclusive) throws Exception;
}
