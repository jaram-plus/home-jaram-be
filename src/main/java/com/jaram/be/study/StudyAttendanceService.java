package com.jaram.be.study;

import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.study.dto.AttendanceBoard;
import com.jaram.be.study.dto.AttendanceMember;
import com.jaram.be.study.dto.AttendanceWeek;
import com.jaram.be.study.dto.MyAttendance;
import com.jaram.be.study.dto.MyAttendanceWeek;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 출석 읽기와 쓰기. 편집 창 판정은 AttendanceWindow 한 곳에만 있다.
 *
 * StudyService 에 넣지 않은 이유: 그 클래스는 이미 신청·개설·승인 흐름으로 길고,
 * 출석은 그 흐름과 공유하는 상태가 study 조회뿐이다.
 */
@Service
public class StudyAttendanceService {

    private final StudyRepository studies;
    private final StudyWeekRepository weeks;
    private final StudyAttendanceRepository attendance;
    private final StudyApplicationRepository applications;
    private final MemberRepository members;
    private final AttendanceWindow window;

    public StudyAttendanceService(StudyRepository studies, StudyWeekRepository weeks,
                                  StudyAttendanceRepository attendance,
                                  StudyApplicationRepository applications,
                                  MemberRepository members,
                                  AttendanceWindow window) {
        this.studies = studies;
        this.weeks = weeks;
        this.attendance = attendance;
        this.applications = applications;
        this.members = members;
        this.window = window;
    }

    @Transactional(readOnly = true)
    public AttendanceBoard board(String studyId, boolean officer) {
        Study study = loadStudy(studyId);
        List<StudyWeek> all = weeks.findByStudyIdOrderByWeekNoAsc(studyId);
        Instant now = Instant.now();

        List<AttendanceWeek> weekRows = all.stream()
                .map(w -> new AttendanceWeek(w.getWeekNo(), w.getTitle(), w.getTakenAt(),
                        window.isOpen(w, officer, now)))
                .toList();

        Map<String, Integer> weekNoById = all.stream()
                .collect(Collectors.toMap(StudyWeek::getId, StudyWeek::getWeekNo));
        Map<String, List<Integer>> presentByMember = attendance
                .findByWeekIdIn(weekNoById.keySet()).stream()
                .collect(Collectors.groupingBy(StudyAttendance::getMemberId,
                        Collectors.mapping(a -> weekNoById.get(a.getWeekId()),
                                Collectors.toList())));

        List<AttendanceMember> memberRows = members.findAllById(memberIdsOf(study)).stream()
                .sorted(memberOrder(study.getLeaderId()))
                .map(m -> new AttendanceMember(m.getId(), m.getName(), m.getGen(),
                        m.getId().equals(study.getLeaderId()),
                        presentByMember.getOrDefault(m.getId(), List.of()).stream().sorted().toList()))
                .toList();

        return new AttendanceBoard(weekRows, memberRows);
    }

    @Transactional(readOnly = true)
    public MyAttendance mine(String studyId, String userId) {
        loadStudy(studyId);
        List<StudyWeek> all = weeks.findByStudyIdOrderByWeekNoAsc(studyId);
        Set<String> myWeekIds = attendance
                .findByWeekIdIn(all.stream().map(StudyWeek::getId).toList()).stream()
                .filter(a -> a.getMemberId().equals(userId))
                .map(StudyAttendance::getWeekId)
                .collect(Collectors.toSet());

        List<MyAttendanceWeek> rows = all.stream().map(w -> new MyAttendanceWeek(
                w.getWeekNo(), w.getTitle(),
                w.getTakenAt() == null ? AttendanceState.NOT_TAKEN
                        : myWeekIds.contains(w.getId()) ? AttendanceState.PRESENT
                        : AttendanceState.ABSENT)).toList();

        int taken = (int) all.stream().filter(w -> w.getTakenAt() != null).count();
        int attended = (int) rows.stream().filter(r -> r.state() == AttendanceState.PRESENT).count();
        return new MyAttendance(attended, taken, rows);
    }

    /** 스터디장이 맨 앞, 나머지는 기수 → 이름. StudyService.roster 와 같은 규칙이다. */
    private static Comparator<Member> memberOrder(String leaderId) {
        return Comparator
                .comparing((Member m) -> m.getId().equals(leaderId) ? 0 : 1)
                .thenComparing(Member::getGen, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Member::getName, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    @Transactional
    public void save(String studyId, int weekNo, List<String> present, boolean officer) {
        Study study = loadStudy(studyId);
        requireNotFinished(study);
        StudyWeek week = loadWeek(studyId, weekNo);
        window.requireOpen(week, officer);

        List<String> eligible = memberIdsOf(study);
        List<String> unknown = present.stream().filter(id -> !eligible.contains(id)).toList();
        if (!unknown.isEmpty()) {
            // 조용히 무시하면 화면이 저장에 성공했다고 믿고 잘못된 명단을 계속 보여준다.
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION",
                    "이 스터디의 참여자가 아닌 사람이 있습니다.",
                    Map.of("present", "알 수 없는 멤버 %d명".formatted(unknown.size())));
        }

        attendance.deleteByWeekId(week.getId());
        // 플러시를 강제한다. Hibernate 는 한 플러시 안에서 insert 를 delete 보다 먼저
        // 내보내므로, 이전 명단에 있던 사람이 새 명단에도 있으면 (week_id, member_id)
        // 유니크 제약에 걸려 교체 저장이 통째로 롤백된다.
        attendance.flush();

        Instant now = Instant.now();
        present.stream().distinct()
                .forEach(id -> attendance.save(StudyAttendance.create(week.getId(), id, now)));

        week.markTaken(now);
        weeks.save(week);
    }

    /**
     * 출석 대상 — 승인된 신청자 + 스터디장. 스터디장을 빼면 "늘 출석"이라는 암묵 규칙이 생긴다.
     * 대시보드 출석률의 분모도 이걸 쓴다 — 규칙이 두 곳에 있으면 화면마다 값이 달라진다.
     */
    public List<String> memberIdsOf(Study study) {
        List<String> ids = new ArrayList<>();
        ids.add(study.getLeaderId());
        applications.findByStudyIdAndStatus(study.getId(), ApplicationStatus.APPROVED)
                .forEach(a -> ids.add(a.getApplicantId()));
        return ids;
    }

    Study loadStudy(String id) {
        return studies.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "스터디를 찾을 수 없습니다."));
    }

    private StudyWeek loadWeek(String studyId, int weekNo) {
        return weeks.findByStudyIdAndWeekNo(studyId, weekNo).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "그런 주차가 없습니다."));
    }

    /** 끝난 스터디의 기록이 나중에 바뀌면 그 기록을 근거로 한 것이 전부 흔들린다. */
    static void requireNotFinished(Study study) {
        if (study.getStatus() == StudyStatus.FINISHED) {
            throw new ApiException(HttpStatus.CONFLICT, "STUDY_FINISHED",
                    "종료된 스터디는 고칠 수 없습니다.");
        }
    }
}
