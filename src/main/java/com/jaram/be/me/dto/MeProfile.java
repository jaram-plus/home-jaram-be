package com.jaram.be.me.dto;

import com.jaram.be.member.Authority;

public record MeProfile(
        String id,
        String name,
        String studentId,
        String email,
        Authority authority,
        String gen,
        String bio,
        String githubUrl,
        String blogUrl) {
}
