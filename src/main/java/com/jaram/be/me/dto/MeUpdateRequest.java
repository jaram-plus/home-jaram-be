package com.jaram.be.me.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 모두 선택 항목. bio/githubUrl/blogUrl 은 null 이면 값을 지우고, phone 은 null 이면
 * 미변경이다 — 가입 필수 항목이라 빈 값이 될 수 없기 때문. faculty 는 읽기 전용이라 없다.
 */
public record MeUpdateRequest(
        @Size(max = 500) String bio,
        String githubUrl,
        String blogUrl,
        // Bean Validation 은 null 을 통과시키므로 "null=미변경, 공백=422"가 이 한 줄로 표현된다.
        @Pattern(regexp = ".*\\S.*", message = "휴대전화 번호를 입력해 주세요.") String phone) {
}
