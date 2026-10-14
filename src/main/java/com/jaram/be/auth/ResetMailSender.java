package com.jaram.be.auth;

public interface ResetMailSender {
    void send(String email, String token);
}
