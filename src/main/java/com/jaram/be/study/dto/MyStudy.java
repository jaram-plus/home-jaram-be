package com.jaram.be.study.dto;

import com.jaram.be.study.StudyStatus;

// 계약 MyStudy. 내가 개설한 스터디. status 하나가 승인축과 생애축을 다 말한다.
public record MyStudy(
        String id,
        String title,
        StudyStatus status,
        String reason) {
}
