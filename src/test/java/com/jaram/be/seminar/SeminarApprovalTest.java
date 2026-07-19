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

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarApprovalTest extends PostgresTest {

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
        officerToken = jwt.generate("officer-1", "임원", "of@hanyang.ac.kr", Authority.OFFICER);
        memberToken = jwt.generate("member-1", "회원", "me@hanyang.ac.kr", Authority.MEMBER);
    }

    private Seminar pending() {
        return seminars.save(Seminar.create("대기", null, null, Instant.now(),
                null, null, "CODE", null, null, "member-1"));
    }

    @Test
    void officerApproves() {
        Seminar s = pending();
        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/approve").then().statusCode(200)
                .body("approvalStatus", equalTo("APPROVED"));
    }

    @Test
    void officerRejectsWithReason() {
        Seminar s = pending();
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of("reason", "보완 필요"))
                .when().post("/api/admin/seminars/" + s.getId() + "/reject").then().statusCode(200)
                .body("approvalStatus", equalTo("REJECTED"))
                .body("rejectReason", equalTo("보완 필요"));
    }

    @Test
    void rejectWithoutReasonIs422() {
        Seminar s = pending();
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of())
                .when().post("/api/admin/seminars/" + s.getId() + "/reject").then().statusCode(422);
    }

    /** 승인 큐는 PENDING만. 일반 목록(/api/admin/seminars)은 걸러주지 않는다. */
    @Test
    void pendingQueueOnlyHasPending() {
        Seminar mine = pending();
        Seminar approved = pending();
        approved.approve();
        seminars.save(approved);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/seminars/pending").then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].id", equalTo(mine.getId()))
                .body("[0].approvalStatus", equalTo("PENDING"));
    }

    @Test
    void memberCannotSeePendingQueue() {
        given().header("Authorization", "Bearer " + memberToken)
                .when().get("/api/admin/seminars/pending").then().statusCode(403);
    }

    @Test
    void memberCannotApprove() {
        Seminar s = pending();
        given().header("Authorization", "Bearer " + memberToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/approve").then().statusCode(403);
    }

    @Test
    void approveMissingIs404() {
        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/nope/approve").then().statusCode(404);
    }
}
