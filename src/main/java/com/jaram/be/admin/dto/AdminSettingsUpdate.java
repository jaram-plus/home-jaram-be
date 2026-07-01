package com.jaram.be.admin.dto;

// 계약 AdminSettingsUpdate. 부분 수정 — 모든 필드 nullable(미포함 시 미변경).
public record AdminSettingsUpdate(
        String semester,
        Integer currentCohort,
        Boolean autoPromote) {
}
