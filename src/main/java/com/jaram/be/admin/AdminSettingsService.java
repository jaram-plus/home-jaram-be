package com.jaram.be.admin;

import com.jaram.be.admin.dto.AdminSettingsResponse;
import com.jaram.be.admin.dto.AdminSettingsUpdate;
import com.jaram.be.admin.dto.SiteLinks;
import com.jaram.be.common.ClubTime;
import com.jaram.be.member.Gen;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

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
        LocalDate today = ClubTime.today();
        if (req.semesterTerm() != null) s.overrideTerm(req.semesterTerm(), today);
        if (req.currentGen() != null) s.overrideGen(req.currentGen(), today);
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

    /**
     * 오늘 기준 현재 기수. 대시보드 집계와 임기 전환이 같은 값을 봐야 해서 한 곳에 둔다
     * — 예전에는 대시보드가 설정을 무시하고 계산값만 써서 둘이 어긋났다.
     */
    @Transactional(readOnly = true)
    public int currentGen() {
        LocalDate today = ClubTime.today();
        return repo.findById(AdminSettings.SINGLETON_ID)
                .map(s -> s.effectiveGen(today))
                .orElseGet(() -> Gen.at(today.getYear()));
    }

    private AdminSettings loadOrCreate() {
        return repo.findById(AdminSettings.SINGLETON_ID)
                .orElseGet(() -> repo.save(AdminSettings.defaults()));
    }

    private AdminSettingsResponse toResponse(AdminSettings s) {
        LocalDate today = ClubTime.today();
        return new AdminSettingsResponse(
                today.getYear(),
                s.effectiveTerm(today),
                s.isTermAuto(today),
                s.effectiveGen(today),
                s.isAutoPromote(),
                s.isDriveConnected(),
                s.getDriveFolder(),
                toLinks(s));
    }

    private static SiteLinks toLinks(AdminSettings s) {
        return new SiteLinks(s.getLinkGithub(), s.getLinkInstagram(), s.getLinkBlog(), s.getLinkDiscord());
    }
}
