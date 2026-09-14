package com.jaram.be.study.dto;

// 계약 Applicant. 승인 대기 지원자. id=스터디 신청 ID, createdAt=ISO date-time.
public record Applicant(
        String id,
        String studyId,
        String studyTitle,
        String name,
        String studentId,
        String motive,
        String createdAt) {
}
