package com.jaram.be.study;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 커리큘럼 주차. ② 단계의 출석이 이 행을 그대로 대상으로 삼는다 — 커리큘럼 주차와
 * 출석 주차를 따로 두면 화면의 "3주차"가 같은 것을 가리키는지 보장할 수 없다.
 *
 * weekNo 는 1부터 빈칸 없이 이어진다. 그 불변식은 저장 시점에 검사한다.
 */
@Entity
@Table(name = "study_week",
        uniqueConstraints = @UniqueConstraint(columnNames = {"study_id", "week_no"}))
public class StudyWeek {

    @Id
    private String id;

    @Column(name = "study_id")
    private String studyId;

    @Column(name = "week_no")
    private int weekNo;

    private String title;

    @Column(length = 2000)
    private String content;   // nullable

    /**
     * 이 주차의 출석을 **처음** 저장한 시각. null 이면 아직 한 번도 찍지 않았다.
     * 편집 창(+24h)의 기점이고, 출석률의 분모를 가르는 값이기도 하다.
     */
    @Column(name = "taken_at")
    private Instant takenAt;

    protected StudyWeek() { }

    public static StudyWeek create(String studyId, int weekNo, String title, String content) {
        StudyWeek w = new StudyWeek();
        w.id = UUID.randomUUID().toString();
        w.studyId = studyId;
        w.weekNo = weekNo;
        w.title = title;
        w.content = content;
        return w;
    }

    public String getId() { return id; }
    public String getStudyId() { return studyId; }
    public int getWeekNo() { return weekNo; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }

    /**
     * 첫 저장에만 박는다. 두 번째 저장에 갱신하면 편집 창이 저장할 때마다
     * 24시간씩 밀려 사실상 무한히 열린다.
     */
    public void markTaken(Instant now) {
        if (takenAt == null) takenAt = now;
    }

    public Instant getTakenAt() { return takenAt; }
}
