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

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyTransitionTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired StudyWeekRepository weeks;
    @Autowired Actors actors;

    private Member leader;
    private String leaderToken;
    private Study study;

    @BeforeEach void setup() {
        RestAssured.port = port;
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        leader = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr"));
        leaderToken = actors.tokenFor(leader);
        study = recruiting(leader.getId());
    }

    private Member approved(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        return m;
    }

    private Study recruiting(String leaderId) {
        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leaderId);
        s.approve();
        return studies.save(s);
    }

    private void post(String token, String path, int expected) {
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/studies/" + study.getId() + path)
                .then().statusCode(expected);
    }

    @Test
    void leaderClosesRecruitingThenFinishes() {
        post(leaderToken, "/close-recruiting", 204);
        assertThat(studies.findById(study.getId()).orElseThrow().getStatus())
                .isEqualTo(StudyStatus.ONGOING);

        post(leaderToken, "/finish", 204);
        assertThat(studies.findById(study.getId()).orElseThrow().getStatus())
                .isEqualTo(StudyStatus.FINISHED);
    }

    @Test
    void anotherLeaderIsRefused() {
        Member other = members.save(approved("남", "2023000002", "other@hanyang.ac.kr"));
        recruiting(other.getId());   // 자기 스터디는 따로 있다
        post(actors.tokenFor(other), "/close-recruiting", 403);
    }

    @Test
    void plainMemberIsRefused() {
        post(actors.member(), "/close-recruiting", 403);
    }

    @Test
    void officerWithStudyEditMayActOnSomeoneElsesStudy() {
        post(actors.token(Role.ACADEMIC_LEAD), "/close-recruiting", 204);
    }

    @Test
    void transitionsRefuseTheWrongStartingState() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().post("/api/studies/" + study.getId() + "/finish")
                .then().statusCode(409).body("code", equalTo("INVALID_STATE"));

        post(leaderToken, "/close-recruiting", 204);

        given().header("Authorization", "Bearer " + leaderToken)
                .when().post("/api/studies/" + study.getId() + "/close-recruiting")
                .then().statusCode(409).body("code", equalTo("INVALID_STATE"));
    }

    @Test
    void applicantApprovalIsGatedByTheApplicationsOwnStudy() {
        Member applicant = members.save(approved("지원", "2023000003", "a@hanyang.ac.kr"));
        StudyApplication app = applications.save(
                StudyApplication.create(study.getId(), applicant.getId(), "하고 싶습니다"));

        Member other = members.save(approved("남", "2023000004", "b@hanyang.ac.kr"));
        given().header("Authorization", "Bearer " + actors.tokenFor(other))
                .when().post("/api/studies/applicants/" + app.getId() + "/approve")
                .then().statusCode(403);

        given().header("Authorization", "Bearer " + leaderToken)
                .when().post("/api/studies/applicants/" + app.getId() + "/approve")
                .then().statusCode(200);
    }

    @Test
    void anApplicantCannotApproveTheirOwnStudyProposal() {
        Study mine = studies.save(Study.create("내 것", List.of("PS"), 6,
                "화", "401", "오프라인", "소개", "010", leader.getId()));
        given().header("Authorization", "Bearer " + leaderToken)
                .when().post("/api/studies/" + mine.getId() + "/approve")
                .then().statusCode(403);
    }
}
