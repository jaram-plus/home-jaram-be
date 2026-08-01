package com.jaram.be.me;

import com.jaram.be.common.ApiException;
import com.jaram.be.me.dto.MeProfile;
import com.jaram.be.me.dto.MeUpdateRequest;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.dto.MemberTermResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET/PATCH /api/me: the authenticated member's own profile. gen goes out as a
 * plain integer — the "기" suffix is FE's to render. Profile edits touch only
 * bio/github/blog.
 */
@Service
public class MeService {

    private final MemberRepository members;

    public MeService(MemberRepository members) { this.members = members; }

    @Transactional(readOnly = true)
    public MeProfile get(String memberId) {
        return toProfile(find(memberId));
    }

    @Transactional
    public MeProfile update(String memberId, MeUpdateRequest req) {
        Member m = find(memberId);
        m.setBio(req.bio());
        m.setGithubUrl(req.githubUrl());
        m.setBlogUrl(req.blogUrl());
        return toProfile(m);
    }

    private Member find(String memberId) {
        return members.findById(memberId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "회원을 찾을 수 없습니다."));
    }

    private MeProfile toProfile(Member m) {
        return new MeProfile(
                m.getId(),
                m.getName(),
                m.getStudentId(),
                m.getEmail(),
                m.getAuthority(),
                m.getGrade(),
                m.getStatus(),
                m.getDepartment(),
                m.getTitle(),
                m.getTerms().stream().map(MemberTermResponse::of).toList(),
                m.getGen(),
                m.getBio(),
                m.getGithubUrl(),
                m.getBlogUrl());
    }
}
