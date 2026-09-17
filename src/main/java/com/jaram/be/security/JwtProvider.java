package com.jaram.be.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtProvider {

    /** 토큰은 신원만 싣는다. 권한은 요청 시점에 DB 에서 읽는다 — 임기를 거두면 즉시 반영된다. */
    public record JwtClaims(String memberId, String name, String email, Instant issuedAt) { }

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtProvider(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.ttl-seconds}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String generate(String memberId, String name, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(memberId)
                .claim("name", name)
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    public JwtClaims parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new JwtClaims(
                c.getSubject(),
                c.get("name", String.class),
                c.get("email", String.class),
                c.getIssuedAt().toInstant());
    }
}
