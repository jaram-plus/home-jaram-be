package com.jaram.be.member;

import org.junit.jupiter.api.Test;

import java.time.Year;

import static org.assertj.core.api.Assertions.assertThat;

class GenTest {

    @Test
    void currentIsYearMinusFoundingYear() {
        assertThat(Gen.current()).isEqualTo(Year.now().getValue() - 1984);
    }

    @Test
    void derivesGenFromStudentIdPrefix() {
        assertThat(Gen.ofStudentId("2026123456")).isEqualTo(42);
        assertThat(Gen.ofStudentId("2023000003")).isEqualTo(39);
    }

    @Test
    void returnsNullWhenStudentIdIsNotParsable() {
        assertThat(Gen.ofStudentId(null)).isNull();
        assertThat(Gen.ofStudentId("202")).isNull();
        assertThat(Gen.ofStudentId("abcd1234")).isNull();
    }
}
