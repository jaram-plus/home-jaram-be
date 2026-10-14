package com.jaram.be.admin;

import com.jaram.be.security.authz.Permission;
import com.jaram.be.security.authz.Permissions;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 관리 목록과 일괄 편집은 핸들러가 하나이고 대상이 경로 변수로 온다. 정적 문자열
 * 하나로는 권한을 적을 수 없어 조건 빈으로 뺀다.
 *
 * 목록은 세 자원 모두 *_LIST 로 연다. 임기 직책이면 누구나 갖는 권한이라
 * FINANCE_STAFF 처럼 편집 권한이 없는 Role 도 목록 화면은 쓸 수 있다.
 * 상세(*_READ)와 편집(*_EDIT)은 이 빈이 아니라 각 핸들러의 권한이 정한다.
 */
@Component("adminResourceAccess")
public class AdminResourceAccess {

    public boolean canList(AdminResource resource, Authentication auth) {
        return Permissions.has(auth, switch (resource) {
            case members -> Permission.MEMBER_LIST;
            case seminars -> Permission.SEMINAR_LIST;
            case studies -> Permission.STUDY_LIST;
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
