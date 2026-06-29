package com.jaram.be.auth.dto;

import com.jaram.be.member.Authority;

public record UserSummary(String id, String name, String email, Authority authority) { }
