package com.jaram.be.schedule;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ScheduleRepositoryTest extends PostgresTest {

    @Autowired ScheduleRepository schedules;

    @BeforeEach void clean() { schedules.deleteAll(); }

    @Test
    void createsCapacitySlotsAndCascades() {
        Schedule s = Schedule.create(Instant.parse("2026-06-27T10:00:00Z"), "IT관", "offline", 3);
        schedules.save(s);

        Schedule loaded = schedules.findById(s.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ScheduleStatus.OPEN);
        assertThat(loaded.getCapacity()).isEqualTo(3);
        assertThat(loaded.getSlots()).hasSize(3);
        assertThat(loaded.getSlots()).extracting(ScheduleSlot::getIndex).containsExactly(0, 1, 2);
        assertThat(loaded.getSlots()).allSatisfy(slot -> {
            assertThat(slot.getMemberId()).isNull();
            assertThat(slot.getSeminarId()).isNull();
        });
    }

    @Test
    void claimReleaseAndLockPersist() {
        Schedule s = Schedule.create(Instant.now(), null, null, 2);
        s.getSlots().get(0).claim("member-1");
        s.getSlots().get(0).attachSeminar("sem-1");
        s.lock();
        schedules.save(s);

        Schedule loaded = schedules.findById(s.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ScheduleStatus.LOCKED);
        assertThat(loaded.getSlots().get(0).getMemberId()).isEqualTo("member-1");
        assertThat(loaded.getSlots().get(0).getSeminarId()).isEqualTo("sem-1");

        loaded.getSlots().get(0).release();
        schedules.save(loaded);
        Schedule again = schedules.findById(s.getId()).orElseThrow();
        assertThat(again.getSlots().get(0).getMemberId()).isNull();
        assertThat(again.getSlots().get(0).getSeminarId()).isNull();
    }

    @Test
    void ordersByStartsAtAsc() {
        schedules.save(Schedule.create(Instant.parse("2026-06-27T10:00:00Z"), null, null, 1));
        schedules.save(Schedule.create(Instant.parse("2026-06-20T10:00:00Z"), null, null, 1));
        List<Schedule> all = schedules.findAllByOrderByStartsAtAsc();
        assertThat(all).extracting(Schedule::getStartsAt)
                .containsExactly(Instant.parse("2026-06-20T10:00:00Z"), Instant.parse("2026-06-27T10:00:00Z"));
    }
}
