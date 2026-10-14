package com.jaram.be.study;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 회원의 스터디 지원. 지원 PENDING → 임원 approve/reject. 승인된 지원 수가 스터디 cur.
 */
@Entity
@Table(name = "study_application",
       uniqueConstraints = @UniqueConstraint(columnNames = {"studyId", "applicantId"}))
public class StudyApplication {

    @Id
    private String id;

    private String studyId;
    private String applicantId;

    @Column(length = 2000)
    private String motive;

    @Enumerated(EnumType.STRING)
    private ApplicationStatus status = ApplicationStatus.PENDING;

    @Column(length = 1000)
    private String reason;        // 거절 사유 (nullable)

    private Instant createdAt = Instant.now();

    protected StudyApplication() { }

    public static StudyApplication create(String studyId, String applicantId, String motive) {
        StudyApplication a = new StudyApplication();
        a.id = UUID.randomUUID().toString();
        a.studyId = studyId;
        a.applicantId = applicantId;
        a.motive = motive;
        a.status = ApplicationStatus.PENDING;
        a.createdAt = Instant.now();
        return a;
    }

    public void approve() { this.status = ApplicationStatus.APPROVED; }

    public void reject(String reason) {
        this.status = ApplicationStatus.REJECTED;
        this.reason = reason;
    }

    public String getId() { return id; }
    public String getStudyId() { return studyId; }
    public String getApplicantId() { return applicantId; }
    public String getMotive() { return motive; }
    public ApplicationStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
