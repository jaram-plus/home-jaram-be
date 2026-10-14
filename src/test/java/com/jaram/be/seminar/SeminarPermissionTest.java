package com.jaram.be.seminar;

import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarPermissionTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired SeminarRepository seminars;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        seminars.deleteAll();
        members.deleteAll();
    }

    @AfterEach void cleanup() {
        seminars.deleteAll();
        members.deleteAll();
    }

    /** 홍보부는 세미나를 만들 수 있다 — 매트릭스에 SEMINAR_CREATE 가 있다. */
    @Test
    void prStaffCanCreateASeminar() {
        given().header("Authorization", "Bearer " + actors.token(Role.PR_STAFF))
                .contentType("application/json")
                .body(Map.of("title", "테스트", "speaker", "홍길동", "topic", "주제",
                        "startsAt", "2026-12-01T19:00:00Z", "place", "강의실", "mode", "OFFLINE"))
                .when().post("/api/seminars")
                .then().statusCode(201);
    }

    /** 승인은 학술부장부터다. 부원은 운영은 하되 승인은 못 한다. */
    @Test
    void academicStaffCannotApprove() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .when().post("/api/admin/seminars/any-id/approve")
                .then().statusCode(403);
    }

    @Test
    void academicLeadCanApprove() {
        // 없는 세미나라 404. 403 이 아니라는 것이 요점이다.
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_LEAD))
                .when().post("/api/admin/seminars/any-id/approve")
                .then().statusCode(404);
    }

    /** 출석 관리와 명단은 부원도 한다 — 실제 운영을 맡는 사람들이다. */
    @Test
    void academicStaffManagesAttendance() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .when().post("/api/admin/seminars/any-id/attendance-code")
                .then().statusCode(404);
    }

    /** 홍보부는 세미나를 만들 뿐 명단은 못 본다 — 출석은 개인정보다. */
    @Test
    void prStaffCannotReadRoster() {
        given().header("Authorization", "Bearer " + actors.token(Role.PR_STAFF))
                .when().get("/api/admin/seminars/any-id/attendees")
                .then().statusCode(403);
    }

    @Test
    void plainMemberCannotCreateASeminar() {
        given().header("Authorization", "Bearer " + actors.member())
                .contentType("application/json")
                .body(Map.of("title", "테스트", "speaker", "홍길동", "topic", "주제",
                        "startsAt", "2026-12-01T19:00:00Z", "place", "강의실", "mode", "OFFLINE"))
                .when().post("/api/seminars")
                .then().statusCode(403);
    }
}
