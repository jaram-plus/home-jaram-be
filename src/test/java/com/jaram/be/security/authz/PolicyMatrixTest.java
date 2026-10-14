package com.jaram.be.security.authz;

import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTitle;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.jaram.be.security.authz.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 권한 매트릭스를 표로 고정한다. 매트릭스가 바뀌면 이 테스트가 먼저 깨지고,
 * PR diff 에 "누가 무엇을 얻고 잃는지"가 드러난다.
 */
class PolicyMatrixTest {

    @Test
    void presidentIsTheOnlyWildcard() {
        assertThat(Policy.permissionsOf(Role.PRESIDENT))
                .containsExactlyInAnyOrder(Permission.values());
    }

    /** 부회장이 받는 권한. 새 Permission 을 추가하면 여기나 VP_EXCLUDED 중 한쪽에 넣어야 한다(R8.4). */
    private static final Set<Permission> VP_GRANTED = EnumSet.of(
            MEMBER_LIST, MEMBER_READ, MEMBER_APPROVE, MEMBER_EDIT, TERM_ASSIGN,
            SEMINAR_LIST, SEMINAR_READ, SEMINAR_CREATE, SEMINAR_APPROVE,
            SEMINAR_ATTENDANCE_MANAGE, SEMINAR_ROSTER_READ, SEMINAR_ROSTER_EDIT, SEMINAR_EDIT,
            STUDY_LIST, STUDY_READ, STUDY_APPROVE, STUDY_APPLICANT_MANAGE, STUDY_EDIT,
            SCHEDULE_MANAGE,
            SETTINGS_READ, SETTINGS_EDIT, SITE_LINKS_EDIT,
            DASHBOARD_READ);

    /** 부회장이 받지 못하는 권한. 되돌리기 어려운 기수 전환과 개인정보 반출이다. */
    private static final Set<Permission> VP_EXCLUDED = EnumSet.of(SETTINGS_ROLLOVER, EXPORT_RUN);

    /** 일반 회원을 뺀 모든 임기 직책의 공통 권한. Policy 의 상수가 아니라 spec 에서 적은 값이다. */
    private static final Set<Permission> OFFICER_BASE = EnumSet.of(
            MEMBER_LIST, SEMINAR_LIST, STUDY_LIST, DASHBOARD_READ);

    private static Set<Permission> withBase(Permission... extra) {
        EnumSet<Permission> set = EnumSet.copyOf(OFFICER_BASE);
        set.addAll(List.of(extra));
        return set;
    }

    @Test
    void vicePresidentPermissionsAreDeclaredExplicitly() {
        assertThat(VP_GRANTED).doesNotContainAnyElementsOf(VP_EXCLUDED);
        Set<Permission> all = EnumSet.copyOf(VP_GRANTED);
        all.addAll(VP_EXCLUDED);
        assertThat(all).containsExactlyInAnyOrder(Permission.values());
        assertThat(Policy.permissionsOf(Role.VICE_PRESIDENT)).isEqualTo(VP_GRANTED);
    }

    @Test
    void serverAdminManagesSettingsAndExportOnly() {
        assertThat(Policy.permissionsOf(Role.SERVER_ADMIN)).isEqualTo(withBase(
                SETTINGS_READ, SETTINGS_EDIT, EXPORT_RUN, MEMBER_READ));
    }

    @Test
    void academicLeadOwnsSeminarsAndStudies() {
        assertThat(Policy.permissionsOf(Role.ACADEMIC_LEAD)).isEqualTo(withBase(
                MEMBER_READ, SEMINAR_READ, STUDY_READ,
                SEMINAR_CREATE, SEMINAR_APPROVE, SEMINAR_ATTENDANCE_MANAGE,
                SEMINAR_ROSTER_READ, SEMINAR_ROSTER_EDIT, SEMINAR_EDIT,
                STUDY_APPROVE, STUDY_APPLICANT_MANAGE, STUDY_EDIT,
                SCHEDULE_MANAGE));
    }

    @Test
    void academicStaffRunsSeminarsButCannotApprove() {
        assertThat(Policy.permissionsOf(Role.ACADEMIC_STAFF)).isEqualTo(withBase(
                SEMINAR_READ, STUDY_READ,
                SEMINAR_CREATE, SEMINAR_ATTENDANCE_MANAGE,
                SEMINAR_ROSTER_READ, SEMINAR_ROSTER_EDIT,
                STUDY_APPLICANT_MANAGE));
    }

    /** 홍보부장만 회원 상세(MEMBER_READ)를 본다. */
    @Test
    void prLeadAndStaffDifferOnlyByMemberRead() {
        Set<Permission> staff = withBase(SETTINGS_READ, SITE_LINKS_EDIT, SEMINAR_CREATE);
        assertThat(Policy.permissionsOf(Role.PR_STAFF)).isEqualTo(staff);
        Set<Permission> lead = EnumSet.copyOf(staff);
        lead.add(MEMBER_READ);
        assertThat(Policy.permissionsOf(Role.PR_LEAD)).isEqualTo(lead);
    }

    @Test
    void financeHasMemberPermissionsOnly() {
        assertThat(Policy.permissionsOf(Role.FINANCE_LEAD))
                .isEqualTo(withBase(MEMBER_READ, MEMBER_APPROVE));
        assertThat(Policy.permissionsOf(Role.FINANCE_STAFF))
                .isEqualTo(withBase(MEMBER_READ));
    }

