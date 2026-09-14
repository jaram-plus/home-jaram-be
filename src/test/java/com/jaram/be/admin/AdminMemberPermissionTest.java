package com.jaram.be.admin;

import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;

/**
 * 회원 관리 권한이 Role 별로 갈리는지 본다. 지금까지는 임기만 있으면 부원도 회장과
 * 똑같이 회원을 승인할 수 있었다 — 이 테스트가 그 경계를 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminMemberPermissionTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @AfterEach void cleanup() { members.deleteAll(); }

    /** 회계부는 MEMBER_READ 를 가지므로 대기 목록을 본다. */
    @Test
    void financeStaffCanReadPendingMembers() {
        given().header("Authorization", "Bearer " + actors.token(Role.FINANCE_STAFF))
                .when().get("/api/admin/members/pending")
                .then().statusCode(200);
    }

    /** 학술부원은 회원 권한이 전혀 없다. */
    @Test
    void academicStaffCannotReadPendingMembers() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .when().get("/api/admin/members/pending")
                .then().statusCode(403);
    }

    /** 회계부원은 읽기만 — 승인은 부장부터다. */
    @Test
    void financeStaffCannotApprove() {
        given().header("Authorization", "Bearer " + actors.token(Role.FINANCE_STAFF))
                .when().post("/api/admin/members/any-id/approve")
                .then().statusCode(403);
    }

    @Test
    void financeLeadCanApprove() {
        // 존재하지 않는 회원이라 404 다. 403 이 아니라는 것이 요점 — 권한은 통과했다.
        given().header("Authorization", "Bearer " + actors.token(Role.FINANCE_LEAD))
                .when().post("/api/admin/members/any-id/approve")
                .then().statusCode(404);
    }

    /** 졸업 정보 수정은 MEMBER_EDIT — 회계부장도 못 한다. */
    @Test
    void financeLeadCannotEditGraduation() {
        given().header("Authorization", "Bearer " + actors.token(Role.FINANCE_LEAD))
                .contentType("application/json").body("{}")
                .when().put("/api/admin/members/any-id/graduation")
                .then().statusCode(403);
    }

    @Test
    void plainMemberIsRejectedEverywhere() {
        String token = actors.member();
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/members/pending").then().statusCode(403);
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/admin/members/any-id/approve").then().statusCode(403);
    }
}
