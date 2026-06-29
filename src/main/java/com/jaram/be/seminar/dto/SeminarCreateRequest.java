package com.jaram.be.seminar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

// Matches OpenAPI schema SeminarCreateRequest. attendanceCode is stored, never echoed back.
public record SeminarCreateRequest(
        @NotBlank String title,
        String speaker,
        String topic,
        @NotNull Instant startsAt,
        String place,
        String mode,
        String attendanceCode,
        String materialUrl,
        Integer capacity
) { }
