package com.jaram.be.admin.dto;

// 계약 AdminSettings.
// semesterYear 는 서버가 오늘 날짜에서 계산한다 — 화면에서 고칠 수 없다.
public record AdminSettingsResponse(
        int semesterYear,
        int semesterTerm,
        int currentGen,
        boolean autoPromote,
        boolean driveConnected,
        String driveFolder,
        SiteLinks links) {
}
