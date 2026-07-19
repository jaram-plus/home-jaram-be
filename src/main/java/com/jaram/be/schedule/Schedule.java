package com.jaram.be.schedule;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "schedule")
public class Schedule {

    @Id
    private String id;

    private Instant startsAt;
    private String place;   // nullable
    private String mode;    // nullable
    private Integer capacity;

    @Enumerated(EnumType.STRING)
    private ScheduleStatus status = ScheduleStatus.OPEN;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("index ASC")
    private List<ScheduleSlot> slots = new ArrayList<>();

    @Version
    private Long version;   // 선착순 claim 낙관적 잠금

    protected Schedule() { }

    public static Schedule create(Instant startsAt, String place, String mode, int capacity) {
        Schedule s = new Schedule();
        s.id = UUID.randomUUID().toString();
        s.startsAt = startsAt;
        s.place = place;
        s.mode = mode;
        s.capacity = capacity;
        s.status = ScheduleStatus.OPEN;
        for (int i = 0; i < capacity; i++) {
            s.slots.add(ScheduleSlot.create(s, i));
        }
        return s;
    }

    public void lock() { this.status = ScheduleStatus.LOCKED; }

    public String getId() { return id; }
    public Instant getStartsAt() { return startsAt; }
    public String getPlace() { return place; }
    public String getMode() { return mode; }
    public Integer getCapacity() { return capacity; }
    public ScheduleStatus getStatus() { return status; }
    public List<ScheduleSlot> getSlots() { return slots; }
    public Long getVersion() { return version; }
}
