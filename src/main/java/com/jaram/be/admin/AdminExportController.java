package com.jaram.be.admin;

import com.jaram.be.admin.dto.DriveExportRequest;
import com.jaram.be.admin.dto.DriveExportResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/export")
public class AdminExportController {

    private final AdminExportService service;

    public AdminExportController(AdminExportService service) { this.service = service; }

    // UC-A5: 현재 목록을 Google Drive 스프레드시트로 내보내기.
    @PostMapping("/google-drive")
    @PreAuthorize("hasAuthority('EXPORT_RUN')")
    public DriveExportResult exportToGoogleDrive(@Valid @RequestBody DriveExportRequest req) {
        return service.export(req);
    }
}
