package com.jaram.be.seminar;

import com.jaram.be.seminar.dto.RejectRequest;
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
}
