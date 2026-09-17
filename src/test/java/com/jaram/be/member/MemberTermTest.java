package com.jaram.be.member;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberTermTest {

    private Member member() {
        return Member.newPending("김자람", "2026123456", "a@jaram.net", "hash");
    }

    @Test
    void assigningFirstTermMakesItCurrent() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);

        assertThat(m.getTitle()).isEqualTo(MemberTitle.LEAD);
        assertThat(m.getDepartment()).isEqualTo(MemberDepartment.ACADEMIC);
        assertThat(m.currentTerm()).isPresent();
        assertThat(m.lastEndedTerm()).isEmpty();
    }

    @Test
    void reassigningSameRoleIsNoOp() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 43);

        assertThat(m.getTerms()).hasSize(1);
        assertThat(m.currentTerm().orElseThrow().getStartGen()).isEqualTo(42);
    }

    @Test
    void assigningNewRoleEndsThePreviousTerm() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.STAFF, 41);
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);

        assertThat(m.getTerms()).hasSize(2);
        assertThat(m.getTitle()).isEqualTo(MemberTitle.LEAD);
        MemberTerm past = m.lastEndedTerm().orElseThrow();
        assertThat(past.getTitle()).isEqualTo(MemberTitle.STAFF);
        assertThat(past.getStartGen()).isEqualTo(41);
        assertThat(past.getEndGen()).isEqualTo(42);
    }

    @Test
    void endingCurrentTermClearsDerivedTitleButKeepsHistory() {
        Member m = member();
        m.assignTerm(MemberDepartment.PR, MemberTitle.LEAD, 42);
        m.endCurrentTerm(42);

        assertThat(m.getTitle()).isNull();
        assertThat(m.getDepartment()).isNull();
        assertThat(m.currentTerm()).isEmpty();
        assertThat(m.lastEndedTerm().orElseThrow().label()).isEqualTo("홍보부장");
    }

    @Test
    void endingWhenThereIsNoCurrentTermDoesNothing() {
        Member m = member();
        m.endCurrentTerm(42);

        assertThat(m.getTerms()).isEmpty();
    }
}
