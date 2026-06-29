package com.jaram.be.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingResetMailSender implements ResetMailSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingResetMailSender.class);

    @Override
    public void send(String email, String token) {
        log.info("[password-reset] would email {} a reset link with token {}", email, token);
    }
}
