package com.jaram.be.people;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
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

    // 승인·활동 상태만 세팅한 회원. 탭 판정(임기/기여자/등급)은 각 테스트가 직접 준다.
    private Member active(String name, String studentId, String email, Integer gen) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setStatus(MemberStatus.ACTIVE);
        m.setGen(gen);
        return m;
    }

    @Test
    void returnsActiveMembersGroupedByTab() {
        Member president = active("김자람", "2023000001", "a@hanyang.ac.kr", 41);
        president.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 42);
        members.save(president);

        Member lead = active("박학술", "2023000002", "b@hanyang.ac.kr", 41);
        lead.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        members.save(lead);

        Member contributor = active("박나눔", "2023000003", "c@hanyang.ac.kr", 38);
        contributor.setContributor(true);
        members.save(contributor);

        Member graduate = active("정졸업", "2023000004", "d@hanyang.ac.kr", null);
        graduate.setGrade(MemberGrade.OB);
        members.save(graduate);

        // PENDING member must be excluded
        Member pending = Member.newPending("대기", "2023000099", "p@hanyang.ac.kr", "hash");
        pending.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.VICE_PRESIDENT, 42);
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
                // gen은 정수로 그대로 나간다; contrib/grad는 이름 없는 단일 그룹
                .body("contrib.groups[0].heading", nullValue())
                .body("contrib.groups[0].members[0].name", equalTo("박나눔"))
                .body("contrib.groups[0].members[0].gen", equalTo(38))
                .body("grad.groups[0].members[0].name", equalTo("정졸업"));
    }

    @Test
    void officerWhoIsAlsoAContributorAppearsInBothTabs() {
        Member m = active("멀티", "2023000010", "m@hanyang.ac.kr", 40);
        m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 42);
        m.setContributor(true);
        members.save(m);

        given().when().get("/api/people").then().statusCode(200)
                .body("exec.groups.flatten().members.flatten().name", hasItem("멀티"))
                .body("contrib.groups.flatten().members.flatten().name", hasItem("멀티"))
                .body("grad.groups.flatten().members.flatten().name", not(hasItem("멀티")));
    }

    @Test
    void plainMemberAppearsInNoTab() {
        // 임기 없음 + 기여자 아님 + 졸업 아님 → 세 탭 모두에서 제외
        Member m = active("일반", "2023000011", "r@hanyang.ac.kr", 41);
        m.setGrade(MemberGrade.REGULAR);
        members.save(m);

        given().when().get("/api/people").then().statusCode(200)
                .body("exec.groups.flatten().members.flatten().name", not(hasItem("일반")))
                .body("contrib.groups.flatten().members.flatten().name", not(hasItem("일반")))
                .body("grad.groups.flatten().members.flatten().name", not(hasItem("일반")));
    }

    @Test
    void pastOfficerKeepsRoleWithFormerPrefix() {
        Member m = active("박선배", "2021000001", "senior@jaram.net", 37);
        m.setGrade(MemberGrade.OB);
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        m.endCurrentTerm(41);
        members.save(m);

        given().when().get("/api/people").then().statusCode(200)
                .body("grad.groups[0].members[0].role", equalTo("전 학술부장"));
    }

    @Test
    void graduateGenComesFromStudentIdPrefix() {
        Member m = active("최선배", "2021000002", "senior2@jaram.net", 40);
        m.setGrade(MemberGrade.OB);
        members.save(m);

        given().when().get("/api/people").then().statusCode(200)
                .body("grad.groups[0].members[0].gen", equalTo(37));   // 2021 - 1984
    }

    @Test
    void emptyDatabaseReturnsEmptyGroups() {
        given().when().get("/api/people").then().statusCode(200)
                .body("exec.groups", hasSize(0))
                .body("contrib.groups", hasSize(0))
                .body("grad.groups", hasSize(0));
    }
}
