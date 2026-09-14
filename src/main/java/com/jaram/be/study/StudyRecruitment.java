package com.jaram.be.study;

import jakarta.persistence.*;

/**
 * 스터디 개설 모집 토글. 단일 행이다.
 *
 * AdminSettings 에 두지 않은 이유: 학술부장(ACADEMIC_LEAD)은 STUDY_EDIT 은 갖지만
 * SETTINGS_* 를 하나도 갖지 않는다. SettingsAccess.canApply 가 마지막에 설정 권한
 * 하나를 요구하므로, 거기 필드를 더하면 스터디 관리 탭의 주인이 자기 토글에서 403 을
 * 받는다. 값이 스터디 쪽에 있으면 게이트가 hasAuthority('STUDY_EDIT') 한 줄이다.
 *
 * 이 값은 '스터디 개설' 버튼의 표시와 동작만 가른다. 어떤 스터디의 status 도
 * 건드리지 않는다 - 모집을 끝내는 판단은 스터디마다 다르고 스터디장이 한다.
 */
@Entity
@Table(name = "study_recruitment")
public class StudyRecruitment {

    static final String SINGLETON_ID = "SINGLETON";

    @Id
    private String id = SINGLETON_ID;

    /** 'open' 은 SQL 표준 예약어라 컬럼 이름을 피한다. */
    @Column(name = "is_open")
    private boolean open;

    protected StudyRecruitment() { }

    static StudyRecruitment closed() {
        StudyRecruitment r = new StudyRecruitment();
        r.id = SINGLETON_ID;
        r.open = false;
        return r;
    }

    public boolean isOpen() { return open; }
    public void setOpen(boolean v) { this.open = v; }
}
