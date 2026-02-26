package kr.teamagent.common.secure.service;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.lang.StringUtils;
import org.springframework.util.Base64Utils;

public class CryptoKeyHolder {

	private String key = "";
	private SecretKeySpec secureKey = null;
	private SecretKeySpec spec = null;

	public void setLocation(String path) {
		Properties masterKeyProp = null;
		Properties masterInfoProp = null;

		masterInfoProp = getProp(path,"classpath");

		if(masterInfoProp != null) {
			masterKeyProp = getProp(masterInfoProp.getProperty("security.crypto.masterPath"),"classpath");
			if(masterKeyProp != null) {
				this.spec = new SecretKeySpec(masterKeyProp.getProperty("key").getBytes(), "AES");
				this.key = decrypt(masterInfoProp.getProperty("security.crypto.key"));
				this.secureKey = new SecretKeySpec(decrypt(masterInfoProp.getProperty("security.crypto.key")).getBytes(),"AES");
			}
		}
	}

	private Properties getProp(String path,String option) {
		Properties prop = new Properties();
		if(StringUtils.equals(option, "classpath")) {
			try(Reader reader = new InputStreamReader(getClass().getResourceAsStream(path) )){
				prop = new Properties();
				prop.load(reader);
			}catch(IOException ioe) {
				//System.out.println("error!!!!");
			}
		}else {
			try(FileReader reader = new FileReader(path)){
				prop = new Properties();
				prop.load(reader);
			}catch(IOException ioe) {
				//System.out.println("error!!!!");
			}
		}

		return prop;
	}

	private String decrypt(String encStr) {
		String str = "";

		if(encStr == null || encStr.trim() == "") {
			//System.out.println("encrypt key do not have to be null!");
		}

		byte[] decTotByte = Base64Utils.decodeFromString(encStr);

		byte[] ivdec = new byte[16];
		byte[] encdec = new byte[decTotByte.length-ivdec.length];

		System.arraycopy(decTotByte, 0, ivdec, 0, ivdec.length);
		System.arraycopy(decTotByte, ivdec.length, encdec, 0, decTotByte.length-ivdec.length);

		Cipher cipherDec;
		try {
			cipherDec = Cipher.getInstance("AES/CBC/PKCS5Padding");
			IvParameterSpec specDec = new IvParameterSpec(ivdec);
			cipherDec.init(Cipher.DECRYPT_MODE,this.spec,specDec);
			byte[] decTot = cipherDec.doFinal(encdec);
			str = new String(decTot);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
			// TODO Auto-generated catch block
			e.getCause();
		}

		return str;
	}

	public String getKey() {
		return this.key;
	}
	public SecretKeySpec getSecureKey() {
		return this.secureKey;
	}



	@PostConstruct
	public void init() {
		//System.out.println("CryptoKeyHolder loaded");
	}

	@PreDestroy
	public void destory() {
		//System.out.println("CryptoKeyHolder destoryed !!!");
	}

}
