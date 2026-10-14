package com.jaram.be.security.authz;

import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 1층 자격 게이트. Role 이 무엇이든 넘지 못하는 상한이다 — AWS 의 permissions
 * boundary 자리이며, 권한 부여(가산 OR)와 교집합으로 만난다.
 *
 * 질문이 두 개라 메서드도 둘이다.
 *
 * isUsable — 이 토큰을 지금도 인증에 쓸 수 있는가. 미승인·탈퇴·자격증명 무효화가
 * 걸린다. JwtAuthFilter 가 매 요청 부르며, 막히면 인증 자체가 서지 않아 401 이 된다.
 * 재등록 대상(REREGISTER)은 여기서 막지 않는다 — 팝업을 띄우고 재등록을 신청하려면
 * 로그인 상태여야 한다.
 *
 * requireActive — 지금 신청류를 할 자격이 있는가. 재등록 대상을 여기서 막는다.
 * 403 과 코드를 던지는 이유는 화면이 재등록 팝업을 띄워야 하기 때문이다.
 */
@Component
public class Eligibility {

    private final MemberRepository members;

    public Eligibility(MemberRepository members) { this.members = members; }

    /**
     * 발급된 토큰이 지금도 유효한가. 경로 목록을 SecurityConfig 에 문자열로 다시
     * 적지 않고 필터 한 곳에서 보는 이유는, 경로가 바뀔 때 조용히 어긋나기 때문이다.
     * 실제로 가드가 신청류 다섯 곳에만 걸려 있어 관리자 경로가 통째로 비어 있었다.
     */
    public boolean isUsable(Member m, Instant issuedAt) {
        if (m == null) return false;
        if (m.getApproval() != MemberApproval.APPROVED) return false;
        if (m.getStatus() == MemberStatus.WITHDRAWN) return false;

        Instant invalidatedAt = m.getCredentialsInvalidatedAt();
        if (invalidatedAt == null || issuedAt == null) return true;
        // iat 는 초 단위로만 저장된다. 무효화 시각을 자르지 않으면, 재설정과 같은 초에
        // 다시 로그인해 받은 새 토큰이 iat < invalidatedAt 이 되어 거부된다.
        return !issuedAt.isBefore(invalidatedAt.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * 활동 자격이 없는 회원의 신청류를 막는다. 조회와 프로필 수정은 막지 않는다 —
     * 팝업을 닫아도 재등록할 이유가 남게 하는 것이 목적이지 사이트를 잠그는 게 아니다.
     */
    @Transactional(readOnly = true)
    public void requireActive(String memberId) {
        members.findById(memberId).ifPresent(m -> {
            if (m.getStatus() == MemberStatus.REREGISTER) {
                throw new ApiException(HttpStatus.FORBIDDEN, "REREGISTRATION_REQUIRED",
                        "재등록이 승인되어야 이용할 수 있습니다.");
            }
            if (m.getStatus() == MemberStatus.WITHDRAWN) {
                throw new ApiException(HttpStatus.FORBIDDEN, "WITHDRAWN", "탈퇴한 계정입니다.");
            }
        });
    }
}
