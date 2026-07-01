package com.jaram.be.admin.dto;

import java.util.List;
import java.util.Map;

// 계약 AdminBatchRequest. 인라인 편집분 일괄 커밋.
public record AdminBatchRequest(
        List<Update> updates,
        List<Create> creates,
        List<String> deletes) {

    public record Update(String id, Integer version, Map<String, Object> fields) { }
    public record Create(String tempId, Map<String, Object> fields) { }

    public List<Update> updates() { return updates == null ? List.of() : updates; }
    public List<Create> creates() { return creates == null ? List.of() : creates; }
    public List<String> deletes() { return deletes == null ? List.of() : deletes; }
}
