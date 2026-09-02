package com.jaram.be.admin.dto;

import jakarta.validation.Valid;

// 계약 AdminSettingsUpdate. 부분 수정 — 모든 필드 nullable(미포함 시 미변경).
// links 는 통째로 교체한다: 보낸 객체의 네 채널이 그대로 새 값이 되고, 그중 null 인
// 채널은 '등록 안 함'이 된다. 채널 하나만 지우는 요청도 네 개를 모두 보내면 된다.
public record AdminSettingsUpdate(
        String semester,
        Integer currentGen,
        Boolean autoPromote,
        @Valid SiteLinks links) {
}
