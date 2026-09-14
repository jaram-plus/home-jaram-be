package com.jaram.be.member;

// 활동 상태 (승인축과 별개). 가입 시 SignupRequest.enrolled로 파생
// (true→ACTIVE, false→ON_LEAVE), 이후 admin이 변경. Wire = enum name (UPPER_CASE).
// REREGISTER는 학기 전환 스윕만 설정한다 — admin이 표에서 직접 고를 수 없다.
public enum MemberStatus { ACTIVE, ON_LEAVE, REREGISTER, WITHDRAWN }
