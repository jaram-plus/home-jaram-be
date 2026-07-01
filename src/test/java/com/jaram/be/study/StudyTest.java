package com.jaram.be.study;

import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.JwtProvider;
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
class StudyTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired JwtProvider jwt;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
    }

    // ── helpers ──

    // `tag` only keeps call sites readable; the real id is the persisted UUID,
    // which token(m) then carries as the JWT subject.
    private Member member(String tag, String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        return members.save(m);
    }

    private String token(Member m) {
        return jwt.generate(m.getId(), m.getName(), m.getEmail(), Authority.MEMBER);
    }

    private Study approvedStudy(String leaderId, int cap) {
        Study s = Study.create("알고리즘", List.of("PS"), cap, null, null, null, null, leaderId);
        s.approve();
        return studies.save(s);
    }

    // ── UC-T3 개설 신청 ──

    @Test
    void createReturns201AndIsPendingHiddenFromPublicList() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        given().header("Authorization", "Bearer " + token(leader))
                .contentType("application/json")
                .body(Map.of("title", "알고리즘", "fields", List.of("PS"), "capacity", 6))
                .when().post("/api/studies")
                .then().statusCode(201)
                .body("title", equalTo("알고리즘"))
                .body("leader", equalTo("리더"))
                .body("cur", equalTo(0))
                .body("cap", equalTo(6))
                .body("status", equalTo("RECRUITING"))
                .body("apply", equalTo("JOINED"));

        // approvalStatus=PENDING → not in public list
        given().when().get("/api/studies").then().statusCode(200).body("size()", equalTo(0));
    }

    @Test
    void createWithoutCapacityReturns422() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        given().header("Authorization", "Bearer " + token(leader))
                .contentType("application/json")
                .body(Map.of("title", "알고리즘", "fields", List.of("PS")))
                .when().post("/api/studies")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    // ── UC-T1 목록 ──

    @Test
    void listReturnsOnlyApprovedWithApplyNullWhenAnonymous() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        approvedStudy(leader.getId(), 5);

        given().when().get("/api/studies").then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].leader", equalTo("리더"))
                .body("[0].apply", nullValue());
    }

    // ── UC-T2 지원 ──

    @Test
    void applySucceedsThenDuplicateReturns409() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Member applicant = member("a", "지원자", "2023000002", "a@hanyang.ac.kr");
        Study s = approvedStudy(leader.getId(), 5);

        given().header("Authorization", "Bearer " + token(applicant))
                .contentType("application/json").body(Map.of("motive", "열심히"))
                .when().post("/api/studies/" + s.getId() + "/apply")
                .then().statusCode(201);

        given().header("Authorization", "Bearer " + token(applicant))
                .contentType("application/json").body(Map.of("motive", "또"))
                .when().post("/api/studies/" + s.getId() + "/apply")
                .then().statusCode(409).body("code", equalTo("ALREADY_APPLIED"));
    }

    @Test
    void leaderCannotApplyToOwnStudy() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Study s = approvedStudy(leader.getId(), 5);

        given().header("Authorization", "Bearer " + token(leader))
                .contentType("application/json").body(Map.of("motive", "내스터디"))
                .when().post("/api/studies/" + s.getId() + "/apply")
                .then().statusCode(409).body("code", equalTo("LEADER_SELF"));
    }

    @Test
    void applyToPendingStudyReturns409() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Member applicant = member("a", "지원자", "2023000002", "a@hanyang.ac.kr");
        Study s = studies.save(Study.create("대기", List.of("x"), 5, null, null, null, null, leader.getId()));

        given().header("Authorization", "Bearer " + token(applicant))
                .contentType("application/json").body(Map.of("motive", "동기"))
                .when().post("/api/studies/" + s.getId() + "/apply")
                .then().statusCode(409).body("code", equalTo("RECRUIT_CLOSED"));
    }

    @Test
    void applyToUnknownStudyReturns404() {
        Member applicant = member("a", "지원자", "2023000002", "a@hanyang.ac.kr");
        given().header("Authorization", "Bearer " + token(applicant))
                .contentType("application/json").body(Map.of("motive", "동기"))
                .when().post("/api/studies/nope/apply")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    @Test
    void applyWithoutMotiveReturns422() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Member applicant = member("a", "지원자", "2023000002", "a@hanyang.ac.kr");
        Study s = approvedStudy(leader.getId(), 5);

        given().header("Authorization", "Bearer " + token(applicant))
                .contentType("application/json").body(Map.of())
                .when().post("/api/studies/" + s.getId() + "/apply")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void applyWhenFullReturns409() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Member first = member("f", "선착", "2023000002", "f@hanyang.ac.kr");
        Member second = member("s", "후착", "2023000003", "s@hanyang.ac.kr");
        Study s = approvedStudy(leader.getId(), 1);
        StudyApplication a = StudyApplication.create(s.getId(), first.getId(), "동기");
        a.approve();
        applications.save(a);

        given().header("Authorization", "Bearer " + token(second))
                .contentType("application/json").body(Map.of("motive", "동기"))
                .when().post("/api/studies/" + s.getId() + "/apply")
                .then().statusCode(409).body("code", equalTo("RECRUIT_CLOSED"));
    }

    @Test
    void rejectedApplicantSeesApplyClosedNotOpen() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Member applicant = member("a", "지원자", "2023000002", "a@hanyang.ac.kr");
        Study s = approvedStudy(leader.getId(), 5);
        StudyApplication app = StudyApplication.create(s.getId(), applicant.getId(), "동기");
        app.reject("사정");
        applications.save(app);

        // capacity is free, but the rejected row blocks re-apply → must not read OPEN
        given().header("Authorization", "Bearer " + token(applicant))
                .when().get("/api/studies").then().statusCode(200)
                .body("[0].apply", equalTo("CLOSED"));
    }

    // ── UC-T4 내 활동 ──

    @Test
    void myActivityReturnsAppsAndLedStudies() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Member other = member("o", "타인", "2023000009", "o@hanyang.ac.kr");
        Study led = studies.save(Study.create("내스터디", List.of("x"), 5, null, null, null, null, leader.getId()));
        Study otherStudy = approvedStudy(other.getId(), 5);
        applications.save(StudyApplication.create(otherStudy.getId(), leader.getId(), "지원"));

        given().header("Authorization", "Bearer " + token(leader))
                .when().get("/api/studies/my").then().statusCode(200)
                .body("apps.size()", equalTo(1))
                .body("apps[0].title", equalTo("알고리즘"))
                .body("apps[0].status", equalTo("PENDING"))
                .body("studies.size()", equalTo(1))
                .body("studies[0].title", equalTo("내스터디"))
                .body("studies[0].approvalStatus", equalTo("PENDING"));
    }

    // ── UC-T5 개설 대기 목록 ──

    @Test
    void pendingListOfficerOnly() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        studies.save(Study.create("대기스터디", List.of("x"), 5, null, null, null, null, leader.getId()));

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/studies/pending").then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].creator", equalTo("리더"))
                .body("[0].title", equalTo("대기스터디"));

        given().header("Authorization", "Bearer " + token(leader))
                .when().get("/api/studies/pending").then().statusCode(403);
    }

    // ── UC-T6 개설 승인/거절 ──

    @Test
    void approveStudyMakesItPublic() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Study s = studies.save(Study.create("승인대상", List.of("x"), 5, null, null, null, null, leader.getId()));

        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/studies/" + s.getId() + "/approve").then().statusCode(200);

        given().when().get("/api/studies").then().statusCode(200).body("size()", equalTo(1));
    }

    @Test
    void rejectStudyStoresReasonAndRequiresIt() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Study s = studies.save(Study.create("반려대상", List.of("x"), 5, null, null, null, null, leader.getId()));

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of())
                .when().post("/api/studies/" + s.getId() + "/reject").then().statusCode(422);

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of("reason", "주제 중복"))
                .when().post("/api/studies/" + s.getId() + "/reject").then().statusCode(200);

        org.assertj.core.api.Assertions.assertThat(
                studies.findById(s.getId()).orElseThrow().getApprovalStatus())
                .isEqualTo(ApprovalStatus.REJECTED);
    }

    // ── UC-T7 신청자 목록 ──

    @Test
    void applicantsListOfficerOnly() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Member applicant = member("a", "지원자", "2023000002", "a@hanyang.ac.kr");
        Study s = approvedStudy(leader.getId(), 5);
        applications.save(StudyApplication.create(s.getId(), applicant.getId(), "동기"));

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/studies/applicants").then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].name", equalTo("지원자"))
                .body("[0].studentId", equalTo("2023000002"))
                .body("[0].studyTitle", equalTo("알고리즘"));
    }

    // ── UC-T8 신청자 승인/거절 ──

    @Test
    void approveApplicantIncrementsCurUntilFull() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Member first = member("f", "선착", "2023000002", "f@hanyang.ac.kr");
        Member second = member("s", "후착", "2023000003", "s@hanyang.ac.kr");
        Study s = approvedStudy(leader.getId(), 1);
        StudyApplication a1 = applications.save(StudyApplication.create(s.getId(), first.getId(), "동기1"));
        StudyApplication a2 = applications.save(StudyApplication.create(s.getId(), second.getId(), "동기2"));

        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/studies/applicants/" + a1.getId() + "/approve").then().statusCode(200);

        // capacity 1 now full → second approval 409
        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/studies/applicants/" + a2.getId() + "/approve")
                .then().statusCode(409).body("code", equalTo("CAPACITY_FULL"));
    }

    @Test
    void rejectApplicantStoresReason() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Member applicant = member("a", "지원자", "2023000002", "a@hanyang.ac.kr");
        Study s = approvedStudy(leader.getId(), 5);
        StudyApplication app = applications.save(StudyApplication.create(s.getId(), applicant.getId(), "동기"));

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of("reason", "정원 사정"))
                .when().post("/api/studies/applicants/" + app.getId() + "/reject").then().statusCode(200);

        org.assertj.core.api.Assertions.assertThat(
                applications.findById(app.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.REJECTED);
    }
}
