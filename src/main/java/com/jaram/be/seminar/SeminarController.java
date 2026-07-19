package com.jaram.be.seminar;

import com.jaram.be.seminar.dto.AttendRequest;
import com.jaram.be.seminar.dto.AttendResult;
import com.jaram.be.seminar.dto.AttendeePreviewResponse;
import com.jaram.be.seminar.dto.RosterResponse;
import com.jaram.be.seminar.dto.SeminarCreateRequest;
import com.jaram.be.seminar.dto.SeminarResponse;
import com.jaram.be.member.Authority;
import com.jaram.be.security.CurrentMember;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/seminars")
public class SeminarController {

    private final SeminarService service;

    public SeminarController(SeminarService service) { this.service = service; }

    @GetMapping
    public List<SeminarResponse> list(@AuthenticationPrincipal CurrentMember me) {
        return service.list(me == null ? null : me.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeminarResponse create(@Valid @RequestBody SeminarCreateRequest req,
                                  @AuthenticationPrincipal CurrentMember me) {
        return service.create(req, me.id());
    }

    @GetMapping("/{id}")
    public SeminarResponse getOne(@PathVariable String id,
                                  @AuthenticationPrincipal CurrentMember me) {
        return service.getOne(id, me == null ? null : me.id(),
                me != null && me.authority() == Authority.OFFICER);
    }

    @PostMapping("/{id}/attend")
    public AttendResult attend(@PathVariable String id,
                               @Valid @RequestBody AttendRequest req,
                               @AuthenticationPrincipal CurrentMember me) {
        return service.attend(id, me.id(), req.code());
    }

    @GetMapping("/{id}/roster")
    public RosterResponse roster(@PathVariable String id) {
        return service.roster(id);
    }

    @GetMapping("/{id}/attendees")
    public AttendeePreviewResponse attendees(@PathVariable String id) {
        return service.attendeePreview(id);
    }
}
