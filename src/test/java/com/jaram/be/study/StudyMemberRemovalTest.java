package com.jaram.be.study;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * DELETE /api/studies/{id}/members/{applicationId} — 참여 확정된 사람을 내보낸다.
 *
 * 본인이 자기 반려 신청을 지우는 DELETE /api/studies/applicants/{id} 와 뜻이 다르다.
 * 저쪽은 다시 신청할 길을 여는 것이고, 여기는 남을 명단에서 빼는 것이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyMemberRemovalTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyAttendanceRepository attendance;
    @Autowired Actors actors;

    private Member leader, joined, waiting;
    private String leaderToken;
    private Study study;
    private StudyApplication joinedApp, waitingApp;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendance.deleteAll();
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        leader  = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr"));
        joined  = members.save(approved("확정", "2023000002", "j@hanyang.ac.kr"));
        waiting = members.save(approved("대기", "2023000003", "w@hanyang.ac.kr"));
        leaderToken = actors.tokenFor(leader);

        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leader.getId());
        s.approve();
        study = studies.save(s);

        joinedApp = StudyApplication.create(study.getId(), joined.getId(), "하고 싶습니다");
        joinedApp.approve();
        joinedApp = applications.save(joinedApp);

        waitingApp = applications.save(
                StudyApplication.create(study.getId(), waiting.getId(), "저도요"));
    }

    private Member approved(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        return m;
    }

    private io.restassured.response.Response remove(String token, String applicationId) {
        return given().header("Authorization", "Bearer " + token)
                .when().delete("/api/studies/" + study.getId() + "/members/" + applicationId);
    }

    @Test
    void leaderRemovesAConfirmedMember() {
        remove(leaderToken, joinedApp.getId()).then().statusCode(204);

        assertThat(applications.findById(joinedApp.getId())).isEmpty();
        // 대기 중인 신청은 그대로다 — 내보내기는 명단만 건드린다.
        assertThat(applications.findById(waitingApp.getId())).isPresent();
    }

    /** 명단에 없는 사람의 출석이 남으면 출석률의 분모와 분자가 어긋난다. */
    @Test
    void theirAttendanceGoesWithThem() {
        StudyWeek w1 = weeks.save(StudyWeek.create(study.getId(), 1, "1주차", null));
        StudyWeek w2 = weeks.save(StudyWeek.create(study.getId(), 2, "2주차", null));
        Instant now = Instant.now();
        attendance.save(StudyAttendance.create(w1.getId(), joined.getId(), now));
        attendance.save(StudyAttendance.create(w2.getId(), joined.getId(), now));
        attendance.save(StudyAttendance.create(w1.getId(), leader.getId(), now));

        remove(leaderToken, joinedApp.getId()).then().statusCode(204);

        List<StudyAttendance> left = attendance.findByWeekIdIn(List.of(w1.getId(), w2.getId()));
        assertThat(left).extracting(StudyAttendance::getMemberId)
                .containsExactly(leader.getId());
    }

    /** 내보낸 뒤에는 다시 신청할 수 있다. (studyId, applicantId) 유니크가 풀린다. */
    @Test
    void theyMayApplyAgain() {
        remove(leaderToken, joinedApp.getId()).then().statusCode(204);

        given().header("Authorization", "Bearer " + actors.tokenFor(joined))
                .contentType(io.restassured.http.ContentType.JSON)
                .body(java.util.Map.of("motive", "다시 해보겠습니다"))
                .when().post("/api/studies/" + study.getId() + "/apply")
                .then().statusCode(201);
    }

    /** 대기 중인 신청은 '내보내기'가 아니라 반려로 처리한다. */
    @Test
    void aPendingApplicationIsNotAMember() {
        remove(leaderToken, waitingApp.getId()).then()
                .statusCode(409).body("code", equalTo("NOT_APPROVED"));

        assertThat(applications.findById(waitingApp.getId())).isPresent();
    }

    /** 끝난 스터디의 명단이 나중에 바뀌면 그 기록을 근거로 한 것이 흔들린다. */
    @Test
    void finishedStudyIsRefused() {
        study.closeRecruiting();
        study.finish();
        studies.save(study);

        remove(leaderToken, joinedApp.getId()).then()
                .statusCode(409).body("code", equalTo("STUDY_FINISHED"));

        assertThat(applications.findById(joinedApp.getId())).isPresent();
    }

    /**
     * 남의 스터디 신청 id 를 끼워 넣어도 지워지지 않는다. 게이트는 경로의 스터디에
     * 대해서만 판정하므로, 서비스가 신청과 스터디를 대조하지 않으면 여기가 뚫린다.
     */
    @Test
    void anApplicationOfAnotherStudyIsNotFound() {
        Study other = Study.create("다른", List.of("PS"), 4,
                "수 19:00", "402호", "오프라인", "소개", "010-0000-0001", leader.getId());
        other.approve();
        other = studies.save(other);
        StudyApplication alien = StudyApplication.create(other.getId(), waiting.getId(), "저기요");
        alien.approve();
        alien = applications.save(alien);

        remove(leaderToken, alien.getId()).then().statusCode(404);
        assertThat(applications.findById(alien.getId())).isPresent();
    }

    @Test
    void anotherMemberIsRefused() {
        remove(actors.tokenFor(joined), joinedApp.getId()).then().statusCode(403);
        assertThat(applications.findById(joinedApp.getId())).isPresent();
    }

    @Test
    void anonymousIsRefused() {
        given().when().delete("/api/studies/" + study.getId() + "/members/" + joinedApp.getId())
                .then().statusCode(401);
    }

    /**
     * 학술부원은 STUDY_APPLICANT_MANAGE 만 갖는다. 명단을 만드는 손과 명단에서 빼는
     * 손이 다르면 부원이 승인만 하고 되돌리지 못한다.
     */
    @Test
    void staffWithApplicantManageMayRemove() {
        remove(actors.token(Role.ACADEMIC_STAFF), joinedApp.getId()).then().statusCode(204);
    }
}
