package com.jaram.be.seminar.dto;

import java.util.List;

// Matches OpenAPI schema RosterResponse. cap is the seminar capacity (0 when unset).
public record RosterResponse(String title, int cap, List<RosterEntry> list) { }
