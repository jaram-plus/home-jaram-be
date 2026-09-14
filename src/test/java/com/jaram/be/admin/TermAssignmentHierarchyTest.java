package com.jaram.be.admin;

import com.jaram.be.member.Member;
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

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6 — 임기를 부여·종료하려면 대상보다 rank 가 높아야 한다. 부회장이 회장을 갈아
 * 치우거나 자기 임기를 스스로 연장하는 것을 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TermAssignmentHierarchyTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @AfterEach void cleanup() { members.deleteAll(); }

    private Map<String, Object> assign(Member target, String dept, String title) {
        return Map.of("updates", List.of(Map.of(
                "id", target.getId(),
                "version", target.getVersion(),
                "fields", Map.of("department", dept, "title", title))));
    }

    @Test
    void vicePresidentCanAppointALead() {
        String vp = actors.token(Role.VICE_PRESIDENT);
        Member target = actors.save(Role.MEMBER);

        given().header("Authorization", "Bearer " + vp)
                .contentType("application/json").body(assign(target, "ACADEMIC", "LEAD"))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200);

        assertThat(members.findById(target.getId()).orElseThrow().getTitle()).isNotNull();
    }

    /** 회장은 rank 가 자기 이상이라 건드리지 못한다. */
    @Test
    void vicePresidentCannotAppointAPresident() {
        String vp = actors.token(Role.VICE_PRESIDENT);
        Member target = actors.save(Role.MEMBER);

        given().header("Authorization", "Bearer " + vp)
                .contentType("application/json").body(assign(target, "LEADERSHIP", "PRESIDENT"))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("errors[0].fieldErrors.title", org.hamcrest.Matchers.notNullValue());

        assertThat(members.findById(target.getId()).orElseThrow().getTitle()).isNull();
    }

    /** 현직 회장의 임기를 부회장이 끝낼 수 없다 — 종료도 부여와 같은 규칙이다. */
    @Test
    void vicePresidentCannotEndAPresidentsTerm() {
        String vp = actors.token(Role.VICE_PRESIDENT);
        Member president = actors.save(Role.PRESIDENT);
        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("title", null);
        Map<String, Object> body = Map.of("updates", List.of(Map.of(
                "id", president.getId(),
                "version", president.getVersion(),
                "fields", fields)));

        given().header("Authorization", "Bearer " + vp)
                .contentType("application/json").body(body)
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("errors[0].fieldErrors.title", org.hamcrest.Matchers.notNullValue());

        assertThat(members.findById(president.getId()).orElseThrow().getTitle()).isNotNull();
    }

    /**
     * 학술부장은 TERM_ASSIGN 이 없다. 다만 MEMBER_EDIT 도 없어서 행 단위 위계 검사까지
     * 가지도 못하고 회원 일괄 편집 자체가 막힌다 — 더 이른 곳에서 걸리는 것이 맞다.
     */
    @Test
    void academicLeadCannotTouchMemberTermsAtAll() {
        Member target = actors.save(Role.MEMBER);
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_LEAD))
                .contentType("application/json").body(assign(target, "ACADEMIC", "STAFF"))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(403);
    }

    /** 학술부장은 세미나 일괄 편집은 할 수 있다 — 권한이 리소스별로 갈린다. */
    @Test
    void academicLeadCanStillBatchEditSeminars() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_LEAD))
                .contentType("application/json").body(Map.of("updates", List.of()))
                .when().patch("/api/admin/seminars:batch")
                .then().statusCode(200);
    }

    /** 회계부원은 회원 목록은 보지만 고치지는 못한다. */
    @Test
    void financeStaffReadsMembersButCannotBatchEdit() {
        String token = actors.token(Role.FINANCE_STAFF);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/members").then().statusCode(200);
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("updates", List.of()))
                .when().patch("/api/admin/members:batch").then().statusCode(403);
    }
}
