package com.jaram.be.study;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 개설 신청→승인 2단 흐름의 스터디. status 는 생애축 하나다 (PENDING 기본).
 * cur(현재 인원)·apply(사용자별 상태)는 저장하지 않고 서비스에서 파생.
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
    private String place;         // nullable — 이행 이전 스터디는 비어 있다
    private String mode;          // nullable
    @Column(length = 2000)
    private String intro;         // nullable
    private String contact;       // nullable — 이행 이전 스터디는 비어 있다

    /**
     * 생애축. @Column(nullable = false) 를 쓰지 않는다 — 행이 있는 테이블에 NOT NULL
     * 컬럼을 붙이면 Postgres 가 거부하고 Hibernate 는 그 예외를 로그로 삼켜, 컬럼이
     * 없는 채로 기동한다. 컬럼은 손으로 먼저 만든다 (docs/migrations/).
     */
    @Enumerated(EnumType.STRING)
    private StudyStatus status = StudyStatus.PENDING;

    @Column(length = 1000)
    private String reason;        // 반려 사유 (nullable)

    private Instant createdAt = Instant.now();

    @Version
    private Long version;   // 관리자 일괄 편집 낙관적 잠금

    protected Study() { }

    public static Study create(String title, List<String> fields, Integer capacity,
                               String schedule, String place, String mode, String intro,
                               String contact, String leaderId) {
        Study s = new Study();
        s.id = UUID.randomUUID().toString();
        s.title = title;
        s.fields = new ArrayList<>(fields);
        s.capacity = capacity;
        s.schedule = schedule;
        s.place = place;
        s.mode = mode;
        s.intro = intro;
        s.contact = contact;
        s.leaderId = leaderId;
        s.status = StudyStatus.PENDING;
        s.createdAt = Instant.now();
        return s;
    }

    public void approve() { this.status = StudyStatus.RECRUITING; }

    public void reject(String reason) {
        this.status = StudyStatus.REJECTED;
        this.reason = reason;
    }

    public void closeRecruiting() { this.status = StudyStatus.ONGOING; }

    public void finish() { this.status = StudyStatus.FINISHED; }

    /**
     * 개설 때 적은 여덟 칸을 다시 적는다. 상태·스터디장·커리큘럼은 건드리지 않는다 —
     * 각각 전이 메서드와 StudyWeek 가 맡는다.
     *
     * 낱개 setter 를 여섯 개 더 만들지 않은 것은 부르는 쪽이 하나여서다. 흩어 두면
     * '모집 중일 때만'이라는 조건이 붙지 않은 채 한 칸만 고치는 길이 생긴다.
     * fields 는 새 리스트로 갈아 끼운다 — 호출자가 들고 있는 리스트를 그대로 물면
     * 영속 컬렉션이 트랜잭션 밖에서 바뀔 수 있다.
     */
    public void editInfo(String title, List<String> fields, Integer capacity,
                         String schedule, String place, String mode, String intro,
                         String contact) {
        this.title = title;
        this.fields = new ArrayList<>(fields);
        this.capacity = capacity;
        this.schedule = schedule;
        this.place = place;
        this.mode = mode;
        this.intro = intro;
        this.contact = contact;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public List<String> getFields() { return fields; }
    public String getLeaderId() { return leaderId; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer v) { this.capacity = v; }
    public String getSchedule() { return schedule; }
    public String getPlace() { return place; }
    public String getMode() { return mode; }
    public String getIntro() { return intro; }
    public String getContact() { return contact; }
    public StudyStatus getStatus() { return status; }
    public void setStatus(StudyStatus v) { this.status = v; }   // 관리자 일괄 편집 (D12)
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getVersion() { return version; }
}
