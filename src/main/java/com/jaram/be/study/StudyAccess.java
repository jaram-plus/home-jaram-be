package com.jaram.be.study;

import com.jaram.be.security.CurrentMember;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 3층 조건 — 권한이 아니라 리소스와의 관계를 본다. "내 스터디만 관리"는 Permission
 * 으로 표현할 수 없다. Role 은 member_term(부서·직책)에서 파생되는데 스터디장은
 * 스터디 한 건에 매인 관계라, 그 축에 올리면 "누구의 스터디장인가"가 사라진다.
 *
 * SeminarAccessPolicy 와 같은 모양이다.
 */
@Component("studyAccess")
public class StudyAccess {

    private final StudyRepository studies;
    private final StudyApplicationRepository applications;

    public StudyAccess(StudyRepository studies, StudyApplicationRepository applications) {
        this.studies = studies;
        this.applications = applications;
    }

    /** 경로 변수가 스터디 id 일 때 — 모집 완료·종료. */
    public boolean isLeader(String studyId, Authentication auth) {
        String me = idOf(auth);
        if (me == null) return false;
        return studies.findById(studyId)
                .map(s -> me.equals(s.getLeaderId()))
                .orElse(false);
    }

    /**
     * 경로 변수가 신청 id 일 때 — 신청 승인·반려.
     *
     * /api/studies/applicants/{id} 의 {id} 는 스터디 id 가 아니라 신청 id 다.
     * 여기에 isLeader(#id, ...) 를 걸면 신청 id 로 스터디를 조회하니 언제나 false 가
     * 되고, 스터디장은 자기 스터디의 신청을 하나도 처리하지 못한다 — 403 만 나오고
     * 이유는 안 보이는 종류의 버그다. 신청을 먼저 읽어 studyId 를 얻는다.
     */
    public boolean isLeaderOfApplication(String applicationId, Authentication auth) {
        String me = idOf(auth);
        if (me == null) return false;
        return applications.findById(applicationId)
                .flatMap(a -> studies.findById(a.getStudyId()))
                .map(s -> me.equals(s.getLeaderId()))
                .orElse(false);
    }

    /**
     * 승인된 신청자이거나 스터디장. 출석 조회의 소유자 조건이다.
     *
     * 스터디장을 포함하는 이유는 출석 대상에 포함하는 이유와 같다 — 스터디장도
     * 자기 스터디에 나오고, 자기 출석을 본다.
     */
    public boolean isMember(String studyId, Authentication auth) {
        String me = idOf(auth);
        if (me == null) return false;
        if (studies.findById(studyId).map(s -> me.equals(s.getLeaderId())).orElse(false)) return true;
        return applications.findByStudyIdAndApplicantId(studyId, me)
                .map(a -> a.getStatus() == ApplicationStatus.APPROVED)
                .orElse(false);
    }

    /**
     * 그 신청의 본인. 반려된 자기 신청을 지우는 소유자 조건이다.
     *
     * 경로 변수가 스터디 id 가 아니라 **신청 id** 다 — isLeaderOfApplication 과 같은
     * 함정이다. 스터디 id 로 착각하면 언제나 false 가 되어 아무도 자기 신청을 지우지
     * 못하고, 403 만 나오고 이유는 안 보인다.
     */
    public boolean isApplicant(String applicationId, Authentication auth) {
        String me = idOf(auth);
        if (me == null) return false;
        return applications.findById(applicationId)
                .map(a -> me.equals(a.getApplicantId()))
                .orElse(false);
    }

    private String idOf(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof CurrentMember me)) return null;
        return me.id();
    }
}
