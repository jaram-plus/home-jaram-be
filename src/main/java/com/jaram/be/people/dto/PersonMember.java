package com.jaram.be.people.dto;

// Matches OpenAPI schema PersonMember. gen is the wire string ("41기") or null.
public record PersonMember(
        String name,
        String role,
        String gen,
        String bio,
        String githubUrl,
        String blogUrl
) { }
