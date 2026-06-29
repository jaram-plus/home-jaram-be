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
                members.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void rejectWithoutReasonReturns422() {
        Member p = pending("wait2@hanyang.ac.kr", "2023022222");
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of())
                .when().post("/api/admin/members/" + p.getId() + "/reject")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }
}
