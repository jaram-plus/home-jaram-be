package com.jaram.be.seminar;

import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
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
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarAttendeePreviewTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        members.deleteAll();
        memberToken = jwt.generate("member-1", "회원", "member@hanyang.ac.kr", Authority.MEMBER);
    }

    @Test
    void memberSeesAttendeesWithoutSid() {
        Member a = members.save(activeMember("김출석", "2023000001", "a@hanyang.ac.kr"));
        Member b = members.save(activeMember("박출석", "2023000002", "b@hanyang.ac.kr"));
        Seminar s = seminars.save(Seminar.create("세미나", null, null,
                Instant.now(), null, null, "CODE", null, null, "officer-1"));
        attendances.save(Attendance.create(s.getId(), b.getId(), Instant.parse("2026-06-27T10:02:00Z")));
        attendances.save(Attendance.create(s.getId(), a.getId(), Instant.parse("2026-06-27T10:01:00Z")));

        given().header("Authorization", "Bearer " + memberToken)
                .when().get("/api/seminars/" + s.getId() + "/attendees")
                .then().statusCode(200)
                .body("count", equalTo(2))
                .body("list.size()", equalTo(2))
                // ascending by attendance time -> a (10:01) before b (10:02)
                .body("list[0].name", equalTo("김출석"))
                .body("list[1].name", equalTo("박출석"))
                .body("list[0]", not(hasKey("sid")))
                .body("list[1]", not(hasKey("sid")));
    }

    @Test
    void anonymousCannotViewAttendees() {
        Seminar s = seminars.save(Seminar.create("세미나", null, null,
                Instant.now(), null, null, "CODE", null, null, "officer-1"));
        given().when().get("/api/seminars/" + s.getId() + "/attendees")
                .then().statusCode(401);
    }

    @Test
    void unknownSeminarReturns404() {
        given().header("Authorization", "Bearer " + memberToken)
                .when().get("/api/seminars/does-not-exist/attendees")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    private Member activeMember(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setStatus(MemberStatus.ACTIVE);
        return m;
    }
}
