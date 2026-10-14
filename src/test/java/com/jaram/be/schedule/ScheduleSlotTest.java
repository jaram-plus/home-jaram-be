package com.jaram.be.schedule;

import com.jaram.be.member.Member;
import com.jaram.be.security.authz.Role;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleSlotTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired ScheduleRepository schedules;
    @Autowired SeminarRepository seminars;
    @Autowired Actors actors;

    private String token;
    private String memberId;

    @BeforeEach void setup() {
        RestAssured.port = port;
        schedules.deleteAll();
        seminars.deleteAll();
        Member actor = actors.save(Role.MEMBER);
        memberId = actor.getId();
        token = actors.tokenFor(actor);
    }

    private Schedule open() { return schedules.save(Schedule.create(Instant.now(), null, null, 3)); }

    @Test
    void claimsEmptySlot() {
        Schedule s = open();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/claim").then().statusCode(200)
                .body("slots[0].member.id", equalTo(memberId));
    }

    @Test
    void claimOccupiedSlotIs409() {
        Schedule s = open();
        s.getSlots().get(0).claim("someone");
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/claim").then().statusCode(409)
                .body("code", equalTo("CONFLICT"));
    }

    @Test
    void claimSecondSlotSameMemberIsAllowed() {
        Schedule s = open();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/claim").then().statusCode(200);
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/1/claim").then().statusCode(200)
                .body("slots[1].member.id", equalTo(memberId));
    }

    @Test
    void claimLockedIs409() {
        Schedule s = open();
        s.lock();
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/claim").then().statusCode(409);
    }

    @Test
    void claimOutOfRangeIs404() {
        Schedule s = open();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/9/claim").then().statusCode(404);
    }

    @Test
    void cancelsOwnSlot() {
        Schedule s = open();
        s.getSlots().get(0).claim(memberId);
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().delete("/api/schedules/" + s.getId() + "/slots/0").then().statusCode(200)
                .body("slots[0].member", nullValue());
    }

    @Test
    void cancelOthersSlotIs403() {
        Schedule s = open();
        s.getSlots().get(0).claim("someone");
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().delete("/api/schedules/" + s.getId() + "/slots/0").then().statusCode(403);
    }

    @Test
    void cancelAfterLockIs403() {
        Schedule s = open();
        s.getSlots().get(0).claim(memberId);
        s.lock();
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().delete("/api/schedules/" + s.getId() + "/slots/0").then().statusCode(403);
    }

    /** unlock 이후 세미나가 붙은 채 OPEN인 슬롯 — 취소하면 세미나가 고아가 된다. */
    @Test
    void cancelSubmittedSlotIs403() {
        Seminar sem = seminars.save(Seminar.create("제출본", null, null, Instant.now(),
                null, null, null, null, null, memberId));
        Schedule s = Schedule.create(Instant.now(), null, null, 3);
        s.getSlots().get(0).claim(memberId);
        s.getSlots().get(0).attachSeminar(sem.getId());
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().delete("/api/schedules/" + s.getId() + "/slots/0").then().statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }
}
