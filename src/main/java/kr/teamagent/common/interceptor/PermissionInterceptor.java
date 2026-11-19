package kr.teamagent.common.interceptor;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.teamagent.common.permission.RequirePermission;
import kr.teamagent.common.system.service.impl.MenuPermissionServiceImpl;

/** @RequirePermission이 붙은 API를 로그인 사용자의 메뉴 권한(조회·기능)으로 검사한다 */
public class PermissionInterceptor extends HandlerInterceptorAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MenuPermissionServiceImpl menuPermissionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        RequirePermission required = ((HandlerMethod) handler).getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            return true;
        }
        boolean allowed = required.type() == RequirePermission.Type.FUNC
                ? menuPermissionService.canFunc(required.menu())
                : menuPermissionService.canRead(required.menu());
        if (allowed) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> result = new HashMap<>();
        result.put("successYn", false);
        result.put("errorType", "forbidden");
        result.put("returnMsg", "접근 권한이 없습니다.");
        response.getWriter().write(objectMapper.writeValueAsString(result));
        return false;
    }
}
