package com.jaram.be.study.dto;

import jakarta.validation.constraints.*;
import java.util.List;

// 계약 StudyUpdateRequest — StudyCreateRequest 에서 weeks 를 뺀 여덟 칸.
// 주차는 개설할 때만 함께 받고 그 뒤로는 /api/studies/{id}/weeks 가 맡는다.
//
// 전부 필수인 것은 부르는 쪽이 '관리하기 > 정보' 폼 하나라서다. 늘 여덟 칸을 함께
// 보내므로, 계약이 반쪽 수정을 말할 수 있는데 화면이 그것을 만들지 않으면 서버에
// 쓰이지 않는 분기만 는다.
public record StudyUpdateRequest(
        @NotBlank String title,
        @NotEmpty List<String> fields,
        @NotNull @Min(1) Integer capacity,
        @NotBlank String intro,
        @NotBlank String schedule,
        @NotBlank String place,
        @NotBlank String mode,
        @NotBlank String contact) {
}
