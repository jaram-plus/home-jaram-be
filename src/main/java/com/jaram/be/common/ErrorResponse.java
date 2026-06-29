package com.jaram.be.common;

import java.util.Map;

public record ErrorResponse(String code, String message, Map<String, String> fieldErrors) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }
}
