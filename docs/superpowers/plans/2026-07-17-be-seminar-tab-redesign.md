# Seminar Tab Redesign — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `GET /api/seminars` a per-caller `attendedAt` + a server-derived `attendanceClosesAt`, add a free-text `description` field to seminars, and add a member-facing `GET /api/seminars/{id}/attendees` preview (no `sid`) — the backend surface the FE seminar-tab redesign (card click → detail modal, countdown, attended/absent chip) needs.

**Architecture:** Extends the existing `seminar` package (Controller → Service → Repository → Entity, unchanged shape). `SeminarService.list/create` gain a `callerId` thread-through, mirroring the already-shipped `StudyService.deriveApply(..., userId)` null-means-anonymous pattern. The new endpoint reuses `SeminarService.roster()`'s member-lookup pattern minus `sid`.

**Tech Stack:** Spring Boot 3.4, Java 21, Gradle, Spring Data JPA, PostgreSQL (Testcontainers), Spring Security (stateless JWT), Bean Validation, JUnit 5, REST-assured, swagger-request-validator (Atlassian) for contract tests.

**Source spec:** `docs/superpowers/specs/2026-07-17-seminar-tab-redesign-design.md` (Backend section). That spec is cross-repo (BE + FE); this plan covers **BE only** — see Scope below.

## Global Constraints

