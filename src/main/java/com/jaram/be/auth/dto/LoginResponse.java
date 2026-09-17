package com.jaram.be.auth.dto;

public record LoginResponse(String accessToken, UserSummary user) { }
