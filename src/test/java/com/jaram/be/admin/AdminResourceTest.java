package com.jaram.be.admin;

import com.jaram.be.member.*;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.study.Study;
import com.jaram.be.study.StudyRepository;
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
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminResourceTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired JwtProvider jwt;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        studies.deleteAll();
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
    }

    private Member approved(String name, String sid) {
        Member m = Member.newPending(name, sid, name + "@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        return members.save(m);
    }

    // ── A1 목록 ──

    @Test
    void listPaginatesMembers() {
        for (int i = 0; i < 10; i++) approved("m" + i, "202300000" + i);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members?page=1&size=8")
                .then().statusCode(200)
                .body("items.size()", equalTo(8))
                .body("page", equalTo(1))
                .body("size", equalTo(8))
                .body("total", equalTo(10));
    }

    @Test
    void listFiltersMembersByTabAndQuery() {
        Member exec = approved("김임원", "2023000001");
        exec.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        members.saveAndFlush(exec);
        Member contrib = approved("박기여", "2023000002");
        contrib.setContributor(true);
        members.saveAndFlush(contrib);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members?tab=exec")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].name", equalTo("김임원"));

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members?q=박기여")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].name", equalTo("박기여"));
    }

    // ── A2 batch ──

    @Test
    void batchUpdateAppliesFieldAndReportsUpdated() {
        Member m = approved("수정대상", "2023000001");
        Map<String, Object> update = new HashMap<>();
        update.put("id", m.getId());
        update.put("version", null);
        update.put("fields", Map.of("grade", "REGULAR", "name", "새이름"));

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("updates", List.of(update)))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("updated.size()", equalTo(1))
                .body("updated[0].id", equalTo(m.getId()))
                .body("errors.size()", equalTo(0));

        Member reloaded = members.findById(m.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getGrade()).isEqualTo(MemberGrade.REGULAR);
        org.assertj.core.api.Assertions.assertThat(reloaded.getName()).isEqualTo("새이름");
    }

    @Test
    void batchUpdateWithStaleVersionReportsConflict() {
        Member m = approved("충돌", "2023000001");
        Map<String, Object> update = new HashMap<>();
        update.put("id", m.getId());
        update.put("version", 999);   // stale
        update.put("fields", Map.of("grade", "REGULAR"));

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("updates", List.of(update)))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("conflicts.size()", equalTo(1))
                .body("conflicts[0].id", equalTo(m.getId()))
                .body("updated.size()", equalTo(0));
    }

    @Test
    void batchUpdateWithBadEnumReportsFieldError() {
        Member m = approved("검증", "2023000001");
        Map<String, Object> update = new HashMap<>();
        update.put("id", m.getId());
        update.put("fields", Map.of("grade", "BOGUS"));

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("updates", List.of(update)))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("errors.size()", equalTo(1))
                .body("errors[0].id", equalTo(m.getId()))
                .body("errors[0].fieldErrors.grade", notNullValue())
                .body("updated.size()", equalTo(0));

        // rejected update must not have partially applied
        org.assertj.core.api.Assertions.assertThat(
                members.findById(m.getId()).orElseThrow().getGrade())
                .isEqualTo(MemberGrade.ASSOCIATE);
    }

    @Test
    void batchAppliesGoodRowsWhileReportingConflictRow() {
        Member ok = approved("정상", "2023000001");
        Member stale = approved("충돌", "2023000002");

        Map<String, Object> good = new HashMap<>();
        good.put("id", ok.getId());
        good.put("version", null);
        good.put("fields", Map.of("grade", "REGULAR"));
        Map<String, Object> bad = new HashMap<>();
        bad.put("id", stale.getId());
        bad.put("version", 999);
        bad.put("fields", Map.of("grade", "OB"));

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("updates", List.of(good, bad)))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("updated.size()", equalTo(1))
                .body("updated[0].id", equalTo(ok.getId()))
                .body("conflicts.size()", equalTo(1))
                .body("conflicts[0].id", equalTo(stale.getId()));

        // the good row's change is committed independently of the conflict row
        org.assertj.core.api.Assertions.assertThat(
                members.findById(ok.getId()).orElseThrow().getGrade()).isEqualTo(MemberGrade.REGULAR);
        org.assertj.core.api.Assertions.assertThat(
                members.findById(stale.getId()).orElseThrow().getGrade()).isEqualTo(MemberGrade.ASSOCIATE);
    }

    @Test
    void batchDeleteRemovesMember() {
        Member m = approved("삭제", "2023000001");
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("deletes", List.of(m.getId())))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("deleted", hasItem(m.getId()));

        org.assertj.core.api.Assertions.assertThat(members.findById(m.getId())).isEmpty();
    }

    @Test
    void batchDeleteBlockedForStudyLeader() {
        Member leader = approved("리더", "2023000001");
        studies.save(Study.create("스터디", List.of("x"), 5, null, null, null, null, leader.getId()));

        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("deletes", List.of(leader.getId())))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("deleted.size()", equalTo(0))
                .body("errors.size()", equalTo(1))
                .body("errors[0].id", equalTo(leader.getId()));

        org.assertj.core.api.Assertions.assertThat(members.findById(leader.getId())).isPresent();
    }

    @Test
    void batchCreateSeminarMapsTempIdToNewId() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("creates", List.of(
                        Map.of("tempId", "t1", "fields", Map.of("title", "새 세미나", "capacity", 30)))))
                .when().patch("/api/admin/seminars:batch")
                .then().statusCode(200)
                .body("created.size()", equalTo(1))
                .body("created[0].tempId", equalTo("t1"))
                .body("created[0].id", notNullValue());
    }

    @Test
    void batchCreateMemberIsUnsupported() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("creates", List.of(
                        Map.of("tempId", "t1", "fields", Map.of("name", "안됨")))))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("created.size()", equalTo(0))
                .body("errors.size()", equalTo(1))
                .body("errors[0].id", equalTo("t1"));
    }
}
