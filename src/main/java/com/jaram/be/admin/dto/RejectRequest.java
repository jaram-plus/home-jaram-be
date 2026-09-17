package com.jaram.be.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectRequest(@NotBlank(message = "거절 사유를 입력해 주세요.") String reason) { }
