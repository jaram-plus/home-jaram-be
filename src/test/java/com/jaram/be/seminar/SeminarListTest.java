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
class SeminarListTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        members.deleteAll();
    }

    @Test
    void listsNewestFirstWithDerivedFieldsAndNoAttendanceCode() {
        // 2026-06-27T10:00:00Z == 2026-06-27 19:00 KST, a Saturday
        Instant past = Instant.parse("2026-06-27T10:00:00Z");
        seminars.save(approved(Seminar.create("지난 세미나", "김연사", "주제A", past,
                "IT관 401", "offline", "SECRET", "https://m.example.com/a", 30, "officer-1")));
        seminars.save(approved(Seminar.create("다음 세미나", null, null, Instant.now().plus(2, ChronoUnit.DAYS),
                null, null, "SECRET2", null, null, "officer-1")));

        given().when().get("/api/seminars").then().statusCode(200)
                .body("size()", equalTo(2))
                // newest (future) first
                .body("[0].title", equalTo("다음 세미나"))
                .body("[0].status", equalTo("UPCOMING"))
                // past seminar derived display fields (Asia/Seoul)
                .body("[1].title", equalTo("지난 세미나"))
                .body("[1].status", equalTo("ENDED"))
                .body("[1].day", equalTo("27"))
                .body("[1].month", equalTo("6월"))
                .body("[1].weekday", equalTo("토"))
                .body("[1].time", equalTo("19:00"))
                .body("[1].place", equalTo("IT관 401"))
                .body("[1].materialUrl", equalTo("https://m.example.com/a"))
                // attendanceCode must never be serialized
                .body("[0]", not(hasKey("attendanceCode")))
                .body("[1]", not(hasKey("attendanceCode")));
    }

    @Test
    void emptyDatabaseReturnsEmptyArray() {
        given().when().get("/api/seminars").then().statusCode(200).body("size()", equalTo(0));
    }

    @Test
    void anonymousListHasClosesAtAndNullAttendedAt() {
        // Postgres stores microsecond precision; truncate so the expected value survives the round-trip
        Instant starts = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        seminars.save(approved(Seminar.create("진행중", null, null, starts,
                null, null, "CODE", null, null, "officer-1")));

        given().when().get("/api/seminars").then().statusCode(200)
                .body("[0].attendanceClosesAt", equalTo(starts.plusSeconds(120 * 60).toString()))
                .body("[0].attendedAt", nullValue())
                .body("[0].description", nullValue());
    }

    @Test
    void authenticatedCallerSeesOwnAttendance() {
        Member m = Member.newPending("김출석", "2023000001", "a@hanyang.ac.kr", "hash");
        m.setStatus(MemberStatus.ACTIVE);
        m = members.save(m);
        String token = jwt.generate(m.getId(), "김출석", "a@hanyang.ac.kr", Authority.MEMBER);

        // now - 200m is outside the default 120m window -> ENDED
        Instant endedStart = Instant.now().minus(200, ChronoUnit.MINUTES);
        Seminar attended = seminars.save(approved(Seminar.create("종료-출석", null, null, endedStart,
                null, null, "CODE1", null, null, "officer-1")));
        Seminar notAttended = seminars.save(approved(Seminar.create("종료-결석", null, null, endedStart.minusSeconds(1),
                null, null, "CODE2", null, null, "officer-1")));
        attendances.save(Attendance.create(attended.getId(), m.getId(), endedStart.plusSeconds(60)));

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/seminars").then().statusCode(200)
                .body("[0].id", equalTo(attended.getId()))
                .body("[0].attendedAt", notNullValue())
                .body("[1].id", equalTo(notAttended.getId()))
                .body("[1].attendedAt", nullValue());
    }

    @Test
    void listExcludesPendingAndRejected() {
        seminars.save(approved(Seminar.create("공개", null, null, Instant.now(),
                null, null, "C1", null, null, "officer-1")));
        seminars.save(Seminar.create("대기", null, null, Instant.now(),
                null, null, "C2", null, null, "officer-1")); // PENDING 기본
        Seminar rej = Seminar.create("반려", null, null, Instant.now(),
                null, null, "C3", null, null, "officer-1");
        rej.reject("사유");
        seminars.save(rej);

        given().when().get("/api/seminars").then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].title", equalTo("공개"))
                .body("[0].approvalStatus", equalTo("APPROVED"));
    }

    private Seminar approved(Seminar s) { s.approve(); return s; }
}
