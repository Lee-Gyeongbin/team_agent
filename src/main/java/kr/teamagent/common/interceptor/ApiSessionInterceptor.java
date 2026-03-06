package kr.teamagent.common.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ApiSessionInterceptor extends HandlerInterceptorAdapter {

    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PathMatcher pathMatcher = new AntPathMatcher();

    private Set<String> excludePatterns;

    public void setExcludePatterns(Set<String> excludePatterns) {
        this.excludePatterns = excludePatterns;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requestURI = request.getRequestURI();
        String contextPath = request.getContextPath();

        if (excludePatterns != null) {
            for (String pattern : excludePatterns) {
                String fullPattern = contextPath + pattern;
                if (pathMatcher.match(fullPattern, requestURI)) {
                    return true;
                }
            }
        }

        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("loginVO") == null) {
            log.debug("Session expired or not found for request: {}", requestURI);

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");

            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("errorType", "sessionExpired");
            result.put("message", "세션이 만료되었습니다. 다시 로그인해주세요.");

            response.getWriter().write(objectMapper.writeValueAsString(result));
            return false;
        }

        return true;
    }
}
