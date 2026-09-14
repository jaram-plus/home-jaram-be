package com.jaram.be.schedule.dto;

import com.jaram.be.schedule.ScheduleStatus;
import java.util.List;

public record ScheduleResponse(
        String id,
        String startsAt,
        String day,
        String month,
        String weekday,
        String time,
        String place,
        String mode,
        Integer capacity,
        ScheduleStatus status,
        List<ScheduleSlotResponse> slots
) { }
