package kr.teamagent.common.web;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.teamagent.common.exception.DuplicateUserIdException;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.security.service.impl.LoginServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.library.service.impl.LibraryServiceImpl;

@RestController
@RequestMapping("/")
@SuppressWarnings("deprecation")
public class ApiSignupController {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private LoginServiceImpl loginService;

    @Autowired
    private LibraryServiceImpl libraryService;

    private final StandardPasswordEncoder passwordEncoder = new StandardPasswordEncoder();

    @PostMapping("/signup.do")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody Map<String, String> signupRequest) {
        Map<String, Object> result = new HashMap<>();
        try {
            String userId = CommonUtil.nullToBlank(signupRequest.get("userId"));
            String password = CommonUtil.nullToBlank(signupRequest.get("password"));
            String userNm = CommonUtil.nullToBlank(signupRequest.get("userNm"));
            String email = CommonUtil.nullToBlank(signupRequest.get("email"));
            String phone = CommonUtil.nullToBlank(signupRequest.get("phone"));
            String orgId = CommonUtil.nullToBlank(signupRequest.get("orgId"));

            if (userId.isEmpty() || password.isEmpty() || userNm.isEmpty()) {
                result.put("success", false);
                result.put("errorType", "validationError");
                result.put("message", "아이디, 비밀번호, 사용자명은 필수입니다.");
                return ResponseEntity.ok(result);
            }

            if (userId.length() < 3 || userId.length() > 50) {
                result.put("success", false);
                result.put("errorType", "validationError");
                result.put("message", "아이디는 3~50자로 입력해주세요.");
                return ResponseEntity.ok(result);
            }

            if (password.length() < 4) {
                result.put("success", false);
                result.put("errorType", "validationError");
                result.put("message", "비밀번호는 4자 이상 입력해주세요.");
                return ResponseEntity.ok(result);
            }

            String compId = PropertyUtil.getProperty("User.CompId");
            String dbId = PropertyUtil.getProperty("Globals.User.db");

            UserVO userVO = new UserVO();
            userVO.setCompId(compId);
            userVO.setUserId(userId);
            userVO.setUserNm(userNm);
            userVO.setEmail(email.isEmpty() ? null : email);
            userVO.setPhone(phone.isEmpty() ? null : phone);
            userVO.setOrgId(orgId.isEmpty() ? null : orgId);
            userVO.setMasterDbId(dbId);
            userVO.setDbId(dbId);
            userVO.setPasswd(passwordEncoder.encode(password));

            loginService.signupUser(userVO);

            try {
                libraryService.insertDefaultCategoryForNewUser(userId);
            } catch (Exception e) {
                log.warn("Signup success but default category insert failed: userId={}", userId, e);
            }

            result.put("success", true);
            result.put("userId", userId);
            log.info("Signup success: userId={}", userId);
            return ResponseEntity.ok(result);

        } catch (DuplicateUserIdException e) {
            result.put("success", false);
            result.put("errorType", "duplicateUserId");
            result.put("message", e.getMessage());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Signup error", e);
            result.put("success", false);
            result.put("errorType", "serverError");
            result.put("message", "회원가입 처리 중 오류가 발생했습니다.");
            return ResponseEntity.internalServerError().body(result);
        }
    }
}
