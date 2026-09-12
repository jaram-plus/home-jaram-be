package com.jaram.be.member;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MemberLifecycleFieldsTest {

    private static final Instant T1 = Instant.parse("2026-09-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-05T00:00:00Z");

    private Member member() {
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setGen(41);
        m.setFaculty("컴퓨터학부");
        m.setPhone("010-1234-5678");
        m.setBio("안녕하세요");
        m.setGithubUrl("https://github.com/hong");
        m.setBlogUrl("https://hong.dev");
        return m;
    }

    /** 재신청은 멱등하다 — 두 번 눌러도 처음 시각이 남는다. */
    @Test
    void reregistrationRequestIsIdempotent() {
        Member m = member();
        m.markReregistrationRequired();
        m.requestReregistration(T1);
        m.requestReregistration(T2);
        assertThat(m.getReregisterRequestedAt()).isEqualTo(T1);
    }

    @Test
    void completingReregistrationReturnsToActive() {
        Member m = member();
        m.markReregistrationRequired();
        m.requestReregistration(T1);
        m.completeReregistration();
        assertThat(m.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(m.getReregisterRequestedAt()).isNull();
    }

    @Test
    void withdrawRecordsTime() {
        Member m = member();
        m.withdraw(T1);
        assertThat(m.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(m.getWithdrawnAt()).isEqualTo(T1);
    }

    @Test
    void hasHistoryFollowsTermsAndContributor() {
        Member m = member();
        assertThat(m.hasHistory()).isFalse();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        assertThat(m.hasHistory()).isTrue();
    }

    @Test
    void contributorAloneCountsAsHistory() {
        Member m = member();
        m.setContributor(true);
        assertThat(m.hasHistory()).isTrue();
    }

    @Test
    void purgeClearsPersonalDataButKeepsNameAndTerms() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        m.purge(T2);

        assertThat(m.getName()).isEqualTo("홍길동");
        assertThat(m.getGen()).isEqualTo(41);
        assertThat(m.getTerms()).hasSize(1);
        assertThat(m.getPurgedAt()).isEqualTo(T2);

        assertThat(m.getStudentId()).isNull();
        assertThat(m.getEmail()).isNull();
        assertThat(m.getPasswordHash()).isNull();
        assertThat(m.getPhone()).isNull();
        assertThat(m.getFaculty()).isNull();
        assertThat(m.getBio()).isNull();
        assertThat(m.getGithubUrl()).isNull();
        assertThat(m.getBlogUrl()).isNull();
    }

    @Test
    void freshMemberHasNoLifecycleTimestamps() {
        Member m = member();
        assertThat(m.getReregisterRequestedAt()).isNull();
        assertThat(m.getWithdrawnAt()).isNull();
        assertThat(m.getPurgedAt()).isNull();
    }
}
