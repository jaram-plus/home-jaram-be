package com.jaram.be.auth;

import com.jaram.be.member.MemberRepository;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignupTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    private Map<String, Object> valid() {
        return Map.of("name", "홍길동", "studentId", "2023012345",
                "email", "hong@hanyang.ac.kr", "password", "passw0rd!");
    }

    @Test
    void signupReturns201() {
        given().contentType("application/json").body(valid())
                .when().post("/api/auth/signup")
                .then().statusCode(201);
    }

    @Test
    void duplicateEmailReturns409EmailTaken() {
        given().contentType("application/json").body(valid()).post("/api/auth/signup");
        var second = Map.of("name", "김철수", "studentId", "2023099999",
                "email", "hong@hanyang.ac.kr", "password", "passw0rd!");
        given().contentType("application/json").body(second)
                .when().post("/api/auth/signup")
                .then().statusCode(409).body("code", equalTo("EMAIL_TAKEN"));
    }

    @Test
    void nonHanyangEmailReturns422() {
        var bad = Map.of("name", "홍길동", "studentId", "2023012345",
                "email", "hong@gmail.com", "password", "passw0rd!");
        given().contentType("application/json").body(bad)
                .when().post("/api/auth/signup")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }
}
