package com.jaram.be.auth;

import com.jaram.be.auth.dto.SignupRequest;
import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AuthService {

    private final MemberRepository members;
    private final PasswordEncoder encoder;

    public AuthService(MemberRepository members, PasswordEncoder encoder) {
        this.members = members;
        this.encoder = encoder;
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
}
