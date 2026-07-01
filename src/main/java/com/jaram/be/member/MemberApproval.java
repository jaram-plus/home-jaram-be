package com.jaram.be.member;

// 가입 승인 상태 (활동축 MemberStatus와 분리). 가입 기본 PENDING,
// admin이 approve/reject. 계약에 직접 노출되지 않고 /admin/members/pending
// 목록 노출 및 로그인 차단(PENDING/REJECTED)에만 쓰임. Wire = enum name.
public enum MemberApproval { PENDING, APPROVED, REJECTED }
