package com.jaram.be.security.authz;

import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTitle;

import java.util.Optional;

/**
 * 주체와 권한 사이의 중간 계층. 권한 집합은 들고 있지 않다 — Policy 가 갖는다.
 * 나눈 이유는 바뀌는 빈도다. Role 목록은 직제가 바뀔 때만 바뀌고(거의 없다),
 * 매트릭스는 기능이 늘 때마다 바뀐다.
 *
 * rank 는 P6 위계에 쓴다. 임기를 부여·종료하려면 대상 Role 보다 rank 가 커야 한다.
 * 간격을 40·60·80·90·100 으로 벌려 둔 것은 나중에 중간 직책이 생겨도 재번호가
 * 필요 없게 하기 위해서다.
 */
public enum Role {
    PRESIDENT(100),
    VICE_PRESIDENT(90),
    SERVER_ADMIN(80),
    ACADEMIC_LEAD(60),
    PR_LEAD(60),
    FINANCE_LEAD(60),
    ACADEMIC_STAFF(40),
    PR_STAFF(40),
    FINANCE_STAFF(40),
    /** 임기가 없는 회원. Discord 의 @everyone 자리이며 권한은 비어 있다. */
    MEMBER(0);

    private final int rank;

    Role(int rank) { this.rank = rank; }

    public int rank() { return rank; }

    /**
     * 임기 한 줄을 Role 로 옮긴다. MemberTitle.allowedIn 이 유효 조합을 이미 9개로
     * 닫아 놨으므로 그 9개만 매핑하고 나머지는 비운다 — 막히는 쪽으로 실패한다.
     */
    public static Optional<Role> of(MemberDepartment d, MemberTitle t) {
        if (d == null || t == null || !t.allowedIn(d)) return Optional.empty();
        return Optional.of(switch (d) {
            case LEADERSHIP -> t == MemberTitle.PRESIDENT ? PRESIDENT : VICE_PRESIDENT;
            case INFRA -> SERVER_ADMIN;
            case ACADEMIC -> t == MemberTitle.LEAD ? ACADEMIC_LEAD : ACADEMIC_STAFF;
            case PR -> t == MemberTitle.LEAD ? PR_LEAD : PR_STAFF;
            case FINANCE -> t == MemberTitle.LEAD ? FINANCE_LEAD : FINANCE_STAFF;
        });
    }
}
