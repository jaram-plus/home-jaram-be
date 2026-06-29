package com.jaram.be.security;

import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
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
    void adminEndpointWithMemberTokenReturns403Forbidden() {
        String token = jwt.generate("m1", "n", "e@hanyang.ac.kr", Authority.MEMBER);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/members/pending")
                .then().statusCode(403).body("code", equalTo("FORBIDDEN"));
    }

    @Test
    void preflightFromFrontendOriginIsAllowed() {
        given()
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .when().options("/api/auth/login")
                .then().statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo("http://localhost:5173"));
    }
}
