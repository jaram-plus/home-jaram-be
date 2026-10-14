package com.jaram.be.schedule.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record ScheduleCreateRequest(
        @NotNull Instant startsAt,
        String place,
        String mode,
        Integer capacity
) { }
