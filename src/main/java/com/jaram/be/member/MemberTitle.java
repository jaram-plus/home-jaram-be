package com.jaram.be.member;

// 직책. Persisted by enum name (@Enumerated STRING); label() is the Korean
// display text surfaced as PersonMember.role. Nullable on Member.
public enum MemberTitle {
    PRESIDENT("회장"),
    VICE_PRESIDENT("부회장"),
    ACADEMIC_LEAD("학술부장"),
    ACADEMIC_MEMBER("학술부원"),
    PR_LEAD("홍보부장"),
    PR_MEMBER("홍보부원"),
    FINANCE_LEAD("회계부장"),
    FINANCE_MEMBER("회계부원"),
    SERVER_ADMIN("서버 관리자");

    private final String label;
    MemberTitle(String label) { this.label = label; }
    public String label() { return label; }
}