- **Repo:** this repo (`home-jaram-be`), package `com.jaram.be`. Run every command from the repo root.
- **Contract is law, and it is edited in this plan.** `docs/api/openapi.yaml` is a symlink to `../../../home-jaram-fe/docs/api/openapi.yaml` (confirmed present on disk at `/home/ksb/Dev/home-jaram-fe`). Normally BE never edits this file — but this feature's contract fields don't exist yet anywhere, and the design spec explicitly calls for editing the FE-owned source directly through the symlink (same author owns both sibling repos here). Edit `docs/api/openapi.yaml`, then run `./scripts/sync-openapi.sh`, then review `git diff src/main/resources/openapi/openapi.yaml` before moving on. Never hand-edit the synced copy directly.
- **Scope: backend only.** The spec's Frontend section (`home-jaram-fe`: `ListView.jsx` tab order, `SeminarCard.jsx` click/chip, `useAttendanceCountdown.js`, `DetailModal.jsx`, `seminar.api.js`/`seminar.queries.js`) is a separate codebase and needs its own plan in that repo. Not covered here.
- **Explicitly out of scope:** `Seminar.capacity` → `target: TargetGrade[]`. This is a pre-existing, unrelated contract drift (FE already ships `target`; BE still has `capacity`) called out by the spec itself ("기존에 발견된 별개 갭... 손대지 않는다"). Do not implement `target`. Do not try to make its currently-failing contract test pass — see the baseline below.
- **Verified baseline (2026-07-17, before this plan's changes):** `./gradlew test --tests '*SeminarContractTest' --tests '*SeminarListTest' --tests '*SeminarRosterTest' --tests '*SeminarCreateTest' --tests '*SeminarStatusTest' --tests '*SeminarAttendTest'` → **25 tests, 1 pre-existing failure**: `SeminarContractTest.createMatchesContract`, `OpenApiValidationException` — `validation.request.body.schema.additionalProperties` and `validation.response.body.schema.additionalProperties`, both `"[\"capacity\"]"` — caused by the `capacity`/`target` drift above. **This exact single failure is the only acceptable one at every checkpoint in this plan.** Any other failure, or a change in this failure's cause, is a regression — stop and investigate (superpowers:systematic-debugging).
- **`seminar.attendance-window-minutes`:** `@Value` on `SeminarService`, default `120`, not set in `application.yml` (default is live).
- **Time display convention:** Asia/Seoul zone (`SeminarService.SEOUL`), `HH:mm` via the existing private `formatTime(Instant)`. Reuse it — don't add a second formatter.
- **Auth precedent (verified):** `@AuthenticationPrincipal CurrentMember me` is `null` on a `permitAll` route when no/invalid bearer token is sent — this is the exact mechanism `StudyController.list`/`StudyService.deriveApply(..., userId)` already use (`StudyController.java:21-24`). Reuse it verbatim for `SeminarController.list`.
- **404 precedent:** `new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다.")` — exactly what `SeminarService.attend`/`roster` already throw for an unknown seminar id. Reuse the same message.
- **`SecurityConfig` (`src/main/java/com/jaram/be/security/SecurityConfig.java:51-59`):** no route-matcher changes anywhere in this plan. Verified: `GET /api/seminars` is already `permitAll` (line 52, exact-string match, not `/**`); `GET /api/seminars/{id}/attendees` matches none of the explicit matchers (lines 51-58) so it falls through to `.anyRequest().authenticated()` (line 59) — any logged-in member, no `OFFICER` requirement, matching the spec.
- **Commit discipline:** per the user's global workflow rule, every commit is delegated to the `committer` subagent — never run `git commit` directly. One commit per task.

## Deliberate deviation from the spec doc's sketch

The spec's Backend section says `description` should be added to `Seminar`'s "factory·getter/setter". This plan adds it as a **getter/setter only, not a `Seminar.create(...)` factory parameter.** `Seminar.create(...)` has 18 call sites across 7 files (`SeminarService`, `AdminBatchExecutor`, and 5 test files); none of them need to set `description`. Appending a 10th positional parameter would force a mechanical, valueless edit to all 18. The entity already has setter-based mutation for exactly this kind of optional field (`setTitle`/`setSpeaker`/`setPlace`/`setMode`/`setCapacity` all exist for the same reason). `SeminarService.create()` is the only real caller that needs to set it, via `s.setDescription(req.description())` right after construction.

## File Structure

```
docs/api/openapi.yaml                                # FE-owned symlink — edited in Tasks 1 & 2
src/main/resources/openapi/openapi.yaml               # generated by sync-openapi.sh — never hand-edit
src/main/java/com/jaram/be/seminar/
  Seminar.java                                        # + description field/getter/setter (Task 1)
  SeminarService.java                                 # list/create/toResponse signatures change (Task 1); + attendeePreview (Task 2)
  SeminarController.java                              # list() gains principal (Task 1); + attendees() (Task 2)
  dto/
    SeminarCreateRequest.java                          # + description (Task 1)
    SeminarResponse.java                               # + description/attendanceClosesAt/attendedAt (Task 1)
    AttendeePreviewEntry.java                           # new (Task 2)
    AttendeePreviewResponse.java                        # new (Task 2)
src/test/java/com/jaram/be/
  seminar/
    SeminarListTest.java                                # + 2 tests (Task 1)
    SeminarCreateTest.java                               # description-echo assertion (Task 1)
    SeminarAttendeePreviewTest.java                       # new, 3 tests (Task 2)
  contract/
    SeminarContractTest.java                             # + attendeesMatchesContract (Task 3)
```

---

### Task 1: Seminar `description` + per-caller `attendedAt`/`attendanceClosesAt`

**Files:**
- Modify (FE-owned, via symlink): `docs/api/openapi.yaml`
- Modify: `src/main/java/com/jaram/be/seminar/Seminar.java`
- Modify: `src/main/java/com/jaram/be/seminar/dto/SeminarCreateRequest.java`
- Modify: `src/main/java/com/jaram/be/seminar/dto/SeminarResponse.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarController.java`
- Test: `src/test/java/com/jaram/be/seminar/SeminarListTest.java`
- Test: `src/test/java/com/jaram/be/seminar/SeminarCreateTest.java`

**Interfaces:**
- Consumes: `AttendanceRepository.findBySeminarIdAndMemberId` (exists), `CurrentMember(id,...)` (exists), `StudyController`'s null-principal precedent (see Global Constraints).
- Produces: `SeminarService.list(String callerId)`, `SeminarService.toResponse(Seminar, String callerId)` (package-private, used by Task 2 unchanged), `SeminarResponse` gains `description`/`attendanceClosesAt`/`attendedAt` as its last 3 components — Task 2 and Task 3 depend on this exact field order.

- [x] **Step 1: Edit the contract and sync**

Open `docs/api/openapi.yaml` (editing the FE repo through the symlink) and in the `Seminar` schema, change:

```yaml
    Seminar:
      type: object
      required: [id, title, startsAt, status]
      properties:
        id: { type: string }
        title: { type: string }
        speaker: { type: [string, 'null'] }
        topic: { type: [string, 'null'] }
        startsAt: { type: string, format: date-time, description: 정규 시작 시각 (ISO-8601) }
        day: { type: string, description: 파생 표시 (예 '27') }
        month: { type: string, description: 파생 표시 (예 '6월') }
        weekday: { type: string, description: 파생 표시 (예 '금') }
        time: { type: string, description: 파생 표시 (예 '19:00') }
        place: { type: [string, 'null'] }
        mode: { type: [string, 'null'] }
        status: { $ref: '#/components/schemas/SeminarStatus' }
        materialUrl: { type: [string, 'null'], format: uri }
        target:
          type: array
          items: { $ref: '#/components/schemas/TargetGrade' }
          description: 공개 대상 등급. 빈 배열은 전체 공개.
```

to (adds `description`/`attendanceClosesAt`/`attendedAt`, adds `attendanceClosesAt` to `required` since it is always computed — leave `target`/`capacity` untouched):

```yaml
    Seminar:
      type: object
      required: [id, title, startsAt, status, attendanceClosesAt]
      properties:
        id: { type: string }
        title: { type: string }
        speaker: { type: [string, 'null'] }
        topic: { type: [string, 'null'] }
        startsAt: { type: string, format: date-time, description: 정규 시작 시각 (ISO-8601) }
        day: { type: string, description: 파생 표시 (예 '27') }
        month: { type: string, description: 파생 표시 (예 '6월') }
        weekday: { type: string, description: 파생 표시 (예 '금') }
        time: { type: string, description: 파생 표시 (예 '19:00') }
        place: { type: [string, 'null'] }
        mode: { type: [string, 'null'] }
        status: { $ref: '#/components/schemas/SeminarStatus' }
        materialUrl: { type: [string, 'null'], format: uri }
        target:
          type: array
          items: { $ref: '#/components/schemas/TargetGrade' }
          description: 공개 대상 등급. 빈 배열은 전체 공개.
        description: { type: [string, 'null'], description: 세미나 상세 설명 }
        attendanceClosesAt: { type: string, format: date-time, description: '출석 인정 마감 시각 (startsAt + 출석창, 서버 파생)' }
        attendedAt: { type: [string, 'null'], description: '내 출석 시각 표시(예 19:02). 미출석/비로그인이면 null' }
```

And in `SeminarCreateRequest`, add `description` after `target`:

```yaml
    SeminarCreateRequest:
      type: object
      required: [title, startsAt]
      properties:
        title: { type: string }
        speaker: { type: [string, 'null'] }
        topic: { type: [string, 'null'] }
        startsAt: { type: string, format: date-time }
        place: { type: [string, 'null'] }
        mode: { type: [string, 'null'] }
        attendanceCode: { type: [string, 'null'], description: 출석 코드 (응답엔 미노출) }
        materialUrl: { type: [string, 'null'], format: uri }
        target:
          type: array
          items: { $ref: '#/components/schemas/TargetGrade' }
          description: 공개 대상 등급. 빈 배열은 전체 공개.
        description: { type: [string, 'null'] }
```

Run: `./scripts/sync-openapi.sh`
Expected: `Synced contract -> .../src/main/resources/openapi/openapi.yaml`. Then `git diff src/main/resources/openapi/openapi.yaml` should show exactly the `description`/`attendanceClosesAt`/`attendedAt` additions above (plus whatever `capacity`/`target` drift already existed — untouched).

- [x] **Step 2: Write the failing tests**

Replace `src/test/java/com/jaram/be/seminar/SeminarListTest.java`:

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
class SeminarListTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        members.deleteAll();
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

    @Test
    void anonymousListHasClosesAtAndNullAttendedAt() {
        Instant starts = Instant.now().minus(30, ChronoUnit.MINUTES);
        seminars.save(Seminar.create("진행중", null, null, starts,
                null, null, "CODE", null, null, "officer-1"));

        given().when().get("/api/seminars").then().statusCode(200)
                .body("[0].attendanceClosesAt", equalTo(starts.plusSeconds(120 * 60).toString()))
                .body("[0].attendedAt", nullValue())
                .body("[0].description", nullValue());
    }

    @Test
    void authenticatedCallerSeesOwnAttendance() {
        Member m = Member.newPending("김출석", "2023000001", "a@hanyang.ac.kr", "hash");
        m.setStatus(MemberStatus.ACTIVE);
        m = members.save(m);
        String token = jwt.generate(m.getId(), "김출석", "a@hanyang.ac.kr", Authority.MEMBER);

        // now - 200m is outside the default 120m window -> ENDED
        Instant endedStart = Instant.now().minus(200, ChronoUnit.MINUTES);
        Seminar attended = seminars.save(Seminar.create("종료-출석", null, null, endedStart,
                null, null, "CODE1", null, null, "officer-1"));
        Seminar notAttended = seminars.save(Seminar.create("종료-결석", null, null, endedStart.minusSeconds(1),
                null, null, "CODE2", null, null, "officer-1"));
        attendances.save(Attendance.create(attended.getId(), m.getId(), endedStart.plusSeconds(60)));

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/seminars").then().statusCode(200)
                .body("[0].id", equalTo(attended.getId()))
                .body("[0].attendedAt", notNullValue())
                .body("[1].id", equalTo(notAttended.getId()))
                .body("[1].attendedAt", nullValue());
    }
}
```

Replace `src/test/java/com/jaram/be/seminar/SeminarCreateTest.java` (only the request body and one assertion change):

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
                "startsAt", "2027-07-01T10:00:00Z",
                "place", "IT관 401",
                "attendanceCode", "JOIN123",
                "capacity", 40,
                "description", "이번 세미나는 신규 회원 대상입니다.");

        String id = given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(body)
                .when().post("/api/seminars")
                .then().statusCode(201)
                .body("title", equalTo("새 세미나"))
                .body("status", equalTo("UPCOMING"))
                .body("capacity", equalTo(40))
                .body("description", equalTo("이번 세미나는 신규 회원 대상입니다."))
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

- [x] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarListTest' --tests 'com.jaram.be.seminar.SeminarCreateTest'`
Expected: FAIL — 3 of 8 tests fail (`anonymousListHasClosesAtAndNullAttendedAt`, `authenticatedCallerSeesOwnAttendance`, `officerCreatesSeminarAndCodeIsNotReturned`), because `SeminarResponse` has no `description`/`attendanceClosesAt`/`attendedAt` keys yet. The other 5 (`listsNewestFirstWithDerivedFieldsAndNoAttendanceCode`, `emptyDatabaseReturnsEmptyArray`, `missingTitleReturns422`, `memberCannotCreateSeminar`, `anonymousCannotCreateSeminar`) still pass.

