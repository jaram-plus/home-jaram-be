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
import static org.hamcrest.Matchers.notNullValue;

// 직책×부서 조합 검증. title을 바꾸면 권한도 함께 바뀌므로 이 검증이 권한 부여의 유일한 관문이다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminMemberAssignmentTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
    }

    private Member approved(String name, String sid) {
        Member m = Member.newPending(name, sid, name + "@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
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
    void assignsTitleThatFitsTheDepartment() {
        Member m = approved("학술장", "2023000001");

        patch(m.getId(), Map.of("department", "ACADEMIC", "title", "LEAD"))
                .body("updated.size()", equalTo(1))
                .body("errors.size()", equalTo(0));

        Member reloaded = members.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo(MemberTitle.LEAD);
        assertThat(reloaded.getDepartment()).isEqualTo(MemberDepartment.ACADEMIC);
        assertThat(reloaded.getAuthority()).isEqualTo(Authority.OFFICER);
    }

    @Test
    void rejectsTitleThatDoesNotFitTheDepartment() {
        Member m = approved("불일치", "2023000002");

        patch(m.getId(), Map.of("department", "LEADERSHIP", "title", "LEAD"))
                .body("errors.size()", equalTo(1))
                .body("errors[0].fieldErrors.title", notNullValue())
                .body("updated.size()", equalTo(0));

        Member reloaded = members.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isNull();
        assertThat(reloaded.getDepartment()).isNull();
    }

    @Test
    void rejectsTitleWithoutDepartment() {
        Member m = approved("부서없음", "2023000003");

        patch(m.getId(), Map.of("title", "PRESIDENT"))
                .body("errors.size()", equalTo(1))
                .body("errors[0].fieldErrors.title", notNullValue())
                .body("updated.size()", equalTo(0));

        assertThat(members.findById(m.getId()).orElseThrow().getTitle()).isNull();
    }

    @Test
    void judgesAgainstTheStoredValueWhenOnlyOneSideIsSent() {
        Member m = approved("기존부서", "2023000004");
        m.setDepartment(MemberDepartment.INFRA);
        members.saveAndFlush(m);

        // 저장된 INFRA 기준으로 LEAD 는 거부
        patch(m.getId(), Map.of("title", "LEAD"))
                .body("errors.size()", equalTo(1))
                .body("errors[0].fieldErrors.title", notNullValue());

        // 같은 기준으로 SERVER_ADMIN 은 허용
        patch(m.getId(), Map.of("title", "SERVER_ADMIN"))
                .body("updated.size()", equalTo(1))
                .body("errors.size()", equalTo(0));

        assertThat(members.findById(m.getId()).orElseThrow().getTitle())
                .isEqualTo(MemberTitle.SERVER_ADMIN);
    }

    @Test
    void allowsClearingTitleAndDepartment() {
        Member m = approved("해임", "2023000005");
        m.setDepartment(MemberDepartment.PR);
        m.setTitle(MemberTitle.STAFF);
        members.saveAndFlush(m);

        Map<String, Object> fields = new HashMap<>();
        fields.put("department", null);
        fields.put("title", null);
        patch(m.getId(), fields)
                .body("updated.size()", equalTo(1))
                .body("errors.size()", equalTo(0));

        Member reloaded = members.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isNull();
        assertThat(reloaded.getAuthority()).isEqualTo(Authority.MEMBER);
    }
}
