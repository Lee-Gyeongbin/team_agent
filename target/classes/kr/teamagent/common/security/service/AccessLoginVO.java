package kr.teamagent.common.security.service;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccessLoginVO {

	private String compId;
	private String userId;
	private String userNm;
	private String inType;
	private String occurDatetime;
	private String clientIp;
	private String status;
	private String failCount;
	private String token;

	private String seq;
	private String otpStatus;
	private String ipStatus;
}
