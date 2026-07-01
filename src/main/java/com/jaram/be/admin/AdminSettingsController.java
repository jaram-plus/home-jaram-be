package com.jaram.be.admin;

import com.jaram.be.admin.dto.AdminSettingsResponse;
import com.jaram.be.admin.dto.AdminSettingsUpdate;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    private final AdminSettingsService service;

    public AdminSettingsController(AdminSettingsService service) { this.service = service; }

    @GetMapping
    public AdminSettingsResponse get() { return service.get(); }

    @PatchMapping
    public AdminSettingsResponse update(@Valid @RequestBody AdminSettingsUpdate req) {
        return service.update(req);
    }
}
