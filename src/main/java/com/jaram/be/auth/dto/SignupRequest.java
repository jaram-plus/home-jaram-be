package com.jaram.be.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record SignupRequest(
        @NotBlank(message = "이름을 입력해 주세요.")
        String name,

        @Pattern(regexp = "^\\d{8,10}$", message = "학번은 8~10자리 숫자여야 합니다.")
        String studentId,

        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Pattern(regexp = ".*@hanyang\\.ac\\.kr$", message = "한양대 이메일(@hanyang.ac.kr)만 사용할 수 있습니다.")
        String email,

        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$",
                 message = "비밀번호는 8자 이상이며 영문·숫자·기호를 각각 포함해야 합니다.")
        String password,

        @NotNull(message = "기수를 입력해 주세요.")
        @Positive(message = "기수는 1 이상의 숫자여야 합니다.")
        Integer gen,

        @NotBlank(message = "학부를 입력해 주세요.")
        String faculty,

        @NotBlank(message = "휴대전화 번호를 입력해 주세요.")
        String phone,

        @NotNull(message = "재학여부를 선택해 주세요.")
        Boolean enrolled
) { }
