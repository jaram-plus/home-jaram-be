package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StudyWeekRepository extends JpaRepository<StudyWeek, String> {
    List<StudyWeek> findByStudyIdOrderByWeekNoAsc(String studyId);
    Optional<StudyWeek> findByStudyIdAndWeekNo(String studyId, int weekNo);
    Optional<StudyWeek> findFirstByStudyIdOrderByWeekNoDesc(String studyId);
    long countByStudyId(String studyId);
}
