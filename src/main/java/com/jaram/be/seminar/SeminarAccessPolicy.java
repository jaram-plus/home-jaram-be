package com.jaram.be.seminar;

import com.jaram.be.security.CurrentMember;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 3층 조건 — 권한이 아니라 리소스와의 관계를 본다. "본인 세미나만 수정"은 Permission
 * 으로 표현할 수 없다. 모두가 갖는 능력이고 대상만 제한되기 때문이다.
 *
 * 지금까지 이 판정은 SeminarService 안에 손으로 박혀 있었다. 애너테이션으로 올리면
 * 권한과 조건이 핸들러 한 줄에 같이 보인다.
 */
@Component("seminarAccess")
public class SeminarAccessPolicy {

    private final SeminarRepository seminars;

    public SeminarAccessPolicy(SeminarRepository seminars) { this.seminars = seminars; }

    public boolean isOwner(String seminarId, Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof CurrentMember me)) return false;
        return seminars.findById(seminarId)
                .map(s -> me.id().equals(s.getCreatedById()))
                .orElse(false);
    }
}
