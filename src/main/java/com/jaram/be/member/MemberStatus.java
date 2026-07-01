package com.jaram.be.member;

// 활동 상태 (승인축과 별개). 가입 시 SignupRequest.enrolled로 파생
// (true→ACTIVE, false→ON_LEAVE), 이후 admin이 변경. Wire = enum name (UPPER_CASE).
public enum MemberStatus { ACTIVE, ON_LEAVE, WITHDRAWN }
