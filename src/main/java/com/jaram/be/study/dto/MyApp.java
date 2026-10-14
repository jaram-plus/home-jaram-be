package com.jaram.be.study.dto;

import com.jaram.be.study.ApplicationStatus;

// 계약 MyApp. 내 지원 항목.
public record MyApp(
        String id,
        String studyId,
        String title,
        ApplicationStatus status,
        String reason) {
}
