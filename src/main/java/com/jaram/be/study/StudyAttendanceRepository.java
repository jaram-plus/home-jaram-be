package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StudyAttendanceRepository extends JpaRepository<StudyAttendance, String> {
    List<StudyAttendance> findByWeekId(String weekId);
    List<StudyAttendance> findByWeekIdIn(Collection<String> weekIds);
    boolean existsByWeekId(String weekId);
    void deleteByWeekId(String weekId);
}
