package com.jaram.be.auth;

import com.jaram.be.auth.dto.LoginRequest;
import com.jaram.be.auth.dto.LoginResponse;
import com.jaram.be.auth.dto.PasswordResetConfirm;
import com.jaram.be.auth.dto.PasswordResetRequest;
import com.jaram.be.auth.dto.SignupRequest;
import com.jaram.be.auth.dto.UserSummary;
import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.security.authz.Policy;
import com.jaram.be.security.authz.RoleResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final MemberRepository members;
    private final PasswordEncoder encoder;
    private final JwtProvider jwt;
    private final PasswordResetTokenRepository tokens;
    private final ResetMailSender mailSender;
    private final RoleResolver roles;
    private final long resetTtlSeconds;

    public AuthService(MemberRepository members, PasswordEncoder encoder, JwtProvider jwt,
                       PasswordResetTokenRepository tokens, ResetMailSender mailSender,
                       RoleResolver roles,
                       @Value("${jwt.reset-ttl-seconds}") long resetTtlSeconds) {
        this.members = members;
        this.encoder = encoder;
        this.jwt = jwt;
        this.tokens = tokens;
        this.mailSender = mailSender;
        this.roles = roles;
        this.resetTtlSeconds = resetTtlSeconds;
    }

    @Transactional
    public void signup(SignupRequest req) {
        if (members.existsByEmail(req.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "이미 가입 신청된 이메일입니다.");
        }
        if (members.existsByStudentId(req.studentId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION", "입력값을 확인해 주세요.",
                    Map.of("studentId", "이미 등록된 학번입니다."));
        }
        Member m = Member.newPending(
                req.name(), req.studentId(), req.email(), encoder.encode(req.password()));
        m.setGen(req.gen());
        m.setFaculty(req.faculty());
        m.setPhone(req.phone());
        // 활동축 파생: 재학 → ACTIVE, 휴학 → ON_LEAVE. 승인축은 PENDING (팩토리 기본).
        m.setStatus(req.enrolled() ? MemberStatus.ACTIVE : MemberStatus.ON_LEAVE);
        // 등급은 본인이 고른 구분으로 정한다 — 신입생만 수습회원, 재학생은 기수와 무관하게 준회원.
        m.setGrade(req.newcomer() ? MemberGrade.NEWCOMER : MemberGrade.ASSOCIATE);
        members.save(m);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        Member m = members.findByEmail(req.email())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "등록된 회원 정보가 없습니다."));
        // 비밀번호를 먼저 본다. 상태 검사가 앞서면 비밀번호를 모르는 사람도 이메일만으로
        // 가입 여부와 승인·탈퇴 상태를 구별할 수 있다 — requestReset 은 열거를 막는데
        // login 이 그대로 알려주고 있었다. 정당한 사용자는 순서가 바뀌어도 같은 안내를 받는다.
        if (!encoder.matches(req.password(), m.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID", "이메일 또는 비밀번호가 일치하지 않습니다.");
        }
        if (m.getApproval() != MemberApproval.APPROVED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PENDING", "가입 승인을 기다리는 중입니다.");
        }
        if (m.getStatus() == MemberStatus.WITHDRAWN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WITHDRAWN", "탈퇴한 계정입니다.");
        }
        String token = jwt.generate(m.getId(), m.getName(), m.getEmail());
        return new LoginResponse(token, new UserSummary(m.getId(), m.getName(), m.getEmail(),
                m.getAuthority(), roleNames(m), permissionNames(m)));
    }

    @Transactional
    public void requestReset(PasswordResetRequest req) {
        members.findByEmail(req.email()).ifPresent(m -> {
            Instant now = Instant.now();
            // 새로 요청하면 이전 링크는 못 쓴다 — 사용자의 일반적 기대이고, 이전 메일이
            // 유출됐을 때 재요청이 대응 수단이 된다.
            tokens.findByMemberIdAndUsedAtIsNull(m.getId()).forEach(t -> t.consume(now));
            String token = UUID.randomUUID().toString();
            tokens.save(PasswordResetToken.issue(
                    m.getId(), token, now.plusSeconds(resetTtlSeconds)));
            mailSender.send(m.getEmail(), token);
        });
        // always succeeds (enumeration defense)
    }

    @Transactional
    public void confirmReset(PasswordResetConfirm req) {
        Instant now = Instant.now();
        PasswordResetToken t = tokens.findByToken(req.token())
                .filter(x -> x.isConsumable(now))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID", "유효하지 않거나 만료된 토큰입니다."));
        Member m = members.findById(t.getMemberId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID", "유효하지 않은 토큰입니다."));
        m.setPasswordHash(encoder.encode(req.password()));
        // 발급된 액세스 토큰까지 끊는다. 이게 없으면 토큰을 탈취당한 사용자가 비밀번호를
        // 바꿔도 공격자는 ttl(12시간) 동안 그대로 접근한다 — 재설정이 대응이 되지 않는다.
        m.invalidateCredentials(now);
        t.consume(now);
    }

    private List<String> roleNames(Member m) {
        return roles.rolesOf(m).stream().map(Enum::name).sorted().toList();
    }

    private List<String> permissionNames(Member m) {
        return Policy.permissionsOf(roles.rolesOf(m)).stream().map(Enum::name).sorted().toList();
    }
}
