package com.jaram.be.me;

import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String token;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();

        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        m.setStatus(MemberStatus.ACTIVE);
        m.setGen(41);
        m.setBio("안녕하세요");
        m.setGithubUrl("https://github.com/hong");
        members.save(m);
        token = jwt.generate(m.getId(), m.getName(), m.getEmail(), Authority.MEMBER);
    }

    @Test
    void getReturnsOwnProfile() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/me")
                .then().statusCode(200)
                .body("name", equalTo("홍길동"))
                .body("studentId", equalTo("2023012345"))
                .body("email", equalTo("hong@hanyang.ac.kr"))
                .body("authority", equalTo("MEMBER"))
                .body("gen", equalTo(41))
                .body("bio", equalTo("안녕하세요"))
                .body("githubUrl", equalTo("https://github.com/hong"));
    }

    @Test
    void getAnonymousReturns401() {
        given().when().get("/api/me")
                .then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
    }

    @Test
    void patchUpdatesProfileFields() {
        Map<String, Object> body = new HashMap<>();
        body.put("bio", "수정된 소개");
        body.put("githubUrl", "https://github.com/new");
        body.put("blogUrl", "https://blog.example.com");
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(body)
                .when().patch("/api/me")
                .then().statusCode(200)
                .body("bio", equalTo("수정된 소개"))
                .body("githubUrl", equalTo("https://github.com/new"))
                .body("blogUrl", equalTo("https://blog.example.com"));
    }

    @Test
    void patchClearsFieldsWithNull() {
        Map<String, Object> body = new HashMap<>();
        body.put("bio", null);
        body.put("githubUrl", null);
        body.put("blogUrl", null);
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(body)
                .when().patch("/api/me")
                .then().statusCode(200)
                .body("bio", nullValue())
                .body("githubUrl", nullValue());
    }

    @Test
    void patchTooLongBioReturns422() {
        Map<String, Object> body = new HashMap<>();
        body.put("bio", "x".repeat(501));
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(body)
                .when().patch("/api/me")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void patchAnonymousReturns401() {
        given().contentType("application/json").body(Map.of("bio", "x"))
                .when().patch("/api/me")
                .then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
    }

    @Test
    void getReturnsTermsOldestFirst() {
        Member m = members.findByEmail("hong@hanyang.ac.kr").orElseThrow();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 40);
        m.endCurrentTerm(40);
        m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 41);
        members.saveAndFlush(m);

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/me")
                .then().statusCode(200)
                .body("terms.size()", equalTo(2))
                .body("terms[0].department", equalTo("ACADEMIC"))
                .body("terms[0].title", equalTo("LEAD"))
                .body("terms[0].startGen", equalTo(40))
                .body("terms[0].endGen", equalTo(40))
                .body("terms[1].department", equalTo("LEADERSHIP"))
                .body("terms[1].title", equalTo("PRESIDENT"))
                .body("terms[1].startGen", equalTo(41))
                .body("terms[1].endGen", nullValue());
    }

    @Test
    void getReturnsEmptyTermsWhenNoTermExists() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/me")
                .then().statusCode(200)
                .body("terms", hasSize(0));
    }
}
