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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

// 졸업(OB) 규칙. 현직 임원과 OB 는 공존하지 않으며, 전환 시 임기는 종료되고 이력은 남는다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminMemberGraduationTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
    }

    private Member approved(String name, String sid, MemberGrade grade) {
        Member m = Member.newPending(name, sid, name + "@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(grade);
        return members.save(m);
    }

    private io.restassured.response.ValidatableResponse patch(String id, Map<String, Object> fields) {
        Map<String, Object> update = new HashMap<>();
        update.put("id", id);
        update.put("version", null);
        update.put("fields", fields);
        return given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("updates", List.of(update)))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200);
    }

    @Test
    void associateCanBecomeGraduate() {
        Member m = approved("준회원", "2021000001", MemberGrade.ASSOCIATE);

        patch(m.getId(), Map.of("grade", "OB"))
                .body("updated.size()", equalTo(1))
                .body("errors.size()", equalTo(0));

        assertThat(members.findById(m.getId()).orElseThrow().getGrade()).isEqualTo(MemberGrade.OB);
    }

    @Test
    void newcomerCannotBecomeGraduate() {
        Member m = approved("신입", "2026000001", MemberGrade.NEWCOMER);

        patch(m.getId(), Map.of("grade", "OB"))
                .body("updated.size()", equalTo(0))
                .body("errors[0].fieldErrors.grade", startsWith("신입부원은 바로 OB로 변경할 수 없습니다."));

        assertThat(members.findById(m.getId()).orElseThrow().getGrade()).isEqualTo(MemberGrade.NEWCOMER);
    }

    @Test
    void graduatingEndsTheCurrentTermButKeepsHistory() {
        Member m = approved("현직임원", "2021000002", MemberGrade.REGULAR);
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        members.saveAndFlush(m);

        patch(m.getId(), Map.of("grade", "OB"))
                .body("updated.size()", equalTo(1))
                .body("errors.size()", equalTo(0));

        Member reloaded = members.findById(m.getId()).orElseThrow();
        assertThat(reloaded.currentTerm()).isEmpty();
        assertThat(reloaded.getTerms()).hasSize(1);
        assertThat(reloaded.getTerms().get(0).getEndGen()).isNotNull();
        assertThat(reloaded.getAuthority()).isEqualTo(Authority.MEMBER);
    }

    @Test
    void cannotGraduateAndAssignTitleInOneRequest() {
        Member m = approved("동시요청", "2021000003", MemberGrade.REGULAR);

        patch(m.getId(), Map.of("grade", "OB", "department", "ACADEMIC", "title", "LEAD"))
                .body("updated.size()", equalTo(0))
                .body("errors[0].fieldErrors.grade", equalTo("OB로 변경하면서 직책을 함께 지정할 수 없습니다."));

        assertThat(members.findById(m.getId()).orElseThrow().getGrade()).isEqualTo(MemberGrade.REGULAR);
    }

    @Test
    void graduateCannotBeAssignedATitle() {
        Member m = approved("졸업생", "2019000001", MemberGrade.OB);

        patch(m.getId(), Map.of("department", "ACADEMIC", "title", "LEAD"))
                .body("updated.size()", equalTo(0))
                .body("errors[0].fieldErrors.title",
                        equalTo("OB 회원에게는 직책을 지정할 수 없습니다. 등급을 먼저 변경해 주세요."));

        assertThat(members.findById(m.getId()).orElseThrow().currentTerm()).isEmpty();
    }
}
