package com.jaram.be.security;

import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 토큰을 검증하고, 그 회원이 지금도 쓸 자격이 있는지 확인한다.
 *
 * 자격 검사를 여기에 둔 이유는 {@link com.jaram.be.member.MemberActivityGuard} 와 같다 —
 * 경로 목록을 SecurityConfig 에 문자열로 다시 적으면 경로가 바뀔 때 조용히 어긋난다.
 * 가드는 신청류 다섯 곳에만 걸려 있어서 관리자 경로가 비어 있었다. 탈퇴 처리된 현직
 * 임원이 손에 든 토큰으로 ttl(12시간) 동안 회원 승인과 개인정보 export 를 계속할 수 있었다.
 *
 * 재등록 대상(REREGISTER)은 여기서 막지 않는다. 팝업을 띄우고 재등록을 신청하려면
 * 로그인 상태여야 한다 — 신청류 차단은 그대로 가드가 맡는다.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwt;
    private final MemberRepository members;

    public JwtAuthFilter(JwtProvider jwt, MemberRepository members) {
        this.jwt = jwt;
        this.members = members;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var claims = jwt.parse(header.substring(7));
                Member m = members.findById(claims.memberId()).orElse(null);
                if (m != null && !usable(m, claims.issuedAt())) {
                    chain.doFilter(req, res);   // 인증 없이 통과 → entrypoint 가 401
                    return;
                }
                // 회원을 찾으면 권한을 DB 에서 다시 파생한다 — 임기를 거둬도 클레임은
                // ttl 동안 옛 값을 들고 있기 때문이다. 없으면 클레임을 쓴다. 서명을 위조할
                // 수 없는 이상 존재하지 않는 id 의 토큰은 우리가 발급한 것뿐이다.
                Authority authority = m != null ? m.getAuthority() : claims.authority();
                var principal = new CurrentMember(
                        claims.memberId(), claims.name(), claims.email(), authority);
                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority(authority.name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // invalid token → leave unauthenticated → entrypoint returns 401
            }
        }
        chain.doFilter(req, res);
    }

    private boolean usable(Member m, Instant issuedAt) {
        if (m.getApproval() != MemberApproval.APPROVED) return false;
        if (m.getStatus() == MemberStatus.WITHDRAWN) return false;

        Instant invalidatedAt = m.getCredentialsInvalidatedAt();
        if (invalidatedAt == null || issuedAt == null) return true;
        // iat 는 초 단위로만 저장된다. 무효화 시각을 자르지 않으면, 재설정과 같은 초에
        // 다시 로그인해 받은 새 토큰이 iat < invalidatedAt 이 되어 거부된다.
        return !issuedAt.isBefore(invalidatedAt.truncatedTo(ChronoUnit.SECONDS));
    }
}
