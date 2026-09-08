package com.jaram.be.admin;

import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
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

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminReregistrationTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        officerToken = jwt.generate("officer-id", "임원", "exec@hanyang.ac.kr", Authority.OFFICER);
    }

    private Member saved(String name, String studentId, String email, MemberStatus status,
                         MemberApproval approval) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(approval);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setStatus(status);
        m.setGen(41);
        return members.save(m);
    }

    /** 가입 대기와 재등록 필요가 한 목록에 kind 로 구분되어 실린다. */
    @Test
    void pendingListCarriesBothKinds() {
        saved("신입", "2026011111", "new@hanyang.ac.kr", MemberStatus.ACTIVE, MemberApproval.PENDING);
        saved("재등록", "2023022222", "re@hanyang.ac.kr", MemberStatus.REREGISTER, MemberApproval.APPROVED);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/pending")
                .then().statusCode(200)
                .body("kind", containsInAnyOrder("SIGNUP", "REREGISTER"));
    }

    /**
     * 파기된 회원은 승인 탭에도 뜨지 않는다. Member.purge 가 상태를 건드리지 않아
     * 이력이 남은 회원은 파기 뒤에도 APPROVED+REREGISTER 로 남는데, 그대로 두면
     * 학번·이메일이 빈 줄이 목록에 영원히 남는다 — 삭제를 눌러도 파기가 행을 남겨
     * 사라지지 않는다. 인원 관리 표는 이미 같은 기준으로 거른다.
     */
    @Test
    void pendingListHidesPurgedMembers() {
        Member purged = saved("파기됨", "2020033333", "gone@hanyang.ac.kr",
                MemberStatus.REREGISTER, MemberApproval.APPROVED);
        purged.purge(Instant.now());
        members.save(purged);
        saved("재등록", "2023022222", "re@hanyang.ac.kr", MemberStatus.REREGISTER, MemberApproval.APPROVED);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/pending")
                .then().statusCode(200)
                .body("name", containsInAnyOrder("재등록"));
    }

    /** 신청하지 않은 재등록 대상은 requestedAt 이 null 이다. */
    @Test
    void unrequestedReregistrationHasNullRequestedAt() {
        saved("재등록", "2023022222", "re@hanyang.ac.kr", MemberStatus.REREGISTER, MemberApproval.APPROVED);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/pending")
                .then().statusCode(200)
                .body("find { it.kind == 'REREGISTER' }.requestedAt", nullValue());
    }

    @Test
    void requestedReregistrationCarriesTime() {
        Member m = saved("재등록", "2023022222", "re@hanyang.ac.kr",
                MemberStatus.REREGISTER, MemberApproval.APPROVED);
        m.requestReregistration(Instant.parse("2027-03-05T00:00:00Z"));
        members.save(m);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/pending")
                .then().statusCode(200)
                .body("find { it.kind == 'REREGISTER' }.requestedAt", notNullValue());
    }

    @Test
    void approvingReregistrationReturnsMemberToActive() {
        Member m = saved("재등록", "2023022222", "re@hanyang.ac.kr",
                MemberStatus.REREGISTER, MemberApproval.APPROVED);
        m.requestReregistration(Instant.parse("2027-03-05T00:00:00Z"));
        members.save(m);

        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/members/" + m.getId() + "/reregister")
                .then().statusCode(204);

        Member found = members.findById(m.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(found.getReregisterRequestedAt()).isNull();
    }

    @Test
    void approvingNonReregisterMemberConflicts() {
        Member m = saved("홍길동", "2023012345", "hong@hanyang.ac.kr",
                MemberStatus.ACTIVE, MemberApproval.APPROVED);

        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/members/" + m.getId() + "/reregister")
                .then().statusCode(409);
    }
}
