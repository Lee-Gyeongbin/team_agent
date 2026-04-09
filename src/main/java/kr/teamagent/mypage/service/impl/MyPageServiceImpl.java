package kr.teamagent.mypage.service.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.net.URL;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.stereotype.Service;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import org.passay.AllowedCharacterRule;
import org.passay.CharacterData;
import org.passay.CharacterRule;
import org.passay.EnglishCharacterData;
import org.passay.EnglishSequenceData;
import org.passay.IllegalSequenceRule;
import org.passay.PasswordData;
import org.passay.PasswordValidator;
import org.passay.RepeatCharacterRegexRule;
import org.passay.RuleResult;

import kr.teamagent.common.CommonVO;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.mypage.service.MyPageVO;

@Service
public class MyPageServiceImpl extends EgovAbstractServiceImpl {

    /** NCP 객체 키: profiles/{userId}/파일명 */
    private static final String PROFILE_STORAGE_PREFIX = "profiles";
    private static final long PROFILE_UPLOAD_EXPIRE_MILLIS = 60 * 60 * 1000L;
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

    private static final PasswordValidator PASSWORD_ALLOWED_VALIDATOR = new PasswordValidator(
            java.util.Arrays.asList(new AllowedCharacterRule(PASSWORD_ALLOWED_CHARS)));

    private static final PasswordValidator PASSWORD_PATTERN_VALIDATOR = new PasswordValidator(java.util.Arrays.asList(
            new IllegalSequenceRule(EnglishSequenceData.Numerical, 4, false),
            new IllegalSequenceRule(EnglishSequenceData.Alphabetical, 4, false),
            new RepeatCharacterRegexRule(4)
    ));
    private static final PasswordValidator PASSWORD_COMPOSITION_VALIDATOR = new PasswordValidator(java.util.Arrays.asList(
            new CharacterRule(EnglishCharacterData.Alphabetical, 1),
            new CharacterRule(EnglishCharacterData.Digit, 1),
            new CharacterRule(PASSWORD_SPECIAL_CHAR_DATA, 1)
    ));

    @Autowired
    private MyPageDAO myPageDAO;

    @Autowired
    private FileServiceImpl fileService;

    @Autowired
    protected AmazonS3 s3Client;

    @SuppressWarnings("deprecation")
    private final StandardPasswordEncoder passwordEncoder = new StandardPasswordEncoder();

    /**
     * 세션 {@code userId}. 비로그인이거나 공백이면 {@code null}.
     */
    private String resolveSessionUserId() {
        String id = SessionUtil.getUserId();
        return CommonUtil.isEmpty(id) ? null : id;
    }

    /**
     * 세션 userId를 {@link CommonVO#userId}에 설정.
     * {@link MyPageVO}, {@link MyPageVO.LoginHistoryVO} 등 CommonVO 계열에 공통 적용.
     */
    private boolean applySessionUserId(CommonVO vo) {
        String id = resolveSessionUserId();
        if (id == null) {
            return false;
        }
        vo.setUserId(id);
        return true;
    }

    /**
     * 마이페이지 목록 조회
     */
    public List<MyPageVO> selectMyPageList(MyPageVO searchVO) throws Exception {
        if (searchVO == null) {
            searchVO = new MyPageVO();
        }
        if (!applySessionUserId(searchVO)) {
            return Collections.emptyList();
        }
        return myPageDAO.selectMyPageList(searchVO);
    }

    /**
     * 마이페이지 수정
     */
    public int updateMyPage(MyPageVO myPageVO) throws Exception {
        if (myPageVO == null) {
            myPageVO = new MyPageVO();
        }
        if (!applySessionUserId(myPageVO)) {
            return 0;
        }
        return myPageDAO.updateMyPage(myPageVO);
    }

    public Map<String, Object> changePassword(MyPageVO.PasswordChangeVO passwordChangeVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        String loginUserId = resolveSessionUserId();
        if (loginUserId == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "로그인이 필요합니다.");
            return resultMap;
        }

        String currentPasswd = passwordChangeVO.getOldPassword();
        String newPasswd = passwordChangeVO.getNewPassword();
        passwordChangeVO.setUserId(loginUserId);

