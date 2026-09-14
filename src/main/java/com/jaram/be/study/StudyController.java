package com.jaram.be.study;

import com.jaram.be.security.CurrentMember;
import com.jaram.be.study.dto.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
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
    public StudyList list(@RequestParam(required = false) String status,
                          @AuthenticationPrincipal CurrentMember me) {
        return service.list(me == null ? null : me.id(), status);
    }

    // 모집 토글. 스터디 관리 탭의 손잡이라 STUDY_EDIT 으로 가른다.
    // literal 경로라 {id} 보다 먼저 선언한다 — Spring 의 매칭 우선순위 자체는 선언
    // 순서와 무관하지만, 읽는 사람에게 이 자리가 고정 경로임을 보인다.
    @PutMapping("/recruitment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('STUDY_EDIT')")
    public void recruitment(@Valid @RequestBody RecruitmentUpdate req) {
        service.setRecruitmentOpen(req.open());
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
    @PreAuthorize("hasAuthority('STUDY_APPROVE')")
    public List<PendingStudy> pending() { return service.pending(); }

    // UC-T7: 신청자 목록 (OFFICER).
    @GetMapping("/applicants")
    @PreAuthorize("hasAuthority('STUDY_APPLICANT_MANAGE')")
    public List<Applicant> applicants() { return service.applicants(); }

    // UC-T2: 지원.
    @PostMapping("/{id}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    public void apply(@PathVariable String id,
                      @Valid @RequestBody ApplyRequest req,
                      @AuthenticationPrincipal CurrentMember me) {
        service.apply(id, me.id(), req.motive());
    }

    // UC-T6: 개설 승인/거절 (OFFICER). 소유자 조건을 걸지 않는다 — 자기가 낸 개설
    // 신청을 자기가 승인할 수 있으면 승인 절차 자체가 없는 것과 같다. 스터디장이라는
    // 지위는 승인된 뒤에 생긴다.
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('STUDY_APPROVE')")
    public void approveStudy(@PathVariable String id) { service.approveStudy(id); }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('STUDY_APPROVE')")
    public void rejectStudy(@PathVariable String id, @Valid @RequestBody RejectRequest req) {
        service.rejectStudy(id, req.reason());
    }

    // 모집 완료 — RECRUITING 에서만. 스터디장이 자기 스터디의 모집을 닫는다.
    @PostMapping("/{id}/close-recruiting")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public void closeRecruiting(@PathVariable String id) { service.closeRecruiting(id); }

    // 종료 — ONGOING 에서만.
    @PostMapping("/{id}/finish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public void finish(@PathVariable String id) { service.finish(id); }

    // UC-T8: 신청자 승인/거절. {id} 는 스터디 id 가 아니라 신청 id 다 — isLeader 를
    // 걸면 언제나 false 가 되어 스터디장이 자기 신청을 하나도 처리하지 못한다.
    @PostMapping("/applicants/{id}/approve")
    @PreAuthorize("@studyAccess.isLeaderOfApplication(#id, authentication)"
            + " or hasAuthority('STUDY_APPLICANT_MANAGE')")
    public void approveApplicant(@PathVariable String id) { service.approveApplicant(id); }

    @PostMapping("/applicants/{id}/reject")
    @PreAuthorize("@studyAccess.isLeaderOfApplication(#id, authentication)"
            + " or hasAuthority('STUDY_APPLICANT_MANAGE')")
    public void rejectApplicant(@PathVariable String id, @Valid @RequestBody RejectRequest req) {
        service.rejectApplicant(id, req.reason());
    }
}
