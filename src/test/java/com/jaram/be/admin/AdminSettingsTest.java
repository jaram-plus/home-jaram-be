package com.jaram.be.admin;

import com.jaram.be.member.Authority;
import com.jaram.be.security.JwtProvider;
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
class AdminSettingsTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired JwtProvider jwt;
    @Autowired AdminSettingsRepository repo;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        repo.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
    }

    @Test
    void getReturnsDefaultsWhenUnset() {
        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/settings")
                .then().statusCode(200)
                .body("autoPromote", equalTo(false))
                .body("driveConnected", equalTo(false));
    }

    @Test
    void patchUpdatesProvidedFieldsOnly() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("semester", "2026-2학기", "currentCohort", 42, "autoPromote", true))
                .when().patch("/api/admin/settings")
                .then().statusCode(200)
                .body("semester", equalTo("2026-2학기"))
                .body("currentCohort", equalTo(42))
                .body("autoPromote", equalTo(true));
    }

    @Test
    void memberIsForbidden() {
        String memberToken = jwt.generate("m1", "회원", "m@hanyang.ac.kr", Authority.MEMBER);
        given().header("Authorization", "Bearer " + memberToken)
                .when().get("/api/admin/settings")
                .then().statusCode(403);
    }
}
