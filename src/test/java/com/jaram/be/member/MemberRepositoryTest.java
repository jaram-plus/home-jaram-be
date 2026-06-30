package com.jaram.be.member;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class MemberRepositoryTest extends PostgresTest {

    @Autowired MemberRepository repo;

    // Shared singleton Postgres: clear rows other @SpringBootTest classes committed
    // so existsBy/hasSize assertions are order-independent.
    @BeforeEach void clean() { repo.deleteAll(); }

    @Test
    void savesAndQueriesByEmailAndStatus() {
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        repo.save(m);

        assertThat(repo.existsByEmail("hong@hanyang.ac.kr")).isTrue();
        assertThat(repo.existsByStudentId("2023012345")).isTrue();
        assertThat(repo.findByStatus(MemberStatus.PENDING)).hasSize(1);
        assertThat(repo.findByEmail("hong@hanyang.ac.kr")).isPresent();
    }
}
