package com.jaram.be.site;

import com.jaram.be.admin.AdminSettingsService;
import com.jaram.be.admin.dto.SiteLinks;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사이트 공개 설정 — 지금은 랜딩 푸터의 외부 링크 하나뿐이다.
 *
 * 값 자체는 학회 설정(admin_settings)에 있고 수정은 임원 전용 /api/admin/settings 로만
 * 한다. 그런데 푸터는 비로그인 방문자도 보는 화면이라 읽기는 열려 있어야 해서, 그 한
 * 조각만 공개 경로로 따로 낸다.
 */
@RestController
@RequestMapping("/api/site/links")
public class SiteLinksController {

    private final AdminSettingsService settings;

    public SiteLinksController(AdminSettingsService settings) { this.settings = settings; }

    @GetMapping
    public SiteLinks get() { return settings.links(); }
}
