package com.jaram.be.seminar;

import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.seminar.dto.AttendResult;
import com.jaram.be.seminar.dto.AttendeePreviewEntry;
import com.jaram.be.seminar.dto.AttendeePreviewResponse;
import com.jaram.be.seminar.dto.RosterEntry;
import com.jaram.be.seminar.dto.RosterResponse;
import com.jaram.be.seminar.dto.SeminarCreateRequest;
import com.jaram.be.seminar.dto.SeminarResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final AttendanceRepository attendances;
    private final MemberRepository members;
    private final long windowMinutes;

    public SeminarService(SeminarRepository seminars,
                          AttendanceRepository attendances,
                          MemberRepository members,
                          @Value("${seminar.attendance-window-minutes:120}") long windowMinutes) {
        this.seminars = seminars;
        this.attendances = attendances;
        this.members = members;
        this.windowMinutes = windowMinutes;
    }

    @Transactional(readOnly = true)
    public List<SeminarResponse> list(String callerId) {
        return seminars.findAllByOrderByStartsAtDesc().stream()
                .map(s -> toResponse(s, callerId)).toList();
    }

    @Transactional
    public SeminarResponse create(SeminarCreateRequest req, String createdById) {
        Seminar s = Seminar.create(
                req.title(), req.speaker(), req.topic(), req.startsAt(),
                req.place(), req.mode(), req.attendanceCode(),
                req.materialUrl(), req.capacity(), createdById);
        s.setDescription(req.description());
        Seminar saved = seminars.save(s);
        return toResponse(saved, createdById);
    }

    SeminarResponse toResponse(Seminar s, String callerId) {
        ZonedDateTime t = s.getStartsAt().atZone(SEOUL);
        Instant closesAt = s.getStartsAt().plus(Duration.ofMinutes(windowMinutes));
        String attendedAt = callerId == null ? null :
                attendances.findBySeminarIdAndMemberId(s.getId(), callerId)
                        .map(a -> formatTime(a.getAt())).orElse(null);
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
                s.getCapacity(),
                s.getDescription(),
                closesAt.toString(),
                attendedAt);
    }

    @Transactional
    public AttendResult attend(String seminarId, String memberId, String code) {
        Seminar s = seminars.findById(seminarId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다."));

        Attendance existing = attendances.findBySeminarIdAndMemberId(seminarId, memberId).orElse(null);
        if (existing != null) {
            return new AttendResult(seminarId, formatTime(existing.getAt()));  // idempotent
        }

        boolean ongoing = SeminarStatus.of(s.getStartsAt(), Instant.now(), windowMinutes) == SeminarStatus.ONGOING;
        // code is @NotBlank (never null); compare from it so a code-less seminar yields
        // INVALID_CODE rather than an NPE/500.
        if (!ongoing || !code.equals(s.getAttendanceCode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CODE", "출석 코드가 올바르지 않습니다.");
        }

        Attendance saved = attendances.save(Attendance.create(seminarId, memberId, Instant.now()));
        return new AttendResult(seminarId, formatTime(saved.getAt()));
    }

    @Transactional(readOnly = true)
    public RosterResponse roster(String seminarId) {
        Seminar s = seminars.findById(seminarId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다."));

        List<Attendance> rows = attendances.findBySeminarIdOrderByAtAsc(seminarId);
        Map<String, Member> byId = members.findAllById(
                        rows.stream().map(Attendance::getMemberId).toList()).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));

        List<RosterEntry> list = rows.stream().map(a -> {
            Member m = byId.get(a.getMemberId());
            return new RosterEntry(
                    m == null ? null : m.getName(),
                    m == null ? null : m.getStudentId(),
                    formatTime(a.getAt()));
        }).toList();

        int cap = s.getCapacity() == null ? 0 : s.getCapacity();
        return new RosterResponse(s.getTitle(), cap, list);
    }

    @Transactional(readOnly = true)
    public AttendeePreviewResponse attendeePreview(String seminarId) {
        seminars.findById(seminarId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다."));

        List<Attendance> rows = attendances.findBySeminarIdOrderByAtAsc(seminarId);
        Map<String, Member> byId = members.findAllById(
                        rows.stream().map(Attendance::getMemberId).toList()).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));

        List<AttendeePreviewEntry> list = rows.stream().map(a -> {
            Member m = byId.get(a.getMemberId());
            return new AttendeePreviewEntry(m == null ? null : m.getName(), formatTime(a.getAt()));
        }).toList();

        return new AttendeePreviewResponse(list.size(), list);
    }

    private String formatTime(Instant at) {
        return at.atZone(SEOUL).format(HHMM);
    }
}
