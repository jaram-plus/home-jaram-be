package com.jaram.be.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingResetMailSender implements ResetMailSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingResetMailSender.class);

    /**
     * 토큰은 INFO 로 남기지 않는다. 이 스텁이 유일한 구현체라 배포 환경에서도 동작하는데,
     * 로그를 읽을 수 있는 사람이 임의 계정의 재설정 토큰(수명 30분)을 주워 계정을 가져갈
     * 수 있었다. 로컬에서 토큰이 필요하면 이 로거만 DEBUG 로 올린다 — 배포 로그 레벨을
     * DEBUG 로 두면 다시 노출된다는 뜻이기도 하다.
     */
    @Override
    public void send(String email, String token) {
        log.info("[password-reset] issued a reset token for {} (token not logged)", mask(email));
        log.debug("[password-reset] token for {}: {}", email, token);
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at < 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
