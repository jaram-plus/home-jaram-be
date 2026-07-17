package com.jaram.be.seminar;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SeminarRepositoryTest extends PostgresTest {

    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;

    // The @SpringBootTest seminar classes commit their fixtures and only clean up on their
    // own @BeforeEach, so rows can outlive them. Clear first — this runs inside the test's
    // rolled-back transaction, so it never destroys another class's data.
    @BeforeEach void clean() {
        attendances.deleteAll();
        seminars.deleteAll();
    }

    @Test
    void listsSeminarsNewestFirst() {
        Instant base = Instant.parse("2026-06-27T10:00:00Z");
        seminars.save(Seminar.create("older", null, null, base.minus(2, ChronoUnit.DAYS),
                null, null, "C1", null, null, "officer-1"));
        seminars.save(Seminar.create("newer", null, null, base,
                null, null, "C2", null, 30, "officer-1"));

        List<Seminar> all = seminars.findAllByOrderByStartsAtDesc();

        assertThat(all).extracting(Seminar::getTitle).containsExactly("newer", "older");
        assertThat(all.get(0).getId()).isNotBlank();
        assertThat(all.get(0).getCapacity()).isEqualTo(30);
        assertThat(all.get(0).getAttendanceCode()).isEqualTo("C2");
    }

    @Test
    void attendanceIsUniquePerMemberPerSeminar() {
        Seminar s = seminars.save(Seminar.create("s", null, null, Instant.now(),
                null, null, "CODE", null, null, "officer-1"));
        attendances.save(Attendance.create(s.getId(), "member-1", Instant.now()));

        assertThatThrownBy(() ->
                attendances.saveAndFlush(Attendance.create(s.getId(), "member-1", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsExistingAttendanceAndRosterInOrder() {
        Seminar s = seminars.save(Seminar.create("s", null, null, Instant.now(),
                null, null, "CODE", null, null, "officer-1"));
        Instant t1 = Instant.parse("2026-06-27T10:01:00Z");
        Instant t2 = Instant.parse("2026-06-27T10:02:00Z");
        attendances.save(Attendance.create(s.getId(), "m2", t2));
        attendances.save(Attendance.create(s.getId(), "m1", t1));

        assertThat(attendances.findBySeminarIdAndMemberId(s.getId(), "m1")).isPresent();
        assertThat(attendances.findBySeminarIdAndMemberId(s.getId(), "absent")).isEmpty();
        assertThat(attendances.findBySeminarIdOrderByAtAsc(s.getId()))
                .extracting(Attendance::getMemberId).containsExactly("m1", "m2");
    }
}
