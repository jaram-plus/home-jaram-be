package com.jaram.be.admin;

import com.jaram.be.common.ClubTime;
import com.jaram.be.member.Authority;
import com.jaram.be.member.Gen;
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
import static org.hamcrest.Matchers.nullValue;

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
                .body("driveConnected", equalTo(false))
                .body("semesterYear", equalTo(ClubTime.today().getYear()))
                .body("semesterTerm", equalTo(autoTerm()))
                .body("semesterTermAuto", equalTo(true))
                .body("currentGen", equalTo(Gen.current()))
                .body("links.github", nullValue())
                .body("links.instagram", nullValue())
                .body("links.blog", nullValue())
                .body("links.discord", nullValue());
    }

    @Test
    void patchReplacesLinks() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body("""
                      {"links": {"github": "https://github.com/jaram-plus",
                                 "instagram": "https://www.instagram.com/jaram",
                                 "blog": "https://blog.jaram.net",
                                 "discord": "https://discord.gg/jaram"}}
                      """)
                .when().patch("/api/admin/settings")
                .then().statusCode(200)
                .body("links.github", equalTo("https://github.com/jaram-plus"))
                .body("links.discord", equalTo("https://discord.gg/jaram"));

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/settings")
                .then().statusCode(200)
                .body("links.blog", equalTo("https://blog.jaram.net"));
    }

    /** links 는 통째로 교체된다 — null 로 보낸 채널은 '등록 안 함'으로 되돌아간다. */
    @Test
    void patchClearsLinkSentAsNull() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body("""
                      {"links": {"github": "https://github.com/jaram-plus", "instagram": null,
                                 "blog": null, "discord": null}}
                      """)
                .when().patch("/api/admin/settings").then().statusCode(200);

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body("""
                      {"links": {"github": null, "instagram": null, "blog": null, "discord": null}}
                      """)
                .when().patch("/api/admin/settings")
                .then().statusCode(200)
                .body("links.github", nullValue());
    }

    /** 새 탭으로 그대로 여는 주소라 스킴이 없으면 쓸 수 없다. */
    @Test
    void patchRejectsLinkWithoutScheme() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body("""
                      {"links": {"github": "github.com/jaram-plus", "instagram": null,
                                 "blog": null, "discord": null}}
                      """)
                .when().patch("/api/admin/settings")
                .then().statusCode(422)
                .body("code", equalTo("VALIDATION"))
                .body("fieldErrors.'links.github'", org.hamcrest.Matchers.notNullValue());
    }

    /**
     * 형식은 맞지만 저장 컬럼(varchar(255))을 넘는 주소. 길이를 막지 않으면 검증을
     * 통과한 뒤 저장 단계에서 터져 422 가 아니라 500 이 나간다.
     */
    @Test
    void patchRejectsLinkLongerThanColumn() {
        String tooLong = "https://blog.jaram.net/" + "a".repeat(240);
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("links", Map.of("blog", tooLong)))
                .when().patch("/api/admin/settings")
                .then().statusCode(422)
                .body("code", equalTo("VALIDATION"))
                .body("fieldErrors.'links.blog'", org.hamcrest.Matchers.notNullValue());
    }

    /** links 를 안 보내면 기존 값이 그대로 남는다 (부분 수정). */
    @Test
    void patchWithoutLinksKeepsThem() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body("""
                      {"links": {"github": "https://github.com/jaram-plus", "instagram": null,
                                 "blog": null, "discord": null}}
                      """)
                .when().patch("/api/admin/settings").then().statusCode(200);

        // semester 는 이 계약에서 없어진 필드다. 그걸 보내면 Jackson 이 조용히 버려
        // 사실상 빈 요청이 되므로, 살아 있는 다른 필드를 바꿔서 확인한다.
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("autoPromote", true))
                .when().patch("/api/admin/settings")
                .then().statusCode(200)
                .body("autoPromote", equalTo(true))
                .body("links.github", equalTo("https://github.com/jaram-plus"));
    }

    @Test
    void patchUpdatesProvidedFieldsOnly() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("currentGen", 42, "autoPromote", true))
                .when().patch("/api/admin/settings")
                .then().statusCode(200)
                .body("currentGen", equalTo(42))
                .body("autoPromote", equalTo(true));
    }

    /** 연도는 서버가 오늘에서 계산한다 — 요청에 실어도 반영되지 않는다. */
    @Test
    void patchCannotChangeSemesterYear() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("semesterYear", 1999))
                .when().patch("/api/admin/settings")
                .then().statusCode(200)
                .body("semesterYear", equalTo(ClubTime.today().getYear()));
    }

    /** 학기는 자동값을 덮어쓸 수 있다. */
    @Test
    void patchOverridesSemesterTerm() {
        int other = autoTerm() == 1 ? 2 : 1;
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("semesterTerm", other))
                .when().patch("/api/admin/settings")
                .then().statusCode(200)
                .body("semesterTerm", equalTo(other));

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/settings")
                .then().statusCode(200)
                .body("semesterTerm", equalTo(other));
    }

    /** 0 은 '자동으로 되돌린다'는 뜻이다 — 기수와 같은 규약이다. */
    @Test
    void patchWithZeroTermReturnsToAuto() {
        int other = autoTerm() == 1 ? 2 : 1;
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("semesterTerm", other))
                .when().patch("/api/admin/settings")
                .then().statusCode(200)
                .body("semesterTerm", equalTo(other))
                .body("semesterTermAuto", equalTo(false));

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("semesterTerm", 0))
                .when().patch("/api/admin/settings")
                .then().statusCode(200)
                .body("semesterTerm", equalTo(autoTerm()))
                .body("semesterTermAuto", equalTo(true));
    }

    @Test
    void patchRejectsSemesterTermOutOfRange() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("semesterTerm", 3))
                .when().patch("/api/admin/settings")
                .then().statusCode(422)
                .body("code", equalTo("VALIDATION"))
                .body("fieldErrors.semesterTerm", org.hamcrest.Matchers.notNullValue());
    }

    /** 0 은 '자동 계산으로 되돌린다'는 뜻이다. */
    @Test
    void patchWithZeroGenReturnsToAuto() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("currentGen", 30))
                .when().patch("/api/admin/settings")
                .then().statusCode(200)
                .body("currentGen", equalTo(30));

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("currentGen", 0))
                .when().patch("/api/admin/settings")
                .then().statusCode(200)
                .body("currentGen", equalTo(Gen.current()));
    }

    @Test
    void patchRejectsNegativeGen() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("currentGen", -1))
                .when().patch("/api/admin/settings")
                .then().statusCode(422)
                .body("code", equalTo("VALIDATION"))
                .body("fieldErrors.currentGen", org.hamcrest.Matchers.notNullValue());
    }

    /** 3~8월은 1학기, 나머지는 2학기 (AdminSettings.autoTerm 과 같은 규칙). */
    private static int autoTerm() {
        int month = ClubTime.today().getMonthValue();
        return (month >= 3 && month <= 8) ? 1 : 2;
    }

    @Test
    void memberIsForbidden() {
        String memberToken = jwt.generate("m1", "회원", "m@hanyang.ac.kr", Authority.MEMBER);
        given().header("Authorization", "Bearer " + memberToken)
                .when().get("/api/admin/settings")
                .then().statusCode(403);
    }
}
