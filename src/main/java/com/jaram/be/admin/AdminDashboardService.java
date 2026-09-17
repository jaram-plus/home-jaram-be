package com.jaram.be.admin;

import com.jaram.be.admin.dto.DashboardStats;
import com.jaram.be.admin.dto.DashboardStats.*;
import com.jaram.be.common.ClubTime;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.seminar.ApprovalStatus;
import com.jaram.be.seminar.Attendance;
import com.jaram.be.seminar.AttendanceRepository;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.study.ApplicationStatus;
import com.jaram.be.study.Study;
import com.jaram.be.study.StudyApplicationRepository;
import com.jaram.be.study.StudyAttendance;
import com.jaram.be.study.StudyAttendanceRepository;
import com.jaram.be.study.StudyAttendanceService;
import com.jaram.be.study.StudyRepository;
import com.jaram.be.study.StudyWeek;
import com.jaram.be.study.StudyWeekRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 대시보드 집계.
 *
 * 화면의 세 값(월별 추세 선, 평균 출석률 KPI, 전월 대비 delta)은 모두 같은 월별
 * 집계 하나에서 나온다 — 따로 세면 선과 숫자가 서로 다른 말을 한다.
 */
@Service
public class AdminDashboardService {

    /** 추세에 보여 줄 개월 수. 한 학기(3~8월 / 9~2월)와 같은 길이다. */
    private static final int TREND_MONTHS = 6;

    private final MemberRepository members;
    private final SeminarRepository seminars;
    private final AttendanceRepository attendances;
    private final StudyRepository studies;
    private final StudyWeekRepository weeks;
    private final StudyAttendanceRepository studyAttendances;
    private final StudyAttendanceService studyAttendance;
    private final StudyApplicationRepository applications;
    private final AdminSettingsService settings;

