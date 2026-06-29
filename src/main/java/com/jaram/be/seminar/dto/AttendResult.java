package com.jaram.be.seminar.dto;

// Matches OpenAPI schema AttendResult. at is the HH:mm display of the attendance time.
public record AttendResult(String seminarId, String at) { }
