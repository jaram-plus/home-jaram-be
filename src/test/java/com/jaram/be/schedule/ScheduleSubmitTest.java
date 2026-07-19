package com.jaram.be.schedule;

import com.jaram.be.member.Authority;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleSubmitTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired ScheduleRepository schedules;
    @Autowired SeminarRepository seminars;
    @Autowired JwtProvider jwt;

    private String token;   // member-1

    @BeforeEach void setup() {
        RestAssured.port = port;
        schedules.deleteAll();
        seminars.deleteAll();
        token = jwt.generate("member-1", "회원", "a@hanyang.ac.kr", Authority.MEMBER);
    }

    private Schedule lockedWithMyClaim() {
        Schedule s = Schedule.create(Instant.parse("2026-06-27T10:00:00Z"), "IT관 401", "offline", 3);
        s.getSlots().get(0).claim("member-1");
        s.lock();
        return schedules.save(s);
    }

    @Test
    void submitsPendingSeminarUsingScheduleTime() {
        Schedule s = lockedWithMyClaim();
        Map<String, Object> body = Map.of(
                "title", "내 세미나",
                "startsAt", "2099-01-01T00:00:00Z", // 무시
                "place", "무시장소",                  // 무시
                "attendanceCode", "IGNORED");

        String id = given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(body)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/seminar").then().statusCode(201)
                .body("title", equalTo("내 세미나"))
                .body("approvalStatus", equalTo("PENDING"))
                .body("place", equalTo("IT관 401"))
                .body("startsAt", equalTo("2026-06-27T10:00:00Z"))
                .body("scheduleId", equalTo(s.getId()))
                .extract().path("id");

        // attendanceCode는 무시(저장 안 함)
        Seminar saved = seminars.findById(id).orElseThrow();
        assertThat(saved.getAttendanceCode()).isNull();
        // 슬롯에 seminarId 연결됨 — 지연로딩 회피 위해 목록 응답으로 확인
        given().when().get("/api/schedules").then().statusCode(200)
                .body("[0].slots[0].seminarId", equalTo(id));
    }

    @Test
    void submitOnOpenScheduleIs409() {
        Schedule s = Schedule.create(Instant.now(), null, null, 3);
        s.getSlots().get(0).claim("member-1");
        schedules.save(s); // OPEN
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("title", "x", "startsAt", "2026-01-01T00:00:00Z"))
                .when().post("/api/schedules/" + s.getId() + "/slots/0/seminar").then().statusCode(409);
    }

    @Test
    void submitOthersSlotIs403() {
        Schedule s = Schedule.create(Instant.now(), null, null, 3);
        s.getSlots().get(0).claim("someone");
        s.lock();
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("title", "x", "startsAt", "2026-01-01T00:00:00Z"))
                .when().post("/api/schedules/" + s.getId() + "/slots/0/seminar").then().statusCode(403);
    }

    @Test
    void submitTwiceIs409() {
        Schedule s = lockedWithMyClaim();
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("title", "첫제출", "startsAt", "2026-01-01T00:00:00Z"))
                .when().post("/api/schedules/" + s.getId() + "/slots/0/seminar").then().statusCode(201);
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("title", "두번째", "startsAt", "2026-01-01T00:00:00Z"))
                .when().post("/api/schedules/" + s.getId() + "/slots/0/seminar").then().statusCode(409);
    }

    /** 세미나가 삭제되면 슬롯의 seminarId도 끊긴다 — 안 그러면 취소도 재제출도 막힌다. */
    @Test
    void deletingSeminarDetachesSlot() {
        Schedule s = lockedWithMyClaim();
        String id = given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("title", "삭제될 세미나", "startsAt", "2026-01-01T00:00:00Z"))
                .when().post("/api/schedules/" + s.getId() + "/slots/0/seminar").then().statusCode(201)
                .extract().path("id");

        String officer = jwt.generate("officer-1", "임원", "of@hanyang.ac.kr", Authority.OFFICER);
        given().header("Authorization", "Bearer " + officer)
                .contentType("application/json").body(Map.of("deletes", java.util.List.of(id)))
                .when().patch("/api/admin/seminars:batch").then().statusCode(200)
                .body("deleted", equalTo(java.util.List.of(id)));

        // 슬롯은 점유는 유지하고 세미나 링크만 잃는다
        given().when().get("/api/schedules").then().statusCode(200)
                .body("[0].slots[0].member.id", equalTo("member-1"))
                .body("[0].slots[0].seminarId", equalTo(null));

        // 그래서 다시 제출할 수 있다
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("title", "재제출", "startsAt", "2026-01-01T00:00:00Z"))
                .when().post("/api/schedules/" + s.getId() + "/slots/0/seminar").then().statusCode(201);
    }

    @Test
    void submitWithoutTitleIs422() {
        Schedule s = lockedWithMyClaim();
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("startsAt", "2026-01-01T00:00:00Z"))
                .when().post("/api/schedules/" + s.getId() + "/slots/0/seminar").then().statusCode(422);
    }
}
