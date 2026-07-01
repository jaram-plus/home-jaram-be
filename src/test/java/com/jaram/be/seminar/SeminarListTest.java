package com.jaram.be.seminar;

import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarListTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
    }

    @Test
    void listsNewestFirstWithDerivedFieldsAndNoAttendanceCode() {
        // 2026-06-27T10:00:00Z == 2026-06-27 19:00 KST, a Saturday
        Instant past = Instant.parse("2026-06-27T10:00:00Z");
        seminars.save(Seminar.create("지난 세미나", "김연사", "주제A", past,
                "IT관 401", "offline", "SECRET", "https://m.example.com/a", 30, "officer-1"));
        seminars.save(Seminar.create("다음 세미나", null, null, Instant.now().plus(2, ChronoUnit.DAYS),
                null, null, "SECRET2", null, null, "officer-1"));

        given().when().get("/api/seminars").then().statusCode(200)
                .body("size()", equalTo(2))
                // newest (future) first
                .body("[0].title", equalTo("다음 세미나"))
                .body("[0].status", equalTo("UPCOMING"))
                // past seminar derived display fields (Asia/Seoul)
                .body("[1].title", equalTo("지난 세미나"))
                .body("[1].status", equalTo("ENDED"))
                .body("[1].day", equalTo("27"))
                .body("[1].month", equalTo("6월"))
                .body("[1].weekday", equalTo("토"))
                .body("[1].time", equalTo("19:00"))
                .body("[1].place", equalTo("IT관 401"))
                .body("[1].materialUrl", equalTo("https://m.example.com/a"))
                .body("[1].capacity", equalTo(30))
                // attendanceCode must never be serialized
                .body("[0]", not(hasKey("attendanceCode")))
                .body("[1]", not(hasKey("attendanceCode")));
    }

    @Test
    void emptyDatabaseReturnsEmptyArray() {
        given().when().get("/api/seminars").then().statusCode(200).body("size()", equalTo(0));
    }
}
