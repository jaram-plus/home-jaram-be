package com.jaram.be.admin.dto;

// 계약 AdminSettings.
// semesterYear 는 서버가 오늘 날짜에서 계산한다 — 화면에서 고칠 수 없다.
// semesterTerm 은 지금 적용 중인 값이고, semesterTermAuto 는 그것이 자동값인지
// 운영이 눌러 둔 값인지를 가른다 — 둘 다 없으면 화면이 '지금 눌려 있는가'를 알 수 없다.
public record AdminSettingsResponse(
        int semesterYear,
        int semesterTerm,
        boolean semesterTermAuto,
        int currentGen,
        boolean autoPromote,
        boolean driveConnected,
        String driveFolder,
        SiteLinks links) {
}
