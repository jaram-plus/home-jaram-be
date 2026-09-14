package com.jaram.be.security;

import com.jaram.be.security.authz.Permission;
import com.jaram.be.security.authz.Role;

import java.util.Set;

/** 인증된 요청의 주체. 무엇을 할 수 있는지는 permissions 가 정한다. */
public record CurrentMember(String id, String name, String email,
                            Set<Role> roles, Set<Permission> permissions) {

    public boolean can(Permission p) { return permissions.contains(p); }
}
