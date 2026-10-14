package com.jaram.be.admin.dto;

import java.util.List;
import java.util.Map;

// 계약 AdminListResponse. items 행은 리소스별 필드 맵(enum 값은 wire UPPER 키).
public record AdminListResponse(
        List<Map<String, Object>> items,
        int page,
        int size,
        int total) {
}
