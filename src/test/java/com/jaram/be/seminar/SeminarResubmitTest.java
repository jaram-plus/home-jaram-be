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
import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarResubmitTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired JwtProvider jwt;

    private String ownerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        ownerToken = jwt.generate("owner-1", "주인", "o@hanyang.ac.kr", Authority.MEMBER);
    }

    private Seminar rejected(String owner, String scheduleId) {
        Seminar s = Seminar.create("옛제목", null, null, Instant.parse("2026-01-01T00:00:00Z"),
                "옛장소", "offline", "CODE", null, null, owner);
        s.reject("보완 필요");
        s.setScheduleId(scheduleId);
        return seminars.save(s);
    }

    @Test
    void ownerResubmitsRejectedGoesPending() {
        Seminar s = rejected("owner-1", null);
        Map<String, Object> body = new HashMap<>();
        body.put("title", "새제목");
        body.put("startsAt", "2026-09-01T10:00:00Z");
        body.put("place", "새장소");

        given().header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").body(body)
                .when().patch("/api/seminars/" + s.getId()).then().statusCode(200)
                .body("title", equalTo("새제목"))
                .body("approvalStatus", equalTo("PENDING"))
                .body("rejectReason", equalTo(null))
                .body("place", equalTo("새장소"));
    }

    @Test
    void slotLinkedResubmitKeepsScheduleTime() {
        Seminar s = rejected("owner-1", "sched-1");
        Map<String, Object> body = new HashMap<>();
        body.put("title", "새제목");
        body.put("startsAt", "2099-09-01T10:00:00Z"); // 무시돼야 함
        body.put("place", "무시장소");                 // 무시돼야 함

        given().header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").body(body)
                .when().patch("/api/seminars/" + s.getId()).then().statusCode(200)
                .body("title", equalTo("새제목"))
                .body("place", equalTo("옛장소"))
                .body("startsAt", equalTo("2026-01-01T00:00:00Z"));

        assertThat(seminars.findById(s.getId()).orElseThrow().getStartsAt())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void nonOwnerGets403() {
        Seminar s = rejected("someone-else", null);
        given().header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").body(Map.of("title", "x", "startsAt", "2026-09-01T10:00:00Z"))
                .when().patch("/api/seminars/" + s.getId()).then().statusCode(403);
    }

    @Test
    void notRejectedGets409() {
        Seminar s = Seminar.create("승인됨", null, null, Instant.now(),
                null, null, "CODE", null, null, "owner-1");
        s.approve();
        seminars.save(s);
        given().header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").body(Map.of("title", "x", "startsAt", "2026-09-01T10:00:00Z"))
                .when().patch("/api/seminars/" + s.getId()).then().statusCode(409)
                .body("code", equalTo("CONFLICT"));
    }
}
