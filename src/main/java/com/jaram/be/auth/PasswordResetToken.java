package com.jaram.be.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_reset_token",
       uniqueConstraints = @UniqueConstraint(columnNames = "token"))
public class PasswordResetToken {

    @Id
    private String id;
    private String memberId;
    private String token;
    private Instant expiresAt;
    private Instant usedAt;

    protected PasswordResetToken() { }

    public static PasswordResetToken issue(String memberId, String token, Instant expiresAt) {
        PasswordResetToken t = new PasswordResetToken();
        t.id = UUID.randomUUID().toString();
        t.memberId = memberId;
        t.token = token;
        t.expiresAt = expiresAt;
        return t;
    }

    public boolean isConsumable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void consume(Instant now) { this.usedAt = now; }

    public String getMemberId() { return memberId; }
    public String getToken() { return token; }
}
