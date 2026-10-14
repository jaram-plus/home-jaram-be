package com.jaram.be.security.authz;

import com.jaram.be.member.Member;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 회원이 지금 어떤 Role 인가. 현직 임기(endGen == null)에서 파생하며 아무것도
 * 저장하지 않는다.
 *
 * 파생을 택한 대가는 셋이고 전부 알고 받아들인 것이다 — 이력 수정이 곧 권한 변경이고,
 * 시간 해상도가 기수 단위이며, 권한만 일부 회수할 수 없다. 임기 없는 사람에게 권한이
 * 필요해지거나, 같은 직책인데 사람마다 권한이 달라야 하거나, 날짜 단위 부여가
 * 필요해지면 member_role_grant 테이블을 만들고 **이 메서드만** 고친다.
 * 계약은 roles[]/permissions[] 로 출처를 노출하지 않으므로 그때도 API 는 그대로다.
 */
@Component
public class RoleResolver {

    public Set<Role> rolesOf(Member m) {
        if (m == null) return Set.of();
        return m.currentTerm()
                .flatMap(t -> Role.of(t.getDepartment(), t.getTitle()))
                .map(Set::of)
                .orElse(Set.of(Role.MEMBER));
    }
}
