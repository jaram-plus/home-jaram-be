package com.jaram.be.admin;

import com.jaram.be.member.*;
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
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminMemberTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
    }

    private Member pending(String email, String sid) {
        return members.save(Member.newPending("대기자", sid, email, "hash"));
    }

    @Test
    void officerSeesPendingListAndApproves() {
        Member p = pending("wait@hanyang.ac.kr", "2023011111");

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/pending")
                .then().statusCode(200).body("size()", equalTo(1))
                .body("[0].email", equalTo("wait@hanyang.ac.kr"));

        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/members/" + p.getId() + "/approve")
                .then().statusCode(200);

        org.assertj.core.api.Assertions.assertThat(
                members.findById(p.getId()).orElseThrow().getApproval())
                .isEqualTo(MemberApproval.APPROVED);
    }

    // 등급은 가입 신청 때 이미 정해졌다 — 승인이 기수를 보고 다시 파생하지 않는다.
    @Test
    void approveKeepsTheGradeChosenAtSignup() {
        int currentGen = java.time.Year.now().getValue() - 1984;
        Member associate = members.save(
                withGrade("sr@hanyang.ac.kr", "2020000001", currentGen, MemberGrade.ASSOCIATE));

        approve(associate.getId());

        org.assertj.core.api.Assertions.assertThat(
                members.findById(associate.getId()).orElseThrow().getGrade())
                .isEqualTo(MemberGrade.ASSOCIATE);
    }

    private Member withGrade(String email, String sid, int gen, MemberGrade grade) {
        Member m = Member.newPending("가입자", sid, email, "hash");
        m.setGen(gen);
        m.setGrade(grade);
        return m;
    }

    private void approve(String id) {
        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/members/" + id + "/approve")
                .then().statusCode(200);
    }

    @Test
    void rejectWithoutReasonReturns422() {
        Member p = pending("wait2@hanyang.ac.kr", "2023022222");
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of())
                .when().post("/api/admin/members/" + p.getId() + "/reject")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void officerReadsMemberDetail() {
        Member m = pending("detail@hanyang.ac.kr", "2023022222");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setGen(41);
        m.setPhone("010-1234-5678");
        m.setFaculty("컴퓨터학부");
        members.save(m);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/" + m.getId())
                .then().statusCode(200)
                .body("id", equalTo(m.getId()))
                .body("studentId", equalTo("2023022222"))
                .body("phone", equalTo("010-1234-5678"))
                .body("faculty", equalTo("컴퓨터학부"))
                .body("grade", equalTo("ASSOCIATE"))
                .body("approval", equalTo("APPROVED"))
                .body("contributor", equalTo(false))
                .body("department", nullValue())
                .body("title", nullValue())
                .body("terms", hasSize(0))
                .body("createdAt", notNullValue());
    }

    @Test
    void memberDetailReturns404ForUnknownId() {
        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/does-not-exist")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    /** /pending 은 리터럴 경로라 {id} 보다 먼저 매칭돼야 한다. */
    @Test
    void pendingPathStillResolvesAfterAddingIdRoute() {
        pending("stillwaiting@hanyang.ac.kr", "2023033333");

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/pending")
                .then().statusCode(200).body("size()", equalTo(1))
                .body("[0].email", equalTo("stillwaiting@hanyang.ac.kr"));
    }
}
