package com.jaram.be.seminar.dto;

// Matches OpenAPI schema RosterEntry. sid is the member's studentId; at is HH:mm.
public record RosterEntry(String name, String sid, String at) { }
