package com.jaram.be.member;

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
import static org.hamcrest.Matchers.equalTo;

/**
 * 재등록 필요 회원은 조회는 되지만 신청류가 막힌다 — 팝업을 닫아도 재등록할
 * 이유가 남아야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReregistrationBlocksActivityTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String token;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.markReregistrationRequired();
        m.setGen(41);
        m = members.save(m);
        token = jwt.generate(m.getId(), m.getName(), m.getEmail(), Authority.MEMBER);
    }

    @Test
    void studyApplicationIsBlocked() {
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("motive", "배우고 싶습니다"))
                .when().post("/api/studies/any-id/apply")
                .then().statusCode(403).body("code", equalTo("REREGISTRATION_REQUIRED"));
    }

    @Test
    void seminarAttendanceIsBlocked() {
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("code", "1234"))
                .when().post("/api/seminars/any-id/attend")
                .then().statusCode(403);
    }

    @Test
    void slotClaimIsBlocked() {
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/any-id/slots/0/claim")
                .then().statusCode(403);
    }

    /** 조회는 열려 있다. */
    @Test
    void readingIsAllowed() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/me")
                .then().statusCode(200);
    }

    /** 프로필 수정도 막지 않는다 — 신청이 아니다. */
    @Test
    void profileEditIsAllowed() {
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(Map.of("bio", "안녕하세요", "githubUrl", "", "blogUrl", ""))
                .when().patch("/api/me")
                .then().statusCode(200);
    }
}
