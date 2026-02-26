package kr.teamagent.common.secure.service;

import egovframework.rte.fdl.cryptography.EgovCryptoService;
import kr.teamagent.common.util.CommonUtil;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * 보안과 관련된 유틸 모음
 * @author mist
 *
 */
@Component
public class SecureService {

	Logger logger = org.slf4j.LoggerFactory.getLogger(SecureService.class);

	@Resource(name="ARIACryptoService")
	private EgovCryptoService cryptoService;

	@Autowired
	private CryptoKeyHolder cryptoKeyHolder;

	private static EgovCryptoService staticCryptoService;
	private static CryptoKeyHolder staticCryptoKeyHolder;

	@PostConstruct
    public void init() {
		/*System.getenv("");*/
		staticCryptoService = cryptoService;
		staticCryptoKeyHolder = cryptoKeyHolder;
    }

	public static String encryptUrlStr(String str) throws Exception {
		/*return str;*/

		String encryptUrlStr="";
		try {
			byte[] encrypted = staticCryptoService.encrypt(str.getBytes(StandardCharsets.UTF_8), staticCryptoKeyHolder.getKey());
			encryptUrlStr = new String(Base64.encodeBase64URLSafeString(encrypted));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.getCause();
		}
		return encryptUrlStr;
	}


	public static String encryptStr(String str) throws Exception {
		/*return str;*/

		String encryptedStr="";
		try {
			byte[] encrypted = staticCryptoService.encrypt(str.getBytes(StandardCharsets.UTF_8), staticCryptoKeyHolder.getKey());
			encryptedStr = new String(Base64.encodeBase64(encrypted), StandardCharsets.UTF_8);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.getCause();
		}
		return encryptedStr;

	}

	public static String decryptStr(String encryptedStr) {
		/*return encryptedStr;*/

		try {
			//암호문 평문 복호화
			if(CommonUtil.isEmpty(encryptedStr))
			{
				return encryptedStr;
			}

			//복호화안된평문 리턴
			String regex = "^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{4}|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{2}==)$";
			if(!encryptedStr.matches(regex)){
				return encryptedStr;
			}

			byte[] decrypted = staticCryptoService.decrypt(Base64.decodeBase64(encryptedStr.getBytes(StandardCharsets.UTF_8)), staticCryptoKeyHolder.getKey());
			return new String(decrypted, StandardCharsets.UTF_8).replaceAll("\\p{C}", "");
		} catch (Exception e){
			return encryptedStr;
		}

	}

	/*public static String decryptStr(String encryptedStr) {
		String decryptedStr="";
		byte[] encryptedStrConv = null;
		try {
			decryptedStr = "";
			if(CommonUtil.isEmpty(encryptedStr))
			{
				return encryptedStr;
			}
			encryptedStrConv = Base64.decodeBase64(encryptedStr.getBytes("UTF-8"));
			byte[] decrypted = staticCryptoService.decrypt(encryptedStrConv, PropertyUtil.getKey());
			decryptedStr = new String(decrypted,"UTF-8");
			if(!isCommon(decryptedStr))
			{
				return encryptedStr;
			}
		} catch (Exception e)
		{
			return encryptedStr;
		}
		return decryptedStr;
	}*/

	/*private static Boolean isCommon(String text) {

		Pattern pattern = Pattern.compile("^[0-9a-zA-Z가-힣 !@#$%^&*(),.?\\\":{}|<>]*$");
		Matcher matcher = pattern.matcher(text);

		return matcher.find();
	}*/

}