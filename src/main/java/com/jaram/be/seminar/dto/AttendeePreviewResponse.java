package com.jaram.be.seminar.dto;

import java.util.List;

// Matches OpenAPI schema AttendeePreviewResponse.
public record AttendeePreviewResponse(int count, List<AttendeePreviewEntry> list) { }
