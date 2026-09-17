package com.jaram.be.admin.dto;

import java.util.List;

// 계약 DashboardStats. 표시용 집계.
// 출석률은 attendanceTrend 가 덮는 창(최근 6개월) 전체를 묶은 값이고, deltas 의
// 두 비율은 이번 달과 지난 달의 차(%p), deltas.members 는 이번 학기 가입자 수다.
public record DashboardStats(
        int totalMembers,
        int alumniCount,
        int seminarAttendanceRate,
        int studyAttendanceRate,
        Deltas deltas,
        GradeBreakdown gradeBreakdown,
        List<GenCount> genBreakdown,
        List<TrendPoint> attendanceTrend,
        int pendingApplications,
        PendingBreakdown pendingBreakdown) {

    public record Deltas(int members, int seminarRate, int studyRate) { }
    public record GradeBreakdown(int probationary, int associate, int regular) { }
    public record GenCount(int gen, int count) { }
    public record TrendPoint(String month, int seminar, int study) { }
    public record PendingBreakdown(int freshman, int enrolled) { }
}
