package com.jaram.be.admin;

import com.jaram.be.security.authz.Role;
import com.jaram.be.study.Study;
import com.jaram.be.study.StudyRepository;
import com.jaram.be.study.StudyStatus;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
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

/**
 * 전이 API 는 한 칸씩만 간다. 건너뛰거나 되돌리는 손은 여기 하나뿐이다 — 스터디장이
 * '모집 완료'를 잘못 눌렀거나, 졸업으로 스터디장이 사라졌을 때 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminStudyBatchTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired StudyRepository studies;
    @Autowired Actors actors;

    private Study study;

    @BeforeEach void setup() {
        RestAssured.port = port;
        studies.deleteAll();
        study = Study.create("알고리즘", List.of("PS"), 6,
                "화", "401", "오프라인", "소개", "010", "leader-id");
        study.approve();
        study.closeRecruiting();
        study.finish();
        studies.save(study);          // FINISHED
    }

    private io.restassured.response.Response patch(Object status) {
        return given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_LEAD))
                .contentType("application/json")
                .body(Map.of("updates", List.of(Map.of(
                        "id", study.getId(),
                        "version", study.getVersion(),
                        "fields", Map.of("status", status)))))
                .when().patch("/api/admin/studies:batch");
    }

    @Test
    void anOfficerMayPutAFinishedStudyBackIntoRecruiting() {
        patch("RECRUITING").then().statusCode(200)
                .body("updated.size()", equalTo(1))
                .body("errors.size()", equalTo(0));
        assertThat(studies.findById(study.getId()).orElseThrow().getStatus())
                .isEqualTo(StudyStatus.RECRUITING);
    }

    @Test
    void anUnknownStatusIsRefusedAndNothingChanges() {
        // 일괄 편집은 행별 부분 성공이라 전체 응답은 200 이고, 실패는 errors 에 실린다.
        patch("NOT_A_STATUS").then().statusCode(200)
                .body("updated.size()", equalTo(0))
                .body("errors.size()", equalTo(1))
                .body("errors[0].fieldErrors.status", equalTo("허용되지 않은 값입니다."));
        assertThat(studies.findById(study.getId()).orElseThrow().getStatus())
                .isEqualTo(StudyStatus.FINISHED);
    }
}
