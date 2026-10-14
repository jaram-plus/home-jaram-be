# JARAM BE Phase 3 — Seminar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the seminar feature (UC-S1..S4) — list, create, attendance check, attendance roster — each verified against the OpenAPI contract.

**Architecture:** New package-by-feature `com.jaram.be.seminar` following the existing layered shape (Controller → Service → Repository → Entity), mirroring `people`/`admin`. Two entities: `Seminar` (status NOT stored — derived from `startsAt` + an attendance window) and `Attendance` (one row per member per seminar, unique). All four endpoints already have route authorization wired in `SecurityConfig` (see Global Constraints) — **no security change is required**.

**Tech Stack:** Spring Boot 3.4.1, Java 21, Spring Data JPA, PostgreSQL (Testcontainers via `support.PostgresTest`), Spring Security (existing JWT filter), Bean Validation, JUnit 5, REST-assured, swagger-request-validator (Atlassian) for contract assertions.

## Global Constraints

- **Repo:** This plan runs in **this repo** (`home-jaram-be`, package `com.jaram.be`). Repo root is the working directory; all paths below are relative to it.
- **Contract is law:** `docs/api/openapi.yaml` (OpenAPI 3.1) is the single source of truth; the test copy is `src/main/resources/openapi/openapi.yaml`. Controller request/response DTOs MUST match its schemas exactly. Run `./scripts/sync-openapi.sh` before starting.
- **Java package root:** `com.jaram.be`. **Java:** 21. **Spring Boot:** 3.4.1. **Build:** Gradle Groovy DSL.
- **No Lombok.** Entities use a `protected` no-arg constructor, a static factory, `String id = UUID.randomUUID().toString()`, plain getters.
- **Enum wire values are fixed by FE.** `SeminarStatus` wire values are lowercase (`upcoming`/`ongoing`/`ended`); the Java enum constants are *named* lowercase so `@Enumerated(EnumType.STRING)` and Jackson round-trip without a converter (same pattern as `MemberCategory`).
- **Error model (fixed):** all business errors throw `com.jaram.be.common.ApiException(HttpStatus, code, message[, fieldErrors])`; the global handler serializes the `{ code, message, fieldErrors }` envelope. For this phase: seminar not found → 404 `NOT_FOUND`; attendance code wrong or window closed → 400 `INVALID_CODE`; bean-validation failure → 422 `VALIDATION`.
- **Security (already wired, do not re-add):** `SecurityConfig.filterChain` already contains:
  - `.requestMatchers(HttpMethod.GET, "/api/people", "/api/seminars", "/api/studies").permitAll()`
  - `.requestMatchers(HttpMethod.POST, "/api/seminars").hasAuthority("OFFICER")`
  - `.requestMatchers(HttpMethod.GET, "/api/seminars/*/roster").hasAuthority("OFFICER")`
  - `POST /api/seminars/{id}/attend` falls through to `.anyRequest().authenticated()` (any logged-in member). No edit to `SecurityConfig` is needed in this phase.
- **Principal:** authenticated handlers inject `@AuthenticationPrincipal com.jaram.be.security.CurrentMember me` — `CurrentMember(String id, String name, String email, Authority authority)`, populated by `JwtAuthFilter`. In tests, mint tokens with `jwt.generate(id, name, email, Authority)` (see `AdminMemberTest`).
- **Derived display fields** (`day`/`month`/`weekday`/`time`/`status` and the `HH:mm` attendance times) are computed in the `Asia/Seoul` zone.

---

## Contract decisions locked for P3 (resolve spec gaps)

The spec (§4.4) leaves the attendance window as "구현 계획에서 확정". Locked here:

- **Attendance window** = `seminar.attendance-window-minutes`, default **120**. Status derivation: `now < startsAt` → `upcoming`; `startsAt ≤ now ≤ startsAt + window` → `ongoing`; otherwise → `ended`. Attendance (`POST .../attend`) is accepted **only** while `ongoing`.
- **Seminar list order:** newest first — `findAllByOrderByStartsAtDesc()`.
- **`attendanceCode`** is persisted on `Seminar` but NEVER serialized (no field on `SeminarResponse`). Exact-string match for attend.
- **Idempotent attend:** if the member already has an `Attendance` row for the seminar, return **200** with the original attendance time (no new row, no error).
- **Derived display formats** (Asia/Seoul): `day` = day-of-month, no leading zero (`"27"`); `month` = `"{monthValue}월"` (`"6월"`); `weekday` = single Korean char from `["월","화","수","목","금","토","일"]` indexed by `DayOfWeek.getValue()-1`; `time` and attendance `at` = `"HH:mm"` (24h, zero-padded).
- **`RosterResponse.cap`** = `seminar.capacity`, or **0** when capacity is null (schema requires an integer).
- **`startsAt`** on the wire is ISO-8601 UTC (`Instant.toString()`, e.g. `2026-06-27T10:00:00Z`); Jackson parses the request string straight to `Instant`.

---

## File Structure

```
src/main/java/com/jaram/be/seminar/
  Seminar.java                 # @Entity, no stored status, static factory
  Attendance.java              # @Entity, unique(seminarId, memberId)
  SeminarStatus.java           # enum upcoming/ongoing/ended + static derivation
  SeminarRepository.java       # JpaRepository<Seminar,String>
  AttendanceRepository.java    # JpaRepository<Attendance,String>
  SeminarService.java          # list / create / attend / roster
  SeminarController.java       # 4 handlers, 1:1 with the OpenAPI operations
  dto/
    SeminarResponse.java       # == schema Seminar
    SeminarCreateRequest.java  # == schema SeminarCreateRequest
    AttendRequest.java         # == schema AttendRequest
    AttendResult.java          # == schema AttendResult
    RosterResponse.java        # == schema RosterResponse
    RosterEntry.java           # == schema RosterEntry

src/test/java/com/jaram/be/seminar/
  SeminarRepositoryTest.java   # @DataJpaTest
  SeminarStatusTest.java       # pure unit test of derivation
  SeminarListTest.java         # UC-S1
  SeminarCreateTest.java       # UC-S3
  SeminarAttendTest.java       # UC-S2
  SeminarRosterTest.java       # UC-S4
src/test/java/com/jaram/be/contract/
  SeminarContractTest.java     # OpenAPI conformance, all 4 operations
```

