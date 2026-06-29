package com.jaram.be.auth;

import com.jaram.be.auth.dto.LoginRequest;
import com.jaram.be.auth.dto.LoginResponse;
import com.jaram.be.auth.dto.SignupRequest;
import com.jaram.be.auth.dto.UserSummary;
import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.security.JwtProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AuthService {

    private final MemberRepository members;
    private final PasswordEncoder encoder;
    private final JwtProvider jwt;

    public AuthService(MemberRepository members, PasswordEncoder encoder, JwtProvider jwt) {
        this.members = members;
        this.encoder = encoder;
        this.jwt = jwt;
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
        members.save(Member.newPending(
                req.name(), req.studentId(), req.email(), encoder.encode(req.password())));
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        Member m = members.findByEmail(req.email())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "등록된 회원 정보가 없습니다."));
        if (m.getStatus() != MemberStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PENDING", "가입 승인을 기다리는 중입니다.");
        }
        if (!encoder.matches(req.password(), m.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID", "이메일 또는 비밀번호가 일치하지 않습니다.");
        }
        String token = jwt.generate(m.getId(), m.getName(), m.getEmail(), m.getAuthority());
        return new LoginResponse(token, new UserSummary(m.getId(), m.getName(), m.getEmail(), m.getAuthority()));
    }
}
