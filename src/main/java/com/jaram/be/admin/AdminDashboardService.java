package com.jaram.be.admin;

import com.jaram.be.admin.dto.DashboardStats;
import com.jaram.be.admin.dto.DashboardStats.*;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.seminar.AttendanceRepository;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.study.ApplicationStatus;
import com.jaram.be.study.StudyApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 대시보드 집계. 필수 지표는 실데이터에서 산출하고, FE 대시보드 스펙이
 * 미확정인 파생 지표(출석률·deltas·attendanceTrend)는 결정적 프록시/0으로 채운다.
 * (§5 열린 질문: 정확한 정의는 FE 확정 후 조정.)
 */
@Service
public class AdminDashboardService {

    private final MemberRepository members;
    private final SeminarRepository seminars;
    private final AttendanceRepository attendances;
    private final StudyApplicationRepository applications;
    private final AdminSettingsService settings;

    public AdminDashboardService(MemberRepository members, SeminarRepository seminars,
                                 AttendanceRepository attendances,
                                 StudyApplicationRepository applications,
                                 AdminSettingsService settings) {
        this.members = members;
        this.seminars = seminars;
        this.attendances = attendances;
        this.applications = applications;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public DashboardStats stats() {
        List<Member> approved = members.findByApproval(MemberApproval.APPROVED);
        List<Member> pending = members.findByApproval(MemberApproval.PENDING);

        int totalMembers = approved.size();
        int alumniCount = (int) approved.stream().filter(m -> m.getGrade() == MemberGrade.OB).count();

        int seminarCount = (int) seminars.count();
        int attendanceCount = (int) attendances.count();
        int seminarRate = rate(attendanceCount, (long) seminarCount * totalMembers);
        // 스터디는 출석 개념이 없어 현재 프록시 0 (FE 스펙 확정 후 조정).
        int studyRate = 0;

        int pendingApplications =
                applications.findByStatusOrderByCreatedAtDesc(ApplicationStatus.PENDING).size();

        return new DashboardStats(
                totalMembers,
                alumniCount,
                seminarRate,
                studyRate,
                new Deltas(0, 0, 0),
                gradeBreakdown(approved),
                genBreakdown(approved),
                List.of(),                       // attendanceTrend: FE 스펙 확정 전까지 빈 배열
                pendingApplications,
                pendingBreakdown(pending));
    }

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
    private int rate(long numerator, long denominator) {
        if (denominator <= 0) return 0;
        return (int) Math.min(100, Math.round(numerator * 100.0 / denominator));
    }
}
