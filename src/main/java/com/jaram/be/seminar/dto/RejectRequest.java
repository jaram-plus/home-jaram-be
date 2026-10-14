package com.jaram.be.seminar.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectRequest(@NotBlank String reason) { }
