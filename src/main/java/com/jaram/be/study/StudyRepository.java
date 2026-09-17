package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface StudyRepository extends JpaRepository<Study, String> {
    List<Study> findByStatusOrderByCreatedAtDesc(StudyStatus status);
    List<Study> findByStatusInOrderByCreatedAtDesc(Collection<StudyStatus> statuses);
    List<Study> findByLeaderIdOrderByCreatedAtDesc(String leaderId);
}
