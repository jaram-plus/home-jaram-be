package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StudyApplicationRepository extends JpaRepository<StudyApplication, String> {
    Optional<StudyApplication> findByStudyIdAndApplicantId(String studyId, String applicantId);
    List<StudyApplication> findByApplicantIdOrderByCreatedAtDesc(String applicantId);
    List<StudyApplication> findByStatusOrderByCreatedAtDesc(ApplicationStatus status);
    int countByStudyIdAndStatus(String studyId, ApplicationStatus status);
    List<StudyApplication> findByStudyIdAndStatus(String studyId, ApplicationStatus status);
    List<StudyApplication> findByStudyId(String studyId);
}
