package com.jaram.be.admin;

import com.jaram.be.security.authz.Permission;
import com.jaram.be.security.authz.Permissions;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 관리 목록과 일괄 편집은 핸들러가 하나이고 대상이 경로 변수로 온다. 정적 문자열
 * 하나로는 권한을 적을 수 없어 조건 빈으로 뺀다.
 *
 * 목록에서 members 만 읽기 권한이 따로인 이유: FINANCE_STAFF 는 MEMBER_READ 만
 * 가진다. 여기서 MEMBER_EDIT 을 요구하면 그 Role 이 쓸 화면이 하나도 없어진다.
 * 세미나·스터디는 매트릭스에 읽기 전용 Role 이 없어 편집 권한으로 함께 본다.
 */
@Component("adminResourceAccess")
public class AdminResourceAccess {

    public boolean canList(AdminResource resource, Authentication auth) {
        return Permissions.has(auth, switch (resource) {
            case members -> Permission.MEMBER_READ;
            case seminars -> Permission.SEMINAR_EDIT;
            case studies -> Permission.STUDY_EDIT;
        });
    }

    public boolean canEdit(AdminResource resource, Authentication auth) {
        return Permissions.has(auth, switch (resource) {
            case members -> Permission.MEMBER_EDIT;
            case seminars -> Permission.SEMINAR_EDIT;
            case studies -> Permission.STUDY_EDIT;
        });
    }
}
