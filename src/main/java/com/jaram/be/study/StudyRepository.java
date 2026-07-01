package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StudyRepository extends JpaRepository<Study, String> {
    List<Study> findByApprovalStatusOrderByCreatedAtDesc(ApprovalStatus approvalStatus);
    List<Study> findByLeaderIdOrderByCreatedAtDesc(String leaderId);
}
