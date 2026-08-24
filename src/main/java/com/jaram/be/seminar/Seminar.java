package com.jaram.be.seminar;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seminar")
public class Seminar {

    @Id
    private String id;

    private String title;
    private String speaker;
    private String topic;
    private Instant startsAt;
    private String place;
    private String mode;
    private String attendanceCode;   // 공개 응답엔 없다. 임원 관리 화면에만 내려간다.
    private Instant attendanceClosedAt;  // 임원이 앞당겨 마감한 시각. null이면 출석창(startsAt+window)을 그대로 쓴다.
    private String materialUrl;
    private Integer capacity;
    private String description;      // nullable, free-text detail (set via setter, not the factory)

    private String scheduleId;       // 슬롯 경로로 생성 시 채움; 임원 직접생성은 null

    @Enumerated(EnumType.STRING)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    @Column(length = 1000)
    private String rejectReason;     // approvalStatus==REJECTED일 때만

    private String createdById;
    private Instant createdAt = Instant.now();

    @Version
    private Long version;   // 관리자 일괄 편집 낙관적 잠금

    protected Seminar() { }

    public static Seminar create(String title, String speaker, String topic, Instant startsAt,
                                 String place, String mode, String attendanceCode,
                                 String materialUrl, Integer capacity, String createdById) {
        Seminar s = new Seminar();
        s.id = UUID.randomUUID().toString();
        s.title = title;
        s.speaker = speaker;
        s.topic = topic;
        s.startsAt = startsAt;
        s.place = place;
        s.mode = mode;
        s.attendanceCode = attendanceCode;
        s.materialUrl = materialUrl;
        s.capacity = capacity;
        s.createdById = createdById;
        s.createdAt = Instant.now();
        return s;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getSpeaker() { return speaker; }
    public void setSpeaker(String v) { this.speaker = v; }
    public String getTopic() { return topic; }
    public void setTopic(String v) { this.topic = v; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant v) { this.startsAt = v; }
    public String getPlace() { return place; }
    public void setPlace(String v) { this.place = v; }
    public String getMode() { return mode; }
    public void setMode(String v) { this.mode = v; }
    public String getAttendanceCode() { return attendanceCode; }
    public void setAttendanceCode(String v) { this.attendanceCode = v; }
    public Instant getAttendanceClosedAt() { return attendanceClosedAt; }

    /** 출석을 지금 닫는다. 이미 닫혀 있으면 처음 닫은 시각을 유지한다(다시 눌러도 같은 상태). */
    public void closeAttendance(Instant at) {
        if (this.attendanceClosedAt == null) this.attendanceClosedAt = at;
    }

    public String getMaterialUrl() { return materialUrl; }
    public void setMaterialUrl(String v) { this.materialUrl = v; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer v) { this.capacity = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getCreatedById() { return createdById; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getVersion() { return version; }

    public void approve() {
        this.approvalStatus = ApprovalStatus.APPROVED;
        this.rejectReason = null;
    }

    public void reject(String reason) {
        this.approvalStatus = ApprovalStatus.REJECTED;
        this.rejectReason = reason;
    }

    public void resubmit() {
        this.approvalStatus = ApprovalStatus.PENDING;
        this.rejectReason = null;
    }

    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String v) { this.scheduleId = v; }
    public ApprovalStatus getApprovalStatus() { return approvalStatus; }
    public String getRejectReason() { return rejectReason; }
}
