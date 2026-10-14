package com.jaram.be.study;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class StudyAttendanceRepositoryTest extends PostgresTest {

    @Autowired StudyAttendanceRepository attendance;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyRepository studies;

    private Study study;

    @BeforeEach void clean() {
        attendance.deleteAllInBatch();
        weeks.deleteAllInBatch();
        studies.deleteAllInBatch();
        study = studies.save(Study.create("알고리즘", List.of("PS"), 6,
                null, null, null, null, null, "leader-1"));
    }

    @Test
    void oneRowPerMemberPerWeek() {
        StudyWeek w = weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        attendance.save(StudyAttendance.create(w.getId(), "member-1", Instant.now()));

        assertThatThrownBy(() -> {
            attendance.save(StudyAttendance.create(w.getId(), "member-1", Instant.now()));
            attendance.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void readsByWeekAndClearsAWholeWeek() {
        StudyWeek w1 = weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        StudyWeek w2 = weeks.save(StudyWeek.create(study.getId(), 2, "그리디", null));
        attendance.save(StudyAttendance.create(w1.getId(), "m1", Instant.now()));
        attendance.save(StudyAttendance.create(w1.getId(), "m2", Instant.now()));
        attendance.save(StudyAttendance.create(w2.getId(), "m1", Instant.now()));

        assertThat(attendance.findByWeekId(w1.getId())).hasSize(2);
        assertThat(attendance.findByWeekIdIn(List.of(w1.getId(), w2.getId()))).hasSize(3);
        assertThat(attendance.existsByWeekId(w2.getId())).isTrue();

        attendance.deleteByWeekId(w1.getId());
        assertThat(attendance.findByWeekId(w1.getId())).isEmpty();
        assertThat(attendance.findByWeekId(w2.getId())).hasSize(1);
    }

    /** 첫 저장에만 박힌다. 갱신하면 편집 창이 무한히 연장된다. */
    @Test
    void takenAtIsStampedOnceAndNeverMoves() {
        StudyWeek w = weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        assertThat(w.getTakenAt()).isNull();

        Instant first = Instant.parse("2026-09-15T10:00:00Z");
        w.markTaken(first);
        w.markTaken(Instant.parse("2026-09-20T10:00:00Z"));
        weeks.save(w);

        assertThat(weeks.findById(w.getId()).orElseThrow().getTakenAt()).isEqualTo(first);
    }

    @Test
    void findsTheLastWeekAndCounts() {
        weeks.save(StudyWeek.create(study.getId(), 1, "1주", null));
        weeks.save(StudyWeek.create(study.getId(), 2, "2주", null));
        weeks.save(StudyWeek.create(study.getId(), 3, "3주", null));

        assertThat(weeks.findFirstByStudyIdOrderByWeekNoDesc(study.getId())
                .orElseThrow().getWeekNo()).isEqualTo(3);
        assertThat(weeks.countByStudyId(study.getId())).isEqualTo(3);
        assertThat(weeks.findByStudyIdAndWeekNo(study.getId(), 2)
                .orElseThrow().getTitle()).isEqualTo("2주");
        assertThat(weeks.findByStudyIdAndWeekNo(study.getId(), 9)).isEmpty();
    }
}
