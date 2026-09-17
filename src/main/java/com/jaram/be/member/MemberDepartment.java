package com.jaram.be.member;

// 부서. Persisted by enum name (@Enumerated STRING); label() is the Korean
// display text surfaced as PeopleGroup.heading. Nullable on Member.
public enum MemberDepartment {
    LEADERSHIP("회장단"),
    ACADEMIC("학술부"),
    PR("홍보부"),
    FINANCE("회계부"),
    INFRA("인프라");

    private final String label;
    MemberDepartment(String label) { this.label = label; }
    public String label() { return label; }
}
