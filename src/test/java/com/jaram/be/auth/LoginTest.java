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

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    private Member active(String email) {
        Member m = Member.newPending("홍길동", "2023012345", email, encoder.encode("passw0rd!"));
        m.setStatus(MemberStatus.ACTIVE);
        return members.save(m);
    }

    @Test
    void loginSuccessReturnsTokenAndUser() {
        active("hong@hanyang.ac.kr");
        given().contentType("application/json")
                .body(Map.of("email", "hong@hanyang.ac.kr", "password", "passw0rd!"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .body("accessToken", notNullValue())
                .body("user.email", equalTo("hong@hanyang.ac.kr"))
                .body("user.authority", equalTo("MEMBER"));
    }

    @Test
    void unknownEmailReturns404() {
        given().contentType("application/json")
                .body(Map.of("email", "nobody@hanyang.ac.kr", "password", "passw0rd!"))
                .when().post("/api/auth/login")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    @Test
    void pendingMemberReturns403() {
        members.save(Member.newPending("대기", "2023011111", "wait@hanyang.ac.kr", encoder.encode("passw0rd!")));
        given().contentType("application/json")
                .body(Map.of("email", "wait@hanyang.ac.kr", "password", "passw0rd!"))
                .when().post("/api/auth/login")
                .then().statusCode(403).body("code", equalTo("PENDING"));
    }

    @Test
    void wrongPasswordReturns401() {
        active("hong@hanyang.ac.kr");
        given().contentType("application/json")
                .body(Map.of("email", "hong@hanyang.ac.kr", "password", "wrongpass!9"))
                .when().post("/api/auth/login")
                .then().statusCode(401).body("code", equalTo("INVALID"));
    }
}
