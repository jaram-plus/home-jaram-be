package com.jaram.be.people;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberCategory;
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
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PeopleTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    private Member active(String name, String studentId, String email,
                         MemberCategory category, MemberDepartment department, MemberTitle title, Integer gen) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setStatus(MemberStatus.ACTIVE);
        m.award(category);
        m.setDepartment(department);
        m.setTitle(title);
        m.setGen(gen);
        return members.save(m);
    }

    @Test
    void returnsActiveMembersGroupedByTab() {
        active("김자람", "2023000001", "a@hanyang.ac.kr", MemberCategory.exec, MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 41);
        active("박학술", "2023000002", "b@hanyang.ac.kr", MemberCategory.exec, MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        active("박나눔", "2023000003", "c@hanyang.ac.kr", MemberCategory.contrib, null, null, 38);
        active("정졸업", "2023000004", "d@hanyang.ac.kr", MemberCategory.grad, null, null, null);

        // PENDING member must be excluded
        Member pending = Member.newPending("대기", "2023000099", "p@hanyang.ac.kr", "hash");
        pending.award(MemberCategory.exec);
        pending.setDepartment(MemberDepartment.LEADERSHIP);
        pending.setTitle(MemberTitle.VICE_PRESIDENT);
        members.save(pending);

        given().when().get("/api/people").then().statusCode(200)
                // tab copy is fixed server-side
                .body("exec.desc", equalTo("지금 자람을 이끄는 임원진입니다."))
                .body("exec.empty", equalTo("등록된 임원 정보가 없습니다."))
                .body("contrib.desc", equalTo("자람에 힘을 더해주신 분들입니다."))
                .body("grad.desc", equalTo("자람을 거쳐 나아간 선배들입니다."))
                // exec grouped by department, PENDING excluded → only the 2 ACTIVE execs
                .body("exec.groups.heading", hasItems("회장단", "학술부"))
                .body("exec.groups.flatten().members.flatten().name", hasItem("김자람"))
                .body("exec.groups.flatten().members.flatten().name", not(hasItem("대기")))
                // gen rendered as "{n}기"; contrib/grad single unnamed group
                .body("contrib.groups[0].heading", nullValue())
                .body("contrib.groups[0].members[0].name", equalTo("박나눔"))
                .body("contrib.groups[0].members[0].gen", equalTo("38기"))
                .body("grad.groups[0].members[0].gen", nullValue());
    }

    @Test
    void memberWithMultipleAwardsAppearsInEachAwardedTab() {
        Member m = Member.newPending("멀티", "2023000010", "m@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setStatus(MemberStatus.ACTIVE);
        m.award(MemberCategory.exec);
        m.award(MemberCategory.grad);
        m.setDepartment(MemberDepartment.LEADERSHIP);
        m.setTitle(MemberTitle.PRESIDENT);
        m.setGen(40);
        members.save(m);

        given().when().get("/api/people").then().statusCode(200)
                .body("exec.groups.flatten().members.flatten().name", hasItem("멀티"))
                .body("grad.groups.flatten().members.flatten().name", hasItem("멀티"))
                .body("contrib.groups.flatten().members.flatten().name", not(hasItem("멀티")));
    }

    @Test
    void regularMemberAppearsInNoTab() {
        // newPending default = regular (no award) → excluded from all three tabs
        Member m = Member.newPending("일반", "2023000011", "r@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setStatus(MemberStatus.ACTIVE);
        members.save(m);

        given().when().get("/api/people").then().statusCode(200)
                .body("exec.groups.flatten().members.flatten().name", not(hasItem("일반")))
                .body("contrib.groups.flatten().members.flatten().name", not(hasItem("일반")))
                .body("grad.groups.flatten().members.flatten().name", not(hasItem("일반")));
    }

    @Test
    void emptyDatabaseReturnsEmptyGroups() {
        given().when().get("/api/people").then().statusCode(200)
                .body("exec.groups", hasSize(0))
                .body("contrib.groups", hasSize(0))
                .body("grad.groups", hasSize(0));
    }
}
