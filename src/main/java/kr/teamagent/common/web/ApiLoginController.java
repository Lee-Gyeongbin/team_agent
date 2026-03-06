package kr.teamagent.common.web;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;

@RestController
@RequestMapping("/api")
@SuppressWarnings("deprecation")
public class ApiLoginController {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    @Qualifier("egov.sqlSessionTemplate")
    private SqlSessionTemplate sqlSession;

    private final StandardPasswordEncoder passwordEncoder = new StandardPasswordEncoder();

    @PostMapping("/login.do")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> loginRequest,
                                                     HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            String userId = CommonUtil.nullToBlank(loginRequest.get("userId"));
            String password = CommonUtil.nullToBlank(loginRequest.get("password"));

            if (userId.isEmpty() || password.isEmpty()) {
                result.put("success", false);
                result.put("errorType", "emptyInput");
                result.put("message", "아이디와 비밀번호를 입력해주세요.");
                return ResponseEntity.ok(result);
            }

            String compId = PropertyUtil.getProperty("User.CompId");
            String dbId = PropertyUtil.getProperty("Globals.User.db");

            UserVO paramVO = new UserVO();
            paramVO.setCompId(compId);
            paramVO.setUserId(userId);
            paramVO.setMasterDbId(dbId);
            paramVO.setDbId(dbId);

            EgovMap loginProviderData = sqlSession.selectOne("login.selectLoginProviderData", paramVO);

            /*
             * [주석처리] 인증 시도 횟수 - COM_ACCESS_AUTH 테이블 없음
             * int authStatusCount = Integer.parseInt(String.valueOf(loginProviderData.get("authStatusCount")));
             * int maxAccessCount = Integer.parseInt(PropertyUtil.getProperty("auth.accessCount"));
             * if (authStatusCount >= maxAccessCount) {
             *     result.put("success", false);
             *     result.put("errorType", "accessDenied");
             *     result.put("message", "로그인 시도 횟수를 초과하였습니다. 관리자에게 문의하세요.");
             *     return ResponseEntity.ok(result);
             * }
             */

            UserVO user = (UserVO) loginProviderData.get("userVO");
            if (user == null) {
                result.put("success", false);
                result.put("errorType", "noUser");
                result.put("message", "존재하지 않는 사용자입니다.");
                return ResponseEntity.ok(result);
            }

            if (!"Y".equals(user.getUseYn())) {
                result.put("success", false);
                result.put("errorType", "inActiveUser");
                result.put("message", "비활성화된 사용자입니다.");
                return ResponseEntity.ok(result);
            }

            if (!passwordEncoder.matches(password, user.getPassword())) {
                result.put("success", false);
                result.put("errorType", "passwordFail");
                result.put("message", "비밀번호가 올바르지 않습니다.");
                return ResponseEntity.ok(result);
            }

            HttpSession session = request.getSession(true);
            session.setAttribute("userId", CommonUtil.nullToBlank(user.getUserId()));
            session.setAttribute("masterDbId", CommonUtil.nullToBlank(PropertyUtil.getProperty("Globals.Master.db")));
            session.setAttribute("loginVO", user);

            Map<String, Object> userData = new HashMap<>();
            userData.put("userId", user.getUserId());
            userData.put("userName", user.getUserName());
            userData.put("email", user.getEmail());
            userData.put("orgId", user.getOrgId());
            userData.put("phone", user.getPhone());

            result.put("success", true);
            result.put("user", userData);

            log.info("Login success: userId={}", user.getUserId());
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Login error", e);
            result.put("success", false);
            result.put("message", "로그인 처리 중 오류가 발생했습니다.");
            return ResponseEntity.internalServerError().body(result);
        }
    }

    @PostMapping("/logout.do")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            result.put("success", true);
        } catch (Exception e) {
            log.error("Logout error", e);
            result.put("success", false);
            result.put("message", "로그아웃 처리 중 오류가 발생했습니다.");
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/session/user.do")
    public ResponseEntity<Map<String, Object>> getSessionUser(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("loginVO") == null) {
            result.put("success", false);
            result.put("message", "로그인이 필요합니다.");
            return ResponseEntity.status(401).body(result);
        }

        UserVO user = (UserVO) session.getAttribute("loginVO");
        Map<String, Object> userData = new HashMap<>();
        userData.put("userId", user.getUserId());
        userData.put("userName", user.getUserName());
        userData.put("email", user.getEmail());
        userData.put("orgId", user.getOrgId());
        userData.put("phone", user.getPhone());

        result.put("success", true);
        result.put("user", userData);
        return ResponseEntity.ok(result);
    }
}
