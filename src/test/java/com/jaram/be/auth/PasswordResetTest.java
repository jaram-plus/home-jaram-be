package com.jaram.be.auth;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasswordResetTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired PasswordResetTokenRepository tokens;
    @Autowired PasswordEncoder encoder;

    @BeforeEach void setup() {
        RestAssured.port = port;
        tokens.deleteAll();
        members.deleteAll();
    }

    @Test
    void resetRequestAlwaysReturns200EvenForUnknownEmail() {
        given().contentType("application/json").body(Map.of("email", "ghost@hanyang.ac.kr"))
                .when().post("/api/auth/password/reset-request")
                .then().statusCode(200);
    }

    @Test
    void resetConfirmChangesPassword() {
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", encoder.encode("oldpass!9"));
        m.setStatus(MemberStatus.ACTIVE);
        members.save(m);
        var t = PasswordResetToken.issue(m.getId(), "tok-abc", Instant.now().plus(30, ChronoUnit.MINUTES));
        tokens.save(t);

        given().contentType("application/json")
                .body(Map.of("token", "tok-abc", "password", "newpass!9"))
                .when().post("/api/auth/password/reset")
                .then().statusCode(200);

        Member updated = members.findById(m.getId()).orElseThrow();
        assertThat(encoder.matches("newpass!9", updated.getPasswordHash())).isTrue();
    }

    @Test
    void resetConfirmWithBadTokenReturns400() {
        given().contentType("application/json")
                .body(Map.of("token", "nope", "password", "newpass!9"))
                .when().post("/api/auth/password/reset")
                .then().statusCode(400).body("code", equalTo("INVALID"));
    }
}
