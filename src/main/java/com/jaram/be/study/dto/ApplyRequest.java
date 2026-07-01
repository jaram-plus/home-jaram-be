package com.jaram.be.study.dto;

import jakarta.validation.constraints.NotBlank;

// 계약 ApplyRequest. 지원 동기 필수.
public record ApplyRequest(@NotBlank String motive) { }
