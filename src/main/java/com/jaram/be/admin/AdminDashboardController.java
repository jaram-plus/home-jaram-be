package com.jaram.be.admin;

import com.jaram.be.admin.dto.DashboardStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService service;

    public AdminDashboardController(AdminDashboardService service) { this.service = service; }

    @GetMapping("/stats")
    public DashboardStats stats() { return service.stats(); }
}
