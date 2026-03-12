package kr.teamagent.common.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.List;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommonUtil {

	private static Logger logger = LoggerFactory.getLogger(CommonUtil.class);

	public static String RSA_INSTANCE = "RSA";
	public static String RSA_WEB_KEY = "_RSA_WEB_Key_";

	public static String nullToBlank(String s) {
		if (s == null) {
			return "";
		} else {
			return s;
		}
	}

	public static String nullToBlank(Object o) {
		return nullToBlank((String) o);
	}

	public static String removeNull(String s) {
		return nullToBlank(s);
	}

	public static String removeNull(Object s) {
		return nullToBlank((String) s);
	}

	public static String nvl(String s, String s2) {
		return removeNull(s, s2);
	}

	public static String removeNull(String s, String s2) {
		if (isEmpty(s)) {
			return s2;
		}
		return s;
	}

	public static String removeNullTrim(String s) {
		return nullToBlank(s).trim();
	}

	public static boolean isEmpty(String s) {
		return nullToBlank(s).trim().equals("");
	}

	public static boolean isNotEmpty(String s) {
		return !isEmpty(s);
	}

	public static boolean isEmpty(String[] s) {
		return (s == null || s.length == 0);
	}

	public static boolean isNotEmpty(String[] s) {
		return !isEmpty(s);
	}

	public static boolean isEmpty(List<?> list) {
		return (list == null || list.size() == 0);
	}

	public static boolean isNotEmpty(List<?> list) {
		return !isEmpty(list);
	}

	public static String escapeSqlString(String x) {
		StringBuilder sBuilder = new StringBuilder(x.length() * 11 / 10);

		int stringLength = x.length();

		for (int i = 0; i < stringLength; ++i) {
			char c = x.charAt(i);

			switch (c) {
				case 0:
					sBuilder.append('\\');
					sBuilder.append('0');
					break;
				case '\n':
					sBuilder.append('\\');
					sBuilder.append('n');
					break;
				case '\r':
					sBuilder.append('\\');
					sBuilder.append('r');
					break;
				case '\\':
					sBuilder.append('\\');
					sBuilder.append('\\');
					break;
				case '\'':
					sBuilder.append('\\');
					sBuilder.append('\'');
					break;
				case '"':
					sBuilder.append('\\');
					sBuilder.append('"');
					break;
				case '\032':
					sBuilder.append('\\');
					sBuilder.append('Z');
					break;
				case ';':
					break;
				case '\u00a5':
				case '\u20a9':
				default:
					sBuilder.append(c);
			}
		}

		return sBuilder.toString();
	}

	/**
	 * 사용자IP (공인 IP 우선 조회)
	 */
	public static String getUserIP(HttpServletRequest request) {
		String ip = request.getHeader("X-FORWARDED-FOR");
		if (ip == null) {
			ip = request.getHeader("Proxy-Client-IP");
		}
		if (ip == null) {
			ip = request.getHeader("WL-Proxy-Client-IP");
		}
		if (ip == null) {
			ip = request.getHeader("HTTP_CLIENT_IP");
		}
		if (ip == null) {
			ip = request.getHeader("HTTP_X_FORWARDED_FOR");
		}
		if (ip == null) {
			ip = request.getRemoteAddr();
		}

		String clientIp = CommonUtil.nullToBlank(ip).split("\\,")[0].trim();
		
		if (isPrivateIP(clientIp)) {
			String publicIp = getPublicIP();
			if (publicIp != null && !publicIp.isEmpty()) {
				logger.debug("내부 IP [{}]를 공인 IP [{}]로 변경", clientIp, publicIp);
				return publicIp;
			}
		}
		
		return clientIp;
	}
	
	private static boolean isPrivateIP(String ip) {
		if (isEmpty(ip)) {
			return false;
		}
		
		if ("0.0.0.0".equals(ip) || "127.0.0.1".equals(ip) || "localhost".equalsIgnoreCase(ip)) {
			return true;
		}
		
		if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
			return true;
		}
		
		Pattern privateIpPattern = Pattern.compile(
			"^(10\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.|192\\.168\\.|127\\.|localhost)"
		);
		
		return privateIpPattern.matcher(ip).find();
	}
	
	private static String getPublicIP() {
		try {
			java.net.URL url = new java.net.URL("https://api.ipify.org");
			java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(2000);
			conn.setReadTimeout(2000);
			
			try (java.io.BufferedReader reader = new java.io.BufferedReader(
					new java.io.InputStreamReader(conn.getInputStream()))) {
				String publicIp = reader.readLine();
				if (publicIp != null && !publicIp.isEmpty()) {
					return publicIp.trim();
				}
			}
		} catch (Exception e) {
			logger.debug("공인 IP 조회 실패, 내부 IP 사용: {}", e.getMessage());
			try {
				java.net.URL url = new java.net.URL("https://checkip.amazonaws.com");
				java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
				conn.setRequestMethod("GET");
				conn.setConnectTimeout(2000);
				conn.setReadTimeout(2000);
				
				try (java.io.BufferedReader reader = new java.io.BufferedReader(
						new java.io.InputStreamReader(conn.getInputStream()))) {
					String publicIp = reader.readLine();
					if (publicIp != null && !publicIp.isEmpty()) {
						return publicIp.trim();
					}
				}
			} catch (Exception e2) {
				logger.debug("공인 IP 조회 실패 (백업 서비스): {}", e2.getMessage());
			}
		}
		
		return null;
	}

	/**
	 * RSA 복호화
	 */
	public static String decryptRsa(PrivateKey privateKey, String securedValue) throws Exception {
		Cipher cipher = Cipher.getInstance(RSA_INSTANCE);
		byte[] encryptedBytes = hexToByteArray(securedValue);
		cipher.init(Cipher.DECRYPT_MODE, privateKey);
		byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
		String decryptedValue = new String(decryptedBytes, StandardCharsets.UTF_8);
		return decryptedValue;
	}

	public static byte[] hexToByteArray(String hex) {
		if (hex == null || hex.length() % 2 != 0) {
			return new byte[]{};
		}

		byte[] bytes = new byte[hex.length() / 2];
		for (int i = 0; i < hex.length(); i += 2) {
			byte value = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
			bytes[(int) Math.floor(i / 2)] = value;
		}
		return bytes;
	}

	public static boolean isAjaxCall(HttpServletRequest request) {
		return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
	}

	/**
	 * RSA 공개키/개인키 생성
	 */
	public static void initRsa(HttpServletRequest request) {
		HttpSession session = request.getSession();

		KeyPairGenerator generator;
		try {
			generator = KeyPairGenerator.getInstance(RSA_INSTANCE);
			generator.initialize(2048);

			KeyPair keyPair = generator.genKeyPair();
			KeyFactory keyFactory = KeyFactory.getInstance(RSA_INSTANCE);
			PublicKey publicKey = keyPair.getPublic();
			PrivateKey privateKey = keyPair.getPrivate();

			session.setAttribute(RSA_WEB_KEY, privateKey);

			RSAPublicKeySpec publicSpec = keyFactory.getKeySpec(publicKey, RSAPublicKeySpec.class);
			String publicKeyModulus = publicSpec.getModulus().toString(16);
			String publicKeyExponent = publicSpec.getPublicExponent().toString(16);

			request.setAttribute("RSAModulus", publicKeyModulus);
			request.setAttribute("RSAExponent", publicKeyExponent);
		} catch (GeneralSecurityException gse) {
			gse.getCause();
		} catch (Exception e) {
			e.getCause();
		}
	}

	public static boolean isProdServer(){
		String prodServerEnv = PropertyUtil.getProperty("Globals.env");
		boolean isProdServer = "prod".equals(prodServerEnv);
		logger.info("prod server : {}", isProdServer);
		return isProdServer;
	}

	public static String getDomainWithPort(HttpServletRequest request) {
	    StringBuilder domain = new StringBuilder(request.getScheme() + "://");
	    domain.append(request.getServerName());

	    int port = request.getServerPort();
	    if (port != 80 && port != 443) {
	        domain.append(":").append(port);
	    }

	    return domain.toString();
	}

	/**
	 * 테이블 키 생성 (업무명 2자리 + 숫자 6자리 = 총 8자리)
	 * @param businessPrefix 업무명 대문자 영어 2자리 (예: KC)
	 * @param lastId 해당 테이블의 마지막 ID (예: KC000001). null/empty면 000001부터 시작
	 * @return 8자리 키 (예: KC000002)
	 */
	public static String generateTableKey(String businessPrefix, String lastId) {
		String prefix = nullToBlank(businessPrefix).toUpperCase();
		if (prefix.length() > 2) {
			prefix = prefix.substring(0, 2);
		} else if (prefix.length() < 2) {
			prefix = String.format("%-2s", prefix).replace(' ', '0');
		}

		int nextNum = 1;
		if (isNotEmpty(lastId) && lastId.length() >= 2) {
			try {
				String numPart = lastId.substring(Math.max(0, lastId.length() - 6));
				nextNum = Integer.parseInt(numPart) + 1;
			} catch (NumberFormatException e) {
				logger.debug("generateTableKey lastId 파싱 실패, 1부터 시작: {}", lastId);
			}
		}

		return prefix + String.format("%06d", nextNum);
	}
}
