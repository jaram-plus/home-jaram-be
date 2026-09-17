package com.jaram.be.auth;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class PasswordResetTokenRepositoryTest extends PostgresTest {

    @Autowired PasswordResetTokenRepository repo;

    @Test
    void consumableUntilUsedOrExpired() {
        Instant now = Instant.now();
        var t = PasswordResetToken.issue("m1", "tok-123", now.plus(30, ChronoUnit.MINUTES));
        repo.save(t);

        var found = repo.findByToken("tok-123").orElseThrow();
        assertThat(found.isConsumable(now)).isTrue();
        assertThat(found.isConsumable(now.plus(31, ChronoUnit.MINUTES))).isFalse(); // expired

        found.consume(now);
        assertThat(found.isConsumable(now)).isFalse(); // used
    }
}
