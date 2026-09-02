package com.jaram.be.admin;

import com.jaram.be.admin.dto.AdminSettingsResponse;
import com.jaram.be.admin.dto.AdminSettingsUpdate;
import com.jaram.be.admin.dto.SiteLinks;
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
        if (req.currentGen() != null) s.setCurrentCohort(req.currentGen());
        if (req.autoPromote() != null) s.setAutoPromote(req.autoPromote());
        if (req.links() != null) {
            s.setLinkGithub(req.links().github());
            s.setLinkInstagram(req.links().instagram());
            s.setLinkBlog(req.links().blog());
            s.setLinkDiscord(req.links().discord());
        }
        return toResponse(s);
    }

    /**
     * 푸터가 읽는 공개 링크 (GET /api/site/links).
     *
     * 임원 화면과 달리 설정 로우를 만들지 않는다 — 아무나 부를 수 있는 읽기가 쓰기를
     * 일으키면 곤란하고, 로우가 없다는 건 등록된 채널이 없다는 뜻이라 답도 정해져 있다.
     */
    @Transactional(readOnly = true)
    public SiteLinks links() {
        return repo.findById(AdminSettings.SINGLETON_ID)
                .map(AdminSettingsService::toLinks)
                .orElseGet(SiteLinks::empty);
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
                s.getDriveFolder(),
                toLinks(s));
    }

    private static SiteLinks toLinks(AdminSettings s) {
        return new SiteLinks(s.getLinkGithub(), s.getLinkInstagram(), s.getLinkBlog(), s.getLinkDiscord());
    }
}
