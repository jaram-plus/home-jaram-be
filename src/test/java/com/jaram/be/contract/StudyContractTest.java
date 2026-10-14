package com.jaram.be.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.study.Study;
import com.jaram.be.study.StudyApplication;
import com.jaram.be.study.StudyApplicationRepository;
import com.jaram.be.study.StudyRecruitmentRepository;
import com.jaram.be.study.StudyRepository;
import com.jaram.be.study.StudyService;
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
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyContractTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired StudyRecruitmentRepository recruitment;
    @Autowired StudyService studyService;
    @Autowired Actors actors;

    // swagger-request-validator 2.43.0 mis-handles OAS 3.1 `type: string` path parameters
    // (see SeminarContractTest); downgrade only that spurious request-param error.
    private final OpenApiValidationFilter validation = new OpenApiValidationFilter(
            OpenApiInteractionValidator.createForSpecificationUrl("openapi/openapi.yaml")
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request.parameter.schema.invalidJson",
                                    ValidationReport.Level.IGNORE)
                            .build())
                    .build());

    private String officerToken;
    private Member leader;

    @BeforeEach void setup() {
        RestAssured.port = port;
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();
        recruitment.deleteAll();
        studyService.setRecruitmentOpen(true);
        officerToken = actors.officer();
        leader = approvedMember("리더", "2023000001", "leader@hanyang.ac.kr");
    }

    private Member approvedMember(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        return members.save(m);
    }

    private String token(Member m) {
        return actors.tokenFor(m);
    }

    @Test
    void createStudyMatchesContract() {
        given().filter(validation)
                .header("Authorization", "Bearer " + token(leader))
                .contentType("application/json")
                .body(Map.of(
                        "title", "알고리즘",
                        "fields", List.of("PS"),
                        "capacity", 6,
                        "intro", "함께 풉니다",
                        "schedule", "매주 화 19:00",
                        "place", "공학관 401",
                        "mode", "오프라인",
                        "contact", "010-0000-0000",
                        "weeks", List.of(Map.of("weekNo", 1, "title", "완전탐색"))))
                .when().post("/api/studies")
                .then().statusCode(201);
    }

    @Test
    void listStudiesMatchesContract() {
        Study s = Study.create("알고리즘", List.of("PS"), 6, "월 19시", "공학관 401", "온라인", "소개", "010-0000-0000", leader.getId());
        s.approve();
        studies.save(s);

        // 검증기가 응답 본문의 모양(배열 vs 객체)까지 잡아 주지는 않는다.
        // 감싼 모양을 여기서 직접 고정한다.
        given().filter(validation)
                .when().get("/api/studies")
                .then().statusCode(200)
                .body("recruiting", equalTo(true))
                .body("items.size()", equalTo(1));
    }

    @Test
    void myActivityMatchesContract() {
        Member applicant = approvedMember("지원자", "2023000002", "a@hanyang.ac.kr");
        Study led = studies.save(Study.create("내스터디", List.of("x"), 5, null, null, null, null, null, applicant.getId()));
        Study other = Study.create("타스터디", List.of("y"), 5, null, null, null, null, null, leader.getId());
        other.approve();
        studies.save(other);
        applications.save(StudyApplication.create(other.getId(), applicant.getId(), "동기"));

        given().filter(validation)
                .header("Authorization", "Bearer " + token(applicant))
                .when().get("/api/studies/my")
                .then().statusCode(200);
    }

    @Test
    void pendingStudiesMatchesContract() {
        studies.save(Study.create("대기", List.of("x"), 5, "월", "공학관 401", "온라인", "소개", "010-0000-0000", leader.getId()));
        given().filter(validation)
                .header("Authorization", "Bearer " + officerToken)
                .when().get("/api/studies/pending")
                .then().statusCode(200);
    }

    @Test
    void applicantsMatchesContract() {
        Member applicant = approvedMember("지원자", "2023000002", "a@hanyang.ac.kr");
        Study s = Study.create("알고리즘", List.of("PS"), 5, null, null, null, null, null, leader.getId());
        s.approve();
        studies.save(s);
        applications.save(StudyApplication.create(s.getId(), applicant.getId(), "동기"));

        given().filter(validation)
                .header("Authorization", "Bearer " + officerToken)
                .when().get("/api/studies/applicants")
                .then().statusCode(200);
    }

    @Test
    void applyMatchesContract() {
        Member applicant = approvedMember("지원자", "2023000002", "a@hanyang.ac.kr");
        Study s = Study.create("알고리즘", List.of("PS"), 5, null, null, null, null, null, leader.getId());
        s.approve();
        studies.save(s);

        given().filter(validation)
                .header("Authorization", "Bearer " + token(applicant))
                .contentType("application/json").body(Map.of("motive", "열심히"))
                .when().post("/api/studies/" + s.getId() + "/apply")
                .then().statusCode(201);
    }
}
