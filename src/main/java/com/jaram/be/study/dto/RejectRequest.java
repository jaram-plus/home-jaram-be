package com.jaram.be.study.dto;

import jakarta.validation.constraints.NotBlank;

// 계약 RejectRequest. 개설/지원 거절 사유 필수.
public record RejectRequest(@NotBlank String reason) { }