---

### Task 1: Seminar + Attendance entities, enum stub, repositories

**Files:**
- Create: `src/main/java/com/jaram/be/seminar/SeminarStatus.java`
- Create: `src/main/java/com/jaram/be/seminar/Seminar.java`
- Create: `src/main/java/com/jaram/be/seminar/Attendance.java`
- Create: `src/main/java/com/jaram/be/seminar/SeminarRepository.java`
- Create: `src/main/java/com/jaram/be/seminar/AttendanceRepository.java`
- Test: `src/test/java/com/jaram/be/seminar/SeminarRepositoryTest.java`

**Interfaces:**
- Produces:
  - `enum SeminarStatus { upcoming, ongoing, ended }` (derivation added in Task 2)
  - `Seminar.create(String title, String speaker, String topic, Instant startsAt, String place, String mode, String attendanceCode, String materialUrl, Integer capacity, String createdById) -> Seminar`; getters `getId/getTitle/getSpeaker/getTopic/getStartsAt/getPlace/getMode/getAttendanceCode/getMaterialUrl/getCapacity/getCreatedById/getCreatedAt`
  - `Attendance.create(String seminarId, String memberId, Instant at) -> Attendance`; getters `getId/getSeminarId/getMemberId/getAt`
  - `SeminarRepository extends JpaRepository<Seminar,String>` with `List<Seminar> findAllByOrderByStartsAtDesc()`
  - `AttendanceRepository extends JpaRepository<Attendance,String>` with `Optional<Attendance> findBySeminarIdAndMemberId(String seminarId, String memberId)` and `List<Attendance> findBySeminarIdOrderByAtAsc(String seminarId)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/jaram/be/seminar/SeminarRepositoryTest.java`:

```java
package com.jaram.be.seminar;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SeminarRepositoryTest extends PostgresTest {

    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;

    @Test
    void listsSeminarsNewestFirst() {
        Instant base = Instant.parse("2026-06-27T10:00:00Z");
        seminars.save(Seminar.create("older", null, null, base.minus(2, ChronoUnit.DAYS),
                null, null, "C1", null, null, "officer-1"));
        seminars.save(Seminar.create("newer", null, null, base,
                null, null, "C2", null, 30, "officer-1"));

        List<Seminar> all = seminars.findAllByOrderByStartsAtDesc();

        assertThat(all).extracting(Seminar::getTitle).containsExactly("newer", "older");
        assertThat(all.get(0).getId()).isNotBlank();
        assertThat(all.get(0).getCapacity()).isEqualTo(30);
        assertThat(all.get(0).getAttendanceCode()).isEqualTo("C2");
    }

    @Test
    void attendanceIsUniquePerMemberPerSeminar() {
        Seminar s = seminars.save(Seminar.create("s", null, null, Instant.now(),
                null, null, "CODE", null, null, "officer-1"));
        attendances.save(Attendance.create(s.getId(), "member-1", Instant.now()));

        assertThatThrownBy(() ->
                attendances.saveAndFlush(Attendance.create(s.getId(), "member-1", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsExistingAttendanceAndRosterInOrder() {
        Seminar s = seminars.save(Seminar.create("s", null, null, Instant.now(),
                null, null, "CODE", null, null, "officer-1"));
        Instant t1 = Instant.parse("2026-06-27T10:01:00Z");
        Instant t2 = Instant.parse("2026-06-27T10:02:00Z");
        attendances.save(Attendance.create(s.getId(), "m2", t2));
        attendances.save(Attendance.create(s.getId(), "m1", t1));

        assertThat(attendances.findBySeminarIdAndMemberId(s.getId(), "m1")).isPresent();
        assertThat(attendances.findBySeminarIdAndMemberId(s.getId(), "absent")).isEmpty();
        assertThat(attendances.findBySeminarIdOrderByAtAsc(s.getId()))
                .extracting(Attendance::getMemberId).containsExactly("m1", "m2");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarRepositoryTest'`
Expected: FAIL — compilation error, `Seminar` / `Attendance` / repositories do not exist.

- [ ] **Step 3: Implement enum stub, entities, repositories**

Create `src/main/java/com/jaram/be/seminar/SeminarStatus.java`:

```java
package com.jaram.be.seminar;

// enum name == JSON wire value (upcoming/ongoing/ended). Server-derived; never stored.
public enum SeminarStatus { upcoming, ongoing, ended }
```

Create `src/main/java/com/jaram/be/seminar/Seminar.java`:

