package com.jaram.be.study;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * PUT /api/studies/{id} — 개설 때 적은 여덟 칸을 모집 중에 다시 적는다.
 *
 * 지키는 것은 두 가지다. 누가(스터디장 본인 또는 STUDY_EDIT)와 언제(RECRUITING 에서만).
 * 뒤엣것은 상태 규칙이 아니라 약속의 문제다 — 신청자는 이 값을 보고 지원했다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyUpdateTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired StudyWeekRepository weeks;
    @Autowired Actors actors;

    private Member leader;
    private String leaderToken;
    private Study study;

    @BeforeEach void setup() {
        RestAssured.port = port;
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        leader = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr"));
        leaderToken = actors.tokenFor(leader);
        study = recruiting(leader.getId());
    }

    private Member approved(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        return m;
    }

    private Study recruiting(String leaderId) {
        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leaderId);
        s.approve();
        return studies.save(s);
    }

    private Map<String, Object> body() {
        return Map.of(
                "title", "알고리즘 심화",
                "fields", List.of("PS", "Algorithm"),
                "capacity", 8,
                "intro", "심화로 갑니다",
                "schedule", "목 20:00",
                "place", "502호",
                "mode", "온라인",
                "contact", "kakao: jaram");
    }

    private io.restassured.response.Response put(String token, Object payload) {
        return given().header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON).body(payload)
                .when().put("/api/studies/" + study.getId());
    }

    @Test
    void leaderEditsEveryFieldAndGetsTheDetailBack() {
        put(leaderToken, body()).then().statusCode(200)
                .body("title", equalTo("알고리즘 심화"))
                .body("fields", hasItems("PS", "Algorithm"))
                .body("cap", equalTo(8))
                .body("schedule", equalTo("목 20:00"))
                .body("place", equalTo("502호"))
                .body("mode", equalTo("온라인"))
                .body("contact", equalTo("kakao: jaram"))
                .body("intro", equalTo("심화로 갑니다"));

        Study saved = studies.findById(study.getId()).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("알고리즘 심화");
        assertThat(saved.getFields()).containsExactlyInAnyOrder("PS", "Algorithm");
        assertThat(saved.getCapacity()).isEqualTo(8);
        // 상태와 스터디장은 이 손잡이가 건드리지 않는다.
        assertThat(saved.getStatus()).isEqualTo(StudyStatus.RECRUITING);
        assertThat(saved.getLeaderId()).isEqualTo(leader.getId());
    }

    @Test
    void ongoingStudyIsRefused() {
        study.closeRecruiting();
        studies.save(study);

        put(leaderToken, body()).then()
                .statusCode(409).body("code", equalTo("INVALID_STATE"));

        assertThat(studies.findById(study.getId()).orElseThrow().getTitle()).isEqualTo("알고리즘");
    }

    @Test
    void anotherMemberIsRefused() {
        Member other = members.save(approved("남", "2023000002", "other@hanyang.ac.kr"));
        put(actors.tokenFor(other), body()).then().statusCode(403);
    }

    @Test
    void anonymousIsRefused() {
        given().contentType(ContentType.JSON).body(body())
                .when().put("/api/studies/" + study.getId())
                .then().statusCode(401);
    }

    @Test
    void officerWithStudyEditMayEditSomeoneElsesStudy() {
        put(actors.token(Role.ACADEMIC_LEAD), body()).then().statusCode(200);
    }

    /**
     * 임원은 상태 제약을 넘는다 — 스터디장이 스스로 고칠 수 없게 된 것을 고쳐 줄 손이
     * 하나는 있어야 한다. 출석 편집 창을 임원이 넘는 것과 같은 자리다.
     */
    @Test
    void officerMayEditAnOngoingStudy() {
        study.closeRecruiting();
        studies.save(study);

        put(actors.token(Role.ACADEMIC_LEAD), body()).then().statusCode(200)
                .body("title", equalTo("알고리즘 심화"))
                .body("status", equalTo("ONGOING"));
    }

    /** 끝난 스터디는 임원도 막는다. 그 기록을 근거로 한 것이 전부 흔들린다. */
    @Test
    void finishedStudyIsRefusedEvenForOfficer() {
        study.closeRecruiting();
        study.finish();
        studies.save(study);

        put(actors.token(Role.ACADEMIC_LEAD), body()).then()
                .statusCode(409).body("code", equalTo("STUDY_FINISHED"));

        assertThat(studies.findById(study.getId()).orElseThrow().getTitle()).isEqualTo("알고리즘");
    }

    @Test
    void blankTitleIsRejected() {
        java.util.Map<String, Object> bad = new java.util.HashMap<>(body());
        bad.put("title", "  ");
        put(leaderToken, bad).then().statusCode(422);
    }

    @Test
    void emptyFieldsIsRejected() {
        java.util.Map<String, Object> bad = new java.util.HashMap<>(body());
        bad.put("fields", List.of());
        put(leaderToken, bad).then().statusCode(422);
    }

    @Test
    void capacityBelowOneIsRejected() {
        java.util.Map<String, Object> bad = new java.util.HashMap<>(body());
        bad.put("capacity", 0);
        put(leaderToken, bad).then().statusCode(422);
    }

    /**
     * cap 은 상한이 아니라 목표다. 이미 승인된 사람보다 작게 줄여도 막지 않는다 —
     * 줄였다고 확정된 인원을 물릴 이유가 없고, 물리지도 않는다.
     */
    @Test
    void capacityMayDropBelowTheApprovedCount() {
        Member a = members.save(approved("지원1", "2023000003", "a@hanyang.ac.kr"));
        Member b = members.save(approved("지원2", "2023000004", "b@hanyang.ac.kr"));
        approve(a);
        approve(b);

        java.util.Map<String, Object> small = new java.util.HashMap<>(body());
        small.put("capacity", 1);
        put(leaderToken, small).then().statusCode(200)
                .body("cap", equalTo(1))
                .body("cur", equalTo(2));
    }

    private void approve(Member m) {
        StudyApplication app = applications.save(
                StudyApplication.create(study.getId(), m.getId(), "하고 싶습니다"));
        app.approve();
        applications.save(app);
    }
}
