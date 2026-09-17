package com.jaram.be.study.dto;

import java.time.Instant;

/**
 * 계약 AttendanceWeek. editable 은 **호출한 사람 기준**이다 — 임원이면 언제나 true.
 * 화면이 takenAt 에 24h 를 더해 스스로 계산하게 두면 시계 차이로 화면과 서버가
 * 다른 답을 낸다.
 */
public record AttendanceWeek(int weekNo, String title, Instant takenAt, boolean editable) { }
