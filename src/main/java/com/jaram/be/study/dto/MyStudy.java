package com.jaram.be.study.dto;

import com.jaram.be.study.ApprovalStatus;
import com.jaram.be.study.StudyStatus;

// 계약 MyStudy. 내가 개설한 스터디.
public record MyStudy(
        String id,
        String title,
        ApprovalStatus approvalStatus,
        StudyStatus status,
        String reason) {
}
