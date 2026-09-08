package kr.teamagent.common.util;

import java.util.Arrays;

import org.passay.AllowedCharacterRule;
import org.passay.CharacterData;
import org.passay.CharacterRule;
import org.passay.EnglishCharacterData;
import org.passay.EnglishSequenceData;
import org.passay.IllegalSequenceRule;
import org.passay.LengthRule;
import org.passay.PasswordData;
import org.passay.PasswordValidator;
import org.passay.RepeatCharacterRegexRule;
import org.passay.RuleResult;

/**
 * 신규 비밀번호 패턴 검증 (마이페이지 변경·회원가입 공용).
 */
public final class PasswordRuleUtil {

    private static final CharacterData PASSWORD_SPECIAL_CHAR_DATA = new CharacterData() {
        @Override
        public String getErrorCode() {
            return "ERR_SPECIAL_CHAR";
        }

        @Override
        public String getCharacters() {
            return "!@#$%^&*()_-+=";
        }
    };

    /** 영문 + 숫자 + {@link #PASSWORD_SPECIAL_CHAR_DATA} (AllowedCharacterRule은 char[] 만 지원해 문자열을 합쳐 구성) */
    private static final char[] PASSWORD_ALLOWED_CHARS = (
            EnglishCharacterData.Alphabetical.getCharacters()
                    + EnglishCharacterData.Digit.getCharacters()
                    + PASSWORD_SPECIAL_CHAR_DATA.getCharacters()
    ).toCharArray();

    private static final PasswordValidator PASSWORD_LENGTH_VALIDATOR = new PasswordValidator(
            Arrays.asList(new LengthRule(8, 20)));

    private static final PasswordValidator PASSWORD_ALLOWED_VALIDATOR = new PasswordValidator(
            Arrays.asList(new AllowedCharacterRule(PASSWORD_ALLOWED_CHARS)));

    private static final PasswordValidator PASSWORD_PATTERN_VALIDATOR = new PasswordValidator(Arrays.asList(
            new IllegalSequenceRule(EnglishSequenceData.Numerical, 4, false),
            new IllegalSequenceRule(EnglishSequenceData.Alphabetical, 4, false),
            new RepeatCharacterRegexRule(4)
    ));

    private static final PasswordValidator PASSWORD_COMPOSITION_VALIDATOR = new PasswordValidator(Arrays.asList(
            new CharacterRule(EnglishCharacterData.Alphabetical, 1),
            new CharacterRule(EnglishCharacterData.Digit, 1),
            new CharacterRule(PASSWORD_SPECIAL_CHAR_DATA, 1)
    ));

    private PasswordRuleUtil() {
    }

    /**
     * 신규 비밀번호 패턴 검증. 통과 시 {@code null}, 실패 시 메시지.
     */
    public static String validateNewPassword(String password, String userId, String email, String phone) {
        if (password == null) {
            return "비밀번호를 입력해주세요.";
        }
        RuleResult lengthRuleResult = PASSWORD_LENGTH_VALIDATOR.validate(new PasswordData(password));
        if (!lengthRuleResult.isValid()) {
            return "비밀번호는 8~20자로 입력해주세요.";
        }
        RuleResult allowedRuleResult = PASSWORD_ALLOWED_VALIDATOR.validate(new PasswordData(password));
        if (!allowedRuleResult.isValid()) {
            return "허용되지 않은 특수문자가 포함되어 있습니다.";
        }

        RuleResult compositionRuleResult = PASSWORD_COMPOSITION_VALIDATOR.validate(new PasswordData(password));
        if (!compositionRuleResult.isValid()) {
            return "문자 숫자 특수문자를 모두 포함해야 합니다.";
        }

        RuleResult passwordRuleResult = PASSWORD_PATTERN_VALIDATOR.validate(new PasswordData(password));
        if (!passwordRuleResult.isValid()) {
            return "연속된 문자 혹은 동일한 문자를 반복하여 사용할 수 없습니다";
        }

        if (containsIdentity(password, CommonUtil.nullToBlank(userId))
                || containsIdentity(password, emailIdentity(email))
                || containsIdentity(password, CommonUtil.nullToBlank(phone))) {
            return "아이디, 이메일 또는 전화번호와 동일한 문자는 사용할 수 없습니다.";
        }
        return null;
    }

    /** 빈 문자열 contains는 모든 값에 매칭되므로 값이 있을 때만 검사한다. */
    private static boolean containsIdentity(String password, String identity) {
        return identity.length() > 0 && password.contains(identity);
    }

    /** `@` 앞 로컬파트. `@`가 없거나 맨 앞이면 이메일 전체. */
    private static String emailIdentity(String email) {
        String raw = CommonUtil.nullToBlank(email);
        int atIdx = raw.indexOf('@');
        if (atIdx > 0) {
            return raw.substring(0, atIdx);
        }
        return raw;
    }
}
