package com.jaram.be.security.authz;

import org.springframework.security.core.Authentication;

/**
 * &#64;PreAuthorize 의 조건 빈들이 쓰는 헬퍼. GrantedAuthority 문자열이 Permission 의
 * enum name 과 같다는 규약이 여기 한 줄에만 적혀 있게 한다.
 */
public final class Permissions {

    private Permissions() { }

    public static boolean has(Authentication auth, Permission p) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> p.name().equals(a.getAuthority()));
    }
}
