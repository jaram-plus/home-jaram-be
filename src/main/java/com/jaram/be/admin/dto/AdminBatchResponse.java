package com.jaram.be.admin.dto;

import java.util.List;
import java.util.Map;

// 계약 AdminBatchResponse. 부분 성공: conflicts(버전 충돌)·errors(검증 실패) 별도 표기.
public record AdminBatchResponse(
        List<Updated> updated,
        List<Created> created,
        List<String> deleted,
        List<Conflict> conflicts,
        List<RowError> errors) {

    public record Updated(String id) { }
    public record Created(String tempId, String id) { }
    public record Conflict(String id, String message) { }
    public record RowError(String id, Map<String, String> fieldErrors) { }
}
