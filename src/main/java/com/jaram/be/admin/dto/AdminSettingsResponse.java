package com.jaram.be.admin.dto;

// 계약 AdminSettings.
public record AdminSettingsResponse(
        String semester,
        int currentCohort,
        boolean autoPromote,
        boolean driveConnected,
        String driveFolder) {
}
