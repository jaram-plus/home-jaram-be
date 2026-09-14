package com.jaram.be.auth.dto;

import com.jaram.be.member.Authority;

import java.util.List;

public record UserSummary(String id, String name, String email, Authority authority,
                          List<String> roles, List<String> permissions) { }
