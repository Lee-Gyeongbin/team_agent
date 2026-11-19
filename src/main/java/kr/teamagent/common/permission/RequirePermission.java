package kr.teamagent.common.permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메뉴 권한 검사 대상 API 표시.
 * 화면 경로(TB_MENU.SRC_PATH)와 권한 종류로 검사하며, 검사는 PermissionInterceptor가 한다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 화면 경로 (TB_MENU.SRC_PATH) */
    String menu();

    /** READ = 조회 권한, FUNC = 기능(등록·수정·삭제·엑셀) 권한 */
    Type type();

    enum Type {
        READ, FUNC
    }
}
