package com.jaram.be.admin.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 계약 SiteLinks — 학회 외부 채널 주소.
 *
 * 설정하지 않은 채널은 <b>null</b>이다. 빈 문자열이 아니다: 이 값은 프론트가 새 탭으로
 * 그대로 여는 주소라, 빈 문자열을 받아 두면 아무 데도 가지 않는 링크가 화면에 생긴다.
 * 그래서 값이 있다면 스킴까지 갖춘 http(s) 주소여야 한다.
 */
public record SiteLinks(
        @Pattern(regexp = LINK, message = LINK_MESSAGE) String github,
        @Pattern(regexp = LINK, message = LINK_MESSAGE) String instagram,
        @Pattern(regexp = LINK, message = LINK_MESSAGE) String blog,
        @Pattern(regexp = LINK, message = LINK_MESSAGE) String discord) {

    static final String LINK = "^https?://\\S+$";
    static final String LINK_MESSAGE = "주소는 http로 시작하는 전체 주소여야 합니다. 등록하지 않을 채널은 비워 두세요(null).";

    /** 설정 로우가 아직 없을 때의 응답 — 채널이 하나도 등록되지 않은 상태. */
    public static SiteLinks empty() {
        return new SiteLinks(null, null, null, null);
    }
}
