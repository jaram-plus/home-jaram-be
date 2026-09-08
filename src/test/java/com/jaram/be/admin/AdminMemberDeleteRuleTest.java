package com.jaram.be.admin;

import com.jaram.be.admin.dto.AdminBatchRequest;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AdminMemberDeleteRuleTest extends PostgresTest {

    @Autowired AdminBatchExecutor executor;
    @Autowired MemberRepository members;

    @BeforeEach void setup() { members.deleteAll(); }

    private Member saved(String name, String studentId, String email) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        return members.save(m);
    }

    /** AdminBatchRequest.Update 의 version 은 Integer 인데 Member.getVersion 은 Long 이다. */
    private static AdminBatchRequest.Update update(Member m, Map<String, Object> fields) {
        return new AdminBatchRequest.Update(m.getId(), m.getVersion().intValue(), fields);
    }

    /** 임기 이력이 있으면 임원이 눌러도 이력이 남는다 — 스윕의 파기와 같은 규칙이다. */
    @Test
    void deletingMemberWithHistoryPurgesInstead() {
        Member m = saved("이임원", "2022022222", "exec@hanyang.ac.kr");
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        members.save(m);

        executor.deleteRow(AdminResource.members, m.getId());

        Member found = members.findById(m.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("이임원");
        assertThat(found.getEmail()).isNull();
        assertThat(found.getPurgedAt()).isNotNull();
    }

    @Test
    void deletingMemberWithoutHistoryRemovesTheRow() {
        Member m = saved("김없음", "2024011111", "none@hanyang.ac.kr");
        executor.deleteRow(AdminResource.members, m.getId());
        assertThat(members.findById(m.getId())).isEmpty();
    }

    /** 재등록 상태는 스윕만 설정한다. 사람이 표에서 고르면 스윕과 어긋난다. */
    @Test
    void statusCannotBeSetToReregisterByHand() {
        Member m = saved("홍길동", "2023012345", "hong@hanyang.ac.kr");

        AdminBatchExecutor.UpdateOutcome outcome =
                executor.updateRow(AdminResource.members, update(m, Map.of("status", "REREGISTER")));

        assertThat(outcome).isInstanceOf(AdminBatchExecutor.Invalid.class);
        assertThat(((AdminBatchExecutor.Invalid) outcome).fieldErrors()).containsKey("status");
        assertThat(members.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void otherStatusesStillWork() {
        Member m = saved("홍길동", "2023012345", "hong@hanyang.ac.kr");

        assertThat(executor.updateRow(AdminResource.members, update(m, Map.of("status", "ON_LEAVE"))))
                .isInstanceOf(AdminBatchExecutor.Applied.class);
        assertThat(members.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.ON_LEAVE);
    }
}
