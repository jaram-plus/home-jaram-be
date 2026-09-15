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
class StudyApplicationDeleteTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired Actors actors;

    private Member applicant, other;
    private String applicantToken, otherToken;
    private Study study;
    private StudyApplication rejected;

    @BeforeEach void setup() {
        RestAssured.port = port;
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        Member leader = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr"));
        applicant     = members.save(approved("신청", "2023000002", "a@hanyang.ac.kr"));
        other         = members.save(approved("남",  "2023000003", "o@hanyang.ac.kr"));
        applicantToken = actors.tokenFor(applicant);
        otherToken     = actors.tokenFor(other);

        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leader.getId());
        s.approve();
        study = studies.save(s);

        StudyApplication a = StudyApplication.create(study.getId(), applicant.getId(), "하고 싶습니다");
        a.reject("이번엔 어렵습니다");
        rejected = applications.save(a);
    }

    private Member approved(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        return m;
    }

    private int delete(String token, String applicationId) {
        return given().header("Authorization", "Bearer " + token)
                .when().delete("/api/studies/applicants/" + applicationId)
                .then().extract().statusCode();
    }

    /** 삭제가 재신청을 연다 — deriveApply 에 새 분기가 생기지 않는다. */
    @Test
    void deletingARejectedApplicationReopensApplying() {
        assertThat(delete(applicantToken, rejected.getId())).isEqualTo(204);
        assertThat(applications.findById(rejected.getId())).isEmpty();

        given().header("Authorization", "Bearer " + applicantToken)
                .contentType("application/json").body(Map.of("motive", "다시 신청합니다"))
                .when().post("/api/studies/" + study.getId() + "/apply")
                .then().statusCode(201);
    }

    @Test
    void anotherMemberCannotDeleteMyApplication() {
        assertThat(delete(otherToken, rejected.getId())).isEqualTo(403);
        assertThat(applications.findById(rejected.getId())).isPresent();
    }

    @Test
    void pendingApplicationCannotBeDeleted() {
        StudyApplication pending = applications.save(
                StudyApplication.create(study.getId(), other.getId(), "저도요"));

        assertThat(delete(otherToken, pending.getId())).isEqualTo(409);
        assertThat(applications.findById(pending.getId())).isPresent();
    }

    /** 승인된 신청을 본인이 지울 수 있으면 그것은 탈퇴이고, 탈퇴는 이 단계에 없다. */
    @Test
    void approvedApplicationCannotBeDeleted() {
        StudyApplication ok = StudyApplication.create(study.getId(), other.getId(), "저도요");
        ok.approve();
        applications.save(ok);

        assertThat(delete(otherToken, ok.getId())).isEqualTo(409);
    }

    @Test
    void unknownApplicationIsForbiddenNotFound() {
        // 없는 신청은 소유자 조건이 false 라 게이트에서 막힌다. 존재 여부가 새지 않는다.
        assertThat(delete(applicantToken, "no-such-id")).isEqualTo(403);
    }
}
