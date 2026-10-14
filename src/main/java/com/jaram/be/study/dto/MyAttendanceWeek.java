package com.jaram.be.study.dto;

import com.jaram.be.study.AttendanceState;

/** 계약 MyAttendanceWeek. */
public record MyAttendanceWeek(int weekNo, String title, AttendanceState state) { }
