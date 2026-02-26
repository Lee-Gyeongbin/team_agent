package kr.teamagent.common.util;

import org.springframework.util.Base64Utils;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileReader;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

public class KeyGenerate {

    private static SecretKeySpec spec = null;

	public static String getKey() {

		if(KeyGenerate.spec == null) {
			KeyGenerate.getMasterKey();
		}

		String key = "";
		FileReader fr = null;
		try {
			Properties properties = new Properties();
			fr = new FileReader(decrypt(getPathEnc()));
			properties.load(fr);
			key = decrypt(properties.getProperty("key"));

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.getCause();
		}finally {
			try {
				if(fr != null)fr.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.getCause();
			}
		}
		return key;
	}

	public static String getHashedKey() {

		if(KeyGenerate.spec == null) {
			KeyGenerate.getMasterKey();
		}

		String key = "";
		FileReader fr = null;
		try {
			Properties properties = new Properties();
			fr = new FileReader(decrypt(getPathEnc()));
			properties.load(fr);
			key = properties.getProperty("hashedkey");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.getCause();
		}finally {
			try {
				if(fr != null)fr.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.getCause();
			}
		}
		return key;
	 }

	/*
	private static String decrypt(String encStr) {
		String str = "";

		if(str == null || str.trim() == "") {
			System.out.println("encrypt key do not have to be null!");
		}

		byte[] decTotByte = Base64Utils.decodeFromString(encStr);

		byte[] ivdec = new byte[16];
		byte[] passwddec = new byte[24];
		byte[] encdec = new byte[decTotByte.length-ivdec.length-passwddec.length];

		int indexDec = 0;
		int eidxDec=0;
		int iidxDec=0;
		int pidxDec=0;

		while(indexDec < decTotByte.length) {
			if(indexDec == 0 || indexDec%2 == 0 || pidxDec >= passwddec.length) {
				if(iidxDec < ivdec.length) {
					//System.out.println("iidxDec : count("+iidxDec+") - " + decTotByte[indexDec]);
					ivdec[iidxDec] = decTotByte[indexDec];
					iidxDec++;
				}else {
					//System.out.println("iidxDec : count("+eidxDec+") - " + decTotByte[indexDec]);
					encdec[eidxDec] = decTotByte[indexDec];
					eidxDec++;
				}
			}else if(indexDec%2 == 1) {
				//System.out.println("pidxDec : count("+pidxDec+") - " + decTotByte[indexDec]);
				passwddec[pidxDec] = decTotByte[indexDec];
				pidxDec++;
			}
			indexDec++;
		}

		Cipher cipherDec;
		try {
			cipherDec = Cipher.getInstance("AES/CBC/PKCS5Padding");
			SecretKeySpec keySpecDec = new SecretKeySpec(Base64.getDecoder().decode(passwddec), "AES");
			IvParameterSpec specDec = new IvParameterSpec(ivdec);
			cipherDec.init(Cipher.DECRYPT_MODE,keySpecDec,specDec);
			byte[] decTot = cipherDec.doFinal(encdec);
			str = new String(decTot);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
			// TODO Auto-generated catch block
			e.getCause();
		}

		return str;
	}
	*/

	private static String decrypt(String encStr) {
		String str = "";

		if(encStr == null || encStr.trim() == "") {
			System.out.println("encrypt key do not have to be null!");
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
			cipherDec.init(Cipher.DECRYPT_MODE,KeyGenerate.spec,specDec);
			byte[] decTot = cipherDec.doFinal(encdec);
			str = new String(decTot);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
			// TODO Auto-generated catch block
			e.getCause();
		}

		return str;
	}

	private static String getPathEnc() {
		if(!"".equals(CommonUtil.nullToBlank(System.getenv("encdecKeyPath")))) {
			return System.getenv("encdecKeyPath");
		}else if(!"".equals(CommonUtil.nullToBlank(System.getProperty("encdecKeyPath")))){
			return System.getProperty("encdecKeyPath");
		}else {
			return null;
		}
	}

	private static void getMasterKey() {
		System.out.println("load - masterkey");

		String root = "";

		if(!"".equals(CommonUtil.nullToBlank(System.getenv("masterKeyPath")))) {

			System.out.println("getenv !!!!!!" );
			System.out.println("masterkeypath : "+System.getenv("masterKeyPath") );
			root = System.getenv("masterKeyPath");
		}else if(!"".equals(CommonUtil.nullToBlank(System.getProperty("masterKeyPath")))){

			System.out.println("getProperty !!!!!!" );
			System.out.println("masterkeypath : "+System.getProperty("masterKeyPath") );
			root = System.getProperty("masterKeyPath");
		}

		FileReader fr = null;
		try {
			Properties properties = new Properties();
			fr = new FileReader(root);
			properties.load(fr);

			KeyGenerate.spec = null;
			if(properties.getProperty("key") != null)
			{
				byte[] key = properties.getProperty("key").getBytes();
				KeyGenerate.spec = new SecretKeySpec(key, "AES");
			}

			/* 운영 환견에서는 주석 제거
		    if(KeyGenerate.spec != null) {
				f = new File(root);
				if(f.exists()) {
					f.delete();
				}
			}
			*/

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.getCause();
		} finally {
			try {
				if(fr != null)fr.close();
			} catch (IOException e) {
				e.getCause();
			}
		}
	}

}
