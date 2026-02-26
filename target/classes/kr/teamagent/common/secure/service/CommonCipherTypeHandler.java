package kr.teamagent.common.secure.service;

import org.springframework.stereotype.Component;


/**
 * MYBATIS 암호화컬럼 타입핸들러 클래스
 * 공통 암복호화 핸들러
 * @author LG
 */
@Component
public class CommonCipherTypeHandler extends AbstractCipherTypeHandler {
	@Override
	protected boolean isCipher() {
		return true;
	}
}
