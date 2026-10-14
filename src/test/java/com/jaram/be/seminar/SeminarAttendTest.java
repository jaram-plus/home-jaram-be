package com.jaram.be.seminar;

import com.jaram.be.member.Member;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarAttendTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired Actors actors;

    private String memberToken;
    private String memberId;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        Member actor = actors.save(Role.MEMBER);
        memberId = actor.getId();
        memberToken = actors.tokenFor(actor);
    }

    private Seminar ongoing(String code) {
        return seminars.save(Seminar.create("ongoing", null, null,
                Instant.now().minus(1, ChronoUnit.MINUTES), null, null, code, null, null, "officer-1"));
    }

    @Test
    void memberAttendsOngoingSeminarWithCorrectCode() {
        Seminar s = ongoing("JOIN123");
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", "JOIN123"))
                .when().post("/api/seminars/" + s.getId() + "/attend")
                .then().statusCode(200)
                .body("seminarId", equalTo(s.getId()))
                .body("at", matchesPattern("\\d{2}:\\d{2}"));

        org.assertj.core.api.Assertions.assertThat(
                attendances.findBySeminarIdAndMemberId(s.getId(), memberId)).isPresent();
    }

    @Test
    void duplicateAttendanceIsIdempotentSuccess() {
        Seminar s = ongoing("JOIN123");
        for (int i = 0; i < 2; i++) {
            given().header("Authorization", "Bearer " + memberToken)
                    .contentType("application/json").body(Map.of("code", "JOIN123"))
                    .when().post("/api/seminars/" + s.getId() + "/attend")
                    .then().statusCode(200);
        }
        org.assertj.core.api.Assertions.assertThat(
                attendances.findBySeminarIdOrderByAtAsc(s.getId())).hasSize(1);
    }

    @Test
    void wrongCodeReturns400InvalidCode() {
        Seminar s = ongoing("JOIN123");
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", "WRONG"))
                .when().post("/api/seminars/" + s.getId() + "/attend")
                .then().statusCode(400).body("code", equalTo("INVALID_CODE"));
    }

    @Test
    void attendOutsideWindowReturns400InvalidCode() {
        Seminar upcoming = seminars.save(Seminar.create("future", null, null,
                Instant.now().plus(1, ChronoUnit.DAYS), null, null, "JOIN123", null, null, "officer-1"));
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", "JOIN123"))
                .when().post("/api/seminars/" + upcoming.getId() + "/attend")
                .then().statusCode(400).body("code", equalTo("INVALID_CODE"));
    }

    @Test
    void attendOngoingSeminarWithoutAttendanceCodeReturns400() {
        // code-less ongoing seminar: any attempt must yield 400 INVALID_CODE, not a 500/NPE
        Seminar s = seminars.save(Seminar.create("nocode", null, null,
                Instant.now().minus(1, ChronoUnit.MINUTES), null, null, null, null, null, "officer-1"));
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", "ANYTHING"))
                .when().post("/api/seminars/" + s.getId() + "/attend")
                .then().statusCode(400).body("code", equalTo("INVALID_CODE"));
    }

    @Test
    void unknownSeminarReturns404() {
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", "JOIN123"))
                .when().post("/api/seminars/does-not-exist/attend")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    @Test
    void anonymousCannotAttend() {
        Seminar s = ongoing("JOIN123");
        given().contentType("application/json").body(Map.of("code", "JOIN123"))
                .when().post("/api/seminars/" + s.getId() + "/attend")
                .then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
    }
}
