package com.jaram.be.admin;

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

    @PostMapping("/{id}/approve")
    public void approve(@PathVariable String id) { service.approve(id); }

    @PostMapping("/{id}/reject")
    public void reject(@PathVariable String id, @Valid @RequestBody RejectRequest req) {
        service.reject(id, req.reason());
    }
}
