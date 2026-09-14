package com.jaram.be.security.authz;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * 권한이 토큰이 아니라 DB 에서 나온다는 것을 /api/me 응답으로 확인한다.
 * 임기를 거둔 사람이 손에 든 토큰으로 계속 임원 권한을 쓰던 것이 결함이었다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GrantedPermissionsTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @AfterEach void cleanup() { members.deleteAll(); }

    private String tokenFor(MemberDepartment d, MemberTitle t) {
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setStatus(MemberStatus.ACTIVE);
        if (d != null) m.assignTerm(d, t, 42);
        m = members.save(m);
        return jwt.generate(m.getId(), m.getName(), m.getEmail());
    }

    @Test
    void academicStaffGetsTheStaffSlice() {
        given().header("Authorization", "Bearer " + tokenFor(MemberDepartment.ACADEMIC, MemberTitle.STAFF))
                .when().get("/api/me")
                .then().statusCode(200)
                .body("roles", containsInAnyOrder("ACADEMIC_STAFF"))
                .body("permissions", hasItem("SEMINAR_CREATE"))
                // 부원은 승인권이 없다 — 이번 변경에서 실제로 잃는 권한이다
                .body("permissions", not(hasItem("SEMINAR_APPROVE")))
                .body("permissions", not(hasItem("MEMBER_APPROVE")));
    }

    @Test
    void presidentGetsEverything() {
        given().header("Authorization", "Bearer " + tokenFor(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT))
                .when().get("/api/me")
                .then().statusCode(200)
                .body("roles", containsInAnyOrder("PRESIDENT"))
                .body("permissions", hasItem("SETTINGS_ROLLOVER"))
                .body("permissions", hasItem("EXPORT_RUN"));
    }

    @Test
    void memberWithoutTermGetsNothing() {
        given().header("Authorization", "Bearer " + tokenFor(null, null))
                .when().get("/api/me")
                .then().statusCode(200)
                .body("roles", containsInAnyOrder("MEMBER"))
                .body("permissions", emptyIterable());
    }
}
