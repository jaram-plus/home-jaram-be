package com.jaram.be.admin;

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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MemberRolloverSweepTest extends PostgresTest {

    private static final LocalDate OCT_2026 = LocalDate.of(2026, 10, 1);
    private static final LocalDate MAR_2027 = LocalDate.of(2027, 3, 1);
    private static final LocalDate JAN_2027 = LocalDate.of(2027, 1, 1);

    @Autowired MemberLifecycleService lifecycle;
    @Autowired MemberRepository members;
    @Autowired AdminSettingsRepository settings;

    @BeforeEach void setup() {
        members.deleteAll();
        settings.deleteAll();
    }

    private Member active(String name, String studentId, String email) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setStatus(MemberStatus.ACTIVE);
        return members.save(m);
    }

    private MemberStatus statusOf(Member m) {
        return members.findById(m.getId()).orElseThrow().getStatus();
    }

    /** 첫 실행은 초기화만 한다 — 이게 없으면 배포 직후 전원이 재등록 대상이 된다. */
    @Test
    void firstSweepInitialisesWithoutRolling() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        assertThat(statusOf(m)).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void nextSemesterRollsActiveMembers() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);   // 초기화
        lifecycle.sweep(MAR_2027);   // 2026-2 → 2027-1
        assertThat(statusOf(m)).isEqualTo(MemberStatus.REREGISTER);
    }

    @Test
    void exemptMembersAreUntouched() {
        Member onLeave = active("휴학생", "2023011111", "leave@hanyang.ac.kr");
        onLeave.setStatus(MemberStatus.ON_LEAVE);
        members.save(onLeave);

        Member ob = active("졸업생", "2019022222", "ob@hanyang.ac.kr");
        ob.setGrade(MemberGrade.OB);
        members.save(ob);

        Member officer = active("임원", "2022033333", "exec@hanyang.ac.kr");
        officer.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        members.save(officer);

        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027);

        assertThat(statusOf(onLeave)).isEqualTo(MemberStatus.ON_LEAVE);
        assertThat(statusOf(ob)).isEqualTo(MemberStatus.ACTIVE);
        assertThat(statusOf(officer)).isEqualTo(MemberStatus.ACTIVE);
    }

    /** 새해 첫날은 학기 경계가 아니다. */
    @Test
    void newYearDoesNotRoll() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(JAN_2027);
        assertThat(statusOf(m)).isEqualTo(MemberStatus.ACTIVE);
    }

    /** 같은 학기에 여러 번 돌아도 한 번만 전환한다. */
    @Test
    void sweepIsIdempotentWithinASemester() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027);
        lifecycle.sweep(MAR_2027.plusDays(10));

        Member found = members.findById(m.getId()).orElseThrow();
        found.completeReregistration();
        members.save(found);

        lifecycle.sweep(MAR_2027.plusDays(20));
        assertThat(statusOf(m)).isEqualTo(MemberStatus.ACTIVE);
    }

    /** 서버가 한 학기 내내 꺼져 있었어도 켜질 때 한 번에 따라잡는다. */
    @Test
    void catchesUpAfterSkippedSemesters() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(LocalDate.of(2028, 3, 1));
        assertThat(statusOf(m)).isEqualTo(MemberStatus.REREGISTER);
    }
}
