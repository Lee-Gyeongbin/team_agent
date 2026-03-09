package kr.teamagent.common.security.service;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccessLoginVO {

	private String dbId;
	private Long logId;
	private String userId;
	private String loginTp;
	private String accessTp;
	private String ipAddr;
	private String userAgent;
	private String result;
	private String failRson;
	private int failCnt;
	private String token;
	private String otpStatus;
	private String ipStatus;
	private String createDt;
}
