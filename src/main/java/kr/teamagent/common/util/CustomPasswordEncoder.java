package kr.teamagent.common.util;

import org.springframework.beans.factory.annotation.Required;

import egovframework.rte.fdl.cryptography.EgovPasswordEncoder;

public class CustomPasswordEncoder extends EgovPasswordEncoder{

	@Required
	@Override
	public void setHashedPassword(String hashedPassword) {
		// TODO Auto-generated method stub
		if(hashedPassword == null || hashedPassword=="") {
			super.setHashedPassword(KeyGenerate.getHashedKey());
		}else {
			super.setHashedPassword(null);
		}
	}
}
