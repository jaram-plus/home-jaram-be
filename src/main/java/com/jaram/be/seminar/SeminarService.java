package com.jaram.be.seminar;

import com.jaram.be.seminar.dto.SeminarResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * UC-S1..S4. Seminar.status and the day/month/weekday/time display fields are
 * derived here (never stored); attendanceCode is never exposed. All display
 * formatting is done in the Asia/Seoul zone.
 */
@Service
public class SeminarService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] WEEKDAYS = {"월", "화", "수", "목", "금", "토", "일"};

    private final SeminarRepository seminars;
    private final long windowMinutes;

    public SeminarService(SeminarRepository seminars,
                          @Value("${seminar.attendance-window-minutes:120}") long windowMinutes) {
        this.seminars = seminars;
        this.windowMinutes = windowMinutes;
    }

    @Transactional(readOnly = true)
    public List<SeminarResponse> list() {
        return seminars.findAllByOrderByStartsAtDesc().stream().map(this::toResponse).toList();
    }

    SeminarResponse toResponse(Seminar s) {
        ZonedDateTime t = s.getStartsAt().atZone(SEOUL);
        return new SeminarResponse(
                s.getId(),
                s.getTitle(),
                s.getSpeaker(),
                s.getTopic(),
                s.getStartsAt().toString(),
                String.valueOf(t.getDayOfMonth()),
                t.getMonthValue() + "월",
                WEEKDAYS[t.getDayOfWeek().getValue() - 1],
                t.format(HHMM),
                s.getPlace(),
                s.getMode(),
                SeminarStatus.of(s.getStartsAt(), Instant.now(), windowMinutes),
                s.getMaterialUrl(),
                s.getCapacity());
    }
}
