package com.jaram.be.auth.dto;

import java.util.List;

public record UserSummary(String id, String name, String email,
                          List<String> roles, List<String> permissions) { }
