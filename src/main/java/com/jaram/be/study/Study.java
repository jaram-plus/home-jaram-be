package com.jaram.be.study;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 개설 신청→승인 2단 흐름의 스터디. approvalStatus는 개설 승인축(PENDING 기본).
 * cur(현재 인원)·status(모집 상태)·apply(사용자별 상태)는 저장하지 않고 서비스에서 파생.
 * 개설 신청자가 곧 leader.
 */
@Entity
@Table(name = "study")
public class Study {

    @Id
    private String id;

    private String title;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "study_field", joinColumns = @JoinColumn(name = "study_id"))
    @Column(name = "field")
    private List<String> fields = new ArrayList<>();

    private String leaderId;      // 개설자 = leader
    private Integer capacity;

    private String schedule;      // nullable
    private String period;        // nullable
    private String mode;          // nullable
    @Column(length = 2000)
    private String intro;         // nullable

    @Enumerated(EnumType.STRING)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    @Column(length = 1000)
    private String reason;        // 반려 사유 (nullable)

    private Instant createdAt = Instant.now();

    protected Study() { }

    public static Study create(String title, List<String> fields, Integer capacity,
                               String schedule, String period, String mode, String intro,
                               String leaderId) {
        Study s = new Study();
        s.id = UUID.randomUUID().toString();
        s.title = title;
        s.fields = new ArrayList<>(fields);
        s.capacity = capacity;
        s.schedule = schedule;
        s.period = period;
        s.mode = mode;
        s.intro = intro;
        s.leaderId = leaderId;
        s.approvalStatus = ApprovalStatus.PENDING;
        s.createdAt = Instant.now();
        return s;
    }

    public void approve() { this.approvalStatus = ApprovalStatus.APPROVED; }

    public void reject(String reason) {
        this.approvalStatus = ApprovalStatus.REJECTED;
        this.reason = reason;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public List<String> getFields() { return fields; }
    public String getLeaderId() { return leaderId; }
    public Integer getCapacity() { return capacity; }
    public String getSchedule() { return schedule; }
    public String getPeriod() { return period; }
    public String getMode() { return mode; }
    public String getIntro() { return intro; }
    public ApprovalStatus getApprovalStatus() { return approvalStatus; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
