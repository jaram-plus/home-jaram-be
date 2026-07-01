package com.jaram.be.study;

import com.jaram.be.security.CurrentMember;
import com.jaram.be.study.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/studies")
public class StudyController {

    private final StudyService service;

    public StudyController(StudyService service) { this.service = service; }

    // UC-T1: public. 미인증 시 principal null → apply 파생 null.
    @GetMapping
    public List<StudyResponse> list(@AuthenticationPrincipal CurrentMember me) {
        return service.list(me == null ? null : me.id());
    }

    // UC-T3: 개설 신청.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StudyResponse create(@Valid @RequestBody StudyCreateRequest req,
                                @AuthenticationPrincipal CurrentMember me) {
        return service.create(req, me.id());
    }

    // UC-T4: 내 활동.
    @GetMapping("/my")
    public MyActivity my(@AuthenticationPrincipal CurrentMember me) {
        return service.myActivity(me.id());
    }

    // UC-T5: 개설 대기 목록 (OFFICER).
    @GetMapping("/pending")
    public List<PendingStudy> pending() { return service.pending(); }

    // UC-T7: 신청자 목록 (OFFICER).
    @GetMapping("/applicants")
    public List<Applicant> applicants() { return service.applicants(); }

    // UC-T2: 지원.
    @PostMapping("/{id}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    public void apply(@PathVariable String id,
                      @Valid @RequestBody ApplyRequest req,
                      @AuthenticationPrincipal CurrentMember me) {
        service.apply(id, me.id(), req.motive());
    }

    // UC-T6: 개설 승인/거절 (OFFICER).
    @PostMapping("/{id}/approve")
    public void approveStudy(@PathVariable String id) { service.approveStudy(id); }

    @PostMapping("/{id}/reject")
    public void rejectStudy(@PathVariable String id, @Valid @RequestBody RejectRequest req) {
        service.rejectStudy(id, req.reason());
    }

    // UC-T8: 신청자 승인/거절 (OFFICER).
    @PostMapping("/applicants/{id}/approve")
    public void approveApplicant(@PathVariable String id) { service.approveApplicant(id); }

    @PostMapping("/applicants/{id}/reject")
    public void rejectApplicant(@PathVariable String id, @Valid @RequestBody RejectRequest req) {
        service.rejectApplicant(id, req.reason());
    }
}
