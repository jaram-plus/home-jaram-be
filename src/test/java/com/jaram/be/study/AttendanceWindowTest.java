package com.jaram.be.study;

import com.jaram.be.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 창 판정만 본다 — 스프링 컨텍스트가 필요 없다. */
class AttendanceWindowTest {

    private final AttendanceWindow window = new AttendanceWindow();

    private static final Instant TAKEN = Instant.parse("2026-09-15T10:00:00Z");

    private StudyWeek weekTakenAt(Instant at) {
        StudyWeek w = StudyWeek.create("study-1", 1, "완전탐색", null);
        if (at != null) w.markTaken(at);
        return w;
    }

    @Test
    void neverTakenIsAlwaysOpen() {
        assertThat(window.isOpen(weekTakenAt(null), false, TAKEN.plusSeconds(999_999))).isTrue();
    }

    @Test
    void openWithinTwentyFourHours() {
        StudyWeek w = weekTakenAt(TAKEN);
        assertThat(window.isOpen(w, false, TAKEN.plusSeconds(60))).isTrue();
        assertThat(window.isOpen(w, false, TAKEN.plusSeconds(24 * 3600 - 1))).isTrue();
    }

    @Test
    void closedAtExactlyTwentyFourHours() {
        StudyWeek w = weekTakenAt(TAKEN);
        assertThat(window.isOpen(w, false, TAKEN.plusSeconds(24 * 3600))).isFalse();
    }

    /** 임원은 창을 무시한다. 이의가 생겼을 때 고칠 손이 어딘가에는 있어야 한다. */
    @Test
    void officerIgnoresTheWindow() {
        StudyWeek w = weekTakenAt(TAKEN);
        assertThat(window.isOpen(w, true, TAKEN.plusSeconds(999_999))).isTrue();
    }

    /**
     * 403 이 아니라 409 다. 스터디장은 이 주차에 대한 권한을 갖고 있다 —
     * 시간이 지났을 뿐이다. 403 으로 내면 화면이 "권한이 없습니다"를 띄운다.
     */
    @Test
    void lockedRaisesConflictNotForbidden() {
        StudyWeek w = weekTakenAt(Instant.now().minusSeconds(48 * 3600));
        assertThatThrownBy(() -> window.requireOpen(w, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("출석 수정 기간")
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("ATTENDANCE_LOCKED");
                });
    }

    @Test
    void openWeekPassesRequireOpen() {
        window.requireOpen(weekTakenAt(null), false);   // 아무것도 던지지 않는다
    }
}
