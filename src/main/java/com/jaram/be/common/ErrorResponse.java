package com.jaram.be.common;

import java.util.Map;

public record ErrorResponse(String code, String message, Map<String, String> fieldErrors) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }

    /**
     * 권한 부족. URL 매처가 막든 @PreAuthorize 가 막든 클라이언트는 같은 응답을 본다 —
     * 막는 지점이 둘이라 모양은 여기 한 곳에 둔다.
     */
    public static ErrorResponse forbidden() {
        return of("FORBIDDEN", "접근 권한이 없습니다.");
    }
}