    @Test
    void seminarReadAndStudyReadBelongToExactlyFourRoles() {
        Set<Role> expected = Set.of(Role.PRESIDENT, Role.VICE_PRESIDENT,
                Role.ACADEMIC_LEAD, Role.ACADEMIC_STAFF);
        for (Permission p : List.of(SEMINAR_READ, STUDY_READ)) {
            assertThat(Arrays.stream(Role.values())
                    .filter(r -> Policy.permissionsOf(r).contains(p)).toList())
                    .as(p.name())
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    /** R1.6 — 상세 권한은 목록 권한을, 운영 권한은 상세 권한을 포함한다. */
    @Test
    void everyRoleKeepsTheListReadInclusion() {
        for (Role role : Role.values()) {
            Set<Permission> p = Policy.permissionsOf(role);
            if (p.contains(SEMINAR_EDIT) || p.contains(SEMINAR_ATTENDANCE_MANAGE)
                    || p.contains(SEMINAR_ROSTER_READ) || p.contains(SEMINAR_ROSTER_EDIT)) {
                assertThat(p).as(role + " seminar ops -> SEMINAR_READ").contains(SEMINAR_READ);
            }
            if (p.contains(SEMINAR_READ)) {
                assertThat(p).as(role + " SEMINAR_READ -> SEMINAR_LIST").contains(SEMINAR_LIST);
            }
            if (p.contains(STUDY_EDIT) || p.contains(STUDY_APPLICANT_MANAGE)) {
                assertThat(p).as(role + " study ops -> STUDY_READ").contains(STUDY_READ);
            }
            if (p.contains(STUDY_READ)) {
                assertThat(p).as(role + " STUDY_READ -> STUDY_LIST").contains(STUDY_LIST);
            }
            if (p.contains(MEMBER_READ)) {
                assertThat(p).as(role + " MEMBER_READ -> MEMBER_LIST").contains(MEMBER_LIST);
            }
        }
    }

    /** 임기 없는 회원은 권한이 없다. 조회·출석·신청은 인증과 자격으로 통과한다. */
    @Test
    void plainMemberHasNoPermissions() {
        assertThat(Policy.permissionsOf(Role.MEMBER)).isEmpty();
    }

    @Test
    void rolesAreAdditive() {
        assertThat(Policy.permissionsOf(Set.of(Role.FINANCE_STAFF, Role.PR_STAFF)))
                .isEqualTo(withBase(MEMBER_READ, SETTINGS_READ, SITE_LINKS_EDIT, SEMINAR_CREATE));
    }

    @Test
    void everyValidDepartmentTitlePairMapsToARole() {
        assertThat(Role.of(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT)).contains(Role.PRESIDENT);
        assertThat(Role.of(MemberDepartment.LEADERSHIP, MemberTitle.VICE_PRESIDENT)).contains(Role.VICE_PRESIDENT);
        assertThat(Role.of(MemberDepartment.INFRA, MemberTitle.SERVER_ADMIN)).contains(Role.SERVER_ADMIN);
        assertThat(Role.of(MemberDepartment.ACADEMIC, MemberTitle.LEAD)).contains(Role.ACADEMIC_LEAD);
        assertThat(Role.of(MemberDepartment.ACADEMIC, MemberTitle.STAFF)).contains(Role.ACADEMIC_STAFF);
        assertThat(Role.of(MemberDepartment.PR, MemberTitle.LEAD)).contains(Role.PR_LEAD);
        assertThat(Role.of(MemberDepartment.PR, MemberTitle.STAFF)).contains(Role.PR_STAFF);
        assertThat(Role.of(MemberDepartment.FINANCE, MemberTitle.LEAD)).contains(Role.FINANCE_LEAD);
        assertThat(Role.of(MemberDepartment.FINANCE, MemberTitle.STAFF)).contains(Role.FINANCE_STAFF);
    }

    /** 허용되지 않는 조합은 Role 이 없다 — 막히는 쪽으로 실패한다. */
    @Test
    void invalidPairHasNoRole() {
        assertThat(Role.of(MemberDepartment.ACADEMIC, MemberTitle.PRESIDENT)).isEmpty();
        assertThat(Role.of(null, MemberTitle.LEAD)).isEmpty();
        assertThat(Role.of(MemberDepartment.ACADEMIC, null)).isEmpty();
    }

    /** P6 — 자기보다 낮은 rank 만 임명할 수 있다. */
    @Test
    void assignmentRequiresStrictlyHigherRank() {
        Set<Role> vp = Set.of(Role.VICE_PRESIDENT);
        assertThat(Policy.canAssign(vp, Role.ACADEMIC_LEAD)).isTrue();
        assertThat(Policy.canAssign(vp, Role.SERVER_ADMIN)).isTrue();
        assertThat(Policy.canAssign(vp, Role.PRESIDENT)).isFalse();
        // 같은 rank 도 안 된다 — 자기 임기를 스스로 고칠 수 없다는 규칙이 여기서 나온다
        assertThat(Policy.canAssign(vp, Role.VICE_PRESIDENT)).isFalse();
        assertThat(Policy.canAssign(Set.of(Role.PRESIDENT), Role.PRESIDENT)).isFalse();
        assertThat(Policy.canAssign(Set.of(Role.PRESIDENT), Role.VICE_PRESIDENT)).isTrue();
    }

    /** TERM_ASSIGN 이 없으면 rank 가 높아도 임명할 수 없다. */
    @Test
    void assignmentRequiresTermAssignPermission() {
        assertThat(Policy.permissionsOf(Role.SERVER_ADMIN)).doesNotContain(TERM_ASSIGN);
        assertThat(Policy.canAssign(Set.of(Role.SERVER_ADMIN), Role.ACADEMIC_STAFF)).isFalse();
    }

    /** Role 을 추가하고 매트릭스를 잊으면 여기서 걸린다. */
    @Test
    void matrixCoversEveryRole() {
        assertThat(Policy.isComplete()).isTrue();
    }
}
