package com.jaram.be.security;

import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import com.jaram.be.member.Authority;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityAccessTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired JwtProvider jwt;

    @BeforeEach void setup() { RestAssured.port = port; }

    @Test
    void adminEndpointWithoutTokenReturns401WithEnvelope() {
        given().when().get("/api/admin/members/pending")
                .then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
    }

    @Test
    @Disabled("enabled in Task 9")
    void adminEndpointWithMemberTokenReturns403Forbidden() {
        String token = jwt.generate("m1", "n", "e@hanyang.ac.kr", Authority.MEMBER);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/members/pending")
                .then().statusCode(403).body("code", equalTo("FORBIDDEN"));
    }
}
