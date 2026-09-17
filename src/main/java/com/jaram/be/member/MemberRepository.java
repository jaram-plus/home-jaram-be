package com.jaram.be.member;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, String> {
    Optional<Member> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByStudentId(String studentId);
    List<Member> findByApproval(MemberApproval approval);
    List<Member> findByApprovalAndStatus(MemberApproval approval, MemberStatus status);
}
