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
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/** 임원 화면의 출석 관리 — 코드 발급 · 출석 마감 · 수기 출석/취소. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminSeminarAttendanceTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String officerToken;
    private Member member;
    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        members.deleteAll();
        Member m = Member.newPending("김회원", "2023000001", "a@hanyang.ac.kr", "hash");
        m.setStatus(MemberStatus.ACTIVE);
        member = members.save(m);
        officerToken = jwt.generate("officer-1", "임원", "of@hanyang.ac.kr", Authority.OFFICER);
        memberToken = jwt.generate(member.getId(), "김회원", "a@hanyang.ac.kr", Authority.MEMBER);
    }

    /** 지금 진행 중인(출석 가능한) 세미나. */
    private Seminar ongoing() {
        return seminars.save(Seminar.create("세미나", null, null,
                Instant.now().minusSeconds(60), null, null, null, null, 30, "officer-1"));
    }

    @Test
    void generatedCodeIsSavedAndWorksForAttendance() {
        Seminar s = ongoing();
        String code = given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/attendance-code")
                .then().statusCode(200)
                .body("attendanceCode", matchesRegex("[A-Z2-9]{6}"))
                .extract().path("attendanceCode");

        // 따로 저장하지 않아도 바로 유효하다
        assertThat(seminars.findById(s.getId()).orElseThrow().getAttendanceCode()).isEqualTo(code);
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", code))
                .when().post("/api/seminars/" + s.getId() + "/attend").then().statusCode(200);
    }

    @Test
    void regeneratingInvalidatesThePreviousCode() {
        Seminar s = ongoing();
        String first = given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/attendance-code")
                .then().statusCode(200).extract().path("attendanceCode");
        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/attendance-code")
                .then().statusCode(200).body("attendanceCode", not(equalTo(first)));

        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", first))
                .when().post("/api/seminars/" + s.getId() + "/attend")
                .then().statusCode(400).body("code", equalTo("INVALID_CODE"));
    }

    @Test
    void closingAttendanceEndsTheSeminarAndBlocksAttendance() {
        Seminar s = ongoing();
        String code = given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/attendance-code")
                .then().statusCode(200).extract().path("attendanceCode");

        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/close-attendance")
                .then().statusCode(200).body("status", equalTo("ENDED"));

        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", code))
                .when().post("/api/seminars/" + s.getId() + "/attend")
                .then().statusCode(400).body("code", equalTo("INVALID_CODE"));
    }

    @Test
    void closingTwiceKeepsTheFirstClosingTime() {
        Seminar s = ongoing();
        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/close-attendance").then().statusCode(200);
        Instant first = seminars.findById(s.getId()).orElseThrow().getAttendanceClosedAt();

        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/close-attendance").then().statusCode(200);
        assertThat(seminars.findById(s.getId()).orElseThrow().getAttendanceClosedAt()).isEqualTo(first);
    }

    @Test
    void officerAddsAndRemovesAttendeeByHand() {
        Seminar s = ongoing();
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of("memberId", member.getId()))
                .when().post("/api/admin/seminars/" + s.getId() + "/attendees")
                .then().statusCode(200)
                .body("list.size()", equalTo(1))
                .body("list[0].memberId", equalTo(member.getId()))
                .body("list[0].name", equalTo("김회원"))
                .body("list[0].sid", equalTo("2023000001"));

        given().header("Authorization", "Bearer " + officerToken)
                .when().delete("/api/admin/seminars/" + s.getId() + "/attendees/" + member.getId())
                .then().statusCode(200).body("list.size()", equalTo(0));
        assertThat(attendances.findBySeminarIdAndMemberId(s.getId(), member.getId())).isEmpty();
    }

    @Test
    void addingTwiceKeepsOneRow() {
        Seminar s = ongoing();
        for (int i = 0; i < 2; i++) {
            given().header("Authorization", "Bearer " + officerToken)
                    .contentType("application/json").body(Map.of("memberId", member.getId()))
                    .when().post("/api/admin/seminars/" + s.getId() + "/attendees")
                    .then().statusCode(200).body("list.size()", equalTo(1));
        }
    }

    @Test
    void addingUnknownMemberIs404() {
        Seminar s = ongoing();
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of("memberId", "nope"))
                .when().post("/api/admin/seminars/" + s.getId() + "/attendees")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    @Test
    void memberCannotManageAttendance() {
        Seminar s = ongoing();
        given().header("Authorization", "Bearer " + memberToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/attendance-code")
                .then().statusCode(403);
        given().header("Authorization", "Bearer " + memberToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/close-attendance")
                .then().statusCode(403);
        given().header("Authorization", "Bearer " + memberToken)
                .when().get("/api/admin/seminars/" + s.getId() + "/attendees")
                .then().statusCode(403);
    }

    @Test
    void unknownSeminarIs404() {
        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/nope/attendance-code")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/nope/close-attendance")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }
}
