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
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyWeekEditTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyAttendanceRepository attendance;
    @Autowired Actors actors;

    private Member leader;
    private String leaderToken;
    private Study study;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendance.deleteAll();
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        leader = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr"));
        leaderToken = actors.tokenFor(leader);

        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leader.getId());
        s.approve();
        s.closeRecruiting();
        study = studies.save(s);

        weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        weeks.save(StudyWeek.create(study.getId(), 2, "그리디", null));
    }

    private Member approved(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        return m;
    }

    private String base() { return "/api/studies/" + study.getId() + "/weeks"; }

    private int addWeek(String title) {
        return given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json")
                .body(Map.of("title", title))
                .when().post(base())
                .then().extract().statusCode();
    }

    private int deleteWeek(int weekNo) {
        return given().header("Authorization", "Bearer " + leaderToken)
                .when().delete(base() + "/" + weekNo)
                .then().extract().statusCode();
    }

    @Test
    void addsAtTheTailOnly() {
        assertThat(addWeek("DP")).isEqualTo(201);

        assertThat(weeks.findByStudyIdOrderByWeekNoAsc(study.getId()))
                .extracting(StudyWeek::getWeekNo)
                .containsExactly(1, 2, 3);
        assertThat(weeks.findByStudyIdAndWeekNo(study.getId(), 3).orElseThrow().getTitle())
                .isEqualTo("DP");
    }

    /** 제목·내용 수정은 언제나 된다 — 출석이 기록된 주차도 마찬가지다(D14). */
    @Test
    void editsTitleEvenAfterAttendanceWasTaken() {
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("present", List.of(leader.getId())))
                .when().put(base() + "/1/attendance").then().statusCode(204);

        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("title", "완전탐색(개정)"))
                .when().put(base() + "/1").then().statusCode(204);

        assertThat(weeks.findByStudyIdAndWeekNo(study.getId(), 1).orElseThrow().getTitle())
                .isEqualTo("완전탐색(개정)");
    }

    @Test
    void deletesTheTailOnly() {
        assertThat(deleteWeek(2)).isEqualTo(204);
        assertThat(weeks.findByStudyIdOrderByWeekNoAsc(study.getId()))
                .extracting(StudyWeek::getWeekNo).containsExactly(1);
    }

    /** 번호를 재배열하지 않으므로 중간 삭제를 아예 막는다. */
    @Test
    void middleWeekCannotBeDeleted() {
        assertThat(deleteWeek(1)).isEqualTo(409);
        assertThat(weeks.countByStudyId(study.getId())).isEqualTo(2);
    }

    /** D5 가 최소 1주차를 요구한다. */
    @Test
    void theLastRemainingWeekCannotBeDeleted() {
        deleteWeek(2);
        assertThat(deleteWeek(1)).isEqualTo(409);
        assertThat(weeks.countByStudyId(study.getId())).isEqualTo(1);
    }

    /** 출석이 찍힌 주차를 지우는 것은 그 출석을 지우는 것이다 — 같은 창을 쓴다. */
    @Test
    void weekWithAttendanceCannotBeDeletedOnceTheWindowClosed() {
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("present", List.of(leader.getId())))
                .when().put(base() + "/2/attendance").then().statusCode(204);

        StudyWeek w = weeks.findByStudyIdAndWeekNo(study.getId(), 2).orElseThrow();
        weeks.save(backdate(w, java.time.Instant.now().minusSeconds(25 * 3600)));

        assertThat(deleteWeek(2)).isEqualTo(409);
        assertThat(weeks.countByStudyId(study.getId())).isEqualTo(2);
    }

    /** 창 안이면 출석까지 같이 지워진다. */
    @Test
    void deletingInsideTheWindowAlsoRemovesItsAttendance() {
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("present", List.of(leader.getId())))
                .when().put(base() + "/2/attendance").then().statusCode(204);

        String weekId = weeks.findByStudyIdAndWeekNo(study.getId(), 2).orElseThrow().getId();
        assertThat(deleteWeek(2)).isEqualTo(204);
        assertThat(attendance.findByWeekId(weekId)).isEmpty();
    }

    @Test
    void finishedStudyRejectsEveryWrite() {
        study.finish();
        studies.save(study);

        assertThat(addWeek("DP")).isEqualTo(409);
        assertThat(deleteWeek(2)).isEqualTo(409);
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("title", "x"))
                .when().put(base() + "/1").then().statusCode(409);
    }

    @Test
    void blankTitleIsRejected() {
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("title", "  "))
                .when().post(base()).then().statusCode(422);
    }

    private StudyWeek backdate(StudyWeek w, java.time.Instant at) {
        try {
            var f = StudyWeek.class.getDeclaredField("takenAt");
            f.setAccessible(true);
            f.set(w, at);
            return w;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
