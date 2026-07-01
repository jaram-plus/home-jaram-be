package com.jaram.be.auth;

import com.jaram.be.member.MemberRepository;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.HashMap;
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
        Map<String, Object> m = new HashMap<>();
        m.put("name", "홍길동");
        m.put("studentId", "2023012345");
        m.put("email", "hong@hanyang.ac.kr");
        m.put("password", "passw0rd!");
        m.put("gen", "41");
        m.put("faculty", "컴퓨터학부");
        m.put("phone", "010-1234-5678");
        m.put("enrolled", true);
        return m;
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
        Map<String, Object> second = valid();
        second.put("name", "김철수");
        second.put("studentId", "2023099999");
        given().contentType("application/json").body(second)
                .when().post("/api/auth/signup")
                .then().statusCode(409).body("code", equalTo("EMAIL_TAKEN"));
    }

    @Test
    void nonHanyangEmailReturns422() {
        Map<String, Object> bad = valid();
        bad.put("email", "hong@gmail.com");
        given().contentType("application/json").body(bad)
                .when().post("/api/auth/signup")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void missingNewFieldsReturns422() {
        Map<String, Object> bad = valid();
        bad.remove("gen");
        bad.remove("faculty");
        bad.remove("phone");
        bad.remove("enrolled");
        given().contentType("application/json").body(bad)
                .when().post("/api/auth/signup")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }
}
