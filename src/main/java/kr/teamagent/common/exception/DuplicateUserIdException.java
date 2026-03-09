/**
 * @Class Name	:	DuplicateUserIdException.java
 * @Description	:	중복 사용자 ID 예외 (회원가입 시)
 * @author	:	teamagent
 */
package kr.teamagent.common.exception;

public class DuplicateUserIdException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateUserIdException() {
        super("이미 존재하는 아이디입니다.");
    }

    public DuplicateUserIdException(String message) {
        super(message);
    }
}
