package com.jaram.be.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// 계약 AdminSettingsUpdate. 부분 수정 — 모든 필드 nullable(미포함 시 미변경).
// semesterYear 는 받지 않는다: 연도는 서버가 오늘에서 계산한다.
// semesterTerm 을 보내면 그 학기 동안만 자동값을 덮고, 다음 학기에는 자동으로 돌아간다.
// currentGen 은 0 을 보내면 '자동 계산으로 되돌린다'는 뜻이다.
// links 는 통째로 교체한다: 보낸 객체의 네 채널이 그대로 새 값이 되고, 그중 null 인
// 채널은 '등록 안 함'이 된다. 채널 하나만 지우는 요청도 네 개를 모두 보내면 된다.
public record AdminSettingsUpdate(
        @Min(value = 1, message = "학기는 1 또는 2여야 합니다.")
        @Max(value = 2, message = "학기는 1 또는 2여야 합니다.")
        Integer semesterTerm,
        @Min(value = 0, message = "기수는 0 이상이어야 합니다. 0 은 자동 계산입니다.")
        Integer currentGen,
        Boolean autoPromote,
        @Valid SiteLinks links) {
}
