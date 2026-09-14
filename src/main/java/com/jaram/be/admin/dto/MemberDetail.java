package com.jaram.be.admin.dto;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTerm;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.member.dto.MemberCareerResponse;
import com.jaram.be.member.dto.MemberTermResponse;

import java.util.Comparator;
import java.util.List;

/**
 * 계약 MemberDetail. admin 회원 상세(조회 전용) — 관리 목록 행(AdminResourceService.memberRow)
 * 보다 넓은 표현이다. createdAt 은 레코드 생성 시각이라 승인 시점이 아니라 가입 신청 시점이다.
 * department·title 은 현직 임기에서 파생되므로 임기가 없으면 null 이다.
 */
public record MemberDetail(
        String id,
        String name,
        String studentId,
        String email,
        String phone,
        String faculty,
        Integer gen,
        MemberGrade grade,
        MemberStatus status,
        MemberApproval approval,
        boolean contributor,
        MemberDepartment department,
        MemberTitle title,
        List<MemberTermResponse> terms,
        Integer gradYear,
        List<MemberCareerResponse> careers,
        String bio,
        String githubUrl,
        String blogUrl,
        String createdAt) {

    /** 지연 로딩되는 terms 를 건드리므로 반드시 트랜잭션 안에서 호출한다. */
    public static MemberDetail of(Member m) {
        return new MemberDetail(
                m.getId(), m.getName(), m.getStudentId(), m.getEmail(),
                m.getPhone(), m.getFaculty(), m.getGen(),
                m.getGrade(), m.getStatus(), m.getApproval(), m.isContributor(),
                m.getDepartment(), m.getTitle(),
                m.getTerms().stream()
                        .sorted(Comparator.comparingInt(MemberTerm::getStartGen))
                        .map(MemberTermResponse::of)
                        .toList(),
                m.getGradYear(),
                m.getCareers().stream().map(MemberCareerResponse::of).toList(),
                m.getBio(), m.getGithubUrl(), m.getBlogUrl(),
                m.getCreatedAt().toString());
    }
}
