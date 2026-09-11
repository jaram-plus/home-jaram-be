package com.jaram.be.site;

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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * 푸터의 외부 링크 공개 읽기. 임원이 설정한 값을 비로그인 방문자가 그대로 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteLinksTest extends PostgresTest {

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
    void anonymousReadsWhatOfficerSaved() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body("""
                      {"links": {"github": "https://github.com/jaram-plus",
                                 "instagram": "https://www.instagram.com/jaram",
                                 "blog": null, "discord": "https://discord.gg/jaram"}}
                      """)
                .when().patch("/api/admin/settings").then().statusCode(200);

        given().when().get("/api/site/links")
                .then().statusCode(200)
                .body("github", equalTo("https://github.com/jaram-plus"))
                .body("instagram", equalTo("https://www.instagram.com/jaram"))
                .body("blog", nullValue())
                .body("discord", equalTo("https://discord.gg/jaram"));
    }

    /** 설정이 아직 없어도 푸터는 그려져야 한다 — 404 가 아니라 빈 링크 모음으로 답한다. */
    @Test
    void returnsNullsWhenNothingConfigured() {
        given().when().get("/api/site/links")
                .then().statusCode(200)
                .body("github", nullValue())
                .body("instagram", nullValue())
                .body("blog", nullValue())
                .body("discord", nullValue());
    }

    /** 아무나 부를 수 있는 읽기라 설정 로우를 만들지 않는다. */
    @Test
    void readDoesNotCreateSettingsRow() {
        given().when().get("/api/site/links").then().statusCode(200);

        assertThat(repo.count()).isZero();
    }
}
