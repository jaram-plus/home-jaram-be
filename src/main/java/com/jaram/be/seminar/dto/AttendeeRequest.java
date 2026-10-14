package com.jaram.be.seminar.dto;

import jakarta.validation.constraints.NotBlank;

// Matches OpenAPI schema AttendeeRequest — 임원이 수기로 출석 처리할 회원.
public record AttendeeRequest(@NotBlank String memberId) { }