    public AdminDashboardService(MemberRepository members, SeminarRepository seminars,
                                 AttendanceRepository attendances,
                                 StudyRepository studies,
                                 StudyWeekRepository weeks,
                                 StudyAttendanceRepository studyAttendances,
                                 StudyAttendanceService studyAttendance,
                                 StudyApplicationRepository applications,
                                 AdminSettingsService settings) {
        this.members = members;
        this.seminars = seminars;
        this.attendances = attendances;
        this.studies = studies;
        this.weeks = weeks;
        this.studyAttendances = studyAttendances;
        this.studyAttendance = studyAttendance;
        this.applications = applications;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public DashboardStats stats() {
        List<Member> approved = members.findByApproval(MemberApproval.APPROVED);
        List<Member> pending = members.findByApproval(MemberApproval.PENDING);

        List<YearMonth> window = window();
        Map<YearMonth, Tally> seminarTally = seminarTally(window, approved);
        Map<YearMonth, Tally> studyTally = studyTally(window);

        List<TrendPoint> trend = window.stream()
                .map(m -> new TrendPoint(label(m),
                        seminarTally.get(m).rate(), studyTally.get(m).rate()))
                .toList();

        int pendingApplications =
                applications.findByStatusOrderByCreatedAtDesc(ApplicationStatus.PENDING).size();

        return new DashboardStats(
                approved.size(),
                (int) approved.stream().filter(m -> m.getGrade() == MemberGrade.OB).count(),
                pooledRate(seminarTally.values()),
                pooledRate(studyTally.values()),
                deltas(window, seminarTally, studyTally, approved),
                gradeBreakdown(approved),
                genBreakdown(approved),
                trend,
                pendingApplications,
                pendingBreakdown(pending));
    }

    // ---- 월별 집계 -----------------------------------------------------------

    /** 이번 달로 끝나는 최근 TREND_MONTHS 개월, 오래된 순. */
    private static List<YearMonth> window() {
        YearMonth now = YearMonth.from(ClubTime.today());
        List<YearMonth> months = new ArrayList<>(TREND_MONTHS);
        for (int i = TREND_MONTHS - 1; i >= 0; i--) months.add(now.minusMonths(i));
        return months;
    }

    /**
     * 세미나: 그 달에 **열린 승인 세미나**마다 그때의 회원 전원이 분모다.
     *
     * 승인되지 않은 세미나를 빼는 이유는 열린 적이 없기 때문이고, 분모를 오늘의 회원 수가
     * 아니라 그 달까지 가입한 회원 수로 잡는 이유는 학회가 커지면 지난 달이 전부
     * 낮아 보이기 때문이다.
     */
    private Map<YearMonth, Tally> seminarTally(List<YearMonth> window, List<Member> approved) {
        Map<YearMonth, Tally> byMonth = emptyTallies(window);

        Map<String, YearMonth> monthBySeminar = new LinkedHashMap<>();
        for (Seminar s : seminars.findByApprovalStatusOrderByStartsAtDesc(ApprovalStatus.APPROVED)) {
            if (s.getStartsAt() == null) continue;
            YearMonth m = monthOf(s.getStartsAt());
            if (!byMonth.containsKey(m)) continue;
            monthBySeminar.put(s.getId(), m);
            byMonth.get(m).expected += membersAsOf(approved, m);
        }
        for (Attendance a : attendances.findAll()) {
            YearMonth m = monthBySeminar.get(a.getSeminarId());
            if (m != null) byMonth.get(m).present++;
        }
        return byMonth;
    }

    /** 스터디: 출석을 찍은 주차(takenAt)마다 그 스터디의 출석 대상 인원이 분모다. */
    private Map<YearMonth, Tally> studyTally(List<YearMonth> window) {
        Map<YearMonth, Tally> byMonth = emptyTallies(window);

        Map<String, Integer> eligibleByStudy = new LinkedHashMap<>();
        for (Study s : studies.findAll()) {
            eligibleByStudy.put(s.getId(), studyAttendance.memberIdsOf(s).size());
        }

        Map<String, YearMonth> monthByWeek = new LinkedHashMap<>();
        for (StudyWeek w : weeks.findAll()) {
            if (w.getTakenAt() == null) continue;   // 안 찍은 주차는 분모가 아니다
            YearMonth m = monthOf(w.getTakenAt());
            if (!byMonth.containsKey(m)) continue;
            monthByWeek.put(w.getId(), m);
            byMonth.get(m).expected += eligibleByStudy.getOrDefault(w.getStudyId(), 0);
        }
        for (StudyAttendance a : studyAttendances.findAll()) {
            YearMonth m = monthByWeek.get(a.getWeekId());
            if (m != null) byMonth.get(m).present++;
        }
        return byMonth;
    }

    private Deltas deltas(List<YearMonth> window, Map<YearMonth, Tally> seminar,
                          Map<YearMonth, Tally> study, List<Member> approved) {
        YearMonth thisMonth = window.get(window.size() - 1);
        YearMonth lastMonth = window.get(window.size() - 2);
        // 화면의 '이번 학기 +N' — 월이 아니라 학기가 기준이다.
        Instant semesterStart =
                ClubTime.startOfDay(Semester.autoAt(ClubTime.today()).start());
        int joined = (int) approved.stream()
                .filter(m -> m.getCreatedAt() != null && !m.getCreatedAt().isBefore(semesterStart))
                .count();
        return new Deltas(joined,
                seminar.get(thisMonth).rate() - seminar.get(lastMonth).rate(),
                study.get(thisMonth).rate() - study.get(lastMonth).rate());
    }

    /** 그 달 말까지 가입한 승인 회원 수. */
    private static int membersAsOf(List<Member> approved, YearMonth month) {
        Instant end = ClubTime.startOfDay(month.plusMonths(1).atDay(1));
        return (int) approved.stream()
                .filter(m -> m.getCreatedAt() == null || m.getCreatedAt().isBefore(end))
                .count();
    }

    private static Map<YearMonth, Tally> emptyTallies(List<YearMonth> window) {
        Map<YearMonth, Tally> byMonth = new LinkedHashMap<>();
        window.forEach(m -> byMonth.put(m, new Tally()));
        return byMonth;
    }

    private static YearMonth monthOf(Instant at) {
        return YearMonth.from(at.atZone(ClubTime.ZONE));
    }

    /** 축이 좁아 '9월'까지만 쓴다. */
    private static String label(YearMonth m) {
        return m.getMonthValue() + "월";
    }

    /** 창 전체를 하나로 묶은 출석률. 달별 비율의 평균이 아니라 총합 대비 총합이다. */
    private static int pooledRate(Iterable<Tally> tallies) {
        long present = 0, expected = 0;
        for (Tally t : tallies) { present += t.present; expected += t.expected; }
        return rate(present, expected);
    }

    /** 한 달치 출석 — 찍은 수와 찍었어야 할 수. */
    private static final class Tally {
        private long present;
        private long expected;
        int rate() { return AdminDashboardService.rate(present, expected); }
    }

    // ---- 인원 집계 -----------------------------------------------------------

    private GradeBreakdown gradeBreakdown(List<Member> approved) {
        int probationary = count(approved, MemberGrade.NEWCOMER);
        int associate = count(approved, MemberGrade.ASSOCIATE);
        int regular = count(approved, MemberGrade.REGULAR);
        return new GradeBreakdown(probationary, associate, regular);
    }

    private int count(List<Member> ms, MemberGrade g) {
        return (int) ms.stream().filter(m -> m.getGrade() == g).count();
    }

    private List<GenCount> genBreakdown(List<Member> approved) {
        Map<Integer, Integer> byGen = new LinkedHashMap<>();
        for (Member m : approved) {
            if (m.getGen() == null) continue;
            byGen.merge(m.getGen(), 1, Integer::sum);
        }
        return byGen.entrySet().stream()
                .map(e -> new GenCount(e.getKey(), e.getValue()))
                .toList();
    }

    private PendingBreakdown pendingBreakdown(List<Member> pending) {
        int currentGen = settings.currentGen();
        int freshman = (int) pending.stream()
                .filter(m -> m.getGen() != null && m.getGen() == currentGen).count();
        int enrolled = (int) pending.stream()
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE).count();
        return new PendingBreakdown(freshman, enrolled);
    }

    // 백분율(0~100), 분모 0 방어.
    private static int rate(long numerator, long denominator) {
        if (denominator <= 0) return 0;
        return (int) Math.min(100, Math.round(numerator * 100.0 / denominator));
    }
}
