package com.jaram.be.seminar.dto;

import jakarta.validation.constraints.NotBlank;

// Matches OpenAPI schema AttendRequest.
public record AttendRequest(@NotBlank String code) { }
