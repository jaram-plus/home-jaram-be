package com.jaram.be.admin;

import com.jaram.be.admin.dto.AdminSettingsUpdate;
import com.jaram.be.security.authz.Permission;
import com.jaram.be.security.authz.Permissions;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 설정 PATCH 는 엔드포인트가 하나인데 그 안의 필드마다 무게가 다르다. SITE_LINKS_EDIT
 * 를 따로 둔 목적(홍보부가 푸터 링크만 고친다)은 필드 조건으로만 달성된다.
 *
 * currentGen 이 SETTINGS_ROLLOVER 인 이유: 기수를 손으로 바꾸는 유일한 통로다.
 * 전환 자체는 MemberLifecycleService 의 스윕이고 사람이 부르는 엔드포인트가 없다.
 */
@Component("settingsAccess")
public class SettingsAccess {

    public boolean canApply(AdminSettingsUpdate req, Authentication auth) {
        if (req == null) return false;

        if (req.currentGen() != null && !Permissions.has(auth, Permission.SETTINGS_ROLLOVER)) {
            return false;
        }
        boolean touchesSettings = req.semesterTerm() != null || req.autoPromote() != null;
        if (touchesSettings && !Permissions.has(auth, Permission.SETTINGS_EDIT)) {
            return false;
        }
        if (req.links() != null
                && !Permissions.has(auth, Permission.SETTINGS_EDIT)
                && !Permissions.has(auth, Permission.SITE_LINKS_EDIT)) {
            return false;
        }
        // 아무 필드도 없는 요청은 SETTINGS_READ 만 있으면 통과시킨다 — 바뀌는 것이 없다.
        return Permissions.has(auth, Permission.SETTINGS_READ)
                || Permissions.has(auth, Permission.SETTINGS_EDIT)
                || Permissions.has(auth, Permission.SITE_LINKS_EDIT);
    }
}
