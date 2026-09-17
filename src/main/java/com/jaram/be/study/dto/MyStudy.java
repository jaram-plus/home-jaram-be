package com.jaram.be.study.dto;

import com.jaram.be.study.StudyStatus;

// 계약 MyStudy. 내가 개설한 스터디. status 하나가 승인축과 생애축을 다 말한다.
// pendingApplicants 는 RECRUITING 일 때만 채운다 — 0 과 null 이 다른 뜻이다.
// 0 은 "대기 중인 신청이 없다", null 은 "셀 단계가 아니다".
public record MyStudy(
        String id,
        String title,
        StudyStatus status,
        String reason,
        Integer pendingApplicants) {
}
