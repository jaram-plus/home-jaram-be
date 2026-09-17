package com.jaram.be.seminar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SeminarStatusTest {

    private final Instant start = Instant.parse("2026-06-27T10:00:00Z");

    @Test
    void beforeStartIsUpcoming() {
        assertThat(SeminarStatus.of(start, start.minus(1, ChronoUnit.MINUTES), 120))
                .isEqualTo(SeminarStatus.UPCOMING);
    }

    @Test
    void atStartAndWithinWindowIsOngoing() {
        assertThat(SeminarStatus.of(start, start, 120)).isEqualTo(SeminarStatus.ONGOING);
        assertThat(SeminarStatus.of(start, start.plus(119, ChronoUnit.MINUTES), 120))
                .isEqualTo(SeminarStatus.ONGOING);
    }

    @Test
    void atWindowEdgeIsOngoingAndAfterIsEnded() {
        assertThat(SeminarStatus.of(start, start.plus(120, ChronoUnit.MINUTES), 120))
                .isEqualTo(SeminarStatus.ONGOING);
        assertThat(SeminarStatus.of(start, start.plus(121, ChronoUnit.MINUTES), 120))
                .isEqualTo(SeminarStatus.ENDED);
    }
}