```java
package com.jaram.be.seminar;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seminar")
public class Seminar {

    @Id
    private String id;

    private String title;
    private String speaker;
    private String topic;
    private Instant startsAt;
    private String place;
    private String mode;
    private String attendanceCode;   // never serialized to clients
    private String materialUrl;
    private Integer capacity;

    private String createdById;
    private Instant createdAt = Instant.now();

    protected Seminar() { }

    public static Seminar create(String title, String speaker, String topic, Instant startsAt,
                                 String place, String mode, String attendanceCode,
                                 String materialUrl, Integer capacity, String createdById) {
        Seminar s = new Seminar();
        s.id = UUID.randomUUID().toString();
        s.title = title;
        s.speaker = speaker;
        s.topic = topic;
        s.startsAt = startsAt;
        s.place = place;
        s.mode = mode;
        s.attendanceCode = attendanceCode;
        s.materialUrl = materialUrl;
        s.capacity = capacity;
        s.createdById = createdById;
        s.createdAt = Instant.now();
        return s;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getSpeaker() { return speaker; }
    public String getTopic() { return topic; }
    public Instant getStartsAt() { return startsAt; }
    public String getPlace() { return place; }
    public String getMode() { return mode; }
    public String getAttendanceCode() { return attendanceCode; }
    public String getMaterialUrl() { return materialUrl; }
    public Integer getCapacity() { return capacity; }
    public String getCreatedById() { return createdById; }
    public Instant getCreatedAt() { return createdAt; }
}
```

Create `src/main/java/com/jaram/be/seminar/Attendance.java`:

```java
package com.jaram.be.seminar;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attendance",
       uniqueConstraints = @UniqueConstraint(columnNames = {"seminarId", "memberId"}))
public class Attendance {

    @Id
    private String id;

    private String seminarId;
    private String memberId;
    private Instant at;

    protected Attendance() { }

    public static Attendance create(String seminarId, String memberId, Instant at) {
        Attendance a = new Attendance();
        a.id = UUID.randomUUID().toString();
        a.seminarId = seminarId;
        a.memberId = memberId;
        a.at = at;
        return a;
    }

    public String getId() { return id; }
    public String getSeminarId() { return seminarId; }
    public String getMemberId() { return memberId; }
    public Instant getAt() { return at; }
}
```

Create `src/main/java/com/jaram/be/seminar/SeminarRepository.java`:

```java
package com.jaram.be.seminar;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SeminarRepository extends JpaRepository<Seminar, String> {
    List<Seminar> findAllByOrderByStartsAtDesc();
}
```

Create `src/main/java/com/jaram/be/seminar/AttendanceRepository.java`:

```java
package com.jaram.be.seminar;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, String> {
    Optional<Attendance> findBySeminarIdAndMemberId(String seminarId, String memberId);
    List<Attendance> findBySeminarIdOrderByAtAsc(String seminarId);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarRepositoryTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jaram/be/seminar src/test/java/com/jaram/be/seminar/SeminarRepositoryTest.java
git commit -m "feat: add seminar and attendance entities and repositories"
```

---

### Task 2: SeminarStatus derivation

**Files:**
- Modify: `src/main/java/com/jaram/be/seminar/SeminarStatus.java`
- Test: `src/test/java/com/jaram/be/seminar/SeminarStatusTest.java`

