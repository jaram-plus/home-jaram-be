package com.jaram.be.admin;

import com.jaram.be.admin.dto.AdminSettingsResponse;
import com.jaram.be.admin.dto.AdminSettingsUpdate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSettingsService {

    private final AdminSettingsRepository repo;

    public AdminSettingsService(AdminSettingsRepository repo) { this.repo = repo; }

    @Transactional
    public AdminSettingsResponse get() {
        return toResponse(loadOrCreate());
    }

    @Transactional
    public AdminSettingsResponse update(AdminSettingsUpdate req) {
        AdminSettings s = loadOrCreate();
        if (req.semester() != null) s.setSemester(req.semester());
        if (req.currentCohort() != null) s.setCurrentCohort(req.currentCohort());
        if (req.autoPromote() != null) s.setAutoPromote(req.autoPromote());
        return toResponse(s);
    }

    private AdminSettings loadOrCreate() {
        return repo.findById(AdminSettings.SINGLETON_ID)
                .orElseGet(() -> repo.save(AdminSettings.defaults()));
    }

    private AdminSettingsResponse toResponse(AdminSettings s) {
        return new AdminSettingsResponse(
                s.getSemester(),
                s.getCurrentCohort() == null ? 0 : s.getCurrentCohort(),
                s.isAutoPromote(),
                s.isDriveConnected(),
                s.getDriveFolder());
    }
}
