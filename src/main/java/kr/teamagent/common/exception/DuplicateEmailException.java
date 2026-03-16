/**
 * @Class Name  : DuplicateEmailException.java
 * @Description : 중복 이메일 예외 (회원가입/사용자 관리 시)
 * @author      : teamagent
 */
package kr.teamagent.common.exception;

public class DuplicateEmailException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateEmailException() {
        super("이미 가입된 이메일입니다.");
    }

    public DuplicateEmailException(String message) {
        super(message);
    }
}