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
import java.time.temporal.ChronoUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarRosterTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String officerToken;
    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
        memberToken = jwt.generate("member-1", "회원", "member@hanyang.ac.kr", Authority.MEMBER);
    }

    private Member active(String id, String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setStatus(MemberStatus.ACTIVE);
        // align the member id with the JWT subject used at attend time
        return members.save(withId(m, id));
    }

    // Member has no public id setter; persist then reload by the generated id is not enough here,
    // so use the JPA-saved entity's own id for attendance rows instead (see below).
    private Member withId(Member m, String ignored) { return m; }

    @Test
    void officerSeesRosterInAttendanceOrder() {
        Member a = active("x", "김출석", "2023000001", "a@hanyang.ac.kr");
        Member b = active("y", "박출석", "2023000002", "b@hanyang.ac.kr");
        Seminar s = seminars.save(Seminar.create("세미나", null, null,
                Instant.now(), null, null, "CODE", null, 30, "officer-1"));
        attendances.save(Attendance.create(s.getId(), b.getId(), Instant.parse("2026-06-27T10:02:00Z")));
        attendances.save(Attendance.create(s.getId(), a.getId(), Instant.parse("2026-06-27T10:01:00Z")));

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/seminars/" + s.getId() + "/roster")
                .then().statusCode(200)
                .body("title", equalTo("세미나"))
                .body("cap", equalTo(30))
                .body("list.size()", equalTo(2))
                // ascending by attendance time → a (10:01) before b (10:02)
                .body("list[0].name", equalTo("김출석"))
                .body("list[0].sid", equalTo("2023000001"))
                .body("list[0].at", equalTo("19:01"))
                .body("list[1].name", equalTo("박출석"));
    }

    @Test
    void capIsZeroWhenCapacityNull() {
        Seminar s = seminars.save(Seminar.create("무정원", null, null,
                Instant.now(), null, null, "CODE", null, null, "officer-1"));
        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/seminars/" + s.getId() + "/roster")
                .then().statusCode(200).body("cap", equalTo(0)).body("list.size()", equalTo(0));
    }

    @Test
    void memberCannotViewRoster() {
        Seminar s = seminars.save(Seminar.create("세미나", null, null,
                Instant.now(), null, null, "CODE", null, 30, "officer-1"));
        given().header("Authorization", "Bearer " + memberToken)
                .when().get("/api/seminars/" + s.getId() + "/roster")
                .then().statusCode(403).body("code", equalTo("FORBIDDEN"));
    }

    @Test
    void unknownSeminarReturns404() {
        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/seminars/does-not-exist/roster")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }
}
