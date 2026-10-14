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

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * SITE_LINKS_EDIT 를 SETTINGS_EDIT 에서 분리한 목적은 홍보부가 푸터 링크만 고치게
 * 하는 것이다. 설정 PATCH 가 엔드포인트 하나라 필드 조건으로만 달성된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettingsPermissionTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired AdminSettingsRepository settings;
    @Autowired Actors actors;

    // 이 테스트는 설정을 실제로 바꾼다(기수·푸터 링크). 설정은 한 행짜리 전역 상태라
    // 남겨 두면 다른 테스트 클래스의 집계가 어긋난다.
    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        settings.deleteAll();
    }

    @AfterEach void cleanup() {
        members.deleteAll();
        settings.deleteAll();
    }

    @Test
    void prLeadEditsFooterLinksOnly() {
        String token = actors.token(Role.PR_LEAD);

        given().header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(Map.of("links", Map.of("github", "https://github.com/jaram")))
                .when().patch("/api/admin/settings")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("autoPromote", true))
                .when().patch("/api/admin/settings")
                .then().statusCode(403);
    }

    /** 기수 변경은 되돌리기 어려워 회장만 한다. */
    @Test
    void onlyPresidentChangesTheGeneration() {
        given().header("Authorization", "Bearer " + actors.token(Role.VICE_PRESIDENT))
                .contentType("application/json").body(Map.of("currentGen", 43))
                .when().patch("/api/admin/settings")
                .then().statusCode(403);

        given().header("Authorization", "Bearer " + actors.token(Role.PRESIDENT))
                .contentType("application/json").body(Map.of("currentGen", 43))
                .when().patch("/api/admin/settings")
                .then().statusCode(200);
    }

    /** 개인정보 반출도 회장과 서버 관리자만. */
    @Test
    void exportIsNarrow() {
        given().header("Authorization", "Bearer " + actors.token(Role.VICE_PRESIDENT))
                .contentType("application/json").body(Map.of("resource", "members"))
                .when().post("/api/admin/export/google-drive")
                .then().statusCode(403);
    }

    /** 임기가 있으면 누구나 대시보드는 본다. */
    @Test
    void everyStaffSeesTheDashboard() {
        for (Role r : new Role[]{Role.FINANCE_STAFF, Role.PR_STAFF, Role.ACADEMIC_STAFF,
                                 Role.SERVER_ADMIN, Role.VICE_PRESIDENT, Role.PRESIDENT}) {
            given().header("Authorization", "Bearer " + actors.token(r))
                    .when().get("/api/admin/dashboard/stats")
                    .then().statusCode(200);
        }
    }

    @Test
    void plainMemberSeesNoDashboard() {
        given().header("Authorization", "Bearer " + actors.member())
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(403);
    }

    /**
     * 일정 관리는 학술부장만 — 세미나 슬롯을 여는 사람이다.
     *
     * 본문을 유효하게 채운다. @Valid 는 인자 바인딩 단계라 @PreAuthorize 보다 먼저
     * 돌고, 빈 본문이면 권한을 보기도 전에 422 가 나간다.
     */
    @Test
    void scheduleManagementIsAcademicLeadUpwards() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .contentType("application/json")
                .body(Map.of("startsAt", "2026-12-01T19:00:00Z"))
                .when().post("/api/admin/schedules")
                .then().statusCode(403);
    }
}
