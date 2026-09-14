package com.jaram.be.study.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

// 계약 StudyCreateRequest. 전부 필수다 — 상세 모달이 이 값들로 스터디를 소개한다.
// period 는 받지 않는다: 커리큘럼 주차 수가 대신한다.
public record StudyCreateRequest(
        @NotBlank String title,
        @NotEmpty List<String> fields,
        @NotNull @Min(1) Integer capacity,
        @NotBlank String intro,
        @NotBlank String schedule,
        @NotBlank String place,
        @NotBlank String mode,
        @NotBlank String contact,
        @NotEmpty @Valid List<WeekInput> weeks) {

    public record WeekInput(
            @NotNull @Min(1) Integer weekNo,
            @NotBlank String title,
            @Size(max = 2000) String content) {
    }
}
