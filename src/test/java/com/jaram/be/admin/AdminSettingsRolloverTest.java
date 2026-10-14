package com.jaram.be.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSettingsRolloverTest {

    /** 아직 한 번도 돌지 않았다는 뜻. 스윕은 이 경우 전환하지 않고 초기화만 한다. */
    @Test
    void defaultsHaveNoRollover() {
        assertThat(AdminSettings.defaults().lastRollover()).isNull();
    }

    @Test
    void rolloverRoundTrips() {
        AdminSettings s = AdminSettings.defaults();
        s.setLastRollover(new Semester(2026, 2));
        assertThat(s.lastRollover()).isEqualTo(new Semester(2026, 2));
    }

    @Test
    void rolloverIsOverwritable() {
        AdminSettings s = AdminSettings.defaults();
        s.setLastRollover(new Semester(2026, 2));
        s.setLastRollover(new Semester(2027, 1));
        assertThat(s.lastRollover()).isEqualTo(new Semester(2027, 1));
    }
}
