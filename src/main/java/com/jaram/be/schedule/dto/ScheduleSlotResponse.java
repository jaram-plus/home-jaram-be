package com.jaram.be.schedule.dto;

import com.jaram.be.seminar.ApprovalStatus;

public record ScheduleSlotResponse(
        int index,
        SlotMember member,                       // 빈 슬롯이면 null
        String seminarId,                        // 미제출이면 null
        ApprovalStatus seminarApprovalStatus,    // seminarId 없으면 null
        String seminarRejectReason               // REJECTED일 때만
) { }
