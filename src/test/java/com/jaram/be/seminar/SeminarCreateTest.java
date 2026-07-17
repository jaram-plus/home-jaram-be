package com.jaram.be.seminar;

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
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarCreateTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired JwtProvider jwt;

    private String officerToken;
    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
        memberToken = jwt.generate("member-1", "회원", "member@hanyang.ac.kr", Authority.MEMBER);
    }

    @Test
    void officerCreatesSeminarAndCodeIsNotReturned() {
        Map<String, Object> body = Map.of(
                "title", "새 세미나",
                "speaker", "이연사",
                "startsAt", "2027-07-01T10:00:00Z",
                "place", "IT관 401",
                "attendanceCode", "JOIN123",
                "capacity", 40,
                "description", "이번 세미나는 신규 회원 대상입니다.");

        String id = given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(body)
                .when().post("/api/seminars")
                .then().statusCode(201)
                .body("title", equalTo("새 세미나"))
                .body("status", equalTo("UPCOMING"))
                .body("capacity", equalTo(40))
                .body("description", equalTo("이번 세미나는 신규 회원 대상입니다."))
                .body("$", not(hasKey("attendanceCode")))
                .extract().path("id");

        // persisted with the (hidden) attendance code
        org.assertj.core.api.Assertions.assertThat(
                seminars.findById(id).orElseThrow().getAttendanceCode()).isEqualTo("JOIN123");
    }

    @Test
    void missingTitleReturns422() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("startsAt", "2026-07-01T10:00:00Z"))
                .when().post("/api/seminars")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void memberCannotCreateSeminar() {
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json")
                .body(Map.of("title", "x", "startsAt", "2026-07-01T10:00:00Z"))
                .when().post("/api/seminars")
                .then().statusCode(403).body("code", equalTo("FORBIDDEN"));
    }

    @Test
    void anonymousCannotCreateSeminar() {
        given().contentType("application/json")
                .body(Map.of("title", "x", "startsAt", "2026-07-01T10:00:00Z"))
                .when().post("/api/seminars")
                .then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
    }
}
