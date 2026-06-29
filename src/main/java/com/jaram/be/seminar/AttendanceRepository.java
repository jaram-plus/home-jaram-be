package com.jaram.be.seminar;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, String> {
    Optional<Attendance> findBySeminarIdAndMemberId(String seminarId, String memberId);
    List<Attendance> findBySeminarIdOrderByAtAsc(String seminarId);
}
