package com.jaram.be.seminar.dto;

import com.jaram.be.seminar.SeminarStatus;

// Matches OpenAPI schema Seminar. attendanceCode is intentionally absent.
public record SeminarResponse(
        String id,
        String title,
        String speaker,
        String topic,
        String startsAt,
        String day,
        String month,
        String weekday,
        String time,
        String place,
        String mode,
        SeminarStatus status,
        String materialUrl,
        Integer capacity,
        String description,
        String attendanceClosesAt,
        String attendedAt
) { }
