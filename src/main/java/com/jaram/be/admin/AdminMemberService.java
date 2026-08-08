package com.jaram.be.admin;

import com.jaram.be.admin.dto.GraduationUpdate;
import com.jaram.be.admin.dto.MemberDetail;
import com.jaram.be.admin.dto.PendingMember;
import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminMemberService {

    private final MemberRepository members;

    public AdminMemberService(MemberRepository members) { this.members = members; }

    @Transactional(readOnly = true)
    public List<PendingMember> listPending() {
        return members.findByApproval(MemberApproval.PENDING).stream()
                .map(m -> new PendingMember(m.getId(), m.getName(), m.getStudentId(),
                        m.getEmail(), m.getCreatedAt().toString()))
                .toList();
    }

    @Transactional(readOnly = true)
    public MemberDetail detail(String id) {
        return MemberDetail.of(load(id));
    }

    // 등급은 가입 시점에 이미 정해져 있다(AuthService.signup) — 승인은 승인축만 건드린다.
    @Transactional
    public void approve(String id) {
        load(id).setApproval(MemberApproval.APPROVED);
    }

    @Transactional
    public void reject(String id, String reason) { load(id).setApproval(MemberApproval.REJECTED); }

    /**
     * 졸업생 상세의 저장 — 졸업연도와 졸업 후 이력. 이력은 보낸 목록으로 통째로 교체한다.
     * 표의 일괄 저장(:batch)에 얹지 않은 이유는 이력이 스칼라 칸이 아니라 줄 목록이라
     * 한 요청 안에서 통째로 맞춰야 하기 때문이다.
     */
    @Transactional
    public MemberDetail updateGraduation(String id, GraduationUpdate req) {
        Member m = load(id);
        m.setGradYear(req.gradYear());
        List<GraduationUpdate.Career> careers = req.careers() == null ? List.of() : req.careers();
        m.replaceCareers(careers.stream()
                .map(c -> new Member.CareerEntry(c.at(), c.org(), c.job()))
                .toList());
        return MemberDetail.of(m);
    }

    private Member load(String id) {
        return members.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "회원을 찾을 수 없습니다."));
    }
}
