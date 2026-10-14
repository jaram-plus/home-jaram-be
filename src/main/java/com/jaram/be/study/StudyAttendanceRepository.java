package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StudyAttendanceRepository extends JpaRepository<StudyAttendance, String> {
    List<StudyAttendance> findByWeekId(String weekId);
    List<StudyAttendance> findByWeekIdIn(Collection<String> weekIds);
    boolean existsByWeekId(String weekId);
    void deleteByWeekId(String weekId);

    /** 스터디원을 내보낼 때 그 사람의 출석을 그 스터디의 모든 주차에서 지운다. */
    void deleteByWeekIdInAndMemberId(Collection<String> weekIds, String memberId);
}
