package com.jaram.be.study.dto;

import java.util.List;

/**
 * 계약 MyAttendance. taken 이 출석률의 분모다 — 아직 안 찍은 주차는 결석이 아니라
 * 일어나지 않은 일이다.
 */
public record MyAttendance(int attended, int taken, List<MyAttendanceWeek> weeks) { }
