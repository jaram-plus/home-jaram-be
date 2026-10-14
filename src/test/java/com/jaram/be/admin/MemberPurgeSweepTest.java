package com.jaram.be.admin;

import com.jaram.be.common.ClubTime;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MemberPurgeSweepTest extends PostgresTest {

    private static final LocalDate OCT_2026 = LocalDate.of(2026, 10, 1);
    private static final LocalDate MAR_2027 = LocalDate.of(2027, 3, 1);
    private static final LocalDate SEP_2027 = LocalDate.of(2027, 9, 1);

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

    private static Instant atStartOf(LocalDate d) {
        return ClubTime.startOfDay(d);
    }

    /** 재등록하지 않고 한 학기를 더 넘기면 사라진다. */
    @Test
    void unrenewedMemberIsPurgedAtNextRollover() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);   // 초기화
        lifecycle.sweep(MAR_2027);   // REREGISTER 로
        assertThat(members.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.REREGISTER);

        lifecycle.sweep(SEP_2027);   // 파기
        assertThat(members.findById(m.getId())).isEmpty();
    }

    /** 방금 넘어간 회원이 같은 스윕에서 지워지면 안 된다 — 파기가 전환보다 먼저다. */
    @Test
    void memberRolledInThisSweepSurvivesIt() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027);
        assertThat(members.findById(m.getId())).isPresent();
    }

    /** 재등록을 마친 회원은 다음 전환에서 다시 한 학기를 얻는다. */
    @Test
    void renewedMemberIsNotPurged() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027);

        Member found = members.findById(m.getId()).orElseThrow();
        found.completeReregistration();
        members.save(found);

        lifecycle.sweep(SEP_2027);
        assertThat(members.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.REREGISTER);
    }

    /** 탈퇴 6개월 하루 전에는 살아 있다. */
    @Test
    void withdrawnMemberSurvivesJustBeforeSixMonths() {
        Member m = active("이탈퇴", "2023099999", "bye@hanyang.ac.kr");
        m.withdraw(atStartOf(MAR_2027));
        members.save(m);

        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027.plusMonths(6).minusDays(1));
        assertThat(members.findById(m.getId())).isPresent();
    }

    /** 6개월이 되는 날 파기된다. */
    @Test
    void withdrawnMemberIsPurgedAtSixMonths() {
        Member m = active("이탈퇴", "2023099999", "bye@hanyang.ac.kr");
        m.withdraw(atStartOf(MAR_2027));
        members.save(m);

        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027.plusMonths(6));
        assertThat(members.findById(m.getId())).isEmpty();
    }

    /** 이미 파기된 회원을 다시 건드리지 않는다. */
    @Test
    void alreadyPurgedMemberIsLeftAlone() {
        Member m = active("박기여", "2021088888", "contrib@hanyang.ac.kr");
        m.setContributor(true);
        m.withdraw(atStartOf(MAR_2027));
        members.save(m);

        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027.plusMonths(6));
        Instant first = members.findById(m.getId()).orElseThrow().getPurgedAt();
        assertThat(first).isNotNull();

        lifecycle.sweep(MAR_2027.plusMonths(7));
        assertThat(members.findById(m.getId()).orElseThrow().getPurgedAt()).isEqualTo(first);
    }
}
