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

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminExportTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
    }

    @Test
    void exportReturnsFileLinkAndId() {
        Member m = Member.newPending("김자람", "2023000001", "a@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        members.save(m);

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("resource", "members", "columns", List.of("name", "email")))
                .when().post("/api/admin/export/google-drive")
                .then().statusCode(200)
                .body("fileId", notNullValue())
                .body("fileUrl", startsWith("https://"));
    }

    @Test
    void invalidResourceReturns422() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("resource", "bogus"))
                .when().post("/api/admin/export/google-drive")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void missingResourceReturns422() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of())
                .when().post("/api/admin/export/google-drive")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void memberIsForbidden() {
        String memberToken = jwt.generate("m1", "회원", "m@hanyang.ac.kr", Authority.MEMBER);
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json")
                .body(Map.of("resource", "members"))
                .when().post("/api/admin/export/google-drive")
                .then().statusCode(403);
    }
}
