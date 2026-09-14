package com.jaram.be.study;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class StudyRepositoryTest extends PostgresTest {

    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;

    @BeforeEach void clean() {
        applications.deleteAllInBatch();
        studies.deleteAllInBatch();
    }

    @Test
    void persistsStudyWithFieldsAndQueriesByApprovalAndLeader() {
        Study s = Study.create("알고리즘", List.of("PS", "그래프"), 6,
                null, null, null, null, "leader-1");
        studies.save(s);

        assertThat(studies.findByApprovalStatusOrderByCreatedAtDesc(ApprovalStatus.PENDING)).hasSize(1);
        assertThat(studies.findByApprovalStatusOrderByCreatedAtDesc(ApprovalStatus.APPROVED)).isEmpty();
        assertThat(studies.findByLeaderIdOrderByCreatedAtDesc("leader-1")).hasSize(1);
        assertThat(studies.findById(s.getId()).orElseThrow().getFields())
                .containsExactly("PS", "그래프");
    }

    @Test
    void countsApprovedApplicationsPerStudy() {
        Study s = studies.save(Study.create("스터디", List.of("x"), 3,
                null, null, null, null, "leader-1"));
        StudyApplication a1 = StudyApplication.create(s.getId(), "u1", "동기1");
        StudyApplication a2 = StudyApplication.create(s.getId(), "u2", "동기2");
        a1.approve();
        applications.save(a1);
        applications.save(a2);

        assertThat(applications.countByStudyIdAndStatus(s.getId(), ApplicationStatus.APPROVED)).isEqualTo(1);
        assertThat(applications.findByStatusOrderByCreatedAtDesc(ApplicationStatus.PENDING)).hasSize(1);
        assertThat(applications.findByStudyIdAndApplicantId(s.getId(), "u1")).isPresent();
    }
}
