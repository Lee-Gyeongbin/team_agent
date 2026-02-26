package kr.teamagent.common.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;

import egovframework.rte.fdl.cryptography.EgovARIACryptoService;
import egovframework.rte.fdl.cryptography.EgovPasswordEncoder;
import egovframework.rte.fdl.cryptography.impl.ARIACipher;
import egovframework.rte.fdl.cryptography.impl.EgovARIACryptoServiceImpl;
import egovframework.rte.fdl.logging.util.EgovResourceReleaser;

import java.math.BigDecimal;

public class CustomAriaCrypto implements EgovARIACryptoService {

	private final Base64 base64 = new Base64();

	private static final Logger LOGGER = LoggerFactory.getLogger(EgovARIACryptoServiceImpl.class);

	private static final int DEFAULT_BLOCKSIZE = 1024;
	private static final int BLOCKSIZE_MODULAR = 16;

	private EgovPasswordEncoder passwordEncoder;
	private int blockSize = DEFAULT_BLOCKSIZE;

	@Required
	public void setPasswordEncoder(EgovPasswordEncoder passwordEncoder) {
		this.passwordEncoder = passwordEncoder;
		LOGGER.debug("passwordEncoder's algorithm : {}", passwordEncoder.getAlgorithm());
	}

	public void setBlockSize(int blockSize) {
		if (blockSize % BLOCKSIZE_MODULAR != 0) {
			blockSize += (BLOCKSIZE_MODULAR - blockSize % BLOCKSIZE_MODULAR);
		}
		this.blockSize = blockSize;
	}

	public BigDecimal encrypt(BigDecimal number, String password) {
		throw new UnsupportedOperationException("Unsupported method.. (ARIA Cryptography service doesn't support BigDecimal en/decryption)");
	}

	public byte[] encrypt(byte[] data, String password) {
		ARIACipher cipher = new ARIACipher();
		cipher.setPassword(password);
		return cipher.encrypt(data);
	}

	public void encrypt(File srcFile, String password, File trgtFile) throws IOException {
		FileInputStream fis = null;
		FileWriter fw = null;
		BufferedInputStream bis = null;
		BufferedWriter bw = null;

		byte[] buffer = null;

		if (passwordEncoder.checkPassword(password)) {
			ARIACipher cipher = new ARIACipher();
			cipher.setPassword(password);
			buffer = new byte[blockSize];

			try {
				fis = new FileInputStream(srcFile);
				bis = new BufferedInputStream(fis);
				fw = new FileWriter(trgtFile);
				bw = new BufferedWriter(fw);

				byte[] encrypted = null;
				int length = 0;
				while ((length = bis.read(buffer)) >= 0) {
					if (length < blockSize) {
						byte[] tmp = new byte[length];
						System.arraycopy(buffer, 0, tmp, 0, length);
						encrypted = cipher.encrypt(tmp);
					} else {
						encrypted = cipher.encrypt(buffer);
					}
					String line;
					try {
						line = new String(base64.encode(encrypted), StandardCharsets.US_ASCII);
					} catch (Exception ee) {
						throw new RuntimeException(ee);
					}
					bw.write(line);
					bw.newLine();
				}
				bw.flush();
			} finally {
				EgovResourceReleaser.close(fw, bw, fis, bis);
			}
		} else {
			throw new IllegalArgumentException("password not matched!!!");
		}
	}

	public BigDecimal decrypt(BigDecimal encryptedNumber, String password) {
		throw new UnsupportedOperationException("Unsupported method.. (ARIA Cryptography service doesn't support BigDecimal en/decryption)");
	}

	public byte[] decrypt(byte[] encryptedData, String password) {
		ARIACipher cipher = new ARIACipher();
		cipher.setPassword(password);
		return cipher.decrypt(encryptedData);
	}

	public void decrypt(File encryptedFile, String password, File trgtFile) throws IOException {
		FileReader fr = null;
		FileOutputStream fos = null;
		BufferedReader br = null;
		BufferedOutputStream bos = null;

		if (passwordEncoder.checkPassword(password)) {
			ARIACipher cipher = new ARIACipher();
			cipher.setPassword(password);

			try {
				fr = new FileReader(encryptedFile);
				br = new BufferedReader(fr);
				fos = new FileOutputStream(trgtFile);
				bos = new BufferedOutputStream(fos);

				byte[] encrypted = null;
				byte[] decrypted = null;
				String line = null;

				while ((line = br.readLine()) != null) {
					try {
						encrypted = base64.decode(line.getBytes(StandardCharsets.US_ASCII));
					} catch (Exception de) {
						throw new RuntimeException(de);
					}
					decrypted = cipher.decrypt(encrypted);
					bos.write(decrypted);
				}
				bos.flush();
			} finally {
				EgovResourceReleaser.close(fos, bos, fr, br);
			}
		} else {
			throw new IllegalArgumentException("password not matched!!!");
		}
	}

}
