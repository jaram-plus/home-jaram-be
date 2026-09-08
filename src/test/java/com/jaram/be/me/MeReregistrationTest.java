package com.jaram.be.me;

import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeReregistrationTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private Member me;
    private String token;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        me = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        me.setApproval(MemberApproval.APPROVED);
        me.setGrade(MemberGrade.ASSOCIATE);
        me.setStatus(MemberStatus.ACTIVE);
        me.setGen(41);
        me = members.save(me);
        token = jwt.generate(me.getId(), me.getName(), me.getEmail(), Authority.MEMBER);
    }

    private Member reload() { return members.findById(me.getId()).orElseThrow(); }

    private void becomeReregister() {
        Member m = reload();
        m.markReregistrationRequired();
        members.save(m);
    }

    @Test
    void reregistrationRequestRecordsTime() {
        becomeReregister();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/reregister")
                .then().statusCode(204);
        assertThat(reload().getReregisterRequestedAt()).isNotNull();
        assertThat(reload().getStatus()).isEqualTo(MemberStatus.REREGISTER);
    }

    /** 두 번 눌러도 처음 신청 시각이 남는다. */
    @Test
    void reregistrationRequestIsIdempotent() {
        becomeReregister();
        given().header("Authorization", "Bearer " + token).post("/api/me/reregister");
        var first = reload().getReregisterRequestedAt();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/reregister")
                .then().statusCode(204);
        assertThat(reload().getReregisterRequestedAt()).isEqualTo(first);
    }

    /**
     * 탈퇴해도 발급된 토큰은 ttl 동안 살아 있다. 로그인만 막으면 방금 탈퇴한 회원이
     * 손에 든 토큰으로 신청류를 계속 쓸 수 있다.
     */
    @Test
    void withdrawnMemberIsBlockedFromActivityWithALiveToken() {
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/withdraw")
                .then().statusCode(204);

        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("motive", "배우고 싶습니다"))
                .when().post("/api/studies/any-id/apply")
                .then().statusCode(403).body("code", org.hamcrest.Matchers.equalTo("WITHDRAWN"));
    }

    /** 탈퇴는 멱등하다 — 두 번 불러도 6개월 파기 시계가 뒤로 밀리지 않는다. */
    @Test
    void withdrawalIsIdempotent() {
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/withdraw").then().statusCode(204);
        java.time.Instant first = reload().getWithdrawnAt();
        assertThat(first).isNotNull();

        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/withdraw").then().statusCode(204);

        assertThat(reload().getWithdrawnAt()).isEqualTo(first);
    }

    @Test
    void activeMemberCannotRequestReregistration() {
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/reregister")
                .then().statusCode(409);
    }

    @Test
    void withdrawMarksMemberAndRecordsTime() {
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/withdraw")
                .then().statusCode(204);
        assertThat(reload().getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(reload().getWithdrawnAt()).isNotNull();
    }

    /** 현직 임기가 있으면 막는다 — 임기를 먼저 정리해야 한다. */
    @Test
    void sittingOfficerCannotWithdraw() {
        Member m = reload();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        members.save(m);

        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/withdraw")
                .then().statusCode(409);
        assertThat(reload().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    /** 탈퇴하면 로그인이 막힌다. */
    @Test
    void withdrawnMemberCannotLogIn() {
        given().header("Authorization", "Bearer " + token).post("/api/me/withdraw");
        given().contentType("application/json")
                .body(Map.of("email", "hong@hanyang.ac.kr", "password", "pw"))
                .when().post("/api/auth/login")
                .then().statusCode(403);
    }
}
