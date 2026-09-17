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
class StudyApplicantListTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired Actors actors;

    private String leaderToken, outsiderToken;
    private Study study;

    @BeforeEach void setup() {
        RestAssured.port = port;
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        Member leader   = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr", 40));
        Member waiting  = members.save(approved("대기", "2023000002", "w@hanyang.ac.kr", 41));
        Member accepted = members.save(approved("확정", "2023000003", "a@hanyang.ac.kr", 40));
        Member turned   = members.save(approved("반려", "2023000004", "r@hanyang.ac.kr", 41));
        Member outsider = members.save(approved("남",  "2023000005", "o@hanyang.ac.kr", 41));
        leaderToken   = actors.tokenFor(leader);
        outsiderToken = actors.tokenFor(outsider);

        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leader.getId());
        s.approve();
        study = studies.save(s);

        applications.save(StudyApplication.create(study.getId(), waiting.getId(), "하고 싶습니다"));

        StudyApplication ok = StudyApplication.create(study.getId(), accepted.getId(), "저도요");
        ok.approve();
        applications.save(ok);

        StudyApplication no = StudyApplication.create(study.getId(), turned.getId(), "저도요");
        no.reject("이번엔 어렵습니다");
        applications.save(no);
    }

    private Member approved(String name, String sid, String email, int gen) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGen(gen);
        return m;
    }

    @Test
    void leaderSeesPendingAndApprovedButNotRejected() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/applicants")
                .then().statusCode(200)
                .body("pending", hasSize(1))
                .body("pending[0].name", equalTo("대기"))
                .body("pending[0].motive", equalTo("하고 싶습니다"))
                .body("approved", hasSize(1))
                .body("approved[0].name", equalTo("확정"));
    }

    /** 승인이 끝난 사람의 지원동기를 계속 보여줄 이유가 없다. */
    @Test
    void approvedEntriesCarryNoMotive() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/applicants")
                .then().statusCode(200)
                .body("approved[0].motive", nullValue());
    }

    /**
     * 학번은 마스킹해 싣는다. 이름과 기수만으로는 동명이인이 갈리지 않는데, 승인·반려·
     * 내보내기는 사람을 잘못 고르면 되돌리기 어렵다. 규칙은 상세 명단과 같다.
     */
    @Test
    void studentIdsAreMasked() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/applicants")
                .then().statusCode(200)
                .body("pending[0].studentId", equalTo("2023*****2"))
                .body("approved[0].studentId", equalTo("2023*****3"));
    }

    @Test
    void anotherMemberCannotSeeTheList() {
        given().header("Authorization", "Bearer " + outsiderToken)
                .when().get("/api/studies/" + study.getId() + "/applicants")
                .then().statusCode(403);
    }
}
