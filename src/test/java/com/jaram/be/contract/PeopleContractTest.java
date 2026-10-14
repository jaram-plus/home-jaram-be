package com.jaram.be.contract;

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItems;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PeopleContractTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;

    private final OpenApiValidationFilter validation =
            new OpenApiValidationFilter("openapi/openapi.yaml");

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @Test
    void peopleResponseMatchesContract() {
        Member m = Member.newPending("김자람", "2023000001", "a@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setStatus(MemberStatus.ACTIVE);
        m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 42);
        m.setGen(41);
        members.save(m);

        Member lead = Member.newPending("박학술", "2023000002", "b@hanyang.ac.kr", "hash");
        lead.setApproval(MemberApproval.APPROVED);
        lead.setStatus(MemberStatus.ACTIVE);
        lead.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        lead.setGen(41);
        members.save(lead);

        given().filter(validation)
                .when().get("/api/people")
                .then().statusCode(200)
                // 라벨은 department + title 조합에서 파생된다
                .body("exec.groups.flatten().members.flatten().role", hasItems("회장", "학술부장"));
    }
}
