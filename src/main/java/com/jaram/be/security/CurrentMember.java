package com.jaram.be.security;

import com.jaram.be.member.Authority;
import com.jaram.be.security.authz.Permission;
import com.jaram.be.security.authz.Role;

import java.util.Set;

/**
 * 인증된 요청의 주체. authority 는 계약(MEMBER/OFFICER)이라 남아 있고, 실제 판정은
 * permissions 로 한다.
 */
public record CurrentMember(String id, String name, String email, Authority authority,
                            Set<Role> roles, Set<Permission> permissions) {

    public boolean can(Permission p) { return permissions.contains(p); }
}
