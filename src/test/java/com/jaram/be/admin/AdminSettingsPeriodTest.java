package com.jaram.be.admin;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학기·기수의 자동값과 수동 override 규칙.
 *
 * 날짜에 의존하는 계산이라 '오늘'을 인자로 받아 고정한다 — 시계를 직접 읽으면
 * 3월 1일이나 새해 첫날에만 깨지는 테스트가 된다.
 */
class AdminSettingsPeriodTest {

    // ── 학기: 3월에 1학기, 9월에 2학기 ──

    @Test
    void termIsFirstFromMarch() {
        assertThat(AdminSettings.autoTerm(LocalDate.of(2026, 3, 1))).isEqualTo(1);
        assertThat(AdminSettings.autoTerm(LocalDate.of(2026, 8, 31))).isEqualTo(1);
    }

    @Test
    void termIsSecondFromSeptember() {
        assertThat(AdminSettings.autoTerm(LocalDate.of(2026, 9, 1))).isEqualTo(2);
        assertThat(AdminSettings.autoTerm(LocalDate.of(2026, 12, 31))).isEqualTo(2);
    }

    /** 1~2월은 아직 3월 전이라 직전 2학기가 이어진다. */
    @Test
    void termStaysSecondBeforeMarch() {
        assertThat(AdminSettings.autoTerm(LocalDate.of(2027, 1, 15))).isEqualTo(2);
        assertThat(AdminSettings.autoTerm(LocalDate.of(2027, 2, 28))).isEqualTo(2);
    }

    @Test
    void termFallsBackToAutoWhenNotOverridden() {
        AdminSettings s = AdminSettings.defaults();
        assertThat(s.effectiveTerm(LocalDate.of(2026, 4, 10))).isEqualTo(1);
    }

    @Test
    void termOverrideWinsWithinSameTerm() {
        AdminSettings s = AdminSettings.defaults();
        s.overrideTerm(2, LocalDate.of(2026, 4, 10));
        assertThat(s.effectiveTerm(LocalDate.of(2026, 5, 20))).isEqualTo(2);
    }

    /** override 는 그 학기 안에서만 산다 — 다음 학기가 오면 자동값으로 돌아간다. */
    @Test
    void termOverrideExpiresWhenTermChanges() {
        AdminSettings s = AdminSettings.defaults();
        s.overrideTerm(2, LocalDate.of(2026, 4, 10));   // 자동은 1학기인데 2로 눌러 둔 상태
        assertThat(s.effectiveTerm(LocalDate.of(2027, 3, 5))).isEqualTo(1);
    }

    /** 해가 바뀌면 같은 2학기라도 다른 학기다. */
    @Test
    void termOverrideExpiresOnYearRollover() {
        AdminSettings s = AdminSettings.defaults();
        s.overrideTerm(1, LocalDate.of(2026, 10, 4));
        assertThat(s.effectiveTerm(LocalDate.of(2027, 1, 9))).isEqualTo(2);
    }

    // ── 기수: 미설정이면 계산, 설정해 두면 해마다 +1 ──

    @Test
    void genIsComputedWhenUnset() {
        AdminSettings s = AdminSettings.defaults();
        assertThat(s.effectiveGen(LocalDate.of(2026, 5, 1))).isEqualTo(42);
    }

    /** 0 은 '자동으로 되돌린다'는 뜻이다. */
    @Test
    void genIsComputedWhenClearedWithZero() {
        AdminSettings s = AdminSettings.defaults();
        s.overrideGen(41, LocalDate.of(2026, 5, 1));
        s.overrideGen(0, LocalDate.of(2026, 5, 1));
        assertThat(s.effectiveGen(LocalDate.of(2026, 5, 1))).isEqualTo(42);
    }

    @Test
    void genUsesOverrideInSameYear() {
        AdminSettings s = AdminSettings.defaults();
        s.overrideGen(41, LocalDate.of(2026, 5, 1));
        assertThat(s.effectiveGen(LocalDate.of(2026, 12, 31))).isEqualTo(41);
    }

    /** 설정해 둔 기수는 해가 바뀔 때마다 하나씩 오른다. */
    @Test
    void genAdvancesEachYearAfterOverride() {
        AdminSettings s = AdminSettings.defaults();
        s.overrideGen(41, LocalDate.of(2026, 5, 1));
        assertThat(s.effectiveGen(LocalDate.of(2027, 1, 1))).isEqualTo(42);
        assertThat(s.effectiveGen(LocalDate.of(2029, 6, 1))).isEqualTo(44);
    }
}
