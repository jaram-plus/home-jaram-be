package com.jaram.be.admin;

import com.jaram.be.admin.dto.DriveExportResult;
import com.jaram.be.security.authz.Role;
import com.jaram.be.seminar.AttendanceRepository;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R5.10 — 목록의 attendanceCode 를 SEMINAR_READ 로 비워도 내보내기는 코드를 그대로 싣는다.
 * 내보내기는 EXPORT_RUN 보유자(회장·서버관리자)만 부를 수 있고, 서버관리자는 SEMINAR_READ 가 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExportKeepsAttendanceCodeTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired Actors actors;
    @MockitoBean DriveExporter exporter;

    @BeforeEach void setup() {
        RestAssured.port = port;
        reset(exporter);
        when(exporter.export(any(), any(), any())).thenReturn(new DriveExportResult("https://x", "id"));
        attendances.deleteAll();
        seminars.deleteAll();
        seminars.save(Seminar.create("세미나", null, null, Instant.now().minusSeconds(60),
                null, null, "ABCDEF", null, 30, "officer-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportCarriesTheCodeForPresidentAndServerAdmin() {
        for (Role role : List.of(Role.PRESIDENT, Role.SERVER_ADMIN)) {
            reset(exporter);
            when(exporter.export(any(), any(), any())).thenReturn(new DriveExportResult("https://x", "id"));

            given().header("Authorization", "Bearer " + actors.token(role))
                    .contentType("application/json")
                    .body(Map.of("resource", "seminars"))
                    .when().post("/api/admin/export/google-drive")
                    .then().statusCode(200);

            ArgumentCaptor<List<Map<String, Object>>> rows = ArgumentCaptor.forClass(List.class);
            verify(exporter).export(eq("seminars"), rows.capture(), any());
            assertThat(rows.getValue()).hasSize(1);
            assertThat(rows.getValue().get(0)).containsEntry("attendanceCode", "ABCDEF");
        }
    }
}
