package com.jaram.be.study;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyPermissionTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @AfterEach void cleanup() { members.deleteAll(); }

    /** 스터디 개설 승인은 학술부장부터다. */
    @Test
    void academicStaffCannotApproveStudies() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .when().get("/api/studies/pending")
                .then().statusCode(403);
    }

    @Test
    void academicLeadSeesPendingStudies() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_LEAD))
                .when().get("/api/studies/pending")
                .then().statusCode(200);
    }

    /** 신청자 관리는 부원도 한다. */
    @Test
    void academicStaffManagesApplicants() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .when().get("/api/studies/applicants")
                .then().statusCode(200);
    }

    @Test
    void prStaffTouchesNeither() {
        String token = actors.token(Role.PR_STAFF);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/studies/pending").then().statusCode(403);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/studies/applicants").then().statusCode(403);
    }

    /** 목록과 개설은 권한이 아니라 인증으로 통과한다. */
    @Test
    void plainMemberStillListsStudies() {
        given().header("Authorization", "Bearer " + actors.member())
                .when().get("/api/studies")
                .then().statusCode(200);
    }
}
