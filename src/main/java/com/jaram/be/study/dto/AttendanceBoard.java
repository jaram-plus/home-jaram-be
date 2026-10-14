package com.jaram.be.study.dto;

import java.util.List;

/** 계약 AttendanceBoard. 주차 x 멤버 격자를 한 번에 내려보낸다. */
public record AttendanceBoard(List<AttendanceWeek> weeks, List<AttendanceMember> members) { }
