package com.jaram.be.contract;

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeContractTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private final OpenApiValidationFilter validation =
            new OpenApiValidationFilter("openapi/openapi.yaml");

    private String token;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setStatus(MemberStatus.ACTIVE);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setGen(41);
        members.save(m);
        token = jwt.generate(m.getId(), m.getName(), m.getEmail(), Authority.MEMBER);
    }

    @Test
    void getMeResponseMatchesContract() {
        given().filter(validation)
                .header("Authorization", "Bearer " + token)
                .when().get("/api/me")
                .then().statusCode(200);
    }

    @Test
    void patchMeResponseMatchesContract() {
        given().filter(validation)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(Map.of("bio", "수정"))
                .when().patch("/api/me")
                .then().statusCode(200);
    }
}
