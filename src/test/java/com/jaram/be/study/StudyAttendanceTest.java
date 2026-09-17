package com.jaram.be.study;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyAttendanceTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyAttendanceRepository attendance;
    @Autowired Actors actors;

    private Member leader, joined, outsider;
    private String leaderToken, outsiderToken;
    private Study study;
    private StudyWeek week1;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendance.deleteAll();
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        leader   = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr"));
        joined   = members.save(approved("참여", "2023000002", "joined@hanyang.ac.kr"));
        outsider = members.save(approved("남", "2023000003", "out@hanyang.ac.kr"));
        leaderToken   = actors.tokenFor(leader);
        outsiderToken = actors.tokenFor(outsider);

        study = ongoing(leader.getId());
        StudyApplication a = StudyApplication.create(study.getId(), joined.getId(), "하고 싶습니다");
        a.approve();
        applications.save(a);

        week1 = weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        weeks.save(StudyWeek.create(study.getId(), 2, "그리디", null));
    }

    private Member approved(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        return m;
    }

    private Study ongoing(String leaderId) {
        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leaderId);
        s.approve();
        s.closeRecruiting();
        return studies.save(s);
    }

    private int putAttendance(String token, int weekNo, List<String> present) {
        return given().header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(Map.of("present", present))
                .when().put("/api/studies/" + study.getId() + "/weeks/" + weekNo + "/attendance")
                .then().extract().statusCode();
    }

    @Test
    void leaderSavesAttendanceAndTakenAtIsStamped() {
        assertThat(putAttendance(leaderToken, 1, List.of(joined.getId()))).isEqualTo(204);

        assertThat(attendance.findByWeekId(week1.getId()))
                .extracting(StudyAttendance::getMemberId)
                .containsExactly(joined.getId());
        assertThat(weeks.findById(week1.getId()).orElseThrow().getTakenAt()).isNotNull();
    }

    /** 전체 교체다 — 두 번째 저장이 첫 번째를 덮는다. 체크 해제가 이렇게 표현된다. */
    @Test
    void savingAgainReplacesTheWholeWeek() {
        putAttendance(leaderToken, 1, List.of(joined.getId(), leader.getId()));
        putAttendance(leaderToken, 1, List.of(leader.getId()));

        assertThat(attendance.findByWeekId(week1.getId()))
                .extracting(StudyAttendance::getMemberId)
                .containsExactly(leader.getId());
    }

    /** takenAt 은 두 번째 저장에 갱신되지 않는다 — 갱신하면 창이 무한히 연장된다. */
    @Test
    void takenAtDoesNotMoveOnSecondSave() {
        putAttendance(leaderToken, 1, List.of(joined.getId()));
        Instant first = weeks.findById(week1.getId()).orElseThrow().getTakenAt();

        putAttendance(leaderToken, 1, List.of());
        assertThat(weeks.findById(week1.getId()).orElseThrow().getTakenAt()).isEqualTo(first);
    }

    /** 전원 결석과 "아직 안 찍음"은 다른 것이다. */
    @Test
    void emptyPresentIsEveryoneAbsentNotUntaken() {
        assertThat(putAttendance(leaderToken, 1, List.of())).isEqualTo(204);

        assertThat(attendance.findByWeekId(week1.getId())).isEmpty();
        assertThat(weeks.findById(week1.getId()).orElseThrow().getTakenAt()).isNotNull();
    }

    /** 조용히 무시하면 화면이 저장에 성공했다고 믿고 잘못된 명단을 계속 보여준다. */
    @Test
    void outsiderIdInPresentIsRejectedAndNothingIsSaved() {
        assertThat(putAttendance(leaderToken, 1, List.of(joined.getId(), outsider.getId())))
                .isEqualTo(422);

        assertThat(attendance.findByWeekId(week1.getId())).isEmpty();
        assertThat(weeks.findById(week1.getId()).orElseThrow().getTakenAt()).isNull();
    }

    @Test
    void windowClosesAfterTwentyFourHours() {
        putAttendance(leaderToken, 1, List.of(joined.getId()));

        StudyWeek w = weeks.findById(week1.getId()).orElseThrow();
        weeks.save(backdate(w, Instant.now().minusSeconds(25 * 3600)));

        assertThat(putAttendance(leaderToken, 1, List.of())).isEqualTo(409);
    }

    @Test
    void officerSavesEvenAfterTheWindowClosed() {
        String officerToken = actors.token(Role.ACADEMIC_LEAD);

        putAttendance(leaderToken, 1, List.of(joined.getId()));
        StudyWeek w = weeks.findById(week1.getId()).orElseThrow();
        weeks.save(backdate(w, Instant.now().minusSeconds(25 * 3600)));

        assertThat(putAttendance(officerToken, 1, List.of())).isEqualTo(204);
    }

    @Test
    void outsiderCannotSaveAttendance() {
        assertThat(putAttendance(outsiderToken, 1, List.of())).isEqualTo(403);
    }

    @Test
    void finishedStudyIsReadOnly() {
        study.finish();
        studies.save(study);

        assertThat(putAttendance(leaderToken, 1, List.of())).isEqualTo(409);
    }

    @Test
    void unknownWeekIsNotFound() {
        assertThat(putAttendance(leaderToken, 9, List.of())).isEqualTo(404);
    }

    /**
     * takenAt 은 markTaken 이 한 번만 박으므로 테스트에서 뒤로 당길 길이 없다.
     * 리플렉션으로 필드를 직접 쓴다 — 엔티티에 테스트 전용 setter 를 뚫는 것보다
     * 이쪽이 프로덕션 코드를 깨끗하게 둔다.
     */
    private StudyWeek backdate(StudyWeek w, Instant at) {
        try {
            var f = StudyWeek.class.getDeclaredField("takenAt");
            f.setAccessible(true);
            f.set(w, at);
            return w;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
