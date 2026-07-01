package com.jaram.be.study.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

// 계약 StudyCreateRequest. title/fields/capacity 필수.
public record StudyCreateRequest(
        @NotBlank String title,
        @NotEmpty List<String> fields,
        @NotNull @Min(1) Integer capacity,
        String schedule,
        String period,
        String mode,
        String intro) {
}
