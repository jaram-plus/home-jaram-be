package com.jaram.be.schedule;

import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.schedule.dto.ScheduleCreateRequest;
import com.jaram.be.schedule.dto.ScheduleResponse;
import com.jaram.be.schedule.dto.ScheduleSlotResponse;
import com.jaram.be.schedule.dto.SlotMember;
import com.jaram.be.seminar.ApprovalStatus;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.seminar.SeminarService;
import com.jaram.be.seminar.dto.SeminarCreateRequest;
import com.jaram.be.seminar.dto.SeminarResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 일정/슬롯 상태전이와 슬롯 응답 파생. 슬롯의 member 이름·세미나 승인상태는 저장하지
 * 않고 Member/Seminar 배치 조회로 얹는다. 시각 표시는 Asia/Seoul 파생.
 */
@Service
public class ScheduleService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] WEEKDAYS = {"월", "화", "수", "목", "금", "토", "일"};

    private final ScheduleRepository schedules;
    private final MemberRepository members;
    private final SeminarRepository seminars;
    private final SeminarService seminarService;

    public ScheduleService(ScheduleRepository schedules, MemberRepository members,
                           SeminarRepository seminars, SeminarService seminarService) {
        this.schedules = schedules;
        this.members = members;
        this.seminars = seminars;
        this.seminarService = seminarService;
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> list() {
        return schedules.findAllByOrderByStartsAtAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public ScheduleResponse claim(String scheduleId, int index, String memberId) {
        Schedule sch = load(scheduleId);
        if (sch.getStatus() != ScheduleStatus.OPEN) {
            throw conflict("잠긴 일정입니다.");
        }
        ScheduleSlot slot = slot(sch, index);
        if (slot.getMemberId() != null) {
            throw conflict("이미 점유된 슬롯입니다.");
        }
        boolean alreadyMine = sch.getSlots().stream().anyMatch(x -> memberId.equals(x.getMemberId()));
        if (alreadyMine) {
            throw conflict("이미 이 일정의 슬롯을 잡았습니다.");
        }
        slot.claim(memberId);
        schedules.save(sch);
        return toResponse(sch);
    }

    @Transactional
    public ScheduleResponse cancel(String scheduleId, int index, String memberId) {
        Schedule sch = load(scheduleId);
        ScheduleSlot slot = slot(sch, index);
        if (sch.getStatus() != ScheduleStatus.OPEN) {
            throw forbidden("잠긴 일정은 취소할 수 없습니다.");
        }
        if (!memberId.equals(slot.getMemberId())) {
            throw forbidden("본인 슬롯만 취소할 수 있습니다.");
        }
        slot.release();
        schedules.save(sch);
        return toResponse(sch);
    }

    @Transactional
    public SeminarResponse submitSeminar(String scheduleId, int index, String memberId,
                                         SeminarCreateRequest req) {
        Schedule sch = load(scheduleId);
        ScheduleSlot slot = slot(sch, index);
        if (sch.getStatus() != ScheduleStatus.LOCKED) {
            throw conflict("잠긴 일정에서만 세미나를 제출할 수 있습니다.");
        }
        if (!memberId.equals(slot.getMemberId())) {
            throw forbidden("본인 슬롯만 제출할 수 있습니다.");
        }
        if (slot.getSeminarId() != null) {
            throw conflict("이미 제출한 슬롯입니다.");
        }
        SeminarResponse resp = seminarService.submitFromSlot(
                req, memberId, sch.getId(), sch.getStartsAt(), sch.getPlace(), sch.getMode());
        slot.attachSeminar(resp.id());
        schedules.save(sch);
        return resp;
    }

    @Transactional
    public ScheduleResponse create(ScheduleCreateRequest req) {
        int capacity = req.capacity() == null ? 3 : req.capacity();
        Schedule sch = Schedule.create(req.startsAt(), req.place(), req.mode(), capacity);
        return toResponse(schedules.save(sch));
    }

    @Transactional
    public ScheduleResponse lock(String scheduleId) {
        Schedule sch = load(scheduleId);
        sch.lock();
        return toResponse(schedules.save(sch));
    }

    @Transactional
    public ScheduleResponse forceRelease(String scheduleId, int index) {
        Schedule sch = load(scheduleId);
        ScheduleSlot slot = slot(sch, index);
        if (slot.getSeminarId() != null) {
            Seminar sem = seminars.findById(slot.getSeminarId()).orElse(null);
            if (sem != null && sem.getApprovalStatus() != ApprovalStatus.REJECTED) {
                throw conflict("먼저 세미나를 반려한 뒤 해제할 수 있습니다.");
            }
        }
        slot.release();
        schedules.save(sch);
        return toResponse(sch);
    }

    private Schedule load(String id) {
        return schedules.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "일정을 찾을 수 없습니다."));
    }

    private ScheduleSlot slot(Schedule sch, int index) {
        return sch.getSlots().stream().filter(x -> x.getIndex() == index).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "슬롯을 찾을 수 없습니다."));
    }

    private ApiException conflict(String msg) { return new ApiException(HttpStatus.CONFLICT, "CONFLICT", msg); }
    private ApiException forbidden(String msg) { return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", msg); }

    ScheduleResponse toResponse(Schedule s) {
        List<ScheduleSlot> slots = s.getSlots();
        Map<String, String> names = members.findAllById(
                        slots.stream().map(ScheduleSlot::getMemberId).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Member::getId, Member::getName));
        Map<String, Seminar> semById = seminars.findAllById(
                        slots.stream().map(ScheduleSlot::getSeminarId).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Seminar::getId, Function.identity()));

        List<ScheduleSlotResponse> slotDtos = slots.stream().map(slot -> {
            SlotMember member = slot.getMemberId() == null ? null
                    : new SlotMember(slot.getMemberId(), names.getOrDefault(slot.getMemberId(), null));
            Seminar sem = slot.getSeminarId() == null ? null : semById.get(slot.getSeminarId());
            return new ScheduleSlotResponse(
                    slot.getIndex(), member, slot.getSeminarId(),
                    sem == null ? null : sem.getApprovalStatus(),
                    sem == null ? null : sem.getRejectReason());
        }).toList();

        ZonedDateTime t = s.getStartsAt().atZone(SEOUL);
        return new ScheduleResponse(
                s.getId(), s.getStartsAt().toString(),
                String.valueOf(t.getDayOfMonth()), t.getMonthValue() + "월",
                WEEKDAYS[t.getDayOfWeek().getValue() - 1], t.format(HHMM),
                s.getPlace(), s.getMode(), s.getCapacity(), s.getStatus(), slotDtos);
    }
}
