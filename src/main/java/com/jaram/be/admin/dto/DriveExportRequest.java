package com.jaram.be.admin.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

// 계약 DriveExportRequest. resource=리소스 경로(members/seminars/studies).
public record DriveExportRequest(
        @NotBlank String resource,
        Map<String, Object> filters,
        List<String> columns) {
}
