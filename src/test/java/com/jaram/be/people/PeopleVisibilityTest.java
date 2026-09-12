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

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PeopleVisibilityTest extends PostgresTest {

    // groups 는 {heading, members} 의 목록이라 이름까지 한 겹 더 들어가야 한다.
    private static final String CONTRIB_NAMES = "contrib.groups.members.flatten().name";

    @LocalServerPort int port;
    @Autowired MemberRepository members;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    private Member saved(String name, String studentId, String email, MemberStatus status) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setStatus(status);
        m.setGen(41);
        m.setContributor(true);
        return members.save(m);
    }

    /** 재등록 필요 회원은 사람들 탭에서 빠진다. */
    @Test
    void reregisterMemberIsHidden() {
        saved("재등록", "2023022222", "re@hanyang.ac.kr", MemberStatus.REREGISTER);
        given().when().get("/api/people")
                .then().statusCode(200)
                .body(CONTRIB_NAMES, not(hasItem("재등록")));
    }

    @Test
    void activeAndOnLeaveMembersAreVisible() {
        saved("활동", "2023011111", "active@hanyang.ac.kr", MemberStatus.ACTIVE);
        saved("휴학", "2023033333", "leave@hanyang.ac.kr", MemberStatus.ON_LEAVE);
        given().when().get("/api/people")
                .then().statusCode(200)
                .body(CONTRIB_NAMES, hasItems("활동", "휴학"));
    }

    /** 파기된 회원은 이력이 남아 있어도 공개 목록에 나오지 않는다. */
    @Test
    void purgedMemberIsHidden() {
        Member m = saved("파기됨", "2021044444", "purged@hanyang.ac.kr", MemberStatus.ACTIVE);
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        m.endCurrentTerm(42);
        m.purge(Instant.parse("2027-03-01T00:00:00Z"));
        members.save(m);

        given().when().get("/api/people")
                .then().statusCode(200)
                .body(CONTRIB_NAMES, not(hasItem("파기됨")));
    }
}
