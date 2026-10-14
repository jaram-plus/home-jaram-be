package com.jaram.be.admin;

import com.jaram.be.member.*;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminDashboardTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired Actors actors;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        officerToken = actors.officer();
    }

    private void approved(String name, String sid, MemberGrade grade, int gen) {
        Member m = Member.newPending(name, sid, name + "@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(grade);
        m.setGen(gen);
        members.save(m);
    }

    @Test
    void statsAggregatesMembersGradesAndCohorts() {
        approved("a", "2023000001", MemberGrade.NEWCOMER, 42);
        approved("b", "2023000002", MemberGrade.ASSOCIATE, 41);
        approved("c", "2023000003", MemberGrade.OB, 38);
        // pending members
        Member p = Member.newPending("대기", "2023000009", "p@hanyang.ac.kr", "hash");
        p.setGen(42);
        members.save(p);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(200)
                // 행위자(회장)도 승인된 회원이라 함께 집계된다 — REGULAR 한 칸이 그 몫이다.
                .body("totalMembers", equalTo(4))
                .body("alumniCount", equalTo(1))
                .body("gradeBreakdown.probationary", equalTo(1))
                .body("gradeBreakdown.associate", equalTo(1))
                .body("gradeBreakdown.regular", equalTo(1))
                .body("genBreakdown.size()", greaterThanOrEqualTo(2))
                .body("pendingBreakdown.freshman", equalTo(1));
    }
}
