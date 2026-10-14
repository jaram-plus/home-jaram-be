package com.jaram.be.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Postgres for all integration tests using the Testcontainers singleton pattern:
 * one container started once in a static initializer and reused across every test class.
 * It is intentionally NOT managed by {@code @Testcontainers}/{@code @Container} — that
 * extension stops the container after the first test class, leaving later classes with no DB.
 * Ryuk reaps the container at JVM exit.
 */
public abstract class PostgresTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
