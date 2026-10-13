package com.jaram.be.security.authz;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.seminar.AttendanceRepository;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.study.StudyRepository;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * 목록·상세 권한 분리(member-read-split)를 HTTP 로 고정한다. 직책별 열은 design.md
 * "HTTP 수준" 표와 같은 순서다: 회원 목록 / 상세 / 대기 목록 / 세미나·스터디 목록 /
 * 일괄 편집 / 회원 승인 / 목록 attendanceCode.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadSplitHttpTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired StudyRepository studies;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        clean();
    }

    @AfterEach void cleanup() { clean(); }

    private void clean() {
        attendances.deleteAll();
        seminars.deleteAll();
        studies.deleteAll();
        members.deleteAll();
    }

    private RequestSpecification as(Role role) {
        return given().header("Authorization", "Bearer " + actors.token(role));
    }

    private Seminar seminar(String title, Instant startsAt, String code) {
        return seminars.save(Seminar.create(title, null, null, startsAt, null, null, code, null, 30, "officer-1"));
    }

    private Member plainMember(String name, String sid) {
        Member m = Member.newPending(name, sid, name + "@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setStatus(MemberStatus.ACTIVE);
        return members.save(m);
    }

    // ── 표: 회원 목록 ──

    /** 표 "회원 목록" 칸 — 학술부원·홍보부원·회계부원은 200, 일반 회원은 403. */
    @Test
    void memberListIsOpenToStaffAndClosedToPlainMembers() {
        for (Role role : List.of(Role.ACADEMIC_STAFF, Role.PR_STAFF, Role.FINANCE_STAFF,
                Role.ACADEMIC_LEAD, Role.PR_LEAD)) {
            as(role).when().get("/api/admin/members").then().statusCode(200);
        }
        as(Role.MEMBER).when().get("/api/admin/members").then().statusCode(403);
    }

    /** R2.1 — 학술부원으로 member·contrib·grad·exec 탭 각각 200. */
    @Test
    void academicStaffOpensEveryMemberTab() {
        String token = actors.token(Role.ACADEMIC_STAFF);
        for (String tab : List.of("member", "contrib", "grad", "exec")) {
            given().header("Authorization", "Bearer " + token)
                    .when().get("/api/admin/members?tab=" + tab)
                    .then().statusCode(200);
        }
    }

    // ── 표: 회원 상세 ──

    /** 표 "회원 상세" 칸 — 학술부원·홍보부원 403. 존재하지 않는 id 여도 403 이다(R3.3). */
    @Test
    void staffWithoutMemberReadCannotReadDetail() {
        Member target = plainMember("대상", "2023000001");
        for (Role role : List.of(Role.ACADEMIC_STAFF, Role.PR_STAFF)) {
            as(role).when().get("/api/admin/members/" + target.getId()).then().statusCode(403);
            as(role).when().get("/api/admin/members/no-such-id").then().statusCode(403);
        }
    }

    /** R3.3 — 학술부원이 자기 id 로 조회해도 403. */
    @Test
    void academicStaffCannotReadOwnDetail() {
        Member self = actors.save(Role.ACADEMIC_STAFF);
        given().header("Authorization", "Bearer " + actors.tokenFor(self))
                .when().get("/api/admin/members/" + self.getId())
                .then().statusCode(403);
    }

    /** 표 "회원 상세" 칸 — 부장·회계부원은 권한 통과(없는 id 는 404). */
    @Test
    void memberReadHoldersPassTheGate() {
        for (Role role : List.of(Role.ACADEMIC_LEAD, Role.PR_LEAD, Role.FINANCE_STAFF)) {
            as(role).when().get("/api/admin/members/no-such-id").then().statusCode(404);
        }
    }

    /** R3.1·R3.5 — 존재하는 회원은 200. 다른 부서 임원, 임기 없는 회원 모두. */
    @Test
    void leadsReadAnyExistingMember() {
        Member otherDept = plainMember("재정임원", "2023000002");
        otherDept.assignTerm(MemberDepartment.FINANCE, MemberTitle.STAFF, 41);
        members.saveAndFlush(otherDept);
        Member noTerm = plainMember("무임기", "2023000003");

        for (Role role : List.of(Role.ACADEMIC_LEAD, Role.PR_LEAD)) {
            String token = actors.token(role);
            for (Member target : List.of(otherDept, noTerm)) {
                given().header("Authorization", "Bearer " + token)
                        .when().get("/api/admin/members/" + target.getId())
                        .then().statusCode(200).body("id", equalTo(target.getId()));
            }
        }
    }

    // ── 표: 대기 목록 ──

    /** 표 "대기 목록" 칸 — 학술부원·홍보부원 403, 부장·회계부원 200. */
    @Test
    void pendingListFollowsMemberRead() {
        for (Role role : List.of(Role.ACADEMIC_STAFF, Role.PR_STAFF)) {
            as(role).when().get("/api/admin/members/pending").then().statusCode(403);
        }
        for (Role role : List.of(Role.ACADEMIC_LEAD, Role.PR_LEAD, Role.FINANCE_STAFF)) {
            as(role).when().get("/api/admin/members/pending").then().statusCode(200);
        }
    }

    // ── 표: 세미나·스터디 목록 ──

    /** 표 "세미나·스터디 목록" 칸 — 학술부원·홍보부원·회계부원·서버관리자 200, 일반 회원 403. */
    @Test
    void seminarAndStudyListsAreOpenToAnyOfficer() {
        for (Role role : List.of(Role.ACADEMIC_STAFF, Role.PR_STAFF, Role.FINANCE_STAFF, Role.SERVER_ADMIN)) {
            String token = actors.token(role);
            for (String resource : List.of("seminars", "studies")) {
                given().header("Authorization", "Bearer " + token)
                        .when().get("/api/admin/" + resource).then().statusCode(200);
            }
        }
        String plain = actors.member();
        for (String resource : List.of("seminars", "studies")) {
            given().header("Authorization", "Bearer " + plain)
                    .when().get("/api/admin/" + resource).then().statusCode(403);
        }
    }

    // ── R2.3·R5.2: 토큰 없음 ──

    @Test
    void anonymousIsRejectedOnEveryList() {
        for (String path : List.of("/api/admin/members", "/api/admin/seminars", "/api/admin/studies")) {
            given().when().get(path).then().statusCode(401);
        }
        given().when().get("/api/admin/members/pending").then().statusCode(401);
        given().when().get("/api/admin/members/some-id").then().statusCode(401);
    }

    // ── 표: 일괄 편집 (R5.3) ──

    /** 학술부원과 홍보부원 모두 members·seminars·studies 세 batch 가 403. *_EDIT 가 없다. */
    @Test
    void staffCannotBatchEdit() {
        for (Role role : List.of(Role.ACADEMIC_STAFF, Role.PR_STAFF)) {
            String token = actors.token(role);
            for (String resource : List.of("members", "seminars", "studies")) {
                given().header("Authorization", "Bearer " + token)
                        .contentType("application/json").body(Map.of("changes", List.of()))
                        .when().patch("/api/admin/" + resource + ":batch")
                        .then().statusCode(403);
            }
        }
    }

    // ── 표: 회원 승인 (R4.3) ──

    /** 학술부장·홍보부장은 대기 목록은 봐도 승인·반려·재등록은 못 한다. */
    @Test
    void academicAndPrLeadsCannotApproveRejectOrReregister() {
        for (Role role : List.of(Role.ACADEMIC_LEAD, Role.PR_LEAD)) {
            for (String action : List.of("approve", "reject", "reregister")) {
                // reject 는 본문을 먼저 읽으므로(@Valid) 본문 없이는 권한 판정 전에 실패한다.
                as(role).contentType("application/json").body(Map.of("reason", "사유"))
                        .when().post("/api/admin/members/any-id/" + action).then().statusCode(403);
            }
        }
    }

    /** 표 "회원 승인" 칸 — 회계부원도 403 (회계부장만 승인한다). */
    @Test
    void financeStaffCannotApprove() {
        as(Role.FINANCE_STAFF).when().post("/api/admin/members/any-id/approve").then().statusCode(403);
    }

    // ── R2.5: 졸업 정보 수정 ──

    /** MEMBER_LIST 만 가진 학술부원도, MEMBER_READ 를 가진 학술부장도 MEMBER_EDIT 는 없다. */
    @Test
    void graduationEditNeedsMemberEdit() {
        for (Role role : List.of(Role.ACADEMIC_STAFF, Role.ACADEMIC_LEAD)) {
            as(role).contentType("application/json").body("{}")
                    .when().put("/api/admin/members/any-id/graduation")
                    .then().statusCode(403);
        }
    }

    // ── 표: 목록 attendanceCode, 정렬 누출 (R5.7·R5.8) ──

    private void codedSeminars() {
        // 기본 순서(startsAt desc)는 최신→오래된 순: 가운데 코드, 가장 작은 코드, 가장 큰 코드.
        // 코드 오름차순은 B,A,C 가 아니라 A,M,Z 라서 기본 순서와 다르다.
        Instant now = Instant.now();
        seminar("최신", now.minusSeconds(3600), "MMMMMM");
        seminar("중간", now.minusSeconds(7200), "AAAAAA");
        seminar("오래됨", now.minusSeconds(10800), "ZZZZZZ");
    }

    /** SEMINAR_READ 가 있는 학술부원·회장은 발급된 코드를 그대로 받는다. */
    @Test
    void holdersOfSeminarReadSeeTheCodes() {
        codedSeminars();
        for (Role role : List.of(Role.ACADEMIC_STAFF, Role.PRESIDENT)) {
            as(role).when().get("/api/admin/seminars")
                    .then().statusCode(200)
                    .body("items.title", contains("최신", "중간", "오래됨"))
                    .body("items.attendanceCode", contains("MMMMMM", "AAAAAA", "ZZZZZZ"));
        }
    }

    /** 홍보부원·회계부원·서버관리자는 키는 남고 값이 null 이다. */
    @Test
    void withoutSeminarReadTheCodesAreNullButKeysRemain() {
        codedSeminars();
        for (Role role : List.of(Role.PR_STAFF, Role.FINANCE_STAFF, Role.SERVER_ADMIN)) {
            as(role).when().get("/api/admin/seminars")
                    .then().statusCode(200)
                    .body("items", hasSize(3))
                    .body("items.attendanceCode", everyItem(nullValue()))
                    .body("items[0].containsKey('attendanceCode')", equalTo(true));
        }
    }

    /** R5.7 — 정렬로 코드의 대소가 새지 않는다: 두 방향 모두 기본 순서이고 값은 전부 null. */
    @Test
    void sortingByAttendanceCodeDoesNotLeakTheOrder() {
        codedSeminars();
        String token = actors.token(Role.PR_STAFF);
        for (String dir : List.of("asc", "desc")) {
            given().header("Authorization", "Bearer " + token)
                    .when().get("/api/admin/seminars?sort=attendanceCode," + dir)
                    .then().statusCode(200)
                    .body("items.title", contains("최신", "중간", "오래됨"))
                    .body("items.attendanceCode", everyItem(nullValue()));
        }
    }

    /** 대조군 — SEMINAR_READ 보유자는 같은 정렬이 실제로 코드 순서를 따른다(위 테스트가 헛돌지 않게). */
    @Test
    void sortingByAttendanceCodeFollowsTheCodeForHolders() {
        codedSeminars();
        String token = actors.token(Role.ACADEMIC_STAFF);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/seminars?sort=attendanceCode,asc")
                .then().statusCode(200).body("items.title", contains("중간", "최신", "오래됨"));
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/seminars?sort=attendanceCode,desc")
                .then().statusCode(200).body("items.title", contains("오래됨", "최신", "중간"));
    }

    // ── R5.5: 학술부원 수기 출석 네 요청 ──

    @Test
    void academicStaffRunsTheManualAttendanceFlow() {
        Member attendee = plainMember("참석자", "2023000009");
        Seminar s = seminar("진행중", Instant.now().minusSeconds(60), null);
        String token = actors.token(Role.ACADEMIC_STAFF);

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/seminars").then().statusCode(200);                       // 1
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/seminars/" + s.getId() + "/attendees")
                .then().statusCode(200);                                                          // 2
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/members").then().statusCode(200);                        // 3
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("memberId", attendee.getId()))
                .when().post("/api/admin/seminars/" + s.getId() + "/attendees")
                .then().statusCode(200).body("list.memberId", hasItem(attendee.getId()));        // 4
    }
}
