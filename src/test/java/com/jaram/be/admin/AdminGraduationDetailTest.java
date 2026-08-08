package com.jaram.be.admin;

import com.jaram.be.member.*;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

// 졸업생 상세 — 졸업연도와 졸업 후 이력. 이력은 통째로 교체되고, 표는 그중 최신 한 건을 본다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminGraduationDetailTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
    }

    private Member graduate(String name, String sid) {
        Member m = Member.newPending(name, sid, name + "@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.OB);
        return members.save(m);
    }

    private Map<String, Object> career(String at, String org, String job) {
        Map<String, Object> c = new HashMap<>();
        c.put("at", at);
        c.put("org", org);
        c.put("job", job);
        return c;
    }

    private io.restassured.response.ValidatableResponse put(String id, Map<String, Object> body) {
        return given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(body)
                .when().put("/api/admin/members/" + id + "/graduation")
                .then();
    }

    @Test
    void savesGradYearAndCareers() {
        Member m = graduate("한지호", "2015004321");

        put(m.getId(), Map.of(
                "gradYear", 2021,
                "careers", List.of(career("2021.03", "네이버", "백엔드 엔지니어"))))
                .statusCode(200)
                .body("gradYear", equalTo(2021))
                .body("careers.size()", equalTo(1))
                .body("careers[0].org", equalTo("네이버"));

        Member saved = members.findById(m.getId()).orElseThrow();
        assertThat(saved.getGradYear()).isEqualTo(2021);
        assertThat(saved.getCareers()).hasSize(1);
    }

    // 목록의 '현재 소속·직무' 는 저장값이 아니라 가장 최근 이력에서 파생한다.
    @Test
    void gradListRowDerivesLatestCareer() {
        Member m = graduate("오세훈", "2014003118");

        put(m.getId(), Map.of(
                "gradYear", 2020,
                "careers", List.of(
                        career("2020.09", "넥슨", "클라이언트 개발자"),
                        career("2023.01", "카카오", "안드로이드 개발자"))))
                .statusCode(200);

        given().header("Authorization", "Bearer " + officerToken)
                .queryParam("tab", "grad")
                .when().get("/api/admin/members")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].gradYear", equalTo(2020))
                .body("items[0].org", equalTo("카카오"))
                .body("items[0].job", equalTo("안드로이드 개발자"));
    }

    // 화면이 줄을 지우면 보낸 목록이 곧 전체다 — 빠진 줄은 남지 않는다.
    @Test
    void careersAreReplacedWholesale() {
        Member m = graduate("임채원", "2013002047");

        put(m.getId(), Map.of("gradYear", 2019, "careers", List.of(
                career("2019.07", "라인", "프론트엔드 개발자"),
                career("2022.04", "당근", "웹 개발자")))).statusCode(200);

        put(m.getId(), Map.of("gradYear", 2019, "careers", List.of(
                career("2019.07", "라인", "프론트엔드 개발자")))).statusCode(200)
                .body("careers.size()", equalTo(1));

        assertThat(members.findById(m.getId()).orElseThrow().getCareers()).hasSize(1);
    }

    // 이력을 모두 지우면 표의 소속·직무도 비어야 한다 — 마지막 값이 남아 있으면 거짓말이 된다.
    @Test
    void clearingCareersEmptiesTheRow() {
        Member m = graduate("서동건", "2012001503");

        put(m.getId(), Map.of("gradYear", 2018, "careers", List.of(
                career("2018.03", "쿠팡", "데이터 엔지니어")))).statusCode(200);

        Map<String, Object> empty = new HashMap<>();
        empty.put("gradYear", null);
        empty.put("careers", List.of());
        put(m.getId(), empty).statusCode(200).body("careers.size()", equalTo(0));

        given().header("Authorization", "Bearer " + officerToken)
                .queryParam("tab", "grad")
                .when().get("/api/admin/members")
                .then().statusCode(200)
                .body("items[0].org", nullValue())
                .body("items[0].gradYear", nullValue());
    }

    @Test
    void unknownMemberIsNotFound() {
        put("no-such-id", Map.of("gradYear", 2021, "careers", List.of()))
                .statusCode(404)
                .body("code", equalTo("NOT_FOUND"));
    }
}
