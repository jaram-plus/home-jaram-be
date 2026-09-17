package com.jaram.be.admin;

import com.jaram.be.common.ClubTime;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.seminar.Attendance;
import com.jaram.be.seminar.AttendanceRepository;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.study.Study;
import com.jaram.be.study.StudyApplication;
import com.jaram.be.study.StudyApplicationRepository;
import com.jaram.be.study.StudyAttendance;
import com.jaram.be.study.StudyAttendanceRepository;
import com.jaram.be.study.StudyRepository;
import com.jaram.be.study.StudyWeek;
import com.jaram.be.study.StudyWeekRepository;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * 월별 출석률 추세(attendanceTrend)와 그것에서 파생하는 KPI·deltas.
 *
 * 과거 달을 만들려면 저장 시각을 뒤로 밀어야 하는데 엔티티가 그걸 허용하지 않는다
 * (takenAt·createdAt 은 한 번만 박힌다). 그래서 그 두 칸만 SQL 로 직접 당긴다 —
 * 프로덕션에 테스트용 setter 를 뚫는 것보다 이쪽이 낫다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminDashboardTrendTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyAttendanceRepository studyAttendances;
    @Autowired JdbcTemplate jdbc;
    @Autowired Actors actors;

    private String officerToken;
    private Member officer;

    @BeforeEach void setup() {
        RestAssured.port = port;
        studyAttendances.deleteAll();
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        attendances.deleteAll();
        seminars.deleteAll();
        members.deleteAll();
        officer = actors.save(com.jaram.be.security.authz.Role.PRESIDENT);   // 승인 회원 1명
        officerToken = actors.tokenFor(officer);
    }

    // ---- 픽스처 -------------------------------------------------------------

    private Member approved(String name, String sid) {
        Member m = Member.newPending(name, sid, name + "@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.REGULAR);
        m.setGen(41);
        return members.save(m);
    }

    /** 그 달 15일 정오(KST). 달 경계에 걸리지 않는 안전한 시각. */
    private static Instant midMonth(int monthsAgo) {
        LocalDate d = ClubTime.today().withDayOfMonth(15).minusMonths(monthsAgo);
        return d.atTime(12, 0).atZone(ClubTime.ZONE).toInstant();
    }

    private static String label(int monthsAgo) {
        return YearMonth.from(ClubTime.today().minusMonths(monthsAgo)).getMonthValue() + "월";
    }

    private Seminar heldSeminar(int monthsAgo, boolean approve) {
        Seminar s = Seminar.create("세미나", "발표자", "주제", midMonth(monthsAgo),
                "401호", "오프라인", "1234", null, null, null);
        if (approve) s.approve();
        return seminars.save(s);
    }

    private void attended(Seminar s, Member... who) {
        for (Member m : who) attendances.save(Attendance.create(s.getId(), m.getId(), s.getStartsAt()));
    }

    /** 스터디장 + 승인 신청자로 이뤄진 스터디 하나. */
    private Study studyOf(Member leader, Member... joiners) {
        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leader.getId());
        s.approve();
        s.closeRecruiting();
        Study saved = studies.save(s);
        for (Member j : joiners) {
            StudyApplication a = StudyApplication.create(saved.getId(), j.getId(), "하고 싶습니다");
            a.approve();
            applications.save(a);
        }
        return saved;
    }

    /** weekNo 주차를 monthsAgo 달에 찍은 것으로 만든다. */
    private void tookAttendance(Study study, int weekNo, int monthsAgo, Member... present) {
        StudyWeek w = weeks.save(StudyWeek.create(study.getId(), weekNo, "주차", null));
        Instant at = midMonth(monthsAgo);
        for (Member m : present) studyAttendances.save(StudyAttendance.create(w.getId(), m.getId(), at));
        jdbc.update("update study_week set taken_at = ? where id = ?", Timestamp.from(at), w.getId());
    }

    private void joinedAt(Member m, Instant at) {
        jdbc.update("update member set created_at = ? where id = ?", Timestamp.from(at), m.getId());
    }

    // ---- 추세 ---------------------------------------------------------------

    @Test
    void trendCoversLastSixMonthsEndingThisMonth() {
        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(200)
                .body("attendanceTrend.size()", equalTo(6))
                .body("attendanceTrend[0].month", equalTo(label(5)))
                .body("attendanceTrend[5].month", equalTo(label(0)))
                // 아무 일도 없던 달은 0 — 선이 끊기지 않아야 한다.
                .body("attendanceTrend[5].seminar", equalTo(0))
                .body("attendanceTrend[5].study", equalTo(0));
    }

    @Test
    void seminarRateIsAttendeesOverMembersOfThatMonth() {
        Member a = approved("가", "2023000001");
        approved("나", "2023000002");
        approved("다", "2023000003");            // 임원까지 합쳐 승인 회원 4명
        Seminar s = heldSeminar(0, true);
        attended(s, a);                          // 1/4 = 25%

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(200)
                .body("attendanceTrend[5].seminar", equalTo(25));
    }

    @Test
    void unapprovedSeminarsDoNotDiluteTheRate() {
        Member a = approved("가", "2023000001");
        Seminar s = heldSeminar(0, true);
        attended(s, a);                          // 승인 세미나: 1/2 = 50%
        heldSeminar(0, false);                   // 반려/대기 세미나는 열린 적이 없다

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(200)
                .body("attendanceTrend[5].seminar", equalTo(50));
    }

    @Test
    void pastMonthsUseTheMembershipOfThatTime() {
        Member old = approved("고참", "2023000001");
        joinedAt(old, midMonth(6));
        joinedAt(officer, midMonth(6));
        Member fresh = approved("신입", "2023000002");
        joinedAt(fresh, midMonth(0));            // 이번 달 가입 — 지난달 분모에 들면 안 된다

        Seminar s = heldSeminar(1, true);
        attended(s, old);                        // 지난달 대상 2명 중 1명 = 50%

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(200)
                .body("attendanceTrend[4].seminar", equalTo(50));
    }

    @Test
    void studyRateIsPresentOverEligibleOfWeeksTakenThatMonth() {
        Member leader = approved("리더", "2023000001");
        Member joined = approved("참여", "2023000002");
        Study study = studyOf(leader, joined);   // 출석 대상 2명
        tookAttendance(study, 1, 0, leader);     // 1/2 = 50%

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(200)
                .body("attendanceTrend[5].study", equalTo(50))
                .body("studyAttendanceRate", equalTo(50));
    }

    // ---- deltas -------------------------------------------------------------

    @Test
    void deltasComparePreviousMonthInPercentagePoints() {
        Member a = approved("가", "2023000001");   // 임원까지 승인 회원 2명
        joinedAt(a, midMonth(2));                  // 지난달에도 이미 회원이어야 분모에 든다
        joinedAt(officer, midMonth(2));
        attended(heldSeminar(1, true), a);         // 지난달 1/2 = 50%
        attended(heldSeminar(0, true), a, officer);// 이번달 2/2 = 100%

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(200)
                .body("deltas.seminarRate", equalTo(50));
    }

    @Test
    void membersDeltaCountsThisSemesterJoiners() {
        Member oldbie = approved("고참", "2023000001");
        joinedAt(oldbie, ClubTime.startOfDay(Semester.autoAt(ClubTime.today()).start().minusDays(1)));
        approved("신입", "2023000002");           // 이번 학기 가입

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(200)
                // 신입 + 이번 학기에 만들어진 임원 = 2명
                .body("deltas.members", equalTo(2));
    }
}
