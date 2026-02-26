package kr.teamagent.common.security.service;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class OtpInfo implements Serializable {
	private static final long serialVersionUID = 9067275909814203218L;
	
	private String userId;
    private String otp;
    private LocalDateTime expireAt;
    private int failCount;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expireAt);
    }

    public void increaseFailCount() {
        this.failCount++;
    }
}
