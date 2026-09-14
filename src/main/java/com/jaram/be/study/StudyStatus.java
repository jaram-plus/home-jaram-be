package com.jaram.be.study;

// 스터디 모집 상태. cur/cap로 서버 파생 (cur >= cap → CLOSED, 그 외 RECRUITING).
// ONGOING은 라이프사이클 전환 엔드포인트가 계약에 없어 현재 미도달. Wire = enum name.
public enum StudyStatus { RECRUITING, ONGOING, CLOSED }
