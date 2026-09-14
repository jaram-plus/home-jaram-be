package com.jaram.be.security;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;

import static io.restassured.RestAssured.given;

/**
 * 발급된 토큰이 지금도 쓸 자격이 있는지 요청마다 확인한다.
 *
 * 이전에는 토큰만 유효하면 통과했다. 자격 검사가 Eligibility 로 모이기 전 신청류 다섯
 * 곳에만 있어서, 탈퇴 처리된 현직 임원이 손에 든 토큰으로 ttl(12시간) 동안 관리자 API 를
 * 계속 쓸 수 있었다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenEligibilityTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    // 공유 컨테이너를 쓰므로 남긴 행을 치운다 — 특히 미승인 회원을 남기면 승인 상태로
    // 개수를 세는 다른 클래스가 실행 순서에 따라 깨진다.
    @AfterEach void cleanup() {
        members.deleteAll();
    }

    private Member saved(MemberApproval approval, MemberStatus status) {
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        m.setApproval(approval);
        m.setStatus(status);
        return members.save(m);
    }

    private String tokenFor(Member m) {
        return jwt.generate(m.getId(), m.getName(), m.getEmail());
    }

    @Test
    void withdrawnMemberTokenIsRejected() {
        Member m = saved(MemberApproval.APPROVED, MemberStatus.ACTIVE);
        String token = tokenFor(m);

        m.withdraw(Instant.now());
        members.save(m);

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/me")
                .then().statusCode(401);
    }

    @Test
    void unapprovedMemberTokenIsRejected() {
        Member m = saved(MemberApproval.PENDING, MemberStatus.ACTIVE);

        given().header("Authorization", "Bearer " + tokenFor(m))
                .when().get("/api/me")
                .then().statusCode(401);
    }

    /** 재등록 대상은 막지 않는다 — 팝업을 띄우고 재등록을 신청하려면 로그인 상태여야 한다. */
    @Test
    void reregisterMemberTokenStillWorks() {
        Member m = saved(MemberApproval.APPROVED, MemberStatus.REREGISTER);

        given().header("Authorization", "Bearer " + tokenFor(m))
                .when().get("/api/me")
                .then().statusCode(200);
    }

    /**
     * 비밀번호를 바꾸면 그 전에 발급된 토큰이 끊긴다. 이게 없으면 토큰을 탈취당한
     * 사용자가 비밀번호를 재설정해도 공격자는 ttl 동안 그대로 접근한다.
     */
    @Test
    void tokenIssuedBeforeCredentialInvalidationIsRejected() {
        Member m = saved(MemberApproval.APPROVED, MemberStatus.ACTIVE);
        String token = tokenFor(m);

        // iat 는 초 단위라 같은 초에 무효화하면 구분되지 않는다. 실제 재설정은 발급보다
        // 한참 뒤에 일어나므로 그 간격을 준다.
        m.invalidateCredentials(Instant.now().plusSeconds(2));
        members.save(m);

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/me")
                .then().statusCode(401);
    }

    /** 무효화 이후에 받은 토큰은 통과해야 한다 — 재설정 직후 재로그인이 막히면 안 된다. */
    @Test
    void tokenIssuedAfterCredentialInvalidationIsAccepted() {
        Member m = saved(MemberApproval.APPROVED, MemberStatus.ACTIVE);
        m.invalidateCredentials(Instant.now());
        m = members.save(m);

        given().header("Authorization", "Bearer " + tokenFor(m))
                .when().get("/api/me")
                .then().statusCode(200);
    }

    /**
     * 임기를 거두면 권한이 즉시 사라진다. 토큰은 신원만 싣고 권한은 요청 시점에
     * 임기에서 나오므로, ttl 이 남아 있어도 관리자 API 가 막힌다.
     */
    @Test
    void authorityComesFromTheDatabaseNotTheToken() {
        Member m = saved(MemberApproval.APPROVED, MemberStatus.ACTIVE);
        m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 41);
        m = members.save(m);
        String token = tokenFor(m);

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(200);

        m.endCurrentTerm(42);
        members.save(m);

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(403);
    }

    /** 회원을 찾지 못하면 인증하지 않는다 — 권한 없는 인증을 만들지 않는다. */
    @Test
    void tokenForAnUnknownMemberIsRejected() {
        given().header("Authorization", "Bearer " + jwt.generate("ghost", "유령", "ghost@hanyang.ac.kr"))
                .when().get("/api/me")
                .then().statusCode(401);
    }
}
