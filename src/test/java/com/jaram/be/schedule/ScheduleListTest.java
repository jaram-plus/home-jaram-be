package com.jaram.be.schedule;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleListTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired ScheduleRepository schedules;
    @Autowired MemberRepository members;

    @BeforeEach void setup() {
        RestAssured.port = port;
        schedules.deleteAll();
        members.deleteAll();
    }

    @Test
    void listsSchedulesWithDerivedFieldsAndSlots() {
        Member m = Member.newPending("김회원", "2023000001", "a@hanyang.ac.kr", "hash");
        m.setStatus(MemberStatus.ACTIVE);
        m = members.save(m);
        // 2026-06-27T10:00:00Z == 2026-06-27 19:00 KST (토)
        Schedule s = Schedule.create(Instant.parse("2026-06-27T10:00:00Z"), "IT관 401", "offline", 3);
        s.getSlots().get(0).claim(m.getId());
        schedules.save(s);

        given().when().get("/api/schedules").then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].day", equalTo("27"))
                .body("[0].month", equalTo("6월"))
                .body("[0].weekday", equalTo("토"))
                .body("[0].time", equalTo("19:00"))
                .body("[0].capacity", equalTo(3))
                .body("[0].status", equalTo("OPEN"))
                .body("[0].slots.size()", equalTo(3))
                .body("[0].slots[0].index", equalTo(0))
                .body("[0].slots[0].member.name", equalTo("김회원"))
                .body("[0].slots[1].member", nullValue())
                .body("[0].slots[0].seminarId", nullValue());
    }

    @Test
    void emptyReturnsEmptyArray() {
        given().when().get("/api/schedules").then().statusCode(200).body("size()", equalTo(0));
    }
}
