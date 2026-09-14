package com.jaram.be.study.dto;

import jakarta.validation.constraints.NotNull;

// 계약 RecruitmentUpdate. 값이 하나라 부분 수정할 것이 없다 — PUT 이다.
public record RecruitmentUpdate(@NotNull Boolean open) {
}
