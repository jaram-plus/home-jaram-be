package com.jaram.be.security.authz;

import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTitle;
import org.junit.jupiter.api.Test;

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

    @Test
    void vicePresidentLacksRolloverAndExport() {
        assertThat(Policy.permissionsOf(Role.VICE_PRESIDENT))
                .doesNotContain(SETTINGS_ROLLOVER, EXPORT_RUN)
                .hasSize(Permission.values().length - 2);
    }

    @Test
    void serverAdminManagesSettingsAndExportOnly() {
        assertThat(Policy.permissionsOf(Role.SERVER_ADMIN)).containsExactlyInAnyOrder(
                SETTINGS_READ, SETTINGS_EDIT, EXPORT_RUN, DASHBOARD_READ, MEMBER_READ);
    }

    @Test
    void academicLeadOwnsSeminarsAndStudies() {
        assertThat(Policy.permissionsOf(Role.ACADEMIC_LEAD)).containsExactlyInAnyOrder(
                SEMINAR_CREATE, SEMINAR_APPROVE, SEMINAR_ATTENDANCE_MANAGE,
                SEMINAR_ROSTER_READ, SEMINAR_ROSTER_EDIT, SEMINAR_EDIT,
                STUDY_APPROVE, STUDY_APPLICANT_MANAGE, STUDY_EDIT,
                SCHEDULE_MANAGE, DASHBOARD_READ);
    }

    @Test
    void academicStaffRunsSeminarsButCannotApprove() {
        assertThat(Policy.permissionsOf(Role.ACADEMIC_STAFF)).containsExactlyInAnyOrder(
                SEMINAR_CREATE, SEMINAR_ATTENDANCE_MANAGE,
                SEMINAR_ROSTER_READ, SEMINAR_ROSTER_EDIT,
                STUDY_APPLICANT_MANAGE, DASHBOARD_READ);
    }

    @Test
    void prLeadAndStaffShareTheSameSet() {
        Set<Permission> expected = Set.of(
                SETTINGS_READ, SITE_LINKS_EDIT, SEMINAR_CREATE, DASHBOARD_READ);
        assertThat(Policy.permissionsOf(Role.PR_LEAD)).isEqualTo(expected);
        assertThat(Policy.permissionsOf(Role.PR_STAFF)).isEqualTo(expected);
    }

    @Test
    void financeHasMemberPermissionsOnly() {
        assertThat(Policy.permissionsOf(Role.FINANCE_LEAD))
                .containsExactlyInAnyOrder(MEMBER_READ, MEMBER_APPROVE, DASHBOARD_READ);
        assertThat(Policy.permissionsOf(Role.FINANCE_STAFF))
                .containsExactlyInAnyOrder(MEMBER_READ, DASHBOARD_READ);
    }

    /** 임기 없는 회원은 권한이 없다. 조회·출석·신청은 인증과 자격으로 통과한다. */
    @Test
    void plainMemberHasNoPermissions() {
        assertThat(Policy.permissionsOf(Role.MEMBER)).isEmpty();
    }

    @Test
    void rolesAreAdditive() {
        assertThat(Policy.permissionsOf(Set.of(Role.FINANCE_STAFF, Role.PR_STAFF)))
                .containsExactlyInAnyOrder(
                        MEMBER_READ, DASHBOARD_READ, SETTINGS_READ, SITE_LINKS_EDIT, SEMINAR_CREATE);
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
