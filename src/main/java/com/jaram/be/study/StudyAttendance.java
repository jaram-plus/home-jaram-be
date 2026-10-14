package com.jaram.be.study;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 출석 한 행. 존재가 곧 출석이다 — seminar/Attendance 와 같은 모양이다.
 *
 * boolean 으로 저장하지 않는 이유: "아직 안 찍음"을 표현할 세 번째 값이 필요해지고,
 * 행이 없는 것과 false 인 것의 차이를 누구도 기억하지 못한다. 안 찍은 주차는
 * StudyWeek.takenAt 이 null 인 것으로 표현한다.
 *
 * weekNo 가 아니라 weekId 에 맨다. 번호는 화면이 보여주는 표시값이지 신원이 아니다.
 */
@Entity
@Table(name = "study_attendance",
        uniqueConstraints = @UniqueConstraint(columnNames = {"week_id", "member_id"}))
public class StudyAttendance {

    @Id
    private String id;

    @Column(name = "week_id")
    private String weekId;

    @Column(name = "member_id")
    private String memberId;

    private Instant at;

    protected StudyAttendance() { }

    public static StudyAttendance create(String weekId, String memberId, Instant at) {
        StudyAttendance a = new StudyAttendance();
        a.id = UUID.randomUUID().toString();
        a.weekId = weekId;
        a.memberId = memberId;
        a.at = at;
        return a;
    }

    public String getId() { return id; }
    public String getWeekId() { return weekId; }
    public String getMemberId() { return memberId; }
    public Instant getAt() { return at; }
}
