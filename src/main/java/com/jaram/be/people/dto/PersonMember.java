package com.jaram.be.people.dto;

import com.jaram.be.member.dto.MemberTermResponse;

import java.util.List;

// Matches OpenAPI schema PersonMember. gen is the display 기수 as a plain integer, or null.
public record PersonMember(
        String name,
        String role,
        Integer gen,
        List<MemberTermResponse> terms,   // 임기 이력(오래된 순), 없으면 빈 배열
        String bio,
        String githubUrl,
        String blogUrl
) { }
