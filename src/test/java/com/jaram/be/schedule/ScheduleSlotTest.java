package com.jaram.be.schedule;

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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleSlotTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired ScheduleRepository schedules;
    @Autowired JwtProvider jwt;

    private String token;   // member-1

    @BeforeEach void setup() {
        RestAssured.port = port;
        schedules.deleteAll();
        token = jwt.generate("member-1", "회원", "a@hanyang.ac.kr", Authority.MEMBER);
    }

    private Schedule open() { return schedules.save(Schedule.create(Instant.now(), null, null, 3)); }

    @Test
    void claimsEmptySlot() {
        Schedule s = open();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/claim").then().statusCode(200)
                .body("slots[0].member.id", equalTo("member-1"));
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
    void claimSecondSlotSameMemberIs409() {
        Schedule s = open();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/claim").then().statusCode(200);
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/1/claim").then().statusCode(409);
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
        s.getSlots().get(0).claim("member-1");
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
        s.getSlots().get(0).claim("member-1");
        s.lock();
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().delete("/api/schedules/" + s.getId() + "/slots/0").then().statusCode(403);
    }
}
