package com.jaram.be.study;

// 사용자별 파생 지원 상태 (미인증 시 null). JOINED=승인됨/leader, APPLIED=대기,
// CLOSED=모집마감·재지원불가, OPEN=지원가능. Wire = enum name.
public enum ApplyState { OPEN, APPLIED, CLOSED, JOINED }
