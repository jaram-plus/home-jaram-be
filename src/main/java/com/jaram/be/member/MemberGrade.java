package com.jaram.be.member;

// 회원 등급 (직책 MemberTitle과 분리). 가입 신청 시 본인이 고른 구분으로 정해진다
// (SignupRequest.newcomer: 신입생 → NEWCOMER, 재학생 → ASSOCIATE). Persisted by enum name
// (@Enumerated STRING); label()은 title이 없는 카드의 PersonMember.role 폴백 표시.
public enum MemberGrade {
    NEWCOMER("신입부원"),
    ASSOCIATE("준회원"),
    REGULAR("정회원"),
    OB("OB");

    private final String label;
    MemberGrade(String label) { this.label = label; }
    public String label() { return label; }
}
