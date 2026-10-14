package com.jaram.be.admin;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학기 경계는 3월 1일과 9월 1일이다. 새해 첫날이 아니다.
 */
class SemesterTest {

    @Test
    void marchStartsFirstTerm() {
        assertThat(Semester.autoAt(LocalDate.of(2026, 3, 1))).isEqualTo(new Semester(2026, 1));
        assertThat(Semester.autoAt(LocalDate.of(2026, 8, 31))).isEqualTo(new Semester(2026, 1));
    }

    @Test
    void septemberStartsSecondTerm() {
        assertThat(Semester.autoAt(LocalDate.of(2026, 9, 1))).isEqualTo(new Semester(2026, 2));
        assertThat(Semester.autoAt(LocalDate.of(2026, 12, 31))).isEqualTo(new Semester(2026, 2));
    }

    /** 1~2월은 직전 2학기의 연장이라 학년도를 하나 물린다. */
    @Test
    void januaryBelongsToPreviousSecondTerm() {
        assertThat(Semester.autoAt(LocalDate.of(2027, 1, 1))).isEqualTo(new Semester(2026, 2));
        assertThat(Semester.autoAt(LocalDate.of(2027, 2, 28))).isEqualTo(new Semester(2026, 2));
    }

    /** 이 한 줄이 "새해 첫날에 전원이 재등록 대상이 된다"를 막는다. */
    @Test
    void newYearIsNotASemesterBoundary() {
        assertThat(Semester.autoAt(LocalDate.of(2027, 1, 1)))
                .isEqualTo(Semester.autoAt(LocalDate.of(2026, 12, 31)));
    }

    @Test
    void ordersByYearThenTerm() {
        assertThat(new Semester(2026, 2)).isGreaterThan(new Semester(2026, 1));
        assertThat(new Semester(2027, 1)).isGreaterThan(new Semester(2026, 2));
        assertThat(new Semester(2026, 1)).isEqualByComparingTo(new Semester(2026, 1));
    }
}
