package com.jaram.be.security.authz;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.jaram.be.security.authz.Permission.*;

/**
 * 권한 매트릭스. 이 파일 하나만 열면 전체 표가 한 화면에 보이고, PR 리뷰에서
 * 누가 무엇을 얻고 잃는지가 diff 로 드러난다.
 *
 * DB 에 두지 않은 이유는 두 가지다. 비엔지니어가 런타임에 이 표를 바꿀 상황이 없고,
 * 코드에 두어야 매트릭스 변경이 코드 리뷰를 거친다.
 *
 * 역할은 가산(OR)만 한다. 역할 레벨의 거부는 없다 — 순서로 해결되는 규칙은 사람이
 * 읽지 못한다.
 */
public final class Policy {

    private Policy() { }

    private static final Map<Role, Set<Permission>> MATRIX = new EnumMap<>(Role.class);

    static {
        // P7 — 탈출 해치는 정확히 하나다.
        MATRIX.put(Role.PRESIDENT, EnumSet.allOf(Permission.class));

        // 되돌리기 어려운 기수 전환과 개인정보 반출만 회장에게 남긴다.
        MATRIX.put(Role.VICE_PRESIDENT,
                EnumSet.complementOf(EnumSet.of(SETTINGS_ROLLOVER, EXPORT_RUN)));

        MATRIX.put(Role.SERVER_ADMIN, EnumSet.of(
                SETTINGS_READ, SETTINGS_EDIT, EXPORT_RUN, DASHBOARD_READ, MEMBER_READ));

        MATRIX.put(Role.ACADEMIC_LEAD, EnumSet.of(
                SEMINAR_CREATE, SEMINAR_APPROVE, SEMINAR_ATTENDANCE_MANAGE,
                SEMINAR_ROSTER_READ, SEMINAR_ROSTER_EDIT, SEMINAR_EDIT,
                STUDY_APPROVE, STUDY_APPLICANT_MANAGE, STUDY_EDIT,
                SCHEDULE_MANAGE, DASHBOARD_READ));

        // 부원은 운영은 하되 승인은 못 한다. 지금까지 부원도 임원 권한 전부를 갖고
        // 있었으므로 이 줄이 이번 변경에서 실제로 권한을 잃는 지점이다.
        MATRIX.put(Role.ACADEMIC_STAFF, EnumSet.of(
                SEMINAR_CREATE, SEMINAR_ATTENDANCE_MANAGE,
                SEMINAR_ROSTER_READ, SEMINAR_ROSTER_EDIT,
                STUDY_APPLICANT_MANAGE, DASHBOARD_READ));

        // 홍보부는 부장과 부원의 권한이 같다. 나눌 일이 아직 없다.
        Set<Permission> pr = EnumSet.of(
                SETTINGS_READ, SITE_LINKS_EDIT, SEMINAR_CREATE, DASHBOARD_READ);
        MATRIX.put(Role.PR_LEAD, pr);
        MATRIX.put(Role.PR_STAFF, pr);

        // 앱에 회계 기능이 없다. 회계 기능이 생기면 FINANCE_* Permission 을 추가한다.
        MATRIX.put(Role.FINANCE_LEAD, EnumSet.of(MEMBER_READ, MEMBER_APPROVE, DASHBOARD_READ));
        MATRIX.put(Role.FINANCE_STAFF, EnumSet.of(MEMBER_READ, DASHBOARD_READ));

        // 비어 있는 것이 의도다. 세미나 조회·출석, 스터디 신청, 일정 슬롯 예약은
        // 권한이 아니라 인증 + 1층 자격으로 통과한다.
        MATRIX.put(Role.MEMBER, EnumSet.noneOf(Permission.class));
    }

    public static Set<Permission> permissionsOf(Role role) {
        return MATRIX.getOrDefault(role, Set.of());
    }

    public static Set<Permission> permissionsOf(Set<Role> roles) {
        EnumSet<Permission> union = EnumSet.noneOf(Permission.class);
        roles.forEach(r -> union.addAll(permissionsOf(r)));
        return union;
    }

    /**
     * P6 — 임기를 부여하거나 종료할 수 있는가. TERM_ASSIGN 을 가진 사람도 자기 최고
     * rank 미만의 Role 만 건드릴 수 있다.
     *
     * "누구도 자기 자신의 임기를 수정할 수 없다"는 별도 규칙이 아니라 이 규칙의
     * 결과다 — 자기 Role 은 rank 가 같아서 통과하지 못한다.
     */
    public static boolean canAssign(Set<Role> actorRoles, Role target) {
        if (!permissionsOf(actorRoles).contains(TERM_ASSIGN)) return false;
        int highest = actorRoles.stream().mapToInt(Role::rank).max().orElse(0);
        return highest > target.rank();
    }

    /** 매트릭스에 빠진 Role 이 없는지 — enum 에 추가하고 표를 잊는 것을 막는다. */
    static boolean isComplete() {
        return Arrays.stream(Role.values()).allMatch(MATRIX::containsKey);
    }
}
