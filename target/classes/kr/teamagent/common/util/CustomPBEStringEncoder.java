package kr.teamagent.common.util;

import org.jasypt.encryption.pbe.config.EnvironmentStringPBEConfig;
import org.jasypt.exceptions.PasswordAlreadyCleanedException;

import kr.teamagent.common.secure.service.CryptoKeyHolder;

public class CustomPBEStringEncoder extends EnvironmentStringPBEConfig {


	private char[] password = null;
	private boolean passwordCleaned = false;
	private CryptoKeyHolder keyHolder = null;

	@Override
	public char[] getPasswordCharArray() {

		this.password = this.keyHolder.getKey().toCharArray();

		// TODO Auto-generated method stub
		if (this.passwordCleaned) {
            throw new PasswordAlreadyCleanedException();
        }
        final char[] result = new char[this.password.length];
        System.arraycopy(this.password, 0, result, 0, this.password.length);
        return result;
	}

	@Override
	public void cleanPassword() {
		if (this.password != null) {
            final int pwdLength = this.password.length;
            for (int i = 0; i < pwdLength; i++) {
                this.password[i] = (char)0;
            }
            this.password = new char[0];
        }
        this.passwordCleaned = true;
	}

	public void setKeyClass(CryptoKeyHolder keyHolder) {
		this.keyHolder = keyHolder;
	}


	/*@PostConstruct
	public void initialize() {

		System.out.println("KeyGenerate.getKey() : "+KeyGenerate.getKey());

		this.password =  KeyGenerate.getKey().toCharArray();
	}*/

}