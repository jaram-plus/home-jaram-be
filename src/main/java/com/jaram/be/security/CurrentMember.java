package com.jaram.be.security;

import com.jaram.be.member.Authority;

public record CurrentMember(String id, String name, String email, Authority authority) { }
