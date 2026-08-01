package com.jaram.be.admin;

import com.jaram.be.admin.dto.MemberDetail;
import com.jaram.be.admin.dto.PendingMember;
import com.jaram.be.admin.dto.RejectRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/members")
public class AdminMemberController {

    private final AdminMemberService service;

    public AdminMemberController(AdminMemberService service) { this.service = service; }

    @GetMapping("/pending")
    public List<PendingMember> pending() { return service.listPending(); }

    // /pending 은 리터럴이라 Spring 이 {id} 보다 먼저 매칭한다.
    @GetMapping("/{id}")
    public MemberDetail detail(@PathVariable String id) { return service.detail(id); }

    @PostMapping("/{id}/approve")
    public void approve(@PathVariable String id) { service.approve(id); }

    @PostMapping("/{id}/reject")
    public void reject(@PathVariable String id, @Valid @RequestBody RejectRequest req) {
        service.reject(id, req.reason());
    }
}
