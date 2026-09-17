package com.jaram.be.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void apiExceptionMapsToEnvelope() {
        var ex = new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "이미 가입 신청된 이메일입니다.");
        var handler = new GlobalExceptionHandler();
        var resp = handler.handleApi(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().code()).isEqualTo("EMAIL_TAKEN");
        assertThat(resp.getBody().fieldErrors()).isNull();
    }
}
