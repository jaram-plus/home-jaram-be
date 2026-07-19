package com.jaram.be.schedule;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "schedule_slot")
public class ScheduleSlot {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @Column(name = "slot_index")
    private int index;

    private String memberId;    // nullable — 빈 슬롯
    private String seminarId;   // nullable — 제출 전

    protected ScheduleSlot() { }

    static ScheduleSlot create(Schedule schedule, int index) {
        ScheduleSlot s = new ScheduleSlot();
        s.id = UUID.randomUUID().toString();
        s.schedule = schedule;
        s.index = index;
        return s;
    }

    public void claim(String memberId) { this.memberId = memberId; }
    public void release() { this.memberId = null; this.seminarId = null; }
    public void attachSeminar(String seminarId) { this.seminarId = seminarId; }

    public String getId() { return id; }
    public int getIndex() { return index; }
    public String getMemberId() { return memberId; }
    public String getSeminarId() { return seminarId; }
}