- [x] **Step 4: Implement**

Modify `src/main/java/com/jaram/be/seminar/Seminar.java` — add a `description` field with getter/setter (no factory parameter — see "Deliberate deviation" above):

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
    private String description;      // nullable, free-text detail (set via setter, not the factory)

    private String createdById;
    private Instant createdAt = Instant.now();

    @Version
    private Long version;   // 관리자 일괄 편집 낙관적 잠금

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
    public void setTitle(String v) { this.title = v; }
    public String getSpeaker() { return speaker; }
    public void setSpeaker(String v) { this.speaker = v; }
    public String getTopic() { return topic; }
    public void setTopic(String v) { this.topic = v; }
    public Instant getStartsAt() { return startsAt; }
    public String getPlace() { return place; }
    public void setPlace(String v) { this.place = v; }
    public String getMode() { return mode; }
    public void setMode(String v) { this.mode = v; }
    public String getAttendanceCode() { return attendanceCode; }
    public String getMaterialUrl() { return materialUrl; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer v) { this.capacity = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getCreatedById() { return createdById; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getVersion() { return version; }
}
```

Modify `src/main/java/com/jaram/be/seminar/dto/SeminarCreateRequest.java`:

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
        Integer capacity,
        String description
) { }
```

Modify `src/main/java/com/jaram/be/seminar/dto/SeminarResponse.java`:

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
        Integer capacity,
        String description,
        String attendanceClosesAt,
        String attendedAt
) { }
```

Modify `src/main/java/com/jaram/be/seminar/SeminarService.java`:

```java
package com.jaram.be.seminar;

