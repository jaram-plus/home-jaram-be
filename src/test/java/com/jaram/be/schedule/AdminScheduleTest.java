package com.jaram.be.schedule;

import com.jaram.be.member.Authority;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
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
import static org.hamcrest.Matchers.nullValue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminScheduleTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired ScheduleRepository schedules;
    @Autowired SeminarRepository seminars;
    @Autowired JwtProvider jwt;

    private String officerToken;
    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        schedules.deleteAll();
        seminars.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "of@hanyang.ac.kr", Authority.OFFICER);
        memberToken = jwt.generate("member-1", "회원", "me@hanyang.ac.kr", Authority.MEMBER);
    }

    @Test
    void officerCreatesScheduleWithDefaultCapacity() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("startsAt", "2026-09-01T10:00:00Z", "place", "IT관"))
                .when().post("/api/admin/schedules").then().statusCode(201)
                .body("capacity", equalTo(3))
                .body("status", equalTo("OPEN"))
                .body("slots.size()", equalTo(3));
    }

    @Test
    void memberCannotCreate() {
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("startsAt", "2026-09-01T10:00:00Z"))
                .when().post("/api/admin/schedules").then().statusCode(403);
    }

    @Test
    void officerLocks() {
        Schedule s = schedules.save(Schedule.create(Instant.now(), null, null, 3));
        given().header("Authorization", "Bearer " + officerToken)
                .when().patch("/api/admin/schedules/" + s.getId() + "/lock").then().statusCode(200)
                .body("status", equalTo("LOCKED"));
    }

    @Test
    void forceReleaseEmptySlot() {
        Schedule s = schedules.save(Schedule.create(Instant.now(), null, null, 3));
        given().header("Authorization", "Bearer " + officerToken)
                .when().delete("/api/admin/schedules/" + s.getId() + "/slots/0").then().statusCode(200)
                .body("slots[0].member", nullValue());
    }

    @Test
    void forceReleaseRejectedSeminarPasses() {
        Seminar sem = Seminar.create("반려됨", null, null, Instant.now(),
                null, null, null, null, null, "member-1");
        sem.reject("사유");
        sem = seminars.save(sem);
        Schedule s = Schedule.create(Instant.now(), null, null, 3);
        s.getSlots().get(0).claim("member-1");
        s.getSlots().get(0).attachSeminar(sem.getId());
        s.lock();
        schedules.save(s);

        given().header("Authorization", "Bearer " + officerToken)
                .when().delete("/api/admin/schedules/" + s.getId() + "/slots/0").then().statusCode(200)
                .body("slots[0].member", nullValue())
                .body("slots[0].seminarId", nullValue());
    }

    @Test
    void forceReleasePendingSeminarIs409() {
        Seminar sem = seminars.save(Seminar.create("대기", null, null, Instant.now(),
                null, null, null, null, null, "member-1")); // PENDING
        Schedule s = Schedule.create(Instant.now(), null, null, 3);
        s.getSlots().get(0).claim("member-1");
        s.getSlots().get(0).attachSeminar(sem.getId());
        s.lock();
        schedules.save(s);

        given().header("Authorization", "Bearer " + officerToken)
                .when().delete("/api/admin/schedules/" + s.getId() + "/slots/0").then().statusCode(409)
                .body("code", equalTo("CONFLICT"));
    }
}
