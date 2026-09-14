package com.jaram.be.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PasswordResetConfirm(
        @NotBlank String token,
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$",
                 message = "비밀번호는 8자 이상이며 영문·숫자·기호를 각각 포함해야 합니다.")
        String password) { }
