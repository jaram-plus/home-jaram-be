package com.jaram.be.me.dto;

import jakarta.validation.constraints.Size;

public record MeUpdateRequest(
        @Size(max = 500) String bio,
        String githubUrl,
        String blogUrl) {
}
