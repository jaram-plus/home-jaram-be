package com.jaram.be.seminar;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SeminarRepository extends JpaRepository<Seminar, String> {
    List<Seminar> findAllByOrderByStartsAtDesc();

    List<Seminar> findByApprovalStatusOrderByStartsAtDesc(ApprovalStatus approvalStatus);

    // 회원 삭제 시 개설자 역참조를 끊기 위해. 세미나 자체는 남긴다.
    List<Seminar> findByCreatedById(String createdById);
}
