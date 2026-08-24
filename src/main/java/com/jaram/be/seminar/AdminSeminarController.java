package com.jaram.be.seminar;

import com.jaram.be.seminar.dto.AttendanceCodeResponse;
import com.jaram.be.seminar.dto.AttendeeRequest;
import com.jaram.be.seminar.dto.RejectRequest;
import com.jaram.be.seminar.dto.RosterResponse;
import com.jaram.be.seminar.dto.SeminarResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/seminars")
public class AdminSeminarController {

    private final SeminarService service;

    public AdminSeminarController(SeminarService service) { this.service = service; }

    @GetMapping("/pending")
    public List<SeminarResponse> pending() {
        return service.listPending();
    }

    @PostMapping("/{id}/approve")
    public SeminarResponse approve(@PathVariable String id) {
        return service.approve(id);
    }

    @PostMapping("/{id}/reject")
    public SeminarResponse reject(@PathVariable String id, @Valid @RequestBody RejectRequest req) {
        return service.reject(id, req.reason());
    }

    /** 출석 코드 발급 — 누르는 즉시 저장된다(일괄 저장을 거치지 않는다). */
    @PostMapping("/{id}/attendance-code")
    public AttendanceCodeResponse attendanceCode(@PathVariable String id) {
        return new AttendanceCodeResponse(service.regenerateAttendanceCode(id));
    }

    @PostMapping("/{id}/close-attendance")
    public SeminarResponse closeAttendance(@PathVariable String id) {
        return service.closeAttendance(id);
    }

    @GetMapping("/{id}/attendees")
    public RosterResponse roster(@PathVariable String id) {
        return service.roster(id);
    }

    @PostMapping("/{id}/attendees")
    public RosterResponse addAttendee(@PathVariable String id, @Valid @RequestBody AttendeeRequest req) {
        return service.addAttendee(id, req.memberId());
    }

    @DeleteMapping("/{id}/attendees/{memberId}")
    public RosterResponse removeAttendee(@PathVariable String id, @PathVariable String memberId) {
        return service.removeAttendee(id, memberId);
    }
}
