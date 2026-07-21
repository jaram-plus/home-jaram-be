package com.jaram.be.admin;

import com.jaram.be.admin.dto.PendingMember;
import com.jaram.be.common.ApiException;
import com.jaram.be.member.Gen;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
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

    @Transactional
    public void approve(String id) {
        Member m = load(id);
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(deriveGrade(m.getGen()));
    }

    @Transactional
    public void reject(String id, String reason) { load(id).setApproval(MemberApproval.REJECTED); }

    // 계약 MemberGrade.description: gen == 현재년도-1984 → NEWCOMER, 그 외 ASSOCIATE.
    private MemberGrade deriveGrade(Integer gen) {
        int currentGen = Gen.current();
        return (gen != null && gen == currentGen) ? MemberGrade.NEWCOMER : MemberGrade.ASSOCIATE;
    }

    private Member load(String id) {
        return members.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "회원을 찾을 수 없습니다."));
    }
}
