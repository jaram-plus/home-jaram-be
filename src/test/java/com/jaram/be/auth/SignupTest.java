package com.jaram.be.auth;

import com.jaram.be.member.Gen;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignupTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    private Map<String, Object> valid() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "홍길동");
        m.put("studentId", "2023012345");
        m.put("email", "hong@hanyang.ac.kr");
        m.put("password", "passw0rd!");
        m.put("gen", 41);
        m.put("faculty", "컴퓨터학부");
        m.put("phone", "010-1234-5678");
        m.put("enrolled", true);
        m.put("newcomer", false);
        return m;
    }

    @Test
    void signupReturns201() {
        given().contentType("application/json").body(valid())
                .when().post("/api/auth/signup")
                .then().statusCode(201);
    }

    @Test
    void duplicateEmailReturns409EmailTaken() {
        given().contentType("application/json").body(valid()).post("/api/auth/signup");
        Map<String, Object> second = valid();
        second.put("name", "김철수");
        second.put("studentId", "2023099999");
        given().contentType("application/json").body(second)
                .when().post("/api/auth/signup")
                .then().statusCode(409).body("code", equalTo("EMAIL_TAKEN"));
    }

    @Test
    void nonHanyangEmailReturns422() {
        Map<String, Object> bad = valid();
        bad.put("email", "hong@gmail.com");
        given().contentType("application/json").body(bad)
                .when().post("/api/auth/signup")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void enrolledTrueBecomesActivePendingApproval() {
        given().contentType("application/json").body(valid()).post("/api/auth/signup");
        var m = members.findByEmail("hong@hanyang.ac.kr").orElseThrow();
        assertThat(m.getApproval()).isEqualTo(MemberApproval.PENDING);
        assertThat(m.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void enrolledFalseBecomesOnLeave() {
        Map<String, Object> body = valid();
        body.put("enrolled", false);
        given().contentType("application/json").body(body).post("/api/auth/signup");
        var m = members.findByEmail("hong@hanyang.ac.kr").orElseThrow();
        assertThat(m.getStatus()).isEqualTo(MemberStatus.ON_LEAVE);
    }

    @Test
    void missingNewFieldsReturns422() {
        Map<String, Object> bad = valid();
        bad.remove("gen");
        bad.remove("faculty");
        bad.remove("phone");
        bad.remove("enrolled");
        given().contentType("application/json").body(bad)
                .when().post("/api/auth/signup")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void missingGenReturns422() {
        Map<String, Object> bad = valid();
        bad.remove("gen");
        given().contentType("application/json").body(bad)
                .when().post("/api/auth/signup")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void zeroGenReturns422() {
        Map<String, Object> bad = valid();
        bad.put("gen", 0);
        given().contentType("application/json").body(bad)
                .when().post("/api/auth/signup")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void newcomerSignsUpAsNewcomerGrade() {
        Map<String, Object> body = valid();
        body.put("newcomer", true);
        given().contentType("application/json").body(body).post("/api/auth/signup");
        assertThat(members.findByEmail("hong@hanyang.ac.kr").orElseThrow().getGrade())
                .isEqualTo(MemberGrade.NEWCOMER);
    }

    // 재학생은 기수와 무관하게 준회원이다 — 올해 들어온 재학생도 수습회원이 되지 않는다.
    @Test
    void currentStudentSignsUpAsAssociateEvenWithTheCurrentGen() {
        Map<String, Object> body = valid();
        body.put("newcomer", false);
        body.put("gen", Gen.current());
        given().contentType("application/json").body(body).post("/api/auth/signup");
        assertThat(members.findByEmail("hong@hanyang.ac.kr").orElseThrow().getGrade())
                .isEqualTo(MemberGrade.ASSOCIATE);
    }

    @Test
    void missingNewcomerReturns422() {
        Map<String, Object> bad = valid();
        bad.remove("newcomer");
        given().contentType("application/json").body(bad)
                .when().post("/api/auth/signup")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }
}
