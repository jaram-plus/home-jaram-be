package com.jaram.be.member;

import com.jaram.be.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재등록 필요 회원의 신청류를 막는다. 조회와 프로필 수정은 막지 않는다 —
 * 팝업을 닫아도 재등록할 이유가 남게 하는 것이 목적이지 사이트를 잠그는 게 아니다.
 *
 * 경로 기반 필터로 두지 않은 이유는, 막을 다섯 개와 그 예외를 SecurityConfig 에
 * 문자열로 다시 적어야 하고 경로가 바뀔 때 조용히 어긋나기 때문이다.
 */
@Component
public class MemberActivityGuard {

    private final MemberRepository members;

    public MemberActivityGuard(MemberRepository members) { this.members = members; }

    @Transactional(readOnly = true)
    public void requireRegistered(String memberId) {
        members.findById(memberId)
                .filter(m -> m.getStatus() == MemberStatus.REREGISTER)
                .ifPresent(m -> {
                    throw new ApiException(HttpStatus.FORBIDDEN, "REREGISTRATION_REQUIRED",
                            "재등록이 승인되어야 이용할 수 있습니다.");
                });
    }
}
