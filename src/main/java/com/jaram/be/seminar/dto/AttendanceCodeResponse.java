package com.jaram.be.seminar.dto;

// Matches OpenAPI schema AttendanceCodeResponse. Officer-only — the public
// Seminar response never carries the code.
public record AttendanceCodeResponse(String attendanceCode) { }
