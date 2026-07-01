package com.jaram.be.admin.dto;

import java.util.List;

// 계약 DashboardStats. 표시용 집계 — 일부 지표(출석률·deltas·trend)는 FE 대시보드
// 스펙 미확정으로 best-effort 프록시. 필수: totalMembers/alumniCount/두 출석률.
public record DashboardStats(
        int totalMembers,
        int alumniCount,
        int seminarAttendanceRate,
        int studyAttendanceRate,
        Deltas deltas,
        GradeBreakdown gradeBreakdown,
        List<CohortCount> cohortBreakdown,
        List<TrendPoint> attendanceTrend,
        int pendingApplications,
        PendingBreakdown pendingBreakdown) {

    public record Deltas(int members, int seminarRate, int studyRate) { }
    public record GradeBreakdown(int probationary, int associate, int regular) { }
    public record CohortCount(int cohort, int count) { }
    public record TrendPoint(String month, int seminar, int study) { }
    public record PendingBreakdown(int freshman, int enrolled) { }
}
