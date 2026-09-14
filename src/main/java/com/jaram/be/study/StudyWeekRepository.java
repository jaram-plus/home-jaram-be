package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StudyWeekRepository extends JpaRepository<StudyWeek, String> {
    List<StudyWeek> findByStudyIdOrderByWeekNoAsc(String studyId);
}
