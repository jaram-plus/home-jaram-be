package com.jaram.be.seminar;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attendance",
       uniqueConstraints = @UniqueConstraint(columnNames = {"seminarId", "memberId"}))
public class Attendance {

    @Id
    private String id;

    private String seminarId;
    private String memberId;
    private Instant at;

    protected Attendance() { }

    public static Attendance create(String seminarId, String memberId, Instant at) {
        Attendance a = new Attendance();
        a.id = UUID.randomUUID().toString();
        a.seminarId = seminarId;
        a.memberId = memberId;
        a.at = at;
        return a;
    }

    public String getId() { return id; }
    public String getSeminarId() { return seminarId; }
    public String getMemberId() { return memberId; }
    public Instant getAt() { return at; }
}
