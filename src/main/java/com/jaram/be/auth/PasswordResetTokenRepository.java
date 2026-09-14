package com.jaram.be.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {
    Optional<PasswordResetToken> findByToken(String token);

    /** 아직 쓰이지 않은 재설정 토큰. 새 토큰을 발급할 때 이것들을 소비해 무효화한다. */
    List<PasswordResetToken> findByMemberIdAndUsedAtIsNull(String memberId);
}
