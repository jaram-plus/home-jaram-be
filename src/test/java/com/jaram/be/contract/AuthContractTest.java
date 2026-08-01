package com.jaram.be.contract;

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthContractTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;

    private final OpenApiValidationFilter validation =
            new OpenApiValidationFilter("openapi/openapi.yaml");

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @Test
    void signupResponseMatchesContract() {
        given().filter(validation)
                .contentType("application/json")
                .body(Map.of("name", "홍길동", "studentId", "2023012345",
                        "email", "hong@hanyang.ac.kr", "password", "passw0rd!",
                        "gen", 41, "faculty", "컴퓨터학부",
                        "phone", "010-1234-5678", "enrolled", true))
                .when().post("/api/auth/signup")
                .then().statusCode(201);
    }

    @Test
    void loginErrorResponseMatchesContract() {
        given().filter(validation)
                .contentType("application/json")
                .body(Map.of("email", "ghost@hanyang.ac.kr", "password", "passw0rd!"))
                .when().post("/api/auth/login")
                .then().statusCode(404);
    }
}
