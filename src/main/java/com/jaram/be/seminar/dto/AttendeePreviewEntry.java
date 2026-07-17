package com.jaram.be.seminar.dto;

// Matches OpenAPI schema AttendeePreviewEntry. No sid — unlike RosterEntry, open to any member.
public record AttendeePreviewEntry(String name, String at) { }