**Interfaces:**
- Consumes: `enum SeminarStatus` (Task 1).
- Produces: `static SeminarStatus SeminarStatus.of(Instant startsAt, Instant now, long windowMinutes)` — `now < startsAt` → `upcoming`; `startsAt ≤ now ≤ startsAt+window` → `ongoing`; else `ended`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/jaram/be/seminar/SeminarStatusTest.java`:

```java
package com.jaram.be.seminar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SeminarStatusTest {

    private final Instant start = Instant.parse("2026-06-27T10:00:00Z");

    @Test
    void beforeStartIsUpcoming() {
        assertThat(SeminarStatus.of(start, start.minus(1, ChronoUnit.MINUTES), 120))
                .isEqualTo(SeminarStatus.upcoming);
    }

    @Test
    void atStartAndWithinWindowIsOngoing() {
        assertThat(SeminarStatus.of(start, start, 120)).isEqualTo(SeminarStatus.ongoing);
        assertThat(SeminarStatus.of(start, start.plus(119, ChronoUnit.MINUTES), 120))
                .isEqualTo(SeminarStatus.ongoing);
    }

    @Test
    void atWindowEdgeIsOngoingAndAfterIsEnded() {
        assertThat(SeminarStatus.of(start, start.plus(120, ChronoUnit.MINUTES), 120))
                .isEqualTo(SeminarStatus.ongoing);
        assertThat(SeminarStatus.of(start, start.plus(121, ChronoUnit.MINUTES), 120))
                .isEqualTo(SeminarStatus.ended);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarStatusTest'`
Expected: FAIL — `of` method not defined.

- [ ] **Step 3: Add the derivation method**

Replace `src/main/java/com/jaram/be/seminar/SeminarStatus.java` with:

```java
package com.jaram.be.seminar;

import java.time.Instant;

// enum name == JSON wire value (upcoming/ongoing/ended). Server-derived; never stored.
public enum SeminarStatus {
    upcoming, ongoing, ended;

    /**
     * now < startsAt           -> upcoming
     * startsAt <= now <= +win  -> ongoing  (attendance allowed only here)
     * else                     -> ended
     */
    public static SeminarStatus of(Instant startsAt, Instant now, long windowMinutes) {
        if (now.isBefore(startsAt)) {
            return upcoming;
        }
        if (!now.isAfter(startsAt.plusSeconds(windowMinutes * 60))) {
            return ongoing;
        }
        return ended;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarStatusTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jaram/be/seminar/SeminarStatus.java src/test/java/com/jaram/be/seminar/SeminarStatusTest.java
git commit -m "feat: derive seminar status from start time and attendance window"
```

---

### Task 3: UC-S1 — list seminars (`GET /api/seminars`)

**Files:**
- Create: `src/main/java/com/jaram/be/seminar/dto/SeminarResponse.java`
- Create: `src/main/java/com/jaram/be/seminar/SeminarService.java`
- Create: `src/main/java/com/jaram/be/seminar/SeminarController.java`
- Test: `src/test/java/com/jaram/be/seminar/SeminarListTest.java`

**Interfaces:**
- Consumes: `SeminarRepository.findAllByOrderByStartsAtDesc()`, `SeminarStatus.of(...)` (Tasks 1–2).
- Produces:
  - `record SeminarResponse(String id, String title, String speaker, String topic, String startsAt, String day, String month, String weekday, String time, String place, String mode, SeminarStatus status, String materialUrl, Integer capacity)`
  - `SeminarService.list() -> List<SeminarResponse>` (also defines the private `toResponse(Seminar)` mapper + `ZoneId SEOUL` used by later tasks).
  - `SeminarController` mapping `GET /api/seminars`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/jaram/be/seminar/SeminarListTest.java`:

```java
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
                .body("[0].status", equalTo("upcoming"))
                // past seminar derived display fields (Asia/Seoul)
                .body("[1].title", equalTo("지난 세미나"))
                .body("[1].status", equalTo("ended"))
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarListTest'`
Expected: FAIL — `SeminarResponse` / `SeminarService` / `SeminarController` do not exist.

- [ ] **Step 3: Implement DTO, service, controller**

Create `src/main/java/com/jaram/be/seminar/dto/SeminarResponse.java`:

```java
package com.jaram.be.seminar.dto;

import com.jaram.be.seminar.SeminarStatus;

// Matches OpenAPI schema Seminar. attendanceCode is intentionally absent.
public record SeminarResponse(
        String id,
        String title,
        String speaker,
        String topic,
        String startsAt,
        String day,
        String month,
        String weekday,
        String time,
        String place,
        String mode,
        SeminarStatus status,
        String materialUrl,
        Integer capacity
) { }
```

Create `src/main/java/com/jaram/be/seminar/SeminarService.java`:

```java
package com.jaram.be.seminar;

import com.jaram.be.seminar.dto.SeminarResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * UC-S1..S4. Seminar.status and the day/month/weekday/time display fields are
 * derived here (never stored); attendanceCode is never exposed. All display
 * formatting is done in the Asia/Seoul zone.
 */
@Service
public class SeminarService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] WEEKDAYS = {"월", "화", "수", "목", "금", "토", "일"};

    private final SeminarRepository seminars;
    private final long windowMinutes;

    public SeminarService(SeminarRepository seminars,
                          @Value("${seminar.attendance-window-minutes:120}") long windowMinutes) {
        this.seminars = seminars;
        this.windowMinutes = windowMinutes;
    }

    @Transactional(readOnly = true)
    public List<SeminarResponse> list() {
        return seminars.findAllByOrderByStartsAtDesc().stream().map(this::toResponse).toList();
    }

    SeminarResponse toResponse(Seminar s) {
        ZonedDateTime t = s.getStartsAt().atZone(SEOUL);
        return new SeminarResponse(
                s.getId(),
                s.getTitle(),
                s.getSpeaker(),
                s.getTopic(),
                s.getStartsAt().toString(),
                String.valueOf(t.getDayOfMonth()),
                t.getMonthValue() + "월",
                WEEKDAYS[t.getDayOfWeek().getValue() - 1],
                t.format(HHMM),
                s.getPlace(),
                s.getMode(),
                SeminarStatus.of(s.getStartsAt(), Instant.now(), windowMinutes),
                s.getMaterialUrl(),
                s.getCapacity());
    }
}
```

Create `src/main/java/com/jaram/be/seminar/SeminarController.java`:

```java
package com.jaram.be.seminar;

import com.jaram.be.seminar.dto.SeminarResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/seminars")
public class SeminarController {

    private final SeminarService service;

    public SeminarController(SeminarService service) { this.service = service; }

    @GetMapping
    public List<SeminarResponse> list() { return service.list(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarListTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jaram/be/seminar src/test/java/com/jaram/be/seminar/SeminarListTest.java
git commit -m "feat: add seminar listing endpoint (UC-S1)"
```

---

### Task 4: UC-S3 — create seminar (`POST /api/seminars`, officer)

**Files:**
- Create: `src/main/java/com/jaram/be/seminar/dto/SeminarCreateRequest.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java` (add `create`)
- Modify: `src/main/java/com/jaram/be/seminar/SeminarController.java` (add `POST`)
- Test: `src/test/java/com/jaram/be/seminar/SeminarCreateTest.java`

**Interfaces:**
- Consumes: `SeminarRepository.save`, `toResponse(Seminar)` (Task 3), `CurrentMember.id()`.
- Produces:
  - `record SeminarCreateRequest(@NotBlank String title, String speaker, String topic, @NotNull Instant startsAt, String place, String mode, String attendanceCode, String materialUrl, Integer capacity)`
  - `SeminarService.create(SeminarCreateRequest req, String createdById) -> SeminarResponse`
  - `SeminarController` mapping `POST /api/seminars` → 201.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/jaram/be/seminar/SeminarCreateTest.java`:

```java
package com.jaram.be.seminar;

import com.jaram.be.member.Authority;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarCreateTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired JwtProvider jwt;

    private String officerToken;
    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
        memberToken = jwt.generate("member-1", "회원", "member@hanyang.ac.kr", Authority.MEMBER);
    }

    @Test
    void officerCreatesSeminarAndCodeIsNotReturned() {
        Map<String, Object> body = Map.of(
                "title", "새 세미나",
                "speaker", "이연사",
                "startsAt", "2026-07-01T10:00:00Z",
                "place", "IT관 401",
                "attendanceCode", "JOIN123",
                "capacity", 40);

        String id = given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(body)
                .when().post("/api/seminars")
                .then().statusCode(201)
                .body("title", equalTo("새 세미나"))
                .body("status", equalTo("upcoming"))
                .body("capacity", equalTo(40))
                .body("$", not(hasKey("attendanceCode")))
                .extract().path("id");

        // persisted with the (hidden) attendance code
        org.assertj.core.api.Assertions.assertThat(
                seminars.findById(id).orElseThrow().getAttendanceCode()).isEqualTo("JOIN123");
    }

    @Test
    void missingTitleReturns422() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("startsAt", "2026-07-01T10:00:00Z"))
                .when().post("/api/seminars")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void memberCannotCreateSeminar() {
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json")
                .body(Map.of("title", "x", "startsAt", "2026-07-01T10:00:00Z"))
                .when().post("/api/seminars")
                .then().statusCode(403).body("code", equalTo("FORBIDDEN"));
    }

    @Test
    void anonymousCannotCreateSeminar() {
        given().contentType("application/json")
                .body(Map.of("title", "x", "startsAt", "2026-07-01T10:00:00Z"))
                .when().post("/api/seminars")
                .then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarCreateTest'`
Expected: FAIL — `SeminarCreateRequest` and the `POST` handler do not exist.

- [ ] **Step 3: Implement DTO, service method, controller mapping**

Create `src/main/java/com/jaram/be/seminar/dto/SeminarCreateRequest.java`:

```java
package com.jaram.be.seminar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

// Matches OpenAPI schema SeminarCreateRequest. attendanceCode is stored, never echoed back.
public record SeminarCreateRequest(
        @NotBlank String title,
        String speaker,
        String topic,
        @NotNull Instant startsAt,
        String place,
        String mode,
        String attendanceCode,
        String materialUrl,
        Integer capacity
) { }
```

Add to `SeminarService` (new import + method; reuse the existing `toResponse`):

```java
// add import
import com.jaram.be.seminar.dto.SeminarCreateRequest;

// add method
@Transactional
public SeminarResponse create(SeminarCreateRequest req, String createdById) {
    Seminar saved = seminars.save(Seminar.create(
            req.title(), req.speaker(), req.topic(), req.startsAt(),
            req.place(), req.mode(), req.attendanceCode(),
            req.materialUrl(), req.capacity(), createdById));
    return toResponse(saved);
}
```

Add to `SeminarController` (new imports + handler):

```java
// add imports
import com.jaram.be.seminar.dto.SeminarCreateRequest;
import com.jaram.be.security.CurrentMember;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;

// add handler
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public SeminarResponse create(@Valid @RequestBody SeminarCreateRequest req,
                              @AuthenticationPrincipal CurrentMember me) {
    return service.create(req, me.id());
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarCreateTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jaram/be/seminar src/test/java/com/jaram/be/seminar/SeminarCreateTest.java
git commit -m "feat: add seminar creation endpoint (UC-S3)"
```

---

### Task 5: UC-S2 — attendance check (`POST /api/seminars/{id}/attend`, member)

**Files:**
- Create: `src/main/java/com/jaram/be/seminar/dto/AttendRequest.java`
- Create: `src/main/java/com/jaram/be/seminar/dto/AttendResult.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java` (add `attend`, `AttendanceRepository` dependency)
- Modify: `src/main/java/com/jaram/be/seminar/SeminarController.java` (add attend handler)
- Test: `src/test/java/com/jaram/be/seminar/SeminarAttendTest.java`

**Interfaces:**
- Consumes: `SeminarRepository.findById`, `AttendanceRepository.findBySeminarIdAndMemberId/save`, `SeminarStatus.of`, `CurrentMember.id()`.
- Produces:
  - `record AttendRequest(@NotBlank String code)`
  - `record AttendResult(String seminarId, String at)`
  - `SeminarService.attend(String seminarId, String memberId, String code) -> AttendResult`
  - `SeminarController` mapping `POST /api/seminars/{id}/attend`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/jaram/be/seminar/SeminarAttendTest.java`:

```java
package com.jaram.be.seminar;

import com.jaram.be.member.Authority;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarAttendTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired JwtProvider jwt;

    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        memberToken = jwt.generate("member-1", "회원", "member@hanyang.ac.kr", Authority.MEMBER);
    }

    private Seminar ongoing(String code) {
        return seminars.save(Seminar.create("ongoing", null, null,
                Instant.now().minus(1, ChronoUnit.MINUTES), null, null, code, null, null, "officer-1"));
    }

    @Test
    void memberAttendsOngoingSeminarWithCorrectCode() {
        Seminar s = ongoing("JOIN123");
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", "JOIN123"))
                .when().post("/api/seminars/" + s.getId() + "/attend")
                .then().statusCode(200)
                .body("seminarId", equalTo(s.getId()))
                .body("at", matchesPattern("\\d{2}:\\d{2}"));

        org.assertj.core.api.Assertions.assertThat(
                attendances.findBySeminarIdAndMemberId(s.getId(), "member-1")).isPresent();
    }

    @Test
    void duplicateAttendanceIsIdempotentSuccess() {
        Seminar s = ongoing("JOIN123");
        for (int i = 0; i < 2; i++) {
            given().header("Authorization", "Bearer " + memberToken)
                    .contentType("application/json").body(Map.of("code", "JOIN123"))
                    .when().post("/api/seminars/" + s.getId() + "/attend")
                    .then().statusCode(200);
        }
        org.assertj.core.api.Assertions.assertThat(
                attendances.findBySeminarIdOrderByAtAsc(s.getId())).hasSize(1);
    }

    @Test
    void wrongCodeReturns400InvalidCode() {
        Seminar s = ongoing("JOIN123");
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", "WRONG"))
                .when().post("/api/seminars/" + s.getId() + "/attend")
                .then().statusCode(400).body("code", equalTo("INVALID_CODE"));
    }

    @Test
    void attendOutsideWindowReturns400InvalidCode() {
        Seminar upcoming = seminars.save(Seminar.create("future", null, null,
                Instant.now().plus(1, ChronoUnit.DAYS), null, null, "JOIN123", null, null, "officer-1"));
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", "JOIN123"))
                .when().post("/api/seminars/" + upcoming.getId() + "/attend")
                .then().statusCode(400).body("code", equalTo("INVALID_CODE"));
    }

    @Test
    void unknownSeminarReturns404() {
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", "JOIN123"))
                .when().post("/api/seminars/does-not-exist/attend")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    @Test
    void anonymousCannotAttend() {
        Seminar s = ongoing("JOIN123");
        given().contentType("application/json").body(Map.of("code", "JOIN123"))
                .when().post("/api/seminars/" + s.getId() + "/attend")
                .then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarAttendTest'`
Expected: FAIL — `AttendRequest` / `AttendResult` / attend handler do not exist.

- [ ] **Step 3: Implement DTOs, service method, controller mapping**

Create `src/main/java/com/jaram/be/seminar/dto/AttendRequest.java`:

```java
package com.jaram.be.seminar.dto;

import jakarta.validation.constraints.NotBlank;

// Matches OpenAPI schema AttendRequest.
public record AttendRequest(@NotBlank String code) { }
```

Create `src/main/java/com/jaram/be/seminar/dto/AttendResult.java`:

```java
package com.jaram.be.seminar.dto;

// Matches OpenAPI schema AttendResult. at is the HH:mm display of the attendance time.
public record AttendResult(String seminarId, String at) { }
```

Update `SeminarService` — inject `AttendanceRepository` and add `attend`. The constructor gains a parameter:

```java
// add imports
import com.jaram.be.common.ApiException;
import com.jaram.be.seminar.dto.AttendResult;
import org.springframework.http.HttpStatus;

// add field
private final AttendanceRepository attendances;

// replace the constructor with this (adds the attendances dependency)
public SeminarService(SeminarRepository seminars,
                      AttendanceRepository attendances,
                      @Value("${seminar.attendance-window-minutes:120}") long windowMinutes) {
    this.seminars = seminars;
    this.attendances = attendances;
    this.windowMinutes = windowMinutes;
}

// add method
@Transactional
public AttendResult attend(String seminarId, String memberId, String code) {
    Seminar s = seminars.findById(seminarId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다."));

    Attendance existing = attendances.findBySeminarIdAndMemberId(seminarId, memberId).orElse(null);
    if (existing != null) {
        return new AttendResult(seminarId, formatTime(existing.getAt()));  // idempotent
    }

    boolean ongoing = SeminarStatus.of(s.getStartsAt(), Instant.now(), windowMinutes) == SeminarStatus.ongoing;
    if (!ongoing || !s.getAttendanceCode().equals(code)) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CODE", "출석 코드가 올바르지 않습니다.");
    }

    Attendance saved = attendances.save(Attendance.create(seminarId, memberId, Instant.now()));
    return new AttendResult(seminarId, formatTime(saved.getAt()));
}
```

Also add a shared time formatter to `SeminarService` (reused by Task 6). Add this private method:

```java
String formatTime(Instant at) {
    return at.atZone(SEOUL).format(HHMM);
}
```

Add to `SeminarController` (new imports + handler):

```java
// add imports
import com.jaram.be.seminar.dto.AttendRequest;
import com.jaram.be.seminar.dto.AttendResult;
import org.springframework.web.bind.annotation.PathVariable;

// add handler
@PostMapping("/{id}/attend")
public AttendResult attend(@PathVariable String id,
                           @Valid @RequestBody AttendRequest req,
                           @AuthenticationPrincipal CurrentMember me) {
    return service.attend(id, me.id(), req.code());
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarAttendTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jaram/be/seminar src/test/java/com/jaram/be/seminar/SeminarAttendTest.java
git commit -m "feat: add seminar attendance check endpoint (UC-S2)"
```

---

### Task 6: UC-S4 — attendance roster (`GET /api/seminars/{id}/roster`, officer)

**Files:**
- Create: `src/main/java/com/jaram/be/seminar/dto/RosterEntry.java`
- Create: `src/main/java/com/jaram/be/seminar/dto/RosterResponse.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java` (add `roster`, `MemberRepository` dependency)
- Modify: `src/main/java/com/jaram/be/seminar/SeminarController.java` (add roster handler)
- Test: `src/test/java/com/jaram/be/seminar/SeminarRosterTest.java`

**Interfaces:**
- Consumes: `SeminarRepository.findById`, `AttendanceRepository.findBySeminarIdOrderByAtAsc`, `MemberRepository.findAllById`, `formatTime` (Task 5), `Member.getName/getStudentId`.
- Produces:
  - `record RosterEntry(String name, String sid, String at)`
  - `record RosterResponse(String title, int cap, List<RosterEntry> list)`
  - `SeminarService.roster(String seminarId) -> RosterResponse`
  - `SeminarController` mapping `GET /api/seminars/{id}/roster`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/jaram/be/seminar/SeminarRosterTest.java`:

```java
package com.jaram.be.seminar;

import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.security.JwtProvider;
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
class SeminarRosterTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String officerToken;
    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
        memberToken = jwt.generate("member-1", "회원", "member@hanyang.ac.kr", Authority.MEMBER);
    }

    private Member active(String id, String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setStatus(MemberStatus.ACTIVE);
        // align the member id with the JWT subject used at attend time
        return members.save(withId(m, id));
    }

    // Member has no public id setter; persist then reload by the generated id is not enough here,
    // so use the JPA-saved entity's own id for attendance rows instead (see below).
    private Member withId(Member m, String ignored) { return m; }

    @Test
    void officerSeesRosterInAttendanceOrder() {
        Member a = active("x", "김출석", "2023000001", "a@hanyang.ac.kr");
        Member b = active("y", "박출석", "2023000002", "b@hanyang.ac.kr");
        Seminar s = seminars.save(Seminar.create("세미나", null, null,
                Instant.now(), null, null, "CODE", null, 30, "officer-1"));
        attendances.save(Attendance.create(s.getId(), b.getId(), Instant.parse("2026-06-27T10:02:00Z")));
        attendances.save(Attendance.create(s.getId(), a.getId(), Instant.parse("2026-06-27T10:01:00Z")));

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/seminars/" + s.getId() + "/roster")
                .then().statusCode(200)
                .body("title", equalTo("세미나"))
                .body("cap", equalTo(30))
                .body("list.size()", equalTo(2))
                // ascending by attendance time → a (10:01) before b (10:02)
                .body("list[0].name", equalTo("김출석"))
                .body("list[0].sid", equalTo("2023000001"))
                .body("list[0].at", equalTo("19:01"))
                .body("list[1].name", equalTo("박출석"));
    }

    @Test
    void capIsZeroWhenCapacityNull() {
        Seminar s = seminars.save(Seminar.create("무정원", null, null,
                Instant.now(), null, null, "CODE", null, null, "officer-1"));
        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/seminars/" + s.getId() + "/roster")
                .then().statusCode(200).body("cap", equalTo(0)).body("list.size()", equalTo(0));
    }

    @Test
    void memberCannotViewRoster() {
        Seminar s = seminars.save(Seminar.create("세미나", null, null,
                Instant.now(), null, null, "CODE", null, 30, "officer-1"));
        given().header("Authorization", "Bearer " + memberToken)
                .when().get("/api/seminars/" + s.getId() + "/roster")
                .then().statusCode(403).body("code", equalTo("FORBIDDEN"));
    }

    @Test
    void unknownSeminarReturns404() {
        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/seminars/does-not-exist/roster")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }
}
```

> Note on member ids: `Member.newPending` assigns its own UUID, so the test uses each saved member's real `getId()` when creating `Attendance` rows (the `active(...)` helper's `id` argument is ignored — kept only for readability). The roster service joins `Attendance.memberId` back to `Member` by that id.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarRosterTest'`
Expected: FAIL — `RosterResponse` / `RosterEntry` / roster handler do not exist.

- [ ] **Step 3: Implement DTOs, service method, controller mapping**

Create `src/main/java/com/jaram/be/seminar/dto/RosterEntry.java`:

```java
package com.jaram.be.seminar.dto;

// Matches OpenAPI schema RosterEntry. sid is the member's studentId; at is HH:mm.
public record RosterEntry(String name, String sid, String at) { }
```

Create `src/main/java/com/jaram/be/seminar/dto/RosterResponse.java`:

```java
package com.jaram.be.seminar.dto;

import java.util.List;

// Matches OpenAPI schema RosterResponse. cap is the seminar capacity (0 when unset).
public record RosterResponse(String title, int cap, List<RosterEntry> list) { }
```

Update `SeminarService` — inject `MemberRepository` and add `roster`. The constructor gains a parameter:

```java
// add imports
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.seminar.dto.RosterEntry;
import com.jaram.be.seminar.dto.RosterResponse;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// add field
private final MemberRepository members;

// replace the constructor again (adds the members dependency)
public SeminarService(SeminarRepository seminars,
                      AttendanceRepository attendances,
                      MemberRepository members,
                      @Value("${seminar.attendance-window-minutes:120}") long windowMinutes) {
    this.seminars = seminars;
    this.attendances = attendances;
    this.members = members;
    this.windowMinutes = windowMinutes;
}

// add method
@Transactional(readOnly = true)
public RosterResponse roster(String seminarId) {
    Seminar s = seminars.findById(seminarId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다."));

    List<Attendance> rows = attendances.findBySeminarIdOrderByAtAsc(seminarId);
    Map<String, Member> byId = members.findAllById(
                    rows.stream().map(Attendance::getMemberId).toList()).stream()
            .collect(Collectors.toMap(Member::getId, Function.identity()));

    List<RosterEntry> list = rows.stream().map(a -> {
        Member m = byId.get(a.getMemberId());
        return new RosterEntry(
                m == null ? null : m.getName(),
                m == null ? null : m.getStudentId(),
                formatTime(a.getAt()));
    }).toList();

    int cap = s.getCapacity() == null ? 0 : s.getCapacity();
    return new RosterResponse(s.getTitle(), cap, list);
}
```

Add to `SeminarController` (new imports + handler):

```java
// add import
import com.jaram.be.seminar.dto.RosterResponse;

// add handler
@GetMapping("/{id}/roster")
public RosterResponse roster(@PathVariable String id) {
    return service.roster(id);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarRosterTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jaram/be/seminar src/test/java/com/jaram/be/seminar/SeminarRosterTest.java
git commit -m "feat: add seminar attendance roster endpoint (UC-S4)"
```

---

### Task 7: Contract conformance test (all 4 seminar operations)

**Files:**
- Test: `src/test/java/com/jaram/be/contract/SeminarContractTest.java`

**Interfaces:**
- Consumes: every seminar endpoint (Tasks 3–6), `JwtProvider`, `MemberRepository`, the seminar repositories. Attaches `OpenApiValidationFilter("openapi/openapi.yaml")` so each real response is validated against the OpenAPI schema.

- [ ] **Step 1: Sync the contract, then write the failing test**

Run: `./scripts/sync-openapi.sh` (ensures the test copy matches FE's source).

Create `src/test/java/com/jaram/be/contract/SeminarContractTest.java`:

```java
package com.jaram.be.contract;

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.seminar.Attendance;
import com.jaram.be.seminar.AttendanceRepository;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static io.restassured.RestAssured.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarContractTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private final OpenApiValidationFilter validation =
            new OpenApiValidationFilter("openapi/openapi.yaml");

    private String officerToken;
    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
        memberToken = jwt.generate("member-1", "회원", "member@hanyang.ac.kr", Authority.MEMBER);
    }

    @Test
    void listMatchesContract() {
        seminars.save(Seminar.create("세미나", "김연사", "주제", Instant.now(),
                "IT관", "offline", "CODE", "https://m.example.com/a", 30, "officer-1"));
        given().filter(validation).when().get("/api/seminars").then().statusCode(200);
    }

    @Test
    void createMatchesContract() {
        given().filter(validation).header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("title", "새 세미나", "startsAt", "2026-07-01T10:00:00Z", "capacity", 40))
                .when().post("/api/seminars").then().statusCode(201);
    }

    @Test
    void attendMatchesContract() {
        Seminar s = seminars.save(Seminar.create("ongoing", null, null,
                Instant.now().minus(1, ChronoUnit.MINUTES), null, null, "JOIN123", null, null, "officer-1"));
        given().filter(validation).header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", "JOIN123"))
                .when().post("/api/seminars/" + s.getId() + "/attend").then().statusCode(200);
    }

    @Test
    void attendWrongCodeMatchesContract() {
        Seminar s = seminars.save(Seminar.create("ongoing", null, null,
                Instant.now().minus(1, ChronoUnit.MINUTES), null, null, "JOIN123", null, null, "officer-1"));
        given().filter(validation).header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("code", "WRONG"))
                .when().post("/api/seminars/" + s.getId() + "/attend").then().statusCode(400);
    }

    @Test
    void rosterMatchesContract() {
        Member m = members.save(activeMember());
        Seminar s = seminars.save(Seminar.create("세미나", null, null,
                Instant.now(), null, null, "CODE", null, 30, "officer-1"));
        attendances.save(Attendance.create(s.getId(), m.getId(), Instant.now()));
        given().filter(validation).header("Authorization", "Bearer " + officerToken)
                .when().get("/api/seminars/" + s.getId() + "/roster").then().statusCode(200);
    }

    private Member activeMember() {
        Member m = Member.newPending("김출석", "2023000001", "a@hanyang.ac.kr", "hash");
        m.setStatus(MemberStatus.ACTIVE);
        return m;
    }
}
```

- [ ] **Step 2: Run test to verify it fails (or passes if endpoints already conform)**

Run: `./gradlew test --tests 'com.jaram.be.contract.SeminarContractTest'`
Expected: PASS if Tasks 3–6 produced schema-accurate DTOs. If any response fails OpenAPI validation, the filter throws and the test fails — fix the DTO to match the schema, do NOT edit `docs/api/openapi.yaml` (it is FE-owned; the test copy may be minimally patched only if FE's file itself fails to parse — see the develop-backend skill's Contract sync section).

- [ ] **Step 3: Run the full contract suite + full build**

Run: `./gradlew test --tests '*ContractTest'`
Expected: PASS (auth, people, seminar contract tests).

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — entire suite green.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/jaram/be/contract/SeminarContractTest.java
git commit -m "test: add OpenAPI contract conformance tests for seminar"
```

---

## Self-Review

**1. Spec coverage (UC-S1..S4):**
- UC-S1 list (status derived, no attendanceCode, materialUrl nullable) → Task 3. ✓
- UC-S2 attend (code match + ongoing, INVALID_CODE on mismatch/closed, idempotent dup) → Task 5. ✓
- UC-S3 create (officer-only via existing security, title required, startsAt ISO-8601) → Task 4. ✓
- UC-S4 roster (officer-only, `{title, cap, list:[{name, sid, at}]}`) → Task 6. ✓
- Contract conformance for all four → Task 7. ✓
- Derived display fields `day/month/weekday/time` (spec §8-1, contract Seminar) → Task 3. ✓
- Attendance window gap (spec §4.4) resolved → "Contract decisions locked for P3". ✓

**2. Placeholder scan:** No TBD/“add validation”/“handle edge cases”. Every code step shows full code; every test step shows the assertions. ✓

**3. Type consistency:**
- `SeminarService` constructor is shown growing across Tasks 3 → 5 → 6 (seminars; +attendances; +members; windowMinutes last). Each task that changes it shows the full replacement constructor, so out-of-order readers get the final shape. ✓
- `toResponse(Seminar)` (Task 3), `formatTime(Instant)` (Task 5) reused by later tasks with the names declared in their Interfaces blocks. ✓
- `SeminarStatus.of(Instant, Instant, long)` defined in Task 2, used in Tasks 3 and 5 with the same signature. ✓
- DTO field names/types match the OpenAPI schemas verbatim (`SeminarResponse`, `SeminarCreateRequest`, `AttendRequest`, `AttendResult`, `RosterResponse`, `RosterEntry`). ✓

## Out of scope for P3 (later phases)
- P4 study (UC-T1..T8) — separate plan.
- Seminar edit/delete, attendance code rotation, capacity enforcement on attend (contract has no such operations).

## Execution Handoff

Implement with subagent-driven-development (fresh subagent per task, review between) or executing-plans (inline, batched with checkpoints).
