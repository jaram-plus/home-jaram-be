package com.jaram.be.schedule;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.schedule.dto.ScheduleResponse;
import com.jaram.be.schedule.dto.ScheduleSlotResponse;
import com.jaram.be.schedule.dto.SlotMember;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
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

    public ScheduleService(ScheduleRepository schedules, MemberRepository members,
                           SeminarRepository seminars) {
        this.schedules = schedules;
        this.members = members;
        this.seminars = seminars;
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> list() {
        return schedules.findAllByOrderByStartsAtAsc().stream().map(this::toResponse).toList();
    }

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
