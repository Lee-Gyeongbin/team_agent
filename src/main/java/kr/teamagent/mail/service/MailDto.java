package kr.teamagent.mail.service;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MailDto {

    /** 메일 제목 */
    private String subject;

    /** 발신자 전체 문자열 (예: 홍길동 <gildong@example.com>) */
    private String from;

    /** 발신자 이름만 (이름이 없으면 이메일 주소) */
    private String fromName;

    /** 수신 일시 */
    private Date receivedDate;

    /** 본문 (text/plain 우선, 없으면 text/html에서 태그 제거) */
    private String body;

    /**
     * 읽음 여부.
     * 필드명에 is 접두사 + Lombok boolean getter → Jackson이 "read"로 직렬화하는 문제를 방지하기 위해
     * @JsonProperty로 JSON 키를 명시한다.
     */
    @JsonProperty("isRead")
    private boolean isRead;
}
