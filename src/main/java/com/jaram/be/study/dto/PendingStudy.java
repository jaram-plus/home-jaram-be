package com.jaram.be.study.dto;

import java.util.List;

// 계약 PendingStudy. 개설 승인 대기 스터디. creator=개설자 이름, createdAt=ISO date-time.
public record PendingStudy(
        String id,
        String title,
        List<String> fields,
        String creator,
        int capacity,
        String schedule,
        String period,
        String intro,
        String createdAt) {
}
