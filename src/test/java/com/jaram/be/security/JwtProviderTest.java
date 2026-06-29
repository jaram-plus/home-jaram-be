package com.jaram.be.security;

import com.jaram.be.member.Authority;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class JwtProviderTest {

    private final JwtProvider provider =
            new JwtProvider("test-secret-test-secret-test-secret-32bytes", 43200);

    @Test
    void roundTripsClaims() {
        String token = provider.generate("m1", "홍길동", "hong@hanyang.ac.kr", Authority.OFFICER);
        var claims = provider.parse(token);

        assertThat(claims.memberId()).isEqualTo("m1");
        assertThat(claims.authority()).isEqualTo(Authority.OFFICER);
        assertThat(claims.email()).isEqualTo("hong@hanyang.ac.kr");
    }

    @Test
    void rejectsTamperedToken() {
        String token = provider.generate("m1", "n", "e@hanyang.ac.kr", Authority.MEMBER);
        assertThatThrownBy(() -> provider.parse(token + "x")).isInstanceOf(JwtException.class);
    }
}
