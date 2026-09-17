package com.jaram.be.study;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyAttendanceReadTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyAttendanceRepository attendance;
    @Autowired Actors actors;

    private Member leader, joined, outsider;
    private String leaderToken, joinedToken, outsiderToken;
    private Study study;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendance.deleteAll();
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        leader   = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr", 40));
        joined   = members.save(approved("참여", "2023000002", "joined@hanyang.ac.kr", 41));
        outsider = members.save(approved("남",  "2023000003", "out@hanyang.ac.kr", 41));
        leaderToken   = actors.tokenFor(leader);
        joinedToken   = actors.tokenFor(joined);
        outsiderToken = actors.tokenFor(outsider);

        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leader.getId());
        s.approve();
        s.closeRecruiting();
        study = studies.save(s);

        StudyApplication a = StudyApplication.create(study.getId(), joined.getId(), "하고 싶습니다");
        a.approve();
        applications.save(a);

        weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        weeks.save(StudyWeek.create(study.getId(), 2, "그리디", null));
        weeks.save(StudyWeek.create(study.getId(), 3, "DP", null));

        // 1주차만 찍는다: 리더 출석, 참여자 결석
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json")
                .body(Map.of("present", List.of(leader.getId())))
                .when().put("/api/studies/" + study.getId() + "/weeks/1/attendance")
                .then().statusCode(204);
    }

    private Member approved(String name, String sid, String email, int gen) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGen(gen);
        return m;
    }

    @Test
    void leaderSeesTheGridWithEditableFlags() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/attendance")
                .then().statusCode(200)
                .body("weeks", hasSize(3))
                .body("weeks[0].weekNo", equalTo(1))
                .body("weeks[0].takenAt", notNullValue())
                .body("weeks[0].editable", equalTo(true))     // 방금 찍었으니 창 안이다
                .body("weeks[1].takenAt", nullValue())
                .body("weeks[1].editable", equalTo(true))     // 안 찍은 주차는 언제나 열려 있다
                .body("members", hasSize(2))
                .body("members[0].leader", equalTo(true))     // 스터디장이 맨 앞
                .body("members[0].present", contains(1))
                .body("members[1].present", hasSize(0));
    }

    /** 명단에 학번이 없다. 필요 없는 값을 실어 보내면 언젠가 샌다. */
    @Test
    void theGridCarriesNoStudentId() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/attendance")
                .then().statusCode(200)
                .body("members.findAll { it.containsKey('studentId') }", hasSize(0));
    }

    @Test
    void outsiderCannotSeeTheGrid() {
        given().header("Authorization", "Bearer " + outsiderToken)
                .when().get("/api/studies/" + study.getId() + "/attendance")
                .then().statusCode(403);
    }

    /** 분모는 기록된 주차 수다. 전체 주차로 나누면 1주차를 마친 모두가 33%로 보인다. */
    @Test
    void memberSeesOwnAttendanceWithTakenAsDenominator() {
        given().header("Authorization", "Bearer " + joinedToken)
                .when().get("/api/studies/" + study.getId() + "/attendance/me")
                .then().statusCode(200)
                .body("attended", equalTo(0))
                .body("taken", equalTo(1))
                .body("weeks", hasSize(3))
                .body("weeks[0].state", equalTo("ABSENT"))
                .body("weeks[1].state", equalTo("NOT_TAKEN"))
                .body("weeks[2].state", equalTo("NOT_TAKEN"));
    }

    @Test
    void leaderIsAlsoAMemberForOwnAttendance() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/attendance/me")
                .then().statusCode(200)
                .body("attended", equalTo(1))
                .body("taken", equalTo(1))
                .body("weeks[0].state", equalTo("PRESENT"));
    }

    @Test
    void outsiderCannotSeeMyAttendance() {
        given().header("Authorization", "Bearer " + outsiderToken)
                .when().get("/api/studies/" + study.getId() + "/attendance/me")
                .then().statusCode(403);
    }
}
