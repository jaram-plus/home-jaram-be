package com.jaram.be.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminContractTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private final OpenApiValidationFilter validation = new OpenApiValidationFilter(
            OpenApiInteractionValidator.createForSpecificationUrl("openapi/openapi.yaml")
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request.parameter.schema.invalidJson",
                                    ValidationReport.Level.IGNORE)
                            .build())
                    .build());

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
        m.setGen(41);
        return members.save(m);
    }

    @Test
    void listMembersMatchesContract() {
        approved("김자람", "2023000001");
        given().filter(validation)
                .header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members?page=1&size=8")
                .then().statusCode(200);
    }

    // NOTE: PATCH /api/admin/{resource}:batch is intentionally NOT contract-filter-validated.
    // swagger-request-validator 2.43.0 percent-encodes the literal ':' in the request path
    // (…/members%3Abatch) and then fails to match it against the templated '{resource}:batch'
    // path, raising a spurious operation.notAllowed. The endpoint itself works and its
    // AdminBatchResponse shape (updated/created/deleted/conflicts/errors) is asserted in
    // AdminResourceTest. Revisit if the validator gains colon-path support.

    @Test
    void dashboardStatsMatchesContract() {
        approved("김자람", "2023000001");
        given().filter(validation)
                .header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(200);
    }

    @Test
    void getSettingsMatchesContract() {
        given().filter(validation)
                .header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/settings")
                .then().statusCode(200);
    }

    @Test
    void driveExportMatchesContract() {
        approved("김자람", "2023000001");
        given().filter(validation)
                .header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("resource", "members", "columns", java.util.List.of("name", "email")))
                .when().post("/api/admin/export/google-drive")
                .then().statusCode(200);
    }

    @Test
    void patchSettingsMatchesContract() {
        given().filter(validation)
                .header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("semesterTerm", 2, "currentGen", 42))
                .when().patch("/api/admin/settings")
                .then().statusCode(200);
    }

    @Test
    void memberDetailMatchesContract() {
        Member m = approved("김자람", "2023000001");
        given().filter(validation)
                .header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/" + m.getId())
                .then().statusCode(200);
    }

    /** grade 는 승인 전까지 null 이다. MemberDetail 이 그 null 을 허용하는지 못박는다. */
    @Test
    void pendingMemberDetailWithNullGradeMatchesContract() {
        Member m = members.save(Member.newPending("신청자", "2023000002", "apply@hanyang.ac.kr", "hash"));
        given().filter(validation)
                .header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/" + m.getId())
                .then().statusCode(200);
    }
}
