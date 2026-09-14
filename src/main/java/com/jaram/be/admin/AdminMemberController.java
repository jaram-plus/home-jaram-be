package com.jaram.be.admin;

import com.jaram.be.admin.dto.GraduationUpdate;
import com.jaram.be.admin.dto.MemberDetail;
import com.jaram.be.admin.dto.PendingMember;
import com.jaram.be.admin.dto.RejectRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/members")
public class AdminMemberController {

    private final AdminMemberService service;

    public AdminMemberController(AdminMemberService service) { this.service = service; }

    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    public List<PendingMember> pending() { return service.listPending(); }

    // /pending 은 리터럴이라 Spring 이 {id} 보다 먼저 매칭한다.
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    public MemberDetail detail(@PathVariable String id) { return service.detail(id); }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('MEMBER_APPROVE')")
    public void approve(@PathVariable String id) { service.approve(id); }

    @PostMapping("/{id}/reregister")
    @PreAuthorize("hasAuthority('MEMBER_APPROVE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reregister(@PathVariable String id) { service.approveReregistration(id); }

    // 졸업연도·졸업 후 이력. 목록 칸이 섞여 있어 :batch 가 아니라 단건 PUT 으로 통째로 맞춘다.
    @PutMapping("/{id}/graduation")
    @PreAuthorize("hasAuthority('MEMBER_EDIT')")
    public MemberDetail graduation(@PathVariable String id, @RequestBody GraduationUpdate req) {
        return service.updateGraduation(id, req);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('MEMBER_APPROVE')")
    public void reject(@PathVariable String id, @Valid @RequestBody RejectRequest req) {
        service.reject(id, req.reason());
    }
}
