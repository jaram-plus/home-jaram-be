package com.jaram.be.security.authz;

/**
 * 행위 단위 권한. 지위가 아니라 할 수 있는 일로 이름 붙인다 — "부장이니까"가 아니라
 * "세미나를 승인할 수 있으니까" 로 읽혀야 매트릭스가 사람 눈에 검증된다.
 *
 * enum name 이 그대로 GrantedAuthority 문자열이며 @PreAuthorize 가 참조한다.
 * 이름을 바꾸면 애너테이션 문자열도 같이 바꿔야 하고, 컴파일러가 잡아 주지 않는다 —
 * AdminAuthorizationCoverageTest 가 잡는다.
 */
public enum Permission {
    MEMBER_LIST,
    /** 회원 상세 + 승인 대기 목록. 목록(MEMBER_LIST)과 달리 연락처 같은 개인정보가 나온다. */
    MEMBER_READ,
    MEMBER_APPROVE,
    MEMBER_EDIT,
    TERM_ASSIGN,

    SEMINAR_LIST,
    /** 엔드포인트 게이트가 아니다. 목록 행의 attendanceCode(BE)와 상세 모달 진입(FE)을 가른다. */
    SEMINAR_READ,
    SEMINAR_CREATE,
    SEMINAR_APPROVE,
    SEMINAR_ATTENDANCE_MANAGE,
    SEMINAR_ROSTER_READ,
    SEMINAR_ROSTER_EDIT,
    SEMINAR_EDIT,

    STUDY_LIST,
    STUDY_READ,
    STUDY_APPROVE,
    STUDY_APPLICANT_MANAGE,
    STUDY_EDIT,

    SCHEDULE_MANAGE,

    SETTINGS_READ,
    SETTINGS_EDIT,
    SETTINGS_ROLLOVER,
    SITE_LINKS_EDIT,

    DASHBOARD_READ,
    EXPORT_RUN
}
