package com.jaram.be.seminar.dto;

// Matches OpenAPI schema RosterEntry. sid is the member's studentId; at is HH:mm.
// memberId lets the admin screen cancel one member's attendance.
public record RosterEntry(String memberId, String name, String sid, String at) { }
