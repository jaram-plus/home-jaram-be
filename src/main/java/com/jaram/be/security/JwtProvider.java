package com.jaram.be.security;

import com.jaram.be.member.Authority;
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

    public record JwtClaims(String memberId, String name, String email, Authority authority) { }

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtProvider(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.ttl-seconds}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String generate(String memberId, String name, String email, Authority authority) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(memberId)
                .claim("name", name)
                .claim("email", email)
                .claim("authority", authority.name())
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
                Authority.valueOf(c.get("authority", String.class)));
    }
}
