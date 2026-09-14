package com.jaram.be.study;

import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyRecruitmentTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired StudyRepository studies;
    @Autowired StudyRecruitmentRepository recruitment;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        studies.deleteAll();
        recruitment.deleteAll();
    }

    private void put(String token, boolean open, int expected) {
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("open", open))
                .when().put("/api/studies/recruitment")
                .then().statusCode(expected);
    }

    @Test
    void academicLeadCanFlipTheToggle() {
        put(actors.token(Role.ACADEMIC_LEAD), true, 204);
        given().when().get("/api/studies").then().statusCode(200)
                .body("recruiting", equalTo(true));
    }

    @Test
    void prLeadAndPlainMemberAreRefused() {
        put(actors.token(Role.PR_LEAD), true, 403);
        put(actors.member(), true, 403);
    }

    @Test
    void defaultsToClosedWhenNobodyHasFlippedIt() {
        given().when().get("/api/studies").then().statusCode(200)
                .body("recruiting", equalTo(false));
    }

    @Test
    void togglingNeverMovesAnyStudyStatus() {
        Study s = studies.save(Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", "leader-id"));
        s.approve();
        studies.save(s);

        String token = actors.token(Role.ACADEMIC_LEAD);
        put(token, true, 204);
        put(token, false, 204);

        assertThat(studies.findById(s.getId()).orElseThrow().getStatus())
                .isEqualTo(StudyStatus.RECRUITING);
    }
}
