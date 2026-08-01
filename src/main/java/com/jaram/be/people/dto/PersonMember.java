package com.jaram.be.people.dto;

// Matches OpenAPI schema PersonMember. gen is the display 기수 as a plain integer, or null.
public record PersonMember(
        String name,
        String role,
        Integer gen,
        String bio,
        String githubUrl,
        String blogUrl
) { }
