package com.jaram.be.me;

import com.jaram.be.common.ApiException;
import com.jaram.be.me.dto.MeProfile;
import com.jaram.be.me.dto.MeUpdateRequest;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.dto.MemberTermResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * GET/PATCH /api/me: the authenticated member's own profile. gen goes out as a
 * plain integer — the "기" suffix is FE's to render. Profile edits touch
 * bio/github/blog/phone; faculty is read-only.
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
        if (req.phone() != null) m.setPhone(req.phone());   // null = 미변경
        return toProfile(m);
    }

    /** 재등록 신청. 임원이 승인해야 완료된다. 이미 신청했으면 시각을 덮지 않는다. */
    @Transactional
    public void requestReregistration(String memberId) {
        Member m = find(memberId);
        if (m.getStatus() != MemberStatus.REREGISTER) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "재등록 대상이 아닙니다.");
        }
        m.requestReregistration(Instant.now());
    }

    /**
     * 본인 탈퇴. 개인정보는 6개월 뒤 스윕이 파기한다.
     *
     * 멱등하다. 탈퇴해도 발급된 토큰은 ttl 동안 살아 있어 다시 부를 수 있는데, 그때
     * withdrawnAt 을 덮으면 6개월 파기 시계가 그만큼 뒤로 밀린다.
     */
    @Transactional
    public void withdraw(String memberId) {
        Member m = find(memberId);
        if (m.getStatus() == MemberStatus.WITHDRAWN) return;
        if (m.currentTerm().isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT",
                    "현직 임기가 있어 탈퇴할 수 없습니다. 임기를 먼저 정리해 주세요.");
        }
        m.withdraw(Instant.now());
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
                m.getFaculty(),
                m.getPhone(),
                m.isContributor(),
                m.getBio(),
                m.getGithubUrl(),
                m.getBlogUrl());
    }
}