        String encodedPassword = myPageDAO.selectUserPassword(passwordChangeVO);
        if (encodedPassword == null || !passwordEncoder.matches(currentPasswd, encodedPassword)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "현재 비밀번호가 일치하지 않습니다.");
            return resultMap;
        }

        if (newPasswd == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "비밀번호를 입력해주세요.");
            return resultMap;
        }
        RuleResult allowedRuleResult = PASSWORD_ALLOWED_VALIDATOR.validate(new PasswordData(newPasswd));
        if (!allowedRuleResult.isValid()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "허용되지 않은 특수문자가 포함되어 있습니다.");
            return resultMap;
        }

        RuleResult compositionRuleResult = PASSWORD_COMPOSITION_VALIDATOR.validate(new PasswordData(newPasswd));
        if (!compositionRuleResult.isValid()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "문자 숫자 특수문자를 모두 포함해야 합니다.");
            return resultMap;
        }

        RuleResult passwordRuleResult = PASSWORD_PATTERN_VALIDATOR.validate(new PasswordData(newPasswd));
        if (!passwordRuleResult.isValid()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "연속된 문자 혹은 동일한 문자를 반복하여 사용할 수 없습니다");
            return resultMap;
        }

        String emailLocalForCheck = null;
        UserVO userVO = SessionUtil.getUserVO();
        String emailRaw = userVO != null ? userVO.getEmail() : null;
        if (!CommonUtil.isEmpty(emailRaw)) {
            int atIdx = emailRaw.indexOf('@');
            if (atIdx > 0) {
                emailLocalForCheck = emailRaw.substring(0, atIdx);
            }
        }

        if (newPasswd.contains(loginUserId) || newPasswd.contains(emailLocalForCheck)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "이메일 혹은 아이디와 동일한 문자는 사용할 수 없습니다.");
            return resultMap;
        }

        if (newPasswd.equals(currentPasswd)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "동일한 비밀번호는 사용할 수 없습니다.");
            return resultMap;
        }

        passwordChangeVO.setPasswd(passwordEncoder.encode(newPasswd));
        int updatedRows = myPageDAO.updateMyPagePassword(passwordChangeVO);

        resultMap.put("successYn", updatedRows > 0);
        if (updatedRows > 0) {
            resultMap.put("data", updatedRows);
        } else {
            resultMap.put("returnMsg", "해당 userId의 사용자를 찾을 수 없습니다.");
        }
        return resultMap;
    }

    /**
     * 사용자 로그인 이력 조회
     */
    public List<MyPageVO.LoginHistoryVO> selectUserLoginHistory(MyPageVO.LoginHistoryVO searchVO) throws Exception {
        if (searchVO == null) {
            searchVO = new MyPageVO.LoginHistoryVO();
        }
        if (!applySessionUserId(searchVO)) {
            return Collections.emptyList();
        }
        return myPageDAO.selectUserLoginHistory(searchVO);
    }

    /**
     * 사용자 프로필 이미지 메타 조회 (세션 userId 적용)
     * @param myPageVO
     * @return
     * @throws Exception
     */
    public MyPageVO selectUserProfileImg(MyPageVO myPageVO) throws Exception {
        if (myPageVO == null) {
            myPageVO = new MyPageVO();
        }
        myPageVO.setUserId(resolveSessionUserId());
        return myPageDAO.selectUserProfileImg(myPageVO);
    }

    /**
     * 프로필 이미지 업로드용 presigned URL
     */
    public Map<String, Object> prepareProfileImageUpload(MyPageVO myPageVO) {
        Map<String, Object> resultMap = new HashMap<>();

        if (myPageVO == null) {
            myPageVO = new MyPageVO();
        }
        if (!applySessionUserId(myPageVO)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "로그인이 필요합니다.");
            return resultMap;
        }

        try {
            String safeName = sanitizeProfileFileName(extractBaseFileName(myPageVO.getProfileImgNm()));
            if (CommonUtil.isEmpty(safeName)) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "올바른 파일명이 아닙니다.");
                return resultMap;
            }
            if (!isImageFileName(safeName)) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "올바른 이미지 형식이 아닙니다.");
                return resultMap;
            }
            String key = buildProfileImageStorageKey(myPageVO.getUserId(), safeName);
            Map<String, Object> upload = createProfileUploadPresignedUrl(key);
            resultMap.putAll(upload);
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
        }

        return resultMap;
    }

    /**
     * TB_USER 프로필 이미지 경로·이름 저장.
     */
    public Map<String, Object> updateUserProfileImg(MyPageVO myPageVO) {
        Map<String, Object> resultMap = new HashMap<>();

        if (myPageVO == null) {
            myPageVO = new MyPageVO();
        }
        if (!applySessionUserId(myPageVO)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "로그인이 필요합니다.");
            return resultMap;
        }

        try {
            if (!isValidProfileStoragePath(myPageVO.getUserId(), myPageVO.getProfileImgPath())) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "올바른 프로필 이미지 경로가 아닙니다.");
                return resultMap;
            }
            int result = myPageDAO.updateUserProfileImg(myPageVO);
            if (result > 0) {
                resultMap.put("successYn", true);
                resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            } else {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
            }
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
        }

        return resultMap;
    }

    /**
     * 프로필 이미지 미리보기 URL 조회
     */
    public Map<String, Object> viewUserProfileImg(MyPageVO searchVO) {
        try {
            if (searchVO == null) {
                searchVO = new MyPageVO();
            }
            if (resolveSessionUserId() == null) {
                return profilePreviewResponse("", "", "LOGIN_REQUIRED");
            }
            MyPageVO row = selectUserProfileImg(searchVO);
            String path = (row == null || row.getProfileImgPath() == null) ? "" : row.getProfileImgPath().trim();
            if (path.isEmpty()) {
                return profilePreviewResponse("", "", "FILE_NOT_FOUND");
            }
            Map<String, Object> raw = fileService.createViewPresignedUrlForStorageObject(profileRowToFileVo(row, path));
            return toProfilePreviewOnlyResponse(raw);
        } catch (Exception e) {
            return profilePreviewResponse("", "", "ERROR");
        }
    }

    /**
     * TB_USER 조회 행을 {@link FileServiceImpl#createViewPresignedUrlForStorageObject} 입력용 {@link FileVO} 로 변환한다.
     */
    private static FileVO profileRowToFileVo(MyPageVO row, String pathTrimmed) {
        FileVO f = new FileVO();
        f.setFilePath(pathTrimmed);
        String nm = row.getProfileImgNm();
        if (CommonUtil.isEmpty(nm)) {
            int s = pathTrimmed.lastIndexOf('/');
            nm = s >= 0 ? pathTrimmed.substring(s + 1) : pathTrimmed;
        }
        f.setFileName(nm);
        return f;
    }

    /**
     * 마이페이지 프로필 미리보기 응답 맵. {@code reason} 이 null 이면 해당 키를 넣지 않는다.
     */
    private static Map<String, Object> profilePreviewResponse(String url, String fileName, Object reason) {
        Map<String, Object> m = new HashMap<>(4);
        m.put("viewType", "IMAGE");
        m.put("url", url == null ? "" : url);
        m.put("fileName", fileName == null ? "" : fileName);
        if (reason != null) {
            m.put("reason", reason);
        }
        return m;
    }

    /**
     * {@link FileServiceImpl#createViewPresignedUrlForStorageObject} 결과를 마이페이지 미리보기 형식으로 변환한다.
     * viewType 이 DOWNLOAD 인 경우 downloadUrl 은 쓰지 않고 url·reason 만 맞춘다.
     */
    private static Map<String, Object> toProfilePreviewOnlyResponse(Map<String, Object> raw) {
        if (raw == null) {
            return profilePreviewResponse("", "", "ERROR");
        }
        String vt = raw.get("viewType") != null ? raw.get("viewType").toString() : "";
        String fn = raw.get("fileName") != null ? raw.get("fileName").toString() : "";
        if ("IMAGE".equals(vt)) {
            String u = raw.get("url") != null ? raw.get("url").toString() : "";
            return profilePreviewResponse(u, fn, null);
        }
        if ("DOWNLOAD".equals(vt)) {
            return profilePreviewResponse("", fn, raw.get("reason"));
        }
        return profilePreviewResponse("", fn, "UNSUPPORTED_VIEW_TYPE");
    }

    private static String extractBaseFileName(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        int ls = t.lastIndexOf('/');
        int bs = t.lastIndexOf('\\');
        int cut = Math.max(ls, bs);
        return cut >= 0 ? t.substring(cut + 1) : t;
    }

    private static String sanitizeProfileFileName(String name) {
        if (name == null) {
            return "";
        }
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.contains("..")) {
            return "";
        }
        return s;
    }

    private static String buildProfileImageStorageKey(String userId, String safeFileName) {
        return PROFILE_STORAGE_PREFIX + "/" + userId.trim() + "/" + safeFileName;
    }

    private static boolean isValidProfileStoragePath(String userId, String path) {
        if (CommonUtil.isEmpty(path) || CommonUtil.isEmpty(userId)) {
            return false;
        }
        String p = path.trim();
        if (p.contains("..")) {
            return false;
        }
        String prefix = PROFILE_STORAGE_PREFIX + "/" + userId.trim() + "/";
        return p.startsWith(prefix) && p.length() > prefix.length();
    }

    private static boolean isImageFileName(String fileName) {
        if (CommonUtil.isEmpty(fileName)) {
            return false;
        }
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dotIdx + 1).toLowerCase();
        return "png".equals(ext)
                || "jpg".equals(ext)
                || "jpeg".equals(ext)
                || "webp".equals(ext)
                || "gif".equals(ext);
    }

    /**
     * 프로필 업로드 URL 만료시간을 1시간으로 설정
     */
    private Map<String, Object> createProfileUploadPresignedUrl(String key) {
        Date expiration = new Date(System.currentTimeMillis() + PROFILE_UPLOAD_EXPIRE_MILLIS);
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(getBucketName(), key)
                        .withMethod(HttpMethod.PUT)
                        .withExpiration(expiration);
        URL url = s3Client.generatePresignedUrl(request);
        Map<String, Object> result = new HashMap<>();
        result.put("uploadUrl", url.toString());
        result.put("filePath", key);
        return result;
    }

    private String getBucketName() {
        return PropertyUtil.getProperty("ncp.storage.bucket");
    }

}