import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.seminar.dto.AttendResult;
import com.jaram.be.seminar.dto.RosterEntry;
import com.jaram.be.seminar.dto.RosterResponse;
import com.jaram.be.seminar.dto.SeminarCreateRequest;
import com.jaram.be.seminar.dto.SeminarResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final AttendanceRepository attendances;
    private final MemberRepository members;
    private final long windowMinutes;

    public SeminarService(SeminarRepository seminars,
                          AttendanceRepository attendances,
                          MemberRepository members,
                          @Value("${seminar.attendance-window-minutes:120}") long windowMinutes) {
        this.seminars = seminars;
        this.attendances = attendances;
        this.members = members;
        this.windowMinutes = windowMinutes;
    }

    @Transactional(readOnly = true)
    public List<SeminarResponse> list(String callerId) {
        return seminars.findAllByOrderByStartsAtDesc().stream()
                .map(s -> toResponse(s, callerId)).toList();
    }

    @Transactional
    public SeminarResponse create(SeminarCreateRequest req, String createdById) {
        Seminar s = Seminar.create(
                req.title(), req.speaker(), req.topic(), req.startsAt(),
                req.place(), req.mode(), req.attendanceCode(),
                req.materialUrl(), req.capacity(), createdById);
        s.setDescription(req.description());
        Seminar saved = seminars.save(s);
        return toResponse(saved, createdById);
    }

    SeminarResponse toResponse(Seminar s, String callerId) {
        ZonedDateTime t = s.getStartsAt().atZone(SEOUL);
        Instant closesAt = s.getStartsAt().plus(Duration.ofMinutes(windowMinutes));
        String attendedAt = callerId == null ? null :
                attendances.findBySeminarIdAndMemberId(s.getId(), callerId)
                        .map(a -> formatTime(a.getAt())).orElse(null);
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
                s.getCapacity(),
                s.getDescription(),
                closesAt.toString(),
                attendedAt);
    }

    @Transactional
    public AttendResult attend(String seminarId, String memberId, String code) {
        Seminar s = seminars.findById(seminarId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다."));

        Attendance existing = attendances.findBySeminarIdAndMemberId(seminarId, memberId).orElse(null);
        if (existing != null) {
            return new AttendResult(seminarId, formatTime(existing.getAt()));  // idempotent
        }

        boolean ongoing = SeminarStatus.of(s.getStartsAt(), Instant.now(), windowMinutes) == SeminarStatus.ONGOING;
        // code is @NotBlank (never null); compare from it so a code-less seminar yields
        // INVALID_CODE rather than an NPE/500.
        if (!ongoing || !code.equals(s.getAttendanceCode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CODE", "출석 코드가 올바르지 않습니다.");
        }

        Attendance saved = attendances.save(Attendance.create(seminarId, memberId, Instant.now()));
        return new AttendResult(seminarId, formatTime(saved.getAt()));
    }

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

    private String formatTime(Instant at) {
        return at.atZone(SEOUL).format(HHMM);
    }
}
```

Modify `src/main/java/com/jaram/be/seminar/SeminarController.java` — only the `list` method changes:

```java
package com.jaram.be.seminar;

import com.jaram.be.seminar.dto.AttendRequest;
import com.jaram.be.seminar.dto.AttendResult;
import com.jaram.be.seminar.dto.RosterResponse;
import com.jaram.be.seminar.dto.SeminarCreateRequest;
import com.jaram.be.seminar.dto.SeminarResponse;
import com.jaram.be.security.CurrentMember;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/seminars")
public class SeminarController {

    private final SeminarService service;

    public SeminarController(SeminarService service) { this.service = service; }

    @GetMapping
    public List<SeminarResponse> list(@AuthenticationPrincipal CurrentMember me) {
        return service.list(me == null ? null : me.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeminarResponse create(@Valid @RequestBody SeminarCreateRequest req,
                                  @AuthenticationPrincipal CurrentMember me) {
        return service.create(req, me.id());
    }

    @PostMapping("/{id}/attend")
    public AttendResult attend(@PathVariable String id,
                               @Valid @RequestBody AttendRequest req,
                               @AuthenticationPrincipal CurrentMember me) {
        return service.attend(id, me.id(), req.code());
    }

    @GetMapping("/{id}/roster")
    public RosterResponse roster(@PathVariable String id) {
        return service.roster(id);
    }
}
```

- [x] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarListTest' --tests 'com.jaram.be.seminar.SeminarCreateTest'`
Expected: PASS (8 tests).

- [x] **Step 6: Commit**

Delegate to the `committer` subagent (per the user's global workflow rule — do not run `git commit` directly). Files: `docs/api/openapi.yaml` (FE repo, commit there separately if that repo tracks it independently — otherwise note it was already synced), `src/main/resources/openapi/openapi.yaml`, `src/main/java/com/jaram/be/seminar/Seminar.java`, `src/main/java/com/jaram/be/seminar/dto/SeminarCreateRequest.java`, `src/main/java/com/jaram/be/seminar/dto/SeminarResponse.java`, `src/main/java/com/jaram/be/seminar/SeminarService.java`, `src/main/java/com/jaram/be/seminar/SeminarController.java`, `src/test/java/com/jaram/be/seminar/SeminarListTest.java`, `src/test/java/com/jaram/be/seminar/SeminarCreateTest.java`.

---

### Task 2: Attendee preview endpoint (`GET /api/seminars/{id}/attendees`)

**Files:**
- Modify (FE-owned, via symlink): `docs/api/openapi.yaml`
- Create: `src/main/java/com/jaram/be/seminar/dto/AttendeePreviewEntry.java`
- Create: `src/main/java/com/jaram/be/seminar/dto/AttendeePreviewResponse.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarController.java`
- Test: `src/test/java/com/jaram/be/seminar/SeminarAttendeePreviewTest.java`

**Interfaces:**
- Consumes: `SeminarService`'s `seminars`/`attendances`/`members` fields and private `formatTime(Instant)` (Task 1, unchanged base — this task doesn't depend on Task 1's `toResponse`/`list` changes, only on the pre-existing scaffold). `AttendanceRepository.findBySeminarIdOrderByAtAsc` (exists, used by `roster()` already).
- Produces: `AttendeePreviewEntry(String name, String at)`, `AttendeePreviewResponse(int count, List<AttendeePreviewEntry> list)`, `SeminarService.attendeePreview(String seminarId)`, `GET /api/seminars/{id}/attendees`.

- [x] **Step 1: Edit the contract and sync**

In `docs/api/openapi.yaml`, add a new path after `/api/seminars/{id}/roster` (before the `# ── study ──` comment / `/api/studies` block):

```yaml
  /api/seminars/{id}/attendees:
    get:
      tags: [seminar]
      summary: 참석자 미리보기 (신규)
      description: 로그인한 회원 누구나 조회. sid(학번) 미노출 — officer 전용 roster와 구분.
      security: [{ bearerAuth: [] }]
      parameters:
        - $ref: '#/components/parameters/SeminarId'
      responses:
        '200':
          description: 참석자 미리보기
          content:
            application/json:
              schema: { $ref: '#/components/schemas/AttendeePreviewResponse' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
        '5XX': { $ref: '#/components/responses/ServerError' }
```

And add two new schemas after `RosterResponse` (before the `# ── study ──` comment in `components.schemas`):

```yaml
    AttendeePreviewEntry:
      type: object
      required: [at]
      properties:
        name: { type: [string, 'null'] }
        at: { type: string, description: 출석 시각 표시 (예 '19:02') }
    AttendeePreviewResponse:
      type: object
      required: [count, list]
      properties:
        count: { type: integer }
        list:
          type: array
          items: { $ref: '#/components/schemas/AttendeePreviewEntry' }
```

Run: `./scripts/sync-openapi.sh`, then `git diff src/main/resources/openapi/openapi.yaml` to confirm only these additions landed.

- [x] **Step 2: Write the failing test**

Create `src/test/java/com/jaram/be/seminar/SeminarAttendeePreviewTest.java`:

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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarAttendeePreviewTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        members.deleteAll();
        memberToken = jwt.generate("member-1", "회원", "member@hanyang.ac.kr", Authority.MEMBER);
    }

    @Test
    void memberSeesAttendeesWithoutSid() {
        Member a = members.save(activeMember("김출석", "2023000001", "a@hanyang.ac.kr"));
        Member b = members.save(activeMember("박출석", "2023000002", "b@hanyang.ac.kr"));
        Seminar s = seminars.save(Seminar.create("세미나", null, null,
                Instant.now(), null, null, "CODE", null, null, "officer-1"));
        attendances.save(Attendance.create(s.getId(), b.getId(), Instant.parse("2026-06-27T10:02:00Z")));
        attendances.save(Attendance.create(s.getId(), a.getId(), Instant.parse("2026-06-27T10:01:00Z")));

        given().header("Authorization", "Bearer " + memberToken)
                .when().get("/api/seminars/" + s.getId() + "/attendees")
                .then().statusCode(200)
                .body("count", equalTo(2))
                .body("list.size()", equalTo(2))
                // ascending by attendance time -> a (10:01) before b (10:02)
                .body("list[0].name", equalTo("김출석"))
                .body("list[1].name", equalTo("박출석"))
                .body("list[0]", not(hasKey("sid")))
                .body("list[1]", not(hasKey("sid")));
    }

    @Test
    void anonymousCannotViewAttendees() {
        Seminar s = seminars.save(Seminar.create("세미나", null, null,
                Instant.now(), null, null, "CODE", null, null, "officer-1"));
        given().when().get("/api/seminars/" + s.getId() + "/attendees")
                .then().statusCode(401);
    }

    @Test
    void unknownSeminarReturns404() {
        given().header("Authorization", "Bearer " + memberToken)
                .when().get("/api/seminars/does-not-exist/attendees")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    private Member activeMember(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setStatus(MemberStatus.ACTIVE);
        return m;
    }
}
```

- [x] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarAttendeePreviewTest'`
Expected: FAIL. `memberSeesAttendeesWithoutSid` fails (no route mapped yet, not 200). `unknownSeminarReturns404` fails on the body assertion (an unmapped route doesn't return the app's `{code,message}` error envelope). `anonymousCannotViewAttendees` may already pass — Spring Security's `anyRequest().authenticated()` (`SecurityConfig.java:59`) rejects the unauthenticated request with 401 before route resolution even happens, regardless of whether a handler exists; that's fine, it's proving the security posture, not the new handler.

- [x] **Step 4: Implement**

Add `src/main/java/com/jaram/be/seminar/dto/AttendeePreviewEntry.java`:

```java
package com.jaram.be.seminar.dto;

// Matches OpenAPI schema AttendeePreviewEntry. No sid — unlike RosterEntry, open to any member.
public record AttendeePreviewEntry(String name, String at) { }
```

Add `src/main/java/com/jaram/be/seminar/dto/AttendeePreviewResponse.java`:

```java
package com.jaram.be.seminar.dto;

import java.util.List;

// Matches OpenAPI schema AttendeePreviewResponse.
public record AttendeePreviewResponse(int count, List<AttendeePreviewEntry> list) { }
```

Modify `src/main/java/com/jaram/be/seminar/SeminarService.java` — add these two imports:

```java
import com.jaram.be.seminar.dto.AttendeePreviewEntry;
import com.jaram.be.seminar.dto.AttendeePreviewResponse;
```

and this method (place it after `roster(...)`, before the private `formatTime` helper):

```java
    @Transactional(readOnly = true)
    public AttendeePreviewResponse attendeePreview(String seminarId) {
        seminars.findById(seminarId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다."));

        List<Attendance> rows = attendances.findBySeminarIdOrderByAtAsc(seminarId);
        Map<String, Member> byId = members.findAllById(
                        rows.stream().map(Attendance::getMemberId).toList()).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));

        List<AttendeePreviewEntry> list = rows.stream().map(a -> {
            Member m = byId.get(a.getMemberId());
            return new AttendeePreviewEntry(m == null ? null : m.getName(), formatTime(a.getAt()));
        }).toList();

        return new AttendeePreviewResponse(list.size(), list);
    }
```

Modify `src/main/java/com/jaram/be/seminar/SeminarController.java` — add the import `com.jaram.be.seminar.dto.AttendeePreviewResponse` and this handler (after `roster`):

```java
    @GetMapping("/{id}/attendees")
    public AttendeePreviewResponse attendees(@PathVariable String id) {
        return service.attendeePreview(id);
    }
```

- [x] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarAttendeePreviewTest'`
Expected: PASS (3 tests).

- [x] **Step 6: Commit**

Delegate to the `committer` subagent. Files: `docs/api/openapi.yaml`, `src/main/resources/openapi/openapi.yaml`, `src/main/java/com/jaram/be/seminar/dto/AttendeePreviewEntry.java`, `src/main/java/com/jaram/be/seminar/dto/AttendeePreviewResponse.java`, `src/main/java/com/jaram/be/seminar/SeminarService.java`, `src/main/java/com/jaram/be/seminar/SeminarController.java`, `src/test/java/com/jaram/be/seminar/SeminarAttendeePreviewTest.java`.

---

### Task 3: Contract conformance + full regression

**Files:**
- Test: `src/test/java/com/jaram/be/contract/SeminarContractTest.java`

**Interfaces:**
- Consumes: every seminar endpoint (Tasks 1–2), `JwtProvider`, `MemberRepository`, the seminar repositories. Attaches the `OpenApiValidationFilter` so each real response is validated against the OpenAPI schema.

- [x] **Step 1: Sync, then add the new contract test**

Run: `./scripts/sync-openapi.sh` (no-op — Tasks 1–2 already synced).

Add this test method to `src/test/java/com/jaram/be/contract/SeminarContractTest.java` (after `rosterMatchesContract`, before the private `activeMember()` helper — reuse that existing helper, don't duplicate it):

```java
    @Test
    void attendeesMatchesContract() {
        Member m = members.save(activeMember());
        Seminar s = seminars.save(Seminar.create("세미나", null, null,
                Instant.now(), null, null, "CODE", null, null, "officer-1"));
        attendances.save(Attendance.create(s.getId(), m.getId(), Instant.now()));
        given().filter(validation).header("Authorization", "Bearer " + memberToken)
                .when().get("/api/seminars/" + s.getId() + "/attendees").then().statusCode(200);
    }
```

Do **not** modify `listMatchesContract` or `createMatchesContract` — `listMatchesContract` automatically starts validating the 3 new `Seminar` fields the moment the schema and DTO agree (no test-code change needed), and `createMatchesContract` is the known pre-existing failure (Global Constraints) — the validator throws before any of its own assertions run, so touching it has no effect either way.

- [x] **Step 2: Run the contract test class**

Run: `./gradlew test --tests 'com.jaram.be.contract.SeminarContractTest'`
Expected: PASS for `attendeesMatchesContract` — Task 2 already produced a schema-accurate response, so there's no red phase for this one. `createMatchesContract` still fails with the exact same `additionalProperties: ["capacity"]` error as the Global Constraints baseline (confirm the error message is unchanged, not new). The other 4 (`listMatchesContract`, `attendMatchesContract`, `attendWrongCodeMatchesContract`, `rosterMatchesContract`) pass. Total: 6 tests, 1 known failure.

- [x] **Step 3: Run the full contract suite, then the full build**

Run: `./gradlew test --tests '*ContractTest'`
Expected: every contract test passes except `SeminarContractTest.createMatchesContract` (pre-existing, unrelated).

Run: `./gradlew test`
Expected: `BUILD FAILED` (Gradle fails the run on any red test) but with **exactly one** failing test — `SeminarContractTest.createMatchesContract` — same as the Global Constraints baseline. Confirm this by checking `build/test-results/test/TEST-com.jaram.be.contract.SeminarContractTest.xml` (or the HTML report) names only that one failure. If any other test is red, that's a regression from this plan — stop and debug (superpowers:systematic-debugging) before continuing.

- [x] **Step 4: Commit**

Delegate to the `committer` subagent. Files: `src/test/java/com/jaram/be/contract/SeminarContractTest.java`.

---

## Self-Review

**1. Spec coverage** (Backend section of `2026-07-17-seminar-tab-redesign-design.md`):
- `Seminar.description` column + echo on create → Task 1. ✓
- `SeminarResponse.description`/`attendanceClosesAt`/`attendedAt` → Task 1. ✓
- `SeminarService.list(callerId)` / `toResponse(s, callerId)`, `create()` call-site update → Task 1. ✓
- `SeminarController.list` gains `@AuthenticationPrincipal CurrentMember me` → Task 1. ✓
- `AttendeePreviewEntry`/`AttendeePreviewResponse`, `SeminarService.attendeePreview`, `GET /api/seminars/{id}/attendees` → Task 2. ✓
- SecurityConfig: verified no change needed → Global Constraints + Task 2 note. ✓
- Contract additions (`Seminar`, `SeminarCreateRequest`, `AttendeePreviewEntry`, `AttendeePreviewResponse`, new path) → Tasks 1–2 Step 1 each. ✓
- Edge case "비로그인 + ENDED → attendedAt null" → Task 1's `anonymousListHasClosesAtAndNullAttendedAt`. ✓
- Edge case "callerId 미출석 ENDED → attendedAt null" → Task 1's `authenticatedCallerSeesOwnAttendance`. ✓
- Edge case "정확히 마감 경계 일치" → by construction (`attendanceClosesAt` and `SeminarStatus.of` share the same `windowMinutes`); `SeminarStatusTest` regression covered by Task 3's full-suite run, no code in `SeminarStatus` changes. ✓
- "참석자 미리보기 개수 제한 없음" → `attendeePreview` returns the full list, no pagination added. ✓
- Explicitly out of scope: `capacity`/`target` migration (not touched anywhere), FE tasks (separate repo/plan). ✓

**2. Placeholder scan:** no TBD/"add validation"/"handle edge cases" — every step shows the full file or exact method being added, every test shows real assertions. ✓

**3. Type consistency:** `SeminarResponse`'s 3 new trailing components (`description, attendanceClosesAt, attendedAt`) are identical in Task 1's DTO and every `new SeminarResponse(...)` call in `toResponse`. `AttendeePreviewEntry(String name, String at)` / `AttendeePreviewResponse(int count, List<AttendeePreviewEntry> list)` match across Task 2's DTO files, `SeminarService.attendeePreview`, and `SeminarController.attendees`. ✓

---

## Not covered by this plan

- **Frontend** (`home-jaram-fe`): tab reorder, card click → modal, countdown hook, attended/absent chip, attendee-preview UI. Needs its own plan in that repo, after this one ships (FE consumes the fields/endpoint this plan adds).
- **`Seminar.capacity` → `target: TargetGrade[]`**: pre-existing gap, confirmed still broken (`SeminarContractTest.createMatchesContract`). Worth its own follow-up plan later — not part of this one.
