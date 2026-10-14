package com.jaram.be.member;

// 직책. Persisted by enum name (@Enumerated STRING); nullable on Member.
// 표시 라벨은 department와 조합해 파생한다 (ACADEMIC+LEAD -> "학술부장").
// 부서마다 허용되는 직책이 정해져 있다 (allowedIn).
public enum MemberTitle {
    PRESIDENT,       // LEADERSHIP 전용
    VICE_PRESIDENT,  // LEADERSHIP 전용
    LEAD,            // ACADEMIC / PR / FINANCE
    STAFF,           // ACADEMIC / PR / FINANCE
    SERVER_ADMIN;    // INFRA 전용

    public String label(MemberDepartment d) {
        return switch (this) {
            case PRESIDENT -> "회장";
            case VICE_PRESIDENT -> "부회장";
            case SERVER_ADMIN -> "서버 관리자";
            case LEAD -> (d == null ? "부" : d.label()) + "장";
            case STAFF -> (d == null ? "부" : d.label()) + "원";
        };
    }

    public boolean allowedIn(MemberDepartment d) {
        if (d == null) return false;
        return switch (d) {
            case LEADERSHIP -> this == PRESIDENT || this == VICE_PRESIDENT;
            case ACADEMIC, PR, FINANCE -> this == LEAD || this == STAFF;
            case INFRA -> this == SERVER_ADMIN;
        };
    }
}
