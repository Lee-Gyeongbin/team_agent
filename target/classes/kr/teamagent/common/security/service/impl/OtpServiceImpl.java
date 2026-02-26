package kr.teamagent.common.security.service.impl;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpSession;

import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.security.service.OtpInfo;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.RestApiManager;

@Service
public class OtpServiceImpl {
	public final Logger log = LoggerFactory.getLogger(this.getClass());

	private static String API_KEY = PropertyUtil.getProperty("send.api.key");
	private static String API_URL = PropertyUtil.getProperty("send.api.url");
	private static String SENDER_KEY = PropertyUtil.getProperty("send.api.sender.key");
	private static String TEMPLATE_CODE = PropertyUtil.getProperty("send.api.template.code");
	private static final String RESULT_API_URL = PropertyUtil.getProperty("send.api.url.result");
	private static final int OTP_EXPIRE_MIN = 3;
	private static final int MAX_FAIL_COUNT = 3;
	private static final String OTP_SESSION_PREFIX = "OTP_";

	private final HttpSession session;

	public OtpServiceImpl(HttpSession session) {
		this.session = session;
	}
	
	@Autowired
	RestApiManager restApiManager;

	/**
	 * OTP 발급
	 */
	public void issueOtp(UserVO userVO) {
		String otp = generateOtp();

		OtpInfo otpInfo = new OtpInfo();
		otpInfo.setUserId(userVO.getUserId());

		if (!CommonUtil.isProdServer()) {
			// 개발일 경우 111111 고정
			otpInfo.setOtp("111111");
		} else {
			otpInfo.setOtp(otp);
			try {
//				// userCode 초기화
//				resultApiMessage(RESULT_API_URL, API_KEY, userVO.getPhone());
				// 카카오톡 발송
				sendApiMessage(API_URL, SENDER_KEY, API_KEY, userVO, otpInfo);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		otpInfo.setExpireAt(LocalDateTime.now().plusMinutes(OTP_EXPIRE_MIN));
		otpInfo.setFailCount(0);

		session.setAttribute(getSessionKey(userVO.getUserId()), otpInfo);

		log.info("[OTP 발급] userId={}, otp={}", userVO.getUserId(), otp);
	}

	/**
	 * 알림톡 API 발송
	 * @param apiKey 
	 * @param senderKey 
	 * @param apiUrl 
	 * @return
	 * @throws Exception
	 */
	private JSONObject sendApiMessage(String apiUrl, String senderKey, String apiKey, UserVO userVO, OtpInfo otpInfo) {
		try {
			Map<String, String> header = new HashMap<>();
			header.put("sejongApiKey", apiKey);
			header.put("Content-Type", "multipart/form-data");

			// body JSON 세팅
			Map<String, String> body = new HashMap<>();
			body.put("messageType", "AT");
			body.put("userKey", "S"+userVO.getPhone().replaceAll("-", ""));
			body.put("senderKey", senderKey);
			body.put("receiverTelNo", userVO.getPhone().replaceAll("-", ""));
			body.put("templateCode", TEMPLATE_CODE);
			body.put("contents", "[CMB ERP]\r\n" + 
					"휴대폰 인증번호는[" + otpInfo.getOtp() + "]입니다.\r\n" + 
					"인증번호를 정확히 입력해주세요.");
			
			// API 연결 (POST Form Data)
			JSONObject apiResponse = restApiManager.postResponseFormJson(apiUrl, body, header);
			
			String code = String.valueOf(apiResponse.get("code"));
			// 중복 userKey → 초기화 후 재시도
	        if ("S405".equals(code)) {
	            resultApiMessage(RESULT_API_URL, apiKey, userVO.getPhone());
	            // 재호출
	            apiResponse = restApiManager.postResponseFormJson(apiUrl, body, header);
	            code = String.valueOf(apiResponse.get("code"));
	        }

			// 결과 code/msg 확인 
			if (!"200".equals(code)) {
				throw new Exception(apiResponse.get("code").toString() + " : " + apiResponse.get("message").toString());
			} else {
				return apiResponse;
			}
		} catch (Exception e) {
			log.error("API 통신 실패", e);
		}
		return null;
	}

	/**
	 * 알림톡 API 확인
	 * @param apiKey 
	 * @param senderKey 
	 * @param apiUrl 
	 * @return
	 * @throws Exception
	 */
	private JSONObject resultApiMessage(String apiUrl, String apiKey, String userPhone) {
		try {
			Map<String, String> header = new HashMap<>();
			header.put("sejongApiKey", apiKey);
			
			// API 결과 확인 (GET)
			Map<String, String> resultHeader = new HashMap<>();
			resultHeader.put("sejongApiKey", apiKey);
			JSONObject apiResponse = restApiManager.getResponseJson(apiUrl + "?sendCode=" + "S" + userPhone.replaceAll("-", ""), header);

			// 결과 code/msg 확인 
			if (!"200".equals(apiResponse.get("code").toString())) {
				throw new Exception(apiResponse.get("code").toString() + " : " + apiResponse.get("message").toString());
			} else {
				return apiResponse;
			}
		} catch (Exception e) {
			log.error("API 통신 실패", e);
		}
		return null;
	}

	/**
	 * OTP 검증 (다음 단계에서 완성)
	 * 999 성공
	 * 001 OTP 정보 없음 / OTP 3분 시간 만료
	 * 002 OTP 실패
	 * 003 OTP 실패 회수 초과
	 */
	public String verifyOtp(UserVO user, String inputOtp) {
		OtpInfo otpInfo = getOtpInfo(user.getUserId());
		
		if (otpInfo == null) {
			return "001";
		}

		// OTP 시간 만료
		if (otpInfo.isExpired()) {
			clearOtp(user.getUserId());
			return "001";
		}

		// OTP 실패 횟수 추가
		if (!otpInfo.getOtp().equals(inputOtp) && MAX_FAIL_COUNT > otpInfo.getFailCount()) {
			otpInfo.increaseFailCount();
			// OTP 실패 횟수 초과
			if (MAX_FAIL_COUNT == otpInfo.getFailCount()) {
				return "003";
			} else {
				return "002";
			}
		}

		if (CommonUtil.isProdServer()) {
			// userCode 초기화
			resultApiMessage(RESULT_API_URL, API_KEY, user.getPhone());
		}

		return "999";
	}

	/**
	 * OTP 실패횟수 조회
	 */
	public int getFailCount(UserVO user) {
		return getOtpInfo(user.getUserId()).getFailCount();
	}

	/**
	 * OTP 제거
	 */
	public void clearOtp(String userId) {
		session.removeAttribute(getSessionKey(userId));
	}

	private OtpInfo getOtpInfo(String userId) {
		return (OtpInfo) session.getAttribute(getSessionKey(userId));
	}

	private String generateOtp() {
		return String.format("%06d", new SecureRandom().nextInt(1_000_000));
	}

	private String getSessionKey(String userId) {
		return OTP_SESSION_PREFIX + userId;
	}
}