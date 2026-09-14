package com.jaram.be.study;

/**
 * 스터디 생애축. 선언 순서가 곧 생애 순서다.
 *
 * 옛 approvalStatus(PENDING/APPROVED/REJECTED)가 여기로 접혔다. APPROVED 는 따로
 * 남지 않는다 — 승인된 스터디는 곧바로 RECRUITING 이고, "승인되었다"는 사실은
 * RECRUITING 이상의 어느 값이든 그 자체로 말해 준다.
 *
 * 옛 CLOSED 는 버렸다. 그 값은 "정원이 찼다"는 뜻이었고 그 개념 자체가 없어졌다.
 * 뜻이 달라진 값을 같은 이름으로 남기면 계약을 읽는 사람이 옛 뜻으로 읽는다.
 *
 * Wire = enum name.
 */
public enum StudyStatus {
    PENDING,      // 개설 승인 대기
    REJECTED,     // 개설 반려 (reason 이 채워진다)
    RECRUITING,   // 모집 중
    ONGOING,      // 진행 중
    FINISHED      // 종료
}
