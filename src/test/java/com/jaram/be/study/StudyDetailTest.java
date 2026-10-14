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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyDetailTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyApplicationRepository applications;
    @Autowired Actors actors;

    private Study study;
    private String viewerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        Member leader = save("리더", "2022123459", "leader@hanyang.ac.kr", 40);
        study = Study.create("알고리즘", List.of("PS"), 8,
                "화 19:00", "공학관 401", "오프라인", "함께 풉니다", "010-1111-2222", leader.getId());
        study.approve();
        studies.save(study);
        weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        weeks.save(StudyWeek.create(study.getId(), 2, "이분탐색", "파라메트릭"));

        Member pending = save("가나", "20231111", "p@hanyang.ac.kr", 42);
        Member approved = save("다라", "20230000", "q@hanyang.ac.kr", 41);
        Member rejected = save("마바", "20239999", "r@hanyang.ac.kr", 41);
        applications.save(StudyApplication.create(study.getId(), pending.getId(), "동기"));
        StudyApplication ok = applications.save(
                StudyApplication.create(study.getId(), approved.getId(), "동기"));
        ok.approve();
        applications.save(ok);
        StudyApplication no = applications.save(
                StudyApplication.create(study.getId(), rejected.getId(), "동기"));
        no.reject("사유");
        applications.save(no);

        viewerToken = actors.member();
    }

    private Member save(String name, String sid, String email, int gen) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGen(gen);
        return members.save(m);
    }

    @Test
    void detailRequiresLoginWhileTheListStaysPublic() {
        given().when().get("/api/studies/" + study.getId()).then().statusCode(401);
        given().when().get("/api/studies").then().statusCode(200);
    }

    @Test
    void detailCarriesCurriculumContactAndPlace() {
        given().header("Authorization", "Bearer " + viewerToken)
                .when().get("/api/studies/" + study.getId())
                .then().statusCode(200)
                .body("title", equalTo("알고리즘"))
                .body("leader", equalTo("리더"))
                .body("leaderGen", equalTo(40))
                .body("place", equalTo("공학관 401"))
                .body("contact", equalTo("010-1111-2222"))
                .body("cur", equalTo(1))
                .body("cap", equalTo(8))
                .body("weeks.size()", equalTo(2))
                .body("weeks[0].weekNo", equalTo(1))
                .body("weeks[1].content", equalTo("파라메트릭"));
    }

    @Test
    void rosterHidesRejectedApplicantsTheLeaderAndEveryApprovalState() {
        given().header("Authorization", "Bearer " + viewerToken)
                .when().get("/api/studies/" + study.getId())
                .then().statusCode(200)
                // 대기 1 + 승인 1. 반려와 스터디장은 빠진다.
                .body("roster.size()", equalTo(2))
                // 기수 → 이름. 41기 '다라' 가 42기 '가나' 보다 앞이다.
                .body("roster[0].name", equalTo("다라"))
                .body("roster[1].name", equalTo("가나"))
                .body("roster[0].studentId", equalTo("2023***0"))
                .body("roster[1].studentId", equalTo("2023***1"))
                .body("roster[0]", not(hasKey("status")))
                .body("roster[0]", not(hasKey("applicationStatus")));
    }

    @Test
    void rosterNeverCarriesAFullStudentId() {
        String body = given().header("Authorization", "Bearer " + viewerToken)
                .when().get("/api/studies/" + study.getId())
                .then().statusCode(200).extract().asString();
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("20231111").doesNotContain("20230000")
                .doesNotContain("20239999").doesNotContain("2022123459");
    }

    @Test
    void literalPathsStillWinOverTheIdPattern() {
        given().header("Authorization", "Bearer " + viewerToken)
                .when().get("/api/studies/my").then().statusCode(200);
        given().header("Authorization", "Bearer " + actors.officer())
                .when().get("/api/studies/pending").then().statusCode(200);
        given().header("Authorization", "Bearer " + actors.officer())
                .when().get("/api/studies/applicants").then().statusCode(200);
    }
}
