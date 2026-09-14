package com.jaram.be.admin;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.schedule.Schedule;
import com.jaram.be.schedule.ScheduleRepository;
import com.jaram.be.schedule.ScheduleSlot;
import com.jaram.be.seminar.AttendanceRepository;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.study.StudyApplicationRepository;
import com.jaram.be.study.StudyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 회원 정리의 단일 규칙. 스윕(자동)과 임원의 삭제 버튼(수동)이 같은 것을 쓴다 —
 * 어느 쪽이 돌았느냐에 따라 기여자 목록이 달라지면 안 된다.
 *
 * 이 프로젝트는 회원을 가리키는 참조 대부분이 FK 없는 varchar 라 DB 가 막아 주지
 * 않는다. 행을 지울 때 끊어진 참조가 남지 않도록 여기서 손으로 정리한다.
 */
@Component
public class MemberPurger {

    public enum Outcome { PURGED, DELETED, SKIPPED_LEADER }

    private final MemberRepository members;
    private final StudyRepository studies;
    private final StudyApplicationRepository applications;
    private final AttendanceRepository attendances;
    private final ScheduleRepository schedules;
    private final SeminarRepository seminars;

    public MemberPurger(MemberRepository members, StudyRepository studies,
                        StudyApplicationRepository applications, AttendanceRepository attendances,
                        ScheduleRepository schedules, SeminarRepository seminars) {
        this.members = members;
        this.studies = studies;
        this.applications = applications;
        this.attendances = attendances;
        this.schedules = schedules;
        this.seminars = seminars;
    }

    @Transactional
    public Outcome purge(Member m, Instant at) {
        if (!studies.findByLeaderIdOrderByCreatedAtDesc(m.getId()).isEmpty()) {
            return Outcome.SKIPPED_LEADER;
        }
        if (m.hasHistory()) {
            m.purge(at);
            members.save(m);
            return Outcome.PURGED;
        }
        detachReferences(m.getId());
        applications.deleteAll(applications.findByApplicantIdOrderByCreatedAtDesc(m.getId()));
        attendances.deleteAll(attendances.findByMemberId(m.getId()));
        members.deleteById(m.getId());
        return Outcome.DELETED;
    }

    /** 행을 지울 때만 필요하다. 파기(행 보존)는 참조가 끊기지 않는다. */
    private void detachReferences(String memberId) {
        for (Schedule s : schedules.findBySlotsMemberId(memberId)) {
            s.getSlots().stream()
                    .filter(slot -> memberId.equals(slot.getMemberId()))
                    .forEach(ScheduleSlot::release);
            schedules.save(s);
        }
        for (Seminar s : seminars.findByCreatedById(memberId)) {
            s.detachCreator();
            seminars.save(s);
        }
    }
}
