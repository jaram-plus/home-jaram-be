package com.jaram.be.security.authz;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTitle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 없이 도는 순수 테스트 — Member 는 엔티티지만 new 로 만들 수 있다. */
class RoleResolverTest {

    private final RoleResolver resolver = new RoleResolver();

    private Member member() {
        return Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
    }

    @Test
    void noTermMeansPlainMember() {
        assertThat(resolver.rolesOf(member())).containsExactly(Role.MEMBER);
    }

    @Test
    void currentTermBecomesItsRole() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        assertThat(resolver.rolesOf(m)).containsExactly(Role.ACADEMIC_LEAD);
    }

    /** 임기가 끝나면 권한도 끝난다 — 이력은 남아도 Role 은 아니다. */
    @Test
    void endedTermGrantsNothing() {
        Member m = member();
        m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 41);
        m.endCurrentTerm(42);
        assertThat(resolver.rolesOf(m)).containsExactly(Role.MEMBER);
    }

    /** 새 임기를 받으면 이전 임기가 끝나고 새 Role 만 남는다. */
    @Test
    void reassignmentReplacesTheRole() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.STAFF, 41);
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        assertThat(resolver.rolesOf(m)).containsExactly(Role.ACADEMIC_LEAD);
    }

    @Test
    void everyValidCombinationResolves() {
        assertRole(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, Role.PRESIDENT);
        assertRole(MemberDepartment.LEADERSHIP, MemberTitle.VICE_PRESIDENT, Role.VICE_PRESIDENT);
        assertRole(MemberDepartment.INFRA, MemberTitle.SERVER_ADMIN, Role.SERVER_ADMIN);
        assertRole(MemberDepartment.ACADEMIC, MemberTitle.LEAD, Role.ACADEMIC_LEAD);
        assertRole(MemberDepartment.ACADEMIC, MemberTitle.STAFF, Role.ACADEMIC_STAFF);
        assertRole(MemberDepartment.PR, MemberTitle.LEAD, Role.PR_LEAD);
        assertRole(MemberDepartment.PR, MemberTitle.STAFF, Role.PR_STAFF);
        assertRole(MemberDepartment.FINANCE, MemberTitle.LEAD, Role.FINANCE_LEAD);
        assertRole(MemberDepartment.FINANCE, MemberTitle.STAFF, Role.FINANCE_STAFF);
    }

    private void assertRole(MemberDepartment d, MemberTitle t, Role expected) {
        Member m = member();
        m.assignTerm(d, t, 42);
        assertThat(resolver.rolesOf(m)).as("%s+%s", d, t).containsExactly(expected);
    }

    @Test
    void nullMemberHasNoRoleAtAll() {
        assertThat(resolver.rolesOf(null)).isEmpty();
    }
}
