package com.jaram.be.contract;

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.jaram.be.admin.AdminSettingsRepository;
import com.jaram.be.member.Authority;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteContractTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired JwtProvider jwt;
    @Autowired AdminSettingsRepository repo;

    private final OpenApiValidationFilter validation =
            new OpenApiValidationFilter("openapi/openapi.yaml");

    @BeforeEach void setup() {
        RestAssured.port = port;
        repo.deleteAll();
    }

    @Test
    void siteLinksMatchesContract() {
        given().header("Authorization", "Bearer "
                        + jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER))
                .contentType("application/json")
                .body("""
                      {"links": {"github": "https://github.com/jaram-plus", "instagram": null,
                                 "blog": null, "discord": "https://discord.gg/jaram"}}
                      """)
                .when().patch("/api/admin/settings").then().statusCode(200);

        given().filter(validation)
                .when().get("/api/site/links")
                .then().statusCode(200);
    }

    /** 등록된 채널이 하나도 없는 상태 — 네 칸이 모두 null 이어도 계약을 만족한다. */
    @Test
    void emptySiteLinksMatchesContract() {
        given().filter(validation)
                .when().get("/api/site/links")
                .then().statusCode(200);
    }
}
