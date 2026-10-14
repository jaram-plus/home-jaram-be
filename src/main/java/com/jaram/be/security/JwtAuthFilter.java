package com.jaram.be.security;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Eligibility;
import com.jaram.be.security.authz.Permission;
import com.jaram.be.security.authz.Policy;
import com.jaram.be.security.authz.Role;
import com.jaram.be.security.authz.RoleResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 토큰을 검증하고, 그 회원이 지금도 쓸 자격이 있는지 확인한다.
 *
 * 자격 검사를 여기에 둔 이유는 {@link Eligibility} 의 신청류 판정과 같다 —
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
    private final Eligibility eligibility;
    private final RoleResolver resolver;

    public JwtAuthFilter(JwtProvider jwt, MemberRepository members, Eligibility eligibility,
                         RoleResolver resolver) {
        this.jwt = jwt;
        this.members = members;
        this.eligibility = eligibility;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var claims = jwt.parse(header.substring(7));
                Member m = members.findById(claims.memberId()).orElse(null);
                // 회원을 찾지 못하거나 자격이 없으면 인증하지 않는다 — 인증 없이 통과시키면
                // entrypoint 가 401 을 만든다. 권한 없는 인증을 세우는 것보다 401 이 맞다.
                if (!eligibility.isUsable(m, claims.issuedAt())) {
                    chain.doFilter(req, res);
                    return;
                }
                Set<Role> roles = resolver.rolesOf(m);
                Set<Permission> permissions = Policy.permissionsOf(roles);

                // 이름과 이메일은 클레임이 아니라 엔티티에서 읽는다 — 회원이 이름을 바꾸면
                // 토큰 안의 옛 이름이 아니라 지금 이름이 나가야 한다.
                var principal = new CurrentMember(
                        m.getId(), m.getName(), m.getEmail(), roles, permissions);
                var auth = new UsernamePasswordAuthenticationToken(principal, null,
                        permissions.stream()
                                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name()))
                                .toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // invalid token → leave unauthenticated → entrypoint returns 401
            }
        }
        chain.doFilter(req, res);
    }
}
