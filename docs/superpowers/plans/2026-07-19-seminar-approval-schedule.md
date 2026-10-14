# Seminar 승인 흐름 + Schedule 도메인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** openapi.yaml에 이미 반영된 11개 operation(세미나 단건조회·재제출·임원 승인/반려 + Schedule 도메인 전체)의 백엔드를 계약에 정확히 일치하도록 구현한다.

**Architecture:** package-by-feature. 기존 `com.jaram.be.seminar` 확장 + `com.jaram.be.schedule` 신규. Controller(계약 operation 1:1) → Service(`@Transactional`, 상태전이·권한·파생, `ApiException`) → Repository → Entity(정적 팩토리, Lombok 없음). 시각 표시(day/month/weekday/time)는 Asia/Seoul에서 파생하며 저장하지 않는다.

**Tech Stack:** Spring Boot 3.4, Java 21, Gradle, Spring Data JPA, PostgreSQL(Testcontainers), RestAssured + swagger-request-validator(계약 테스트).

## Global Constraints

- 계약 `src/main/resources/openapi/openapi.yaml`(및 심링크 `docs/api/openapi.yaml`)는 **법**이다. 이 작업에서 계약 파일은 편집하지 않는다(이미 동기화됨).
- 에러 봉투는 `GlobalExceptionHandler`가 `ApiException`을 `{code, message, fieldErrors}`로 직렬화한다. 신규 매핑은 **409 → code `"CONFLICT"`** 뿐이며 별도 핸들러 추가 없이 `new ApiException(HttpStatus.CONFLICT, "CONFLICT", ...)`로 처리된다(403→`FORBIDDEN`, 404→`NOT_FOUND`, 422→`VALIDATION` 기존 그대로).
- enum wire 값 = enum name(`@Enumerated(EnumType.STRING)`). ApprovalStatus 값은 `PENDING`/`APPROVED`/`REJECTED`, ScheduleStatus 값은 `OPEN`/`LOCKED`.
- 엔티티는 Lombok 없이 `protected` 무인자 생성자 + 정적 팩토리 + String UUID id. 기존 `Seminar.create(...)` 팩토리(호출처 다수)는 **시그니처 변경 금지** — 신규 필드는 setter/전이 메서드로만.
- 라우트 인가는 `SecurityConfig.filterChain`에서 선언적으로. 컨트롤러에서 권한 판단하지 않는다(단, 세미나 단건 가시성 게이트는 서비스 로직).
- 커밋은 각 task 끝에서만. 계약 심링크는 커밋에 포함하지 않는다.
- 테스트: 엔드포인트 테스트는 `@SpringBootTest(RANDOM_PORT)` + RestAssured + `extends PostgresTest`, 리포지토리 테스트는 `@DataJpaTest` + `extends PostgresTest`, 계약 테스트는 `com.jaram.be.contract` 패키지 + `OpenApiValidationFilter`.
- 검증: 각 task 끝에서 해당 테스트 클래스 실행, 최종 task에서 `./gradlew test` 전체 green.

**설계 문서:** `docs/superpowers/specs/2026-07-19-seminar-approval-schedule-design.md`

---

### Task 1: Seminar 엔티티 — 승인 필드 + 전이 메서드 + 리포지토리 finder

**Files:**
- Create: `src/main/java/com/jaram/be/seminar/ApprovalStatus.java`
- Modify: `src/main/java/com/jaram/be/seminar/Seminar.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarRepository.java`
- Test: `src/test/java/com/jaram/be/seminar/SeminarRepositoryTest.java`

**Interfaces:**
- Produces:
  - `enum com.jaram.be.seminar.ApprovalStatus { PENDING, APPROVED, REJECTED }`
  - `Seminar`: 필드 `scheduleId`(String,nullable)/`approvalStatus`(ApprovalStatus,기본 PENDING)/`rejectReason`(String,nullable); 메서드 `void approve()`, `void reject(String reason)`, `void resubmit()`, `String getScheduleId()`, `void setScheduleId(String)`, `ApprovalStatus getApprovalStatus()`, `String getRejectReason()`
  - `SeminarRepository.findByApprovalStatusOrderByStartsAtDesc(ApprovalStatus)` → `List<Seminar>`

- [ ] **Step 1: Write the failing test**

`SeminarRepositoryTest.java`에 아래 테스트를 추가한다(기존 import에 더해 `import static org.assertj.core.api.Assertions.assertThat;`는 이미 존재).

```java
    @Test
    void defaultsToPendingAndFiltersByApprovalStatus() {
        Seminar pending = Seminar.create("대기", null, null, Instant.now(),
                null, null, "C1", null, null, "officer-1");
        Seminar approved = Seminar.create("승인", null, null, Instant.now(),
                null, null, "C2", null, null, "officer-1");
        approved.approve();
        Seminar rejected = Seminar.create("반려", null, null, Instant.now(),
                null, null, "C3", null, null, "officer-1");
        rejected.reject("사유");
        seminars.save(pending);
        seminars.save(approved);
        seminars.save(rejected);

        assertThat(pending.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(seminars.findByApprovalStatusOrderByStartsAtDesc(ApprovalStatus.APPROVED))
                .extracting(Seminar::getTitle).containsExactly("승인");
        assertThat(rejected.getRejectReason()).isEqualTo("사유");

        rejected.resubmit();
        assertThat(rejected.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(rejected.getRejectReason()).isNull();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarRepositoryTest'`
Expected: 컴파일 실패 — `ApprovalStatus`/`approve()`/`findByApprovalStatusOrderByStartsAtDesc` 미존재.

- [ ] **Step 3: Create the ApprovalStatus enum**

`src/main/java/com/jaram/be/seminar/ApprovalStatus.java`:

```java
package com.jaram.be.seminar;

// 세미나 승인축. 슬롯 제출 PENDING → 임원 approve/reject → 본인 resubmit. Wire = enum name.
public enum ApprovalStatus { PENDING, APPROVED, REJECTED }
```

- [ ] **Step 4: Add fields + transition methods to Seminar**

`Seminar.java` — `description` 필드 선언 바로 아래(현재 23행 뒤)에 신규 필드를 추가:

```java
    private String scheduleId;       // 슬롯 경로로 생성 시 채움; 임원 직접생성은 null

    @Enumerated(EnumType.STRING)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    @Column(length = 1000)
    private String rejectReason;     // approvalStatus==REJECTED일 때만
```

`getVersion()` 게터 아래(현재 72행 뒤, 클래스 닫는 `}` 직전)에 전이 메서드 + 접근자를 추가:

```java
    public void approve() {
        this.approvalStatus = ApprovalStatus.APPROVED;
        this.rejectReason = null;
    }

    public void reject(String reason) {
        this.approvalStatus = ApprovalStatus.REJECTED;
        this.rejectReason = reason;
    }

    public void resubmit() {
        this.approvalStatus = ApprovalStatus.PENDING;
        this.rejectReason = null;
    }

    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String v) { this.scheduleId = v; }
    public ApprovalStatus getApprovalStatus() { return approvalStatus; }
    public String getRejectReason() { return rejectReason; }
```

`Seminar.create(...)` 팩토리는 수정하지 않는다(기본값 `PENDING`은 필드 초기화로 이미 적용됨).

- [ ] **Step 5: Add the repository finder**

`SeminarRepository.java`에 메서드 추가:

```java
    List<Seminar> findByApprovalStatusOrderByStartsAtDesc(ApprovalStatus approvalStatus);
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarRepositoryTest'`
Expected: PASS(신규 테스트 포함 전체 통과).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/jaram/be/seminar/ApprovalStatus.java \
        src/main/java/com/jaram/be/seminar/Seminar.java \
        src/main/java/com/jaram/be/seminar/SeminarRepository.java \
        src/test/java/com/jaram/be/seminar/SeminarRepositoryTest.java
git commit -m "feat(seminar): add approval status, transitions, and approved-status finder"
```

---

### Task 2: 목록 APPROVED-only + 응답 필드 + 임원 create 즉시 APPROVED

**Files:**
- Modify: `src/main/java/com/jaram/be/seminar/dto/SeminarResponse.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java`
- Test: `src/test/java/com/jaram/be/seminar/SeminarListTest.java`
- Test: `src/test/java/com/jaram/be/contract/SeminarContractTest.java` (fixture 승인 처리)

**Interfaces:**
- Consumes: Task 1의 `Seminar.approve()`, `SeminarRepository.findByApprovalStatusOrderByStartsAtDesc`, `getScheduleId/getApprovalStatus/getRejectReason`.
- Produces:
  - `SeminarResponse` 말미 3필드 추가: `String scheduleId, ApprovalStatus approvalStatus, String rejectReason`.
  - `SeminarService.toResponse(Seminar, String)`를 **public**으로 승격(Task 9에서 schedule 패키지가 호출).
  - `SeminarService.list(callerId)`는 APPROVED만 반환. `SeminarService.create(...)`는 저장 세미나를 APPROVED로.

- [ ] **Step 1: Update existing list fixtures to APPROVED (make the change testable)**

목록이 APPROVED만 반환하도록 바뀌므로, 기존 `SeminarListTest`의 세미나 fixture는 모두 승인 상태여야 한다. `SeminarListTest.java`에서 각 `seminars.save(Seminar.create(...))` 호출을 승인 후 저장으로 바꾼다. 구체적으로 4개 테스트의 저장부를 아래 헬퍼 방식으로 통일 — 클래스 하단(닫는 `}` 직전)에 헬퍼를 추가:

```java
    private Seminar approved(Seminar s) { s.approve(); return s; }
```

그리고 각 `seminars.save(Seminar.create(...))` → `seminars.save(approved(Seminar.create(...)))`로 변경한다(총 6곳: `listsNewestFirst...`의 2곳, `anonymousListHasClosesAtAndNullAttendedAt`의 1곳, `authenticatedCallerSeesOwnAttendance`의 2곳 + attended/notAttended). `attend`/`roster`가 아닌 순수 목록 fixture만 대상이며, 이 테스트 클래스의 모든 저장은 목록 노출을 전제하므로 전부 승인 처리한다.

- [ ] **Step 2: Add a failing test that PENDING/REJECTED are hidden from the list**

`SeminarListTest.java`에 추가:

```java
    @Test
    void listExcludesPendingAndRejected() {
        seminars.save(approved(Seminar.create("공개", null, null, Instant.now(),
                null, null, "C1", null, null, "officer-1")));
        seminars.save(Seminar.create("대기", null, null, Instant.now(),
                null, null, "C2", null, null, "officer-1")); // PENDING 기본
        Seminar rej = Seminar.create("반려", null, null, Instant.now(),
                null, null, "C3", null, null, "officer-1");
        rej.reject("사유");
        seminars.save(rej);

        given().when().get("/api/seminars").then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].title", equalTo("공개"))
                .body("[0].approvalStatus", equalTo("APPROVED"));
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarListTest'`
Expected: `listExcludesPendingAndRejected` FAIL — 현재 목록은 전체 반환(size 3)이며 `approvalStatus` 필드도 응답에 없음.

- [ ] **Step 4: Add the response fields**

`SeminarResponse.java` — import 추가 `import com.jaram.be.seminar.ApprovalStatus;`, record 말미(`attendedAt` 뒤)에 3필드 추가:

```java
        String attendedAt,
        String scheduleId,
        ApprovalStatus approvalStatus,
        String rejectReason
```

- [ ] **Step 5: Filter the list, approve on create, populate response fields**

`SeminarService.java`:

`list(...)` 본문의 `seminars.findAllByOrderByStartsAtDesc()`를 교체:

```java
    @Transactional(readOnly = true)
    public List<SeminarResponse> list(String callerId) {
        return seminars.findByApprovalStatusOrderByStartsAtDesc(ApprovalStatus.APPROVED).stream()
                .map(s -> toResponse(s, callerId)).toList();
    }
```

`create(...)`는 저장 전에 승인(임원 직접 생성은 승인 흐름 밖):

```java
    @Transactional
    public SeminarResponse create(SeminarCreateRequest req, String createdById) {
        Seminar s = Seminar.create(
                req.title(), req.speaker(), req.topic(), req.startsAt(),
                req.place(), req.mode(), req.attendanceCode(),
                req.materialUrl(), req.capacity(), createdById);
        s.setDescription(req.description());
        s.approve();
        Seminar saved = seminars.save(s);
        return toResponse(saved, createdById);
    }
```

`toResponse(...)`를 **public**으로 바꾸고 마지막 3필드를 채운다 — 시그니처를 `public SeminarResponse toResponse(...)`로, `return new SeminarResponse(...)`의 `attendedAt` 뒤에 추가:

```java
                attendedAt,
                s.getScheduleId(),
                s.getApprovalStatus(),
                s.getRejectReason());
```

- [ ] **Step 6: Fix the contract test list fixture**

`SeminarContractTest.java`의 `listMatchesContract`에서 저장 세미나를 승인 처리(목록 노출 보장):

```java
    @Test
    void listMatchesContract() {
        Seminar s = Seminar.create("세미나", "김연사", "주제", Instant.now(),
                "IT관", "offline", "CODE", "https://m.example.com/a", 30, "officer-1");
        s.approve();
        seminars.save(s);
        given().filter(validation).when().get("/api/seminars").then().statusCode(200);
    }
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarListTest' --tests 'com.jaram.be.seminar.SeminarCreateTest' --tests 'com.jaram.be.contract.SeminarContractTest'`
Expected: PASS(목록 필터·응답 필드·create 승인 반영).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/jaram/be/seminar/dto/SeminarResponse.java \
        src/main/java/com/jaram/be/seminar/SeminarService.java \
        src/test/java/com/jaram/be/seminar/SeminarListTest.java \
        src/test/java/com/jaram/be/contract/SeminarContractTest.java
git commit -m "feat(seminar): expose approval fields, filter list to APPROVED, approve on officer create"
```

---

### Task 3: GET /api/seminars/{id} 단건 조회 + 가시성 게이트 + 보안 라우팅

**Files:**
- Modify: `src/main/java/com/jaram/be/seminar/SeminarController.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java`
- Modify: `src/main/java/com/jaram/be/security/SecurityConfig.java`
- Test: `src/test/java/com/jaram/be/seminar/SeminarDetailTest.java` (Create)

**Interfaces:**
- Consumes: Task 2의 public `toResponse`, `ApprovalStatus`.
- Produces: `SeminarService.getOne(String id, String callerId, boolean officer)` → `SeminarResponse` (APPROVED 공개; PENDING/REJECTED는 본인 또는 officer만, 그 외 404).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/seminar/SeminarDetailTest.java`:

```java
package com.jaram.be.seminar;

import com.jaram.be.member.Authority;
import com.jaram.be.support.PostgresTest;
import com.jaram.be.security.JwtProvider;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarDetailTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired JwtProvider jwt;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
    }

    private Seminar save(String title, String owner, java.util.function.Consumer<Seminar> mut) {
        Seminar s = Seminar.create(title, null, null, Instant.now(),
                null, null, "CODE", null, null, owner);
        mut.accept(s);
        return seminars.save(s);
    }

    @Test
    void approvedIsPublic() {
        Seminar s = save("공개", "officer-1", Seminar::approve);
        given().when().get("/api/seminars/" + s.getId()).then().statusCode(200)
                .body("title", equalTo("공개"))
                .body("approvalStatus", equalTo("APPROVED"));
    }

    @Test
    void pendingHiddenFromStrangerAs404() {
        Seminar s = save("대기", "owner-1", x -> {}); // PENDING
        String other = jwt.generate("member-2", "남", "b@hanyang.ac.kr", Authority.MEMBER);
        given().header("Authorization", "Bearer " + other)
                .when().get("/api/seminars/" + s.getId()).then().statusCode(404);
    }

    @Test
    void pendingVisibleToOwner() {
        Seminar s = save("대기", "owner-1", x -> {});
        String owner = jwt.generate("owner-1", "주인", "o@hanyang.ac.kr", Authority.MEMBER);
        given().header("Authorization", "Bearer " + owner)
                .when().get("/api/seminars/" + s.getId()).then().statusCode(200)
                .body("approvalStatus", equalTo("PENDING"));
    }

    @Test
    void rejectedVisibleToOfficer() {
        Seminar s = save("반려", "owner-1", x -> x.reject("사유"));
        String officer = jwt.generate("officer-9", "임원", "of@hanyang.ac.kr", Authority.OFFICER);
        given().header("Authorization", "Bearer " + officer)
                .when().get("/api/seminars/" + s.getId()).then().statusCode(200)
                .body("rejectReason", equalTo("사유"));
    }

    @Test
    void missingReturns404() {
        given().when().get("/api/seminars/nope").then().statusCode(404);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarDetailTest'`
Expected: FAIL — 단건 라우트는 현재 `authenticated()`로 막히거나 핸들러 미존재.

- [ ] **Step 3: Add the service method**

`SeminarService.java`에 추가(`list` 아래 등 적절한 위치):

```java
    @Transactional(readOnly = true)
    public SeminarResponse getOne(String id, String callerId, boolean officer) {
        Seminar s = seminars.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다."));
        if (s.getApprovalStatus() != ApprovalStatus.APPROVED) {
            boolean owner = callerId != null && callerId.equals(s.getCreatedById());
            if (!owner && !officer) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다.");
            }
        }
        return toResponse(s, callerId);
    }
```

- [ ] **Step 4: Add the controller handler**

`SeminarController.java` — import `com.jaram.be.member.Authority` 추가, `create` 아래에:

```java
    @GetMapping("/{id}")
    public SeminarResponse getOne(@PathVariable String id,
                                  @AuthenticationPrincipal CurrentMember me) {
        return service.getOne(id, me == null ? null : me.id(),
                me != null && me.authority() == Authority.OFFICER);
    }
```

- [ ] **Step 5: Permit GET /api/seminars/* (single-segment) publicly**

`SeminarConfig`가 아니라 `SecurityConfig.java`의 `authorizeHttpRequests` 블록에서, 기존 `.requestMatchers(HttpMethod.GET, "/api/seminars/*/roster").hasAuthority("OFFICER")` 줄 **바로 아래**에 추가(roster/attendees는 2세그먼트라 `*` 단일 세그먼트 매처에 걸리지 않음):

```java
                .requestMatchers(HttpMethod.GET, "/api/seminars/*").permitAll()
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarDetailTest' --tests 'com.jaram.be.seminar.SeminarRosterTest' --tests 'com.jaram.be.seminar.SeminarAttendeePreviewTest'`
Expected: PASS(단건 가시성 통과, roster/attendees 인가 회귀 없음).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/jaram/be/seminar/SeminarController.java \
        src/main/java/com/jaram/be/seminar/SeminarService.java \
        src/main/java/com/jaram/be/security/SecurityConfig.java \
        src/test/java/com/jaram/be/seminar/SeminarDetailTest.java
git commit -m "feat(seminar): add single GET with visibility gate"
```

---

### Task 4: PATCH /api/seminars/{id} 반려본 재제출 (본인)

**Files:**
- Modify: `src/main/java/com/jaram/be/seminar/Seminar.java` (setStartsAt/setMaterialUrl 추가)
- Modify: `src/main/java/com/jaram/be/seminar/SeminarController.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java`
- Test: `src/test/java/com/jaram/be/seminar/SeminarResubmitTest.java` (Create)

**Interfaces:**
- Consumes: `SeminarCreateRequest`, `Seminar.resubmit()`, `getScheduleId`, `getApprovalStatus`.
- Produces: `SeminarService.resubmit(String id, SeminarCreateRequest req, String callerId)` → `SeminarResponse`. 규칙: 본인 아님→403, REJECTED 아님→409, 성공 시 필드 갱신 후 PENDING. `scheduleId != null`이면 startsAt/place/mode는 유지, attendanceCode는 항상 무시.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/seminar/SeminarResubmitTest.java`:

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
import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarResubmitTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired JwtProvider jwt;

    private String ownerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
        ownerToken = jwt.generate("owner-1", "주인", "o@hanyang.ac.kr", Authority.MEMBER);
    }

    private Seminar rejected(String owner, String scheduleId) {
        Seminar s = Seminar.create("옛제목", null, null, Instant.parse("2026-01-01T00:00:00Z"),
                "옛장소", "offline", "CODE", null, null, owner);
        s.reject("보완 필요");
        s.setScheduleId(scheduleId);
        return seminars.save(s);
    }

    @Test
    void ownerResubmitsRejectedGoesPending() {
        Seminar s = rejected("owner-1", null);
        Map<String, Object> body = new HashMap<>();
        body.put("title", "새제목");
        body.put("startsAt", "2026-09-01T10:00:00Z");
        body.put("place", "새장소");

        given().header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").body(body)
                .when().patch("/api/seminars/" + s.getId()).then().statusCode(200)
                .body("title", equalTo("새제목"))
                .body("approvalStatus", equalTo("PENDING"))
                .body("rejectReason", equalTo(null))
                .body("place", equalTo("새장소"));
    }

    @Test
    void slotLinkedResubmitKeepsScheduleTime() {
        Seminar s = rejected("owner-1", "sched-1");
        Map<String, Object> body = new HashMap<>();
        body.put("title", "새제목");
        body.put("startsAt", "2099-09-01T10:00:00Z"); // 무시돼야 함
        body.put("place", "무시장소");                 // 무시돼야 함

        given().header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").body(body)
                .when().patch("/api/seminars/" + s.getId()).then().statusCode(200)
                .body("title", equalTo("새제목"))
                .body("place", equalTo("옛장소"))
                .body("startsAt", equalTo("2026-01-01T00:00:00Z"));

        assertThat(seminars.findById(s.getId()).orElseThrow().getStartsAt())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void nonOwnerGets403() {
        Seminar s = rejected("someone-else", null);
        given().header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").body(Map.of("title", "x", "startsAt", "2026-09-01T10:00:00Z"))
                .when().patch("/api/seminars/" + s.getId()).then().statusCode(403);
    }

    @Test
    void notRejectedGets409() {
        Seminar s = Seminar.create("승인됨", null, null, Instant.now(),
                null, null, "CODE", null, null, "owner-1");
        s.approve();
        seminars.save(s);
        given().header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").body(Map.of("title", "x", "startsAt", "2026-09-01T10:00:00Z"))
                .when().patch("/api/seminars/" + s.getId()).then().statusCode(409)
                .body("code", equalTo("CONFLICT"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarResubmitTest'`
Expected: FAIL — PATCH 핸들러/서비스 미존재.

- [ ] **Step 3: Add the two setters to Seminar**

`Seminar.java`에서 `setPlace`/`setMode` 근처에 재제출용 setter 추가:

```java
    public void setStartsAt(Instant v) { this.startsAt = v; }
    public void setMaterialUrl(String v) { this.materialUrl = v; }
```

- [ ] **Step 4: Add the service method**

`SeminarService.java`:

```java
    @Transactional
    public SeminarResponse resubmit(String id, SeminarCreateRequest req, String callerId) {
        Seminar s = seminars.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다."));
        if (!callerId.equals(s.getCreatedById())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "본인 세미나만 수정할 수 있습니다.");
        }
        if (s.getApprovalStatus() != ApprovalStatus.REJECTED) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "반려된 세미나만 재제출할 수 있습니다.");
        }
        s.setTitle(req.title());
        s.setSpeaker(req.speaker());
        s.setTopic(req.topic());
        s.setMaterialUrl(req.materialUrl());
        s.setDescription(req.description());
        s.setCapacity(req.capacity());
        // 슬롯 연동 세미나는 시간/장소/모드를 Schedule 값으로 유지(요청 무시). attendanceCode는 항상 무시.
        if (s.getScheduleId() == null) {
            s.setStartsAt(req.startsAt());
            s.setPlace(req.place());
            s.setMode(req.mode());
        }
        s.resubmit();
        return toResponse(s, callerId);
    }
```

- [ ] **Step 5: Add the controller handler**

`SeminarController.java` — `getOne` 아래:

```java
    @PatchMapping("/{id}")
    public SeminarResponse resubmit(@PathVariable String id,
                                    @Valid @RequestBody SeminarCreateRequest req,
                                    @AuthenticationPrincipal CurrentMember me) {
        return service.resubmit(id, req, me.id());
    }
```

`PATCH /api/seminars/{id}`는 `SecurityConfig`의 `.anyRequest().authenticated()`로 이미 커버되므로 보안 설정 변경 불필요.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarResubmitTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/jaram/be/seminar/Seminar.java \
        src/main/java/com/jaram/be/seminar/SeminarController.java \
        src/main/java/com/jaram/be/seminar/SeminarService.java \
        src/test/java/com/jaram/be/seminar/SeminarResubmitTest.java
git commit -m "feat(seminar): add rejected-seminar resubmit (owner-only)"
```

---

### Task 5: 임원 세미나 승인/반려 (POST /api/admin/seminars/{id}/approve|reject)

**Files:**
- Create: `src/main/java/com/jaram/be/seminar/dto/RejectRequest.java`
- Create: `src/main/java/com/jaram/be/seminar/AdminSeminarController.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java`
- Test: `src/test/java/com/jaram/be/seminar/SeminarApprovalTest.java` (Create)

**Interfaces:**
- Consumes: `Seminar.approve()/reject(reason)`, public `toResponse`.
- Produces:
  - `record com.jaram.be.seminar.dto.RejectRequest(@NotBlank String reason)`.
  - `SeminarService.approve(String id)` → `SeminarResponse`; `SeminarService.reject(String id, String reason)` → `SeminarResponse`.
  - `AdminSeminarController` @ `/api/admin/seminars`: `POST /{id}/approve` 200+Seminar, `POST /{id}/reject` 200+Seminar.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/seminar/SeminarApprovalTest.java`:

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
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarApprovalTest extends PostgresTest {

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
        officerToken = jwt.generate("officer-1", "임원", "of@hanyang.ac.kr", Authority.OFFICER);
        memberToken = jwt.generate("member-1", "회원", "me@hanyang.ac.kr", Authority.MEMBER);
    }

    private Seminar pending() {
        return seminars.save(Seminar.create("대기", null, null, Instant.now(),
                null, null, "CODE", null, null, "member-1"));
    }

    @Test
    void officerApproves() {
        Seminar s = pending();
        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/approve").then().statusCode(200)
                .body("approvalStatus", equalTo("APPROVED"));
    }

    @Test
    void officerRejectsWithReason() {
        Seminar s = pending();
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of("reason", "보완 필요"))
                .when().post("/api/admin/seminars/" + s.getId() + "/reject").then().statusCode(200)
                .body("approvalStatus", equalTo("REJECTED"))
                .body("rejectReason", equalTo("보완 필요"));
    }

    @Test
    void rejectWithoutReasonIs422() {
        Seminar s = pending();
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of())
                .when().post("/api/admin/seminars/" + s.getId() + "/reject").then().statusCode(422);
    }

    @Test
    void memberCannotApprove() {
        Seminar s = pending();
        given().header("Authorization", "Bearer " + memberToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/approve").then().statusCode(403);
    }

    @Test
    void approveMissingIs404() {
        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/nope/approve").then().statusCode(404);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarApprovalTest'`
Expected: FAIL — admin 세미나 컨트롤러/서비스 미존재.

- [ ] **Step 3: Create the RejectRequest DTO**

`src/main/java/com/jaram/be/seminar/dto/RejectRequest.java`:

```java
package com.jaram.be.seminar.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectRequest(@NotBlank String reason) { }
```

- [ ] **Step 4: Add service methods**

`SeminarService.java`:

```java
    @Transactional
    public SeminarResponse approve(String id) {
        Seminar s = seminars.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다."));
        s.approve();
        return toResponse(s, null);
    }

    @Transactional
    public SeminarResponse reject(String id, String reason) {
        Seminar s = seminars.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "세미나를 찾을 수 없습니다."));
        s.reject(reason);
        return toResponse(s, null);
    }
```

- [ ] **Step 5: Create the admin controller**

`src/main/java/com/jaram/be/seminar/AdminSeminarController.java`:

```java
package com.jaram.be.seminar;

import com.jaram.be.seminar.dto.RejectRequest;
import com.jaram.be.seminar.dto.SeminarResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/seminars")
public class AdminSeminarController {

    private final SeminarService service;

    public AdminSeminarController(SeminarService service) { this.service = service; }

    @PostMapping("/{id}/approve")
    public SeminarResponse approve(@PathVariable String id) {
        return service.approve(id);
    }

    @PostMapping("/{id}/reject")
    public SeminarResponse reject(@PathVariable String id, @Valid @RequestBody RejectRequest req) {
        return service.reject(id, req.reason());
    }
}
```

`/api/admin/**`는 `SecurityConfig`의 `hasAuthority("OFFICER")`가 이미 커버 — 보안 변경 불필요.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.seminar.SeminarApprovalTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/jaram/be/seminar/dto/RejectRequest.java \
        src/main/java/com/jaram/be/seminar/AdminSeminarController.java \
        src/main/java/com/jaram/be/seminar/SeminarService.java \
        src/test/java/com/jaram/be/seminar/SeminarApprovalTest.java
git commit -m "feat(seminar): add officer approve/reject endpoints"
```

---

### Task 6: Schedule 도메인 엔티티 + 리포지토리

**Files:**
- Create: `src/main/java/com/jaram/be/schedule/ScheduleStatus.java`
- Create: `src/main/java/com/jaram/be/schedule/Schedule.java`
- Create: `src/main/java/com/jaram/be/schedule/ScheduleSlot.java`
- Create: `src/main/java/com/jaram/be/schedule/ScheduleRepository.java`
- Test: `src/test/java/com/jaram/be/schedule/ScheduleRepositoryTest.java` (Create)

**Interfaces:**
- Produces:
  - `enum ScheduleStatus { OPEN, LOCKED }`
  - `Schedule`: `static Schedule create(Instant startsAt, String place, String mode, int capacity)`(빈 슬롯 index 0..capacity-1 생성), `void lock()`, getters `getId/getStartsAt/getPlace/getMode/getCapacity()(Integer)/getStatus/getSlots()(List<ScheduleSlot>, index ASC)`.
  - `ScheduleSlot`: `void claim(String memberId)`, `void release()`, `void attachSeminar(String seminarId)`, getters `getIndex()(int)/getMemberId/getSeminarId`.
  - `ScheduleRepository.findAllByOrderByStartsAtAsc()` → `List<Schedule>`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/schedule/ScheduleRepositoryTest.java`:

```java
package com.jaram.be.schedule;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ScheduleRepositoryTest extends PostgresTest {

    @Autowired ScheduleRepository schedules;

    @BeforeEach void clean() { schedules.deleteAll(); }

    @Test
    void createsCapacitySlotsAndCascades() {
        Schedule s = Schedule.create(Instant.parse("2026-06-27T10:00:00Z"), "IT관", "offline", 3);
        schedules.save(s);

        Schedule loaded = schedules.findById(s.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ScheduleStatus.OPEN);
        assertThat(loaded.getCapacity()).isEqualTo(3);
        assertThat(loaded.getSlots()).hasSize(3);
        assertThat(loaded.getSlots()).extracting(ScheduleSlot::getIndex).containsExactly(0, 1, 2);
        assertThat(loaded.getSlots()).allSatisfy(slot -> {
            assertThat(slot.getMemberId()).isNull();
            assertThat(slot.getSeminarId()).isNull();
        });
    }

    @Test
    void claimReleaseAndLockPersist() {
        Schedule s = Schedule.create(Instant.now(), null, null, 2);
        s.getSlots().get(0).claim("member-1");
        s.getSlots().get(0).attachSeminar("sem-1");
        s.lock();
        schedules.save(s);

        Schedule loaded = schedules.findById(s.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ScheduleStatus.LOCKED);
        assertThat(loaded.getSlots().get(0).getMemberId()).isEqualTo("member-1");
        assertThat(loaded.getSlots().get(0).getSeminarId()).isEqualTo("sem-1");

        loaded.getSlots().get(0).release();
        schedules.save(loaded);
        Schedule again = schedules.findById(s.getId()).orElseThrow();
        assertThat(again.getSlots().get(0).getMemberId()).isNull();
        assertThat(again.getSlots().get(0).getSeminarId()).isNull();
    }

    @Test
    void ordersByStartsAtAsc() {
        schedules.save(Schedule.create(Instant.parse("2026-06-27T10:00:00Z"), null, null, 1));
        schedules.save(Schedule.create(Instant.parse("2026-06-20T10:00:00Z"), null, null, 1));
        List<Schedule> all = schedules.findAllByOrderByStartsAtAsc();
        assertThat(all).extracting(Schedule::getStartsAt)
                .containsExactly(Instant.parse("2026-06-20T10:00:00Z"), Instant.parse("2026-06-27T10:00:00Z"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.schedule.ScheduleRepositoryTest'`
Expected: 컴파일 실패 — schedule 패키지 미존재.

- [ ] **Step 3: Create ScheduleStatus**

`src/main/java/com/jaram/be/schedule/ScheduleStatus.java`:

```java
package com.jaram.be.schedule;

// 임원 수동 토글로만 LOCKED. 역방향(재오픈) 없음. Wire = enum name.
public enum ScheduleStatus { OPEN, LOCKED }
```

- [ ] **Step 4: Create ScheduleSlot**

`src/main/java/com/jaram/be/schedule/ScheduleSlot.java`:

```java
package com.jaram.be.schedule;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "schedule_slot")
public class ScheduleSlot {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @Column(name = "slot_index")
    private int index;

    private String memberId;    // nullable — 빈 슬롯
    private String seminarId;   // nullable — 제출 전

    protected ScheduleSlot() { }

    static ScheduleSlot create(Schedule schedule, int index) {
        ScheduleSlot s = new ScheduleSlot();
        s.id = UUID.randomUUID().toString();
        s.schedule = schedule;
        s.index = index;
        return s;
    }

    public void claim(String memberId) { this.memberId = memberId; }
    public void release() { this.memberId = null; this.seminarId = null; }
    public void attachSeminar(String seminarId) { this.seminarId = seminarId; }

    public String getId() { return id; }
    public int getIndex() { return index; }
    public String getMemberId() { return memberId; }
    public String getSeminarId() { return seminarId; }
}
```

- [ ] **Step 5: Create Schedule**

`src/main/java/com/jaram/be/schedule/Schedule.java`:

```java
package com.jaram.be.schedule;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "schedule")
public class Schedule {

    @Id
    private String id;

    private Instant startsAt;
    private String place;   // nullable
    private String mode;    // nullable
    private Integer capacity;

    @Enumerated(EnumType.STRING)
    private ScheduleStatus status = ScheduleStatus.OPEN;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("index ASC")
    private List<ScheduleSlot> slots = new ArrayList<>();

    @Version
    private Long version;   // 선착순 claim 낙관적 잠금

    protected Schedule() { }

    public static Schedule create(Instant startsAt, String place, String mode, int capacity) {
        Schedule s = new Schedule();
        s.id = UUID.randomUUID().toString();
        s.startsAt = startsAt;
        s.place = place;
        s.mode = mode;
        s.capacity = capacity;
        s.status = ScheduleStatus.OPEN;
        for (int i = 0; i < capacity; i++) {
            s.slots.add(ScheduleSlot.create(s, i));
        }
        return s;
    }

    public void lock() { this.status = ScheduleStatus.LOCKED; }

    public String getId() { return id; }
    public Instant getStartsAt() { return startsAt; }
    public String getPlace() { return place; }
    public String getMode() { return mode; }
    public Integer getCapacity() { return capacity; }
    public ScheduleStatus getStatus() { return status; }
    public List<ScheduleSlot> getSlots() { return slots; }
    public Long getVersion() { return version; }
}
```

- [ ] **Step 6: Create ScheduleRepository**

`src/main/java/com/jaram/be/schedule/ScheduleRepository.java`:

```java
package com.jaram.be.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, String> {
    List<Schedule> findAllByOrderByStartsAtAsc();
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.schedule.ScheduleRepositoryTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/jaram/be/schedule/ScheduleStatus.java \
        src/main/java/com/jaram/be/schedule/Schedule.java \
        src/main/java/com/jaram/be/schedule/ScheduleSlot.java \
        src/main/java/com/jaram/be/schedule/ScheduleRepository.java \
        src/test/java/com/jaram/be/schedule/ScheduleRepositoryTest.java
git commit -m "feat(schedule): add Schedule/ScheduleSlot entities with fixed slots"
```

---

### Task 7: GET /api/schedules 목록 + DTO + 서비스 파생 + 보안 라우팅

**Files:**
- Create: `src/main/java/com/jaram/be/schedule/dto/SlotMember.java`
- Create: `src/main/java/com/jaram/be/schedule/dto/ScheduleSlotResponse.java`
- Create: `src/main/java/com/jaram/be/schedule/dto/ScheduleResponse.java`
- Create: `src/main/java/com/jaram/be/schedule/dto/ScheduleCreateRequest.java`
- Create: `src/main/java/com/jaram/be/schedule/ScheduleService.java`
- Create: `src/main/java/com/jaram/be/schedule/ScheduleController.java`
- Modify: `src/main/java/com/jaram/be/security/SecurityConfig.java`
- Test: `src/test/java/com/jaram/be/schedule/ScheduleListTest.java` (Create)

**Interfaces:**
- Consumes: Task 6 엔티티/리포지토리, `com.jaram.be.seminar.{ApprovalStatus,Seminar,SeminarRepository,SeminarService}`, `MemberRepository`.
- Produces:
  - `record SlotMember(String id, String name)`
  - `record ScheduleSlotResponse(int index, SlotMember member, String seminarId, ApprovalStatus seminarApprovalStatus, String seminarRejectReason)`
  - `record ScheduleResponse(String id, String startsAt, String day, String month, String weekday, String time, String place, String mode, Integer capacity, ScheduleStatus status, List<ScheduleSlotResponse> slots)`
  - `record ScheduleCreateRequest(@NotNull Instant startsAt, String place, String mode, Integer capacity)`
  - `ScheduleService.list()` → `List<ScheduleResponse>` + private `toResponse(Schedule)`; 후속 task가 claim/cancel/submit/create/lock/forceRelease를 이 서비스에 추가.
  - `ScheduleController` @ `/api/schedules`: `GET` → list.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/schedule/ScheduleListTest.java`:

```java
package com.jaram.be.schedule;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
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
class ScheduleListTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired ScheduleRepository schedules;
    @Autowired MemberRepository members;

    @BeforeEach void setup() {
        RestAssured.port = port;
        schedules.deleteAll();
        members.deleteAll();
    }

    @Test
    void listsSchedulesWithDerivedFieldsAndSlots() {
        Member m = Member.newPending("김회원", "2023000001", "a@hanyang.ac.kr", "hash");
        m.setStatus(MemberStatus.ACTIVE);
        m = members.save(m);
        // 2026-06-27T10:00:00Z == 2026-06-27 19:00 KST (토)
        Schedule s = Schedule.create(Instant.parse("2026-06-27T10:00:00Z"), "IT관 401", "offline", 3);
        s.getSlots().get(0).claim(m.getId());
        schedules.save(s);

        given().when().get("/api/schedules").then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].day", equalTo("27"))
                .body("[0].month", equalTo("6월"))
                .body("[0].weekday", equalTo("토"))
                .body("[0].time", equalTo("19:00"))
                .body("[0].capacity", equalTo(3))
                .body("[0].status", equalTo("OPEN"))
                .body("[0].slots.size()", equalTo(3))
                .body("[0].slots[0].index", equalTo(0))
                .body("[0].slots[0].member.name", equalTo("김회원"))
                .body("[0].slots[1].member", nullValue())
                .body("[0].slots[0].seminarId", nullValue());
    }

    @Test
    void emptyReturnsEmptyArray() {
        given().when().get("/api/schedules").then().statusCode(200).body("size()", equalTo(0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.schedule.ScheduleListTest'`
Expected: 컴파일/404 실패 — DTO/서비스/컨트롤러 미존재.

- [ ] **Step 3: Create the DTOs**

`src/main/java/com/jaram/be/schedule/dto/SlotMember.java`:

```java
package com.jaram.be.schedule.dto;

public record SlotMember(String id, String name) { }
```

`src/main/java/com/jaram/be/schedule/dto/ScheduleSlotResponse.java`:

```java
package com.jaram.be.schedule.dto;

import com.jaram.be.seminar.ApprovalStatus;

public record ScheduleSlotResponse(
        int index,
        SlotMember member,                       // 빈 슬롯이면 null
        String seminarId,                        // 미제출이면 null
        ApprovalStatus seminarApprovalStatus,    // seminarId 없으면 null
        String seminarRejectReason               // REJECTED일 때만
) { }
```

`src/main/java/com/jaram/be/schedule/dto/ScheduleResponse.java`:

```java
package com.jaram.be.schedule.dto;

import com.jaram.be.schedule.ScheduleStatus;
import java.util.List;

public record ScheduleResponse(
        String id,
        String startsAt,
        String day,
        String month,
        String weekday,
        String time,
        String place,
        String mode,
        Integer capacity,
        ScheduleStatus status,
        List<ScheduleSlotResponse> slots
) { }
```

`src/main/java/com/jaram/be/schedule/dto/ScheduleCreateRequest.java`:

```java
package com.jaram.be.schedule.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record ScheduleCreateRequest(
        @NotNull Instant startsAt,
        String place,
        String mode,
        Integer capacity
) { }
```

- [ ] **Step 4: Create the service (list + toResponse + helpers)**

`src/main/java/com/jaram/be/schedule/ScheduleService.java`:

```java
package com.jaram.be.schedule;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.schedule.dto.ScheduleResponse;
import com.jaram.be.schedule.dto.ScheduleSlotResponse;
import com.jaram.be.schedule.dto.SlotMember;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 일정/슬롯 상태전이와 슬롯 응답 파생. 슬롯의 member 이름·세미나 승인상태는 저장하지
 * 않고 Member/Seminar 배치 조회로 얹는다. 시각 표시는 Asia/Seoul 파생.
 */
@Service
public class ScheduleService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] WEEKDAYS = {"월", "화", "수", "목", "금", "토", "일"};

    private final ScheduleRepository schedules;
    private final MemberRepository members;
    private final SeminarRepository seminars;

    public ScheduleService(ScheduleRepository schedules, MemberRepository members,
                           SeminarRepository seminars) {
        this.schedules = schedules;
        this.members = members;
        this.seminars = seminars;
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> list() {
        return schedules.findAllByOrderByStartsAtAsc().stream().map(this::toResponse).toList();
    }

    ScheduleResponse toResponse(Schedule s) {
        List<ScheduleSlot> slots = s.getSlots();
        Map<String, String> names = members.findAllById(
                        slots.stream().map(ScheduleSlot::getMemberId).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Member::getId, Member::getName));
        Map<String, Seminar> semById = seminars.findAllById(
                        slots.stream().map(ScheduleSlot::getSeminarId).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Seminar::getId, Function.identity()));

        List<ScheduleSlotResponse> slotDtos = slots.stream().map(slot -> {
            SlotMember member = slot.getMemberId() == null ? null
                    : new SlotMember(slot.getMemberId(), names.getOrDefault(slot.getMemberId(), null));
            Seminar sem = slot.getSeminarId() == null ? null : semById.get(slot.getSeminarId());
            return new ScheduleSlotResponse(
                    slot.getIndex(), member, slot.getSeminarId(),
                    sem == null ? null : sem.getApprovalStatus(),
                    sem == null ? null : sem.getRejectReason());
        }).toList();

        ZonedDateTime t = s.getStartsAt().atZone(SEOUL);
        return new ScheduleResponse(
                s.getId(), s.getStartsAt().toString(),
                String.valueOf(t.getDayOfMonth()), t.getMonthValue() + "월",
                WEEKDAYS[t.getDayOfWeek().getValue() - 1], t.format(HHMM),
                s.getPlace(), s.getMode(), s.getCapacity(), s.getStatus(), slotDtos);
    }
}
```

- [ ] **Step 5: Create the controller (list only)**

`src/main/java/com/jaram/be/schedule/ScheduleController.java`:

```java
package com.jaram.be.schedule;

import com.jaram.be.schedule.dto.ScheduleResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) { this.service = service; }

    @GetMapping
    public List<ScheduleResponse> list() { return service.list(); }
}
```

- [ ] **Step 6: Permit GET /api/schedules publicly**

`SecurityConfig.java` — Task 3에서 추가한 `.requestMatchers(HttpMethod.GET, "/api/seminars/*").permitAll()` 아래에 추가:

```java
                .requestMatchers(HttpMethod.GET, "/api/schedules").permitAll()
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.schedule.ScheduleListTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/jaram/be/schedule/dto/ \
        src/main/java/com/jaram/be/schedule/ScheduleService.java \
        src/main/java/com/jaram/be/schedule/ScheduleController.java \
        src/main/java/com/jaram/be/security/SecurityConfig.java \
        src/test/java/com/jaram/be/schedule/ScheduleListTest.java
git commit -m "feat(schedule): add public schedule list with derived slot view"
```

---

### Task 8: 슬롯 자기등록(claim) + 자진취소(cancel)

**Files:**
- Modify: `src/main/java/com/jaram/be/schedule/ScheduleService.java`
- Modify: `src/main/java/com/jaram/be/schedule/ScheduleController.java`
- Test: `src/test/java/com/jaram/be/schedule/ScheduleSlotTest.java` (Create)

**Interfaces:**
- Produces:
  - `ScheduleService.claim(String scheduleId, int index, String memberId)` → `ScheduleResponse` (OPEN만; LOCKED·이미점유·본인중복→409; 미존재/범위밖→404).
  - `ScheduleService.cancel(String scheduleId, int index, String memberId)` → `ScheduleResponse` (OPEN & 본인 슬롯만; LOCKED·비소유·빈슬롯→403; 미존재→404).
  - private helper `load(String)`→Schedule(404), `slot(Schedule,int)`→ScheduleSlot(404), `conflict(String)`/`forbidden(String)`→ApiException.
  - `ScheduleController`: `POST /{id}/slots/{index}/claim`, `DELETE /{id}/slots/{index}`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/schedule/ScheduleSlotTest.java`:

```java
package com.jaram.be.schedule;

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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleSlotTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired ScheduleRepository schedules;
    @Autowired JwtProvider jwt;

    private String token;   // member-1

    @BeforeEach void setup() {
        RestAssured.port = port;
        schedules.deleteAll();
        token = jwt.generate("member-1", "회원", "a@hanyang.ac.kr", Authority.MEMBER);
    }

    private Schedule open() { return schedules.save(Schedule.create(Instant.now(), null, null, 3)); }

    @Test
    void claimsEmptySlot() {
        Schedule s = open();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/claim").then().statusCode(200)
                .body("slots[0].member.id", equalTo("member-1"));
    }

    @Test
    void claimOccupiedSlotIs409() {
        Schedule s = open();
        s.getSlots().get(0).claim("someone");
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/claim").then().statusCode(409)
                .body("code", equalTo("CONFLICT"));
    }

    @Test
    void claimSecondSlotSameMemberIs409() {
        Schedule s = open();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/claim").then().statusCode(200);
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/1/claim").then().statusCode(409);
    }

    @Test
    void claimLockedIs409() {
        Schedule s = open();
        s.lock();
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/claim").then().statusCode(409);
    }

    @Test
    void claimOutOfRangeIs404() {
        Schedule s = open();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/" + s.getId() + "/slots/9/claim").then().statusCode(404);
    }

    @Test
    void cancelsOwnSlot() {
        Schedule s = open();
        s.getSlots().get(0).claim("member-1");
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().delete("/api/schedules/" + s.getId() + "/slots/0").then().statusCode(200)
                .body("slots[0].member", nullValue());
    }

    @Test
    void cancelOthersSlotIs403() {
        Schedule s = open();
        s.getSlots().get(0).claim("someone");
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().delete("/api/schedules/" + s.getId() + "/slots/0").then().statusCode(403);
    }

    @Test
    void cancelAfterLockIs403() {
        Schedule s = open();
        s.getSlots().get(0).claim("member-1");
        s.lock();
        schedules.save(s);
        given().header("Authorization", "Bearer " + token)
                .when().delete("/api/schedules/" + s.getId() + "/slots/0").then().statusCode(403);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.schedule.ScheduleSlotTest'`
Expected: FAIL — claim/cancel 핸들러 미존재.

- [ ] **Step 3: Add claim/cancel + helpers to the service**

`ScheduleService.java`의 `list()`와 `toResponse(...)` 사이(또는 `toResponse` 위)에 추가. import 추가: `import com.jaram.be.common.ApiException;`, `import org.springframework.http.HttpStatus;`.

```java
    @Transactional
    public ScheduleResponse claim(String scheduleId, int index, String memberId) {
        Schedule sch = load(scheduleId);
        if (sch.getStatus() != ScheduleStatus.OPEN) {
            throw conflict("잠긴 일정입니다.");
        }
        ScheduleSlot slot = slot(sch, index);
        if (slot.getMemberId() != null) {
            throw conflict("이미 점유된 슬롯입니다.");
        }
        boolean alreadyMine = sch.getSlots().stream().anyMatch(x -> memberId.equals(x.getMemberId()));
        if (alreadyMine) {
            throw conflict("이미 이 일정의 슬롯을 잡았습니다.");
        }
        slot.claim(memberId);
        schedules.save(sch);
        return toResponse(sch);
    }

    @Transactional
    public ScheduleResponse cancel(String scheduleId, int index, String memberId) {
        Schedule sch = load(scheduleId);
        ScheduleSlot slot = slot(sch, index);
        if (sch.getStatus() != ScheduleStatus.OPEN) {
            throw forbidden("잠긴 일정은 취소할 수 없습니다.");
        }
        if (!memberId.equals(slot.getMemberId())) {
            throw forbidden("본인 슬롯만 취소할 수 있습니다.");
        }
        slot.release();
        schedules.save(sch);
        return toResponse(sch);
    }

    private Schedule load(String id) {
        return schedules.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "일정을 찾을 수 없습니다."));
    }

    private ScheduleSlot slot(Schedule sch, int index) {
        return sch.getSlots().stream().filter(x -> x.getIndex() == index).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "슬롯을 찾을 수 없습니다."));
    }

    private ApiException conflict(String msg) { return new ApiException(HttpStatus.CONFLICT, "CONFLICT", msg); }
    private ApiException forbidden(String msg) { return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", msg); }
```

- [ ] **Step 4: Add claim/cancel handlers to the controller**

`ScheduleController.java` — import 추가:

```java
import com.jaram.be.security.CurrentMember;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
```

`list()` 아래:

```java
    @PostMapping("/{id}/slots/{index}/claim")
    public ScheduleResponse claim(@PathVariable String id, @PathVariable int index,
                                  @AuthenticationPrincipal CurrentMember me) {
        return service.claim(id, index, me.id());
    }

    @DeleteMapping("/{id}/slots/{index}")
    public ScheduleResponse cancel(@PathVariable String id, @PathVariable int index,
                                   @AuthenticationPrincipal CurrentMember me) {
        return service.cancel(id, index, me.id());
    }
```

`POST/DELETE /api/schedules/**`는 `.anyRequest().authenticated()`로 커버 — 보안 변경 불필요.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.schedule.ScheduleSlotTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/jaram/be/schedule/ScheduleService.java \
        src/main/java/com/jaram/be/schedule/ScheduleController.java \
        src/test/java/com/jaram/be/schedule/ScheduleSlotTest.java
git commit -m "feat(schedule): add slot claim and self-cancel"
```

---

### Task 9: 슬롯에서 세미나 제출 (POST /api/schedules/{id}/slots/{index}/seminar)

**Files:**
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java` (submitFromSlot 추가)
- Modify: `src/main/java/com/jaram/be/schedule/ScheduleService.java`
- Modify: `src/main/java/com/jaram/be/schedule/ScheduleController.java`
- Test: `src/test/java/com/jaram/be/schedule/ScheduleSubmitTest.java` (Create)

**Interfaces:**
- Consumes: `SeminarCreateRequest`, `SeminarResponse`, `Seminar.create/setDescription/setScheduleId`.
- Produces:
  - `SeminarService.submitFromSlot(SeminarCreateRequest req, String memberId, String scheduleId, Instant startsAt, String place, String mode)` → `SeminarResponse` (PENDING 유지, scheduleId/startsAt/place/mode를 Schedule 값으로, attendanceCode·capacity 무시).
  - `ScheduleService.submitSeminar(String scheduleId, int index, String memberId, SeminarCreateRequest req)` → `SeminarResponse` (LOCKED & 본인 & 미제출; 아니면 409/403).
  - `ScheduleController`: `POST /{id}/slots/{index}/seminar` → 201 + Seminar.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/schedule/ScheduleSubmitTest.java`:

```java
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

        // attendanceCode는 무시(저장 안 함), 슬롯에 seminarId 연결됨
        Seminar saved = seminars.findById(id).orElseThrow();
        assertThat(saved.getAttendanceCode()).isNull();
        assertThat(schedules.findById(s.getId()).orElseThrow()
                .getSlots().get(0).getSeminarId()).isEqualTo(id);
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

    @Test
    void submitWithoutTitleIs422() {
        Schedule s = lockedWithMyClaim();
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("startsAt", "2026-01-01T00:00:00Z"))
                .when().post("/api/schedules/" + s.getId() + "/slots/0/seminar").then().statusCode(422);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.schedule.ScheduleSubmitTest'`
Expected: FAIL — submit 핸들러/서비스 미존재.

- [ ] **Step 3: Add submitFromSlot to SeminarService**

`SeminarService.java`에 추가(`create(...)` 아래). `Instant`는 이미 import됨.

```java
    // 슬롯 제출 경로: PENDING 유지, 시간/장소/모드는 Schedule 값, attendanceCode·capacity 무시.
    @Transactional
    public SeminarResponse submitFromSlot(SeminarCreateRequest req, String memberId, String scheduleId,
                                          Instant startsAt, String place, String mode) {
        Seminar s = Seminar.create(
                req.title(), req.speaker(), req.topic(), startsAt,
                place, mode, null, req.materialUrl(), null, memberId);
        s.setDescription(req.description());
        s.setScheduleId(scheduleId);
        Seminar saved = seminars.save(s);
        return toResponse(saved, memberId);
    }
```

- [ ] **Step 4: Add submitSeminar to ScheduleService**

`ScheduleService.java` — 필드에 `SeminarService` 주입 추가. import: `import com.jaram.be.seminar.SeminarService;`, `import com.jaram.be.seminar.dto.SeminarCreateRequest;`, `import com.jaram.be.seminar.dto.SeminarResponse;`, `import java.time.Instant;`(사용 안 하면 생략).

생성자와 필드를 교체:

```java
    private final ScheduleRepository schedules;
    private final MemberRepository members;
    private final SeminarRepository seminars;
    private final SeminarService seminarService;

    public ScheduleService(ScheduleRepository schedules, MemberRepository members,
                           SeminarRepository seminars, SeminarService seminarService) {
        this.schedules = schedules;
        this.members = members;
        this.seminars = seminars;
        this.seminarService = seminarService;
    }
```

메서드 추가(`cancel(...)` 아래):

```java
    @Transactional
    public SeminarResponse submitSeminar(String scheduleId, int index, String memberId,
                                         SeminarCreateRequest req) {
        Schedule sch = load(scheduleId);
        ScheduleSlot slot = slot(sch, index);
        if (sch.getStatus() != ScheduleStatus.LOCKED) {
            throw conflict("잠긴 일정에서만 세미나를 제출할 수 있습니다.");
        }
        if (!memberId.equals(slot.getMemberId())) {
            throw forbidden("본인 슬롯만 제출할 수 있습니다.");
        }
        if (slot.getSeminarId() != null) {
            throw conflict("이미 제출한 슬롯입니다.");
        }
        SeminarResponse resp = seminarService.submitFromSlot(
                req, memberId, sch.getId(), sch.getStartsAt(), sch.getPlace(), sch.getMode());
        slot.attachSeminar(resp.id());
        schedules.save(sch);
        return resp;
    }
```

- [ ] **Step 5: Add submit handler to controller**

`ScheduleController.java` — import 추가:

```java
import com.jaram.be.seminar.dto.SeminarCreateRequest;
import com.jaram.be.seminar.dto.SeminarResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
```

핸들러:

```java
    @PostMapping("/{id}/slots/{index}/seminar")
    @ResponseStatus(HttpStatus.CREATED)
    public SeminarResponse submit(@PathVariable String id, @PathVariable int index,
                                  @Valid @RequestBody SeminarCreateRequest req,
                                  @AuthenticationPrincipal CurrentMember me) {
        return service.submitSeminar(id, index, me.id(), req);
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.schedule.ScheduleSubmitTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/jaram/be/seminar/SeminarService.java \
        src/main/java/com/jaram/be/schedule/ScheduleService.java \
        src/main/java/com/jaram/be/schedule/ScheduleController.java \
        src/test/java/com/jaram/be/schedule/ScheduleSubmitTest.java
git commit -m "feat(schedule): submit pending seminar from a locked slot"
```

---

### Task 10: 임원 일정 생성/잠금/강제해제 (/api/admin/schedules)

**Files:**
- Create: `src/main/java/com/jaram/be/schedule/AdminScheduleController.java`
- Modify: `src/main/java/com/jaram/be/schedule/ScheduleService.java`
- Test: `src/test/java/com/jaram/be/schedule/AdminScheduleTest.java` (Create)

**Interfaces:**
- Consumes: `ScheduleCreateRequest`, `Schedule.create/lock`, `SeminarRepository`, `com.jaram.be.seminar.ApprovalStatus`.
- Produces:
  - `ScheduleService.create(ScheduleCreateRequest req)` → `ScheduleResponse` (capacity 기본 3).
  - `ScheduleService.lock(String scheduleId)` → `ScheduleResponse` (멱등).
  - `ScheduleService.forceRelease(String scheduleId, int index)` → `ScheduleResponse` (seminarId 존재 AND 세미나 approvalStatus != REJECTED이면 409; 그 외 release).
  - `AdminScheduleController` @ `/api/admin/schedules`: `POST` 201, `PATCH /{id}/lock` 200, `DELETE /{id}/slots/{index}` 200.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/schedule/AdminScheduleTest.java`:

```java
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminScheduleTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired ScheduleRepository schedules;
    @Autowired SeminarRepository seminars;
    @Autowired JwtProvider jwt;

    private String officerToken;
    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        schedules.deleteAll();
        seminars.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "of@hanyang.ac.kr", Authority.OFFICER);
        memberToken = jwt.generate("member-1", "회원", "me@hanyang.ac.kr", Authority.MEMBER);
    }

    @Test
    void officerCreatesScheduleWithDefaultCapacity() {
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("startsAt", "2026-09-01T10:00:00Z", "place", "IT관"))
                .when().post("/api/admin/schedules").then().statusCode(201)
                .body("capacity", equalTo(3))
                .body("status", equalTo("OPEN"))
                .body("slots.size()", equalTo(3));
    }

    @Test
    void memberCannotCreate() {
        given().header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("startsAt", "2026-09-01T10:00:00Z"))
                .when().post("/api/admin/schedules").then().statusCode(403);
    }

    @Test
    void officerLocks() {
        Schedule s = schedules.save(Schedule.create(Instant.now(), null, null, 3));
        given().header("Authorization", "Bearer " + officerToken)
                .when().patch("/api/admin/schedules/" + s.getId() + "/lock").then().statusCode(200)
                .body("status", equalTo("LOCKED"));
    }

    @Test
    void forceReleaseEmptySlot() {
        Schedule s = schedules.save(Schedule.create(Instant.now(), null, null, 3));
        given().header("Authorization", "Bearer " + officerToken)
                .when().delete("/api/admin/schedules/" + s.getId() + "/slots/0").then().statusCode(200)
                .body("slots[0].member", nullValue());
    }

    @Test
    void forceReleaseRejectedSeminarPasses() {
        Seminar sem = Seminar.create("반려됨", null, null, Instant.now(),
                null, null, null, null, null, "member-1");
        sem.reject("사유");
        sem = seminars.save(sem);
        Schedule s = Schedule.create(Instant.now(), null, null, 3);
        s.getSlots().get(0).claim("member-1");
        s.getSlots().get(0).attachSeminar(sem.getId());
        s.lock();
        schedules.save(s);

        given().header("Authorization", "Bearer " + officerToken)
                .when().delete("/api/admin/schedules/" + s.getId() + "/slots/0").then().statusCode(200)
                .body("slots[0].member", nullValue())
                .body("slots[0].seminarId", nullValue());
    }

    @Test
    void forceReleasePendingSeminarIs409() {
        Seminar sem = seminars.save(Seminar.create("대기", null, null, Instant.now(),
                null, null, null, null, null, "member-1")); // PENDING
        Schedule s = Schedule.create(Instant.now(), null, null, 3);
        s.getSlots().get(0).claim("member-1");
        s.getSlots().get(0).attachSeminar(sem.getId());
        s.lock();
        schedules.save(s);

        given().header("Authorization", "Bearer " + officerToken)
                .when().delete("/api/admin/schedules/" + s.getId() + "/slots/0").then().statusCode(409)
                .body("code", equalTo("CONFLICT"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.jaram.be.schedule.AdminScheduleTest'`
Expected: FAIL — admin schedule 컨트롤러/서비스 미존재.

- [ ] **Step 3: Add create/lock/forceRelease to the service**

`ScheduleService.java` — import 추가: `import com.jaram.be.schedule.dto.ScheduleCreateRequest;`, `import com.jaram.be.seminar.ApprovalStatus;`, `import com.jaram.be.seminar.Seminar;`(이미 import됨). `submitSeminar(...)` 아래에:

```java
    @Transactional
    public ScheduleResponse create(ScheduleCreateRequest req) {
        int capacity = req.capacity() == null ? 3 : req.capacity();
        Schedule sch = Schedule.create(req.startsAt(), req.place(), req.mode(), capacity);
        return toResponse(schedules.save(sch));
    }

    @Transactional
    public ScheduleResponse lock(String scheduleId) {
        Schedule sch = load(scheduleId);
        sch.lock();
        return toResponse(schedules.save(sch));
    }

    @Transactional
    public ScheduleResponse forceRelease(String scheduleId, int index) {
        Schedule sch = load(scheduleId);
        ScheduleSlot slot = slot(sch, index);
        if (slot.getSeminarId() != null) {
            Seminar sem = seminars.findById(slot.getSeminarId()).orElse(null);
            if (sem != null && sem.getApprovalStatus() != ApprovalStatus.REJECTED) {
                throw conflict("먼저 세미나를 반려한 뒤 해제할 수 있습니다.");
            }
        }
        slot.release();
        schedules.save(sch);
        return toResponse(sch);
    }
```

- [ ] **Step 4: Create the admin controller**

`src/main/java/com/jaram/be/schedule/AdminScheduleController.java`:

```java
package com.jaram.be.schedule;

import com.jaram.be.schedule.dto.ScheduleCreateRequest;
import com.jaram.be.schedule.dto.ScheduleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/schedules")
public class AdminScheduleController {

    private final ScheduleService service;

    public AdminScheduleController(ScheduleService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleResponse create(@Valid @RequestBody ScheduleCreateRequest req) {
        return service.create(req);
    }

    @PatchMapping("/{id}/lock")
    public ScheduleResponse lock(@PathVariable String id) {
        return service.lock(id);
    }

    @DeleteMapping("/{id}/slots/{index}")
    public ScheduleResponse forceRelease(@PathVariable String id, @PathVariable int index) {
        return service.forceRelease(id, index);
    }
}
```

`/api/admin/**`는 `hasAuthority("OFFICER")`가 이미 커버 — 보안 변경 불필요.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.schedule.AdminScheduleTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/jaram/be/schedule/AdminScheduleController.java \
        src/main/java/com/jaram/be/schedule/ScheduleService.java \
        src/test/java/com/jaram/be/schedule/AdminScheduleTest.java
git commit -m "feat(schedule): add officer create/lock/force-release endpoints"
```

---

### Task 11: 계약 테스트 (Seminar 추가 + Schedule 신규) + 전체 회귀

**Files:**
- Modify: `src/test/java/com/jaram/be/contract/SeminarContractTest.java`
- Create: `src/test/java/com/jaram/be/contract/ScheduleContractTest.java`

**Interfaces:**
- Consumes: 전 task의 엔드포인트 전부. `OpenApiValidationFilter`로 응답 스키마 계약 준수 검증.

- [ ] **Step 1: Add seminar contract cases (get/patch/approve/reject)**

`SeminarContractTest.java`의 마지막 `@Test`(`attendeesMatchesContract`) 아래, `activeMember()` 헬퍼 위에 추가:

```java
    @Test
    void getSingleMatchesContract() {
        Seminar s = Seminar.create("공개", null, null, Instant.now(),
                null, null, "CODE", null, null, "officer-1");
        s.approve();
        seminars.save(s);
        given().filter(validation).when().get("/api/seminars/" + s.getId()).then().statusCode(200);
    }

    @Test
    void resubmitMatchesContract() {
        Seminar s = Seminar.create("반려", null, null, Instant.now(),
                null, null, "CODE", null, null, "member-1");
        s.reject("보완");
        seminars.save(s);
        given().filter(validation).header("Authorization", "Bearer " + memberToken)
                .contentType("application/json")
                .body(Map.of("title", "재제출", "startsAt", "2026-09-01T10:00:00Z"))
                .when().patch("/api/seminars/" + s.getId()).then().statusCode(200);
    }

    @Test
    void approveMatchesContract() {
        Seminar s = seminars.save(Seminar.create("대기", null, null, Instant.now(),
                null, null, "CODE", null, null, "member-1"));
        given().filter(validation).header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/seminars/" + s.getId() + "/approve").then().statusCode(200);
    }

    @Test
    void rejectMatchesContract() {
        Seminar s = seminars.save(Seminar.create("대기", null, null, Instant.now(),
                null, null, "CODE", null, null, "member-1"));
        given().filter(validation).header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of("reason", "보완 필요"))
                .when().post("/api/admin/seminars/" + s.getId() + "/reject").then().statusCode(200);
    }
```

`memberToken`으로 재제출하려면 세미나 소유자가 `member-1`이어야 한다(위 fixture에서 이미 `member-1` 소유). `memberToken`은 setup에서 `jwt.generate("member-1", ...)`로 생성됨 — 일치.

- [ ] **Step 2: Run seminar contract test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.contract.SeminarContractTest'`
Expected: PASS(응답이 Seminar 스키마 계약 준수).

- [ ] **Step 3: Create the schedule contract test**

`src/test/java/com/jaram/be/contract/ScheduleContractTest.java`:

```java
package com.jaram.be.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.schedule.Schedule;
import com.jaram.be.schedule.ScheduleRepository;
import com.jaram.be.security.JwtProvider;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleContractTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired ScheduleRepository schedules;
    @Autowired SeminarRepository seminars;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private final OpenApiValidationFilter validation = new OpenApiValidationFilter(
            OpenApiInteractionValidator.createForSpecificationUrl("openapi/openapi.yaml")
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request.parameter.schema.invalidJson",
                                    ValidationReport.Level.IGNORE)
                            .build())
                    .build());

    private String officerToken;
    private String memberToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        schedules.deleteAll();
        seminars.deleteAll();
        members.deleteAll();
        // 슬롯 member 응답의 name(계약상 non-null)을 위해 실제 회원 저장
        Member m = Member.newPending("김회원", "2023000001", "a@hanyang.ac.kr", "hash");
        m.setStatus(MemberStatus.ACTIVE);
        m = members.save(m);
        officerToken = jwt.generate("officer-1", "임원", "of@hanyang.ac.kr", Authority.OFFICER);
        memberToken = jwt.generate(m.getId(), "김회원", "a@hanyang.ac.kr", Authority.MEMBER);
    }

    @Test
    void listMatchesContract() {
        schedules.save(Schedule.create(Instant.now(), "IT관", "offline", 3));
        given().filter(validation).when().get("/api/schedules").then().statusCode(200);
    }

    @Test
    void createMatchesContract() {
        given().filter(validation).header("Authorization", "Bearer " + officerToken)
                .contentType("application/json")
                .body(Map.of("startsAt", "2026-09-01T10:00:00Z", "place", "IT관"))
                .when().post("/api/admin/schedules").then().statusCode(201);
    }

    @Test
    void claimMatchesContract() {
        Schedule s = schedules.save(Schedule.create(Instant.now(), null, null, 3));
        given().filter(validation).header("Authorization", "Bearer " + memberToken)
                .when().post("/api/schedules/" + s.getId() + "/slots/0/claim").then().statusCode(200);
    }

    @Test
    void lockMatchesContract() {
        Schedule s = schedules.save(Schedule.create(Instant.now(), null, null, 3));
        given().filter(validation).header("Authorization", "Bearer " + officerToken)
                .when().patch("/api/admin/schedules/" + s.getId() + "/lock").then().statusCode(200);
    }

    @Test
    void submitMatchesContract() {
        Member m = members.findAll().get(0);
        Schedule s = Schedule.create(Instant.now(), "IT관", "offline", 3);
        s.getSlots().get(0).claim(m.getId());
        s.lock();
        schedules.save(s);
        given().filter(validation).header("Authorization", "Bearer " + memberToken)
                .contentType("application/json").body(Map.of("title", "내 세미나", "startsAt", "2026-09-01T10:00:00Z"))
                .when().post("/api/schedules/" + s.getId() + "/slots/0/seminar").then().statusCode(201);
    }

    @Test
    void forceReleaseMatchesContract() {
        Schedule s = schedules.save(Schedule.create(Instant.now(), null, null, 3));
        given().filter(validation).header("Authorization", "Bearer " + officerToken)
                .when().delete("/api/admin/schedules/" + s.getId() + "/slots/0").then().statusCode(200);
    }
}
```

- [ ] **Step 4: Run schedule contract test to verify it passes**

Run: `./gradlew test --tests 'com.jaram.be.contract.ScheduleContractTest'`
Expected: PASS(Schedule·ScheduleSlot·SlotMember·Seminar 응답 스키마 계약 준수).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. 신규 클래스 전부 통과, 기존 테스트 회귀 없음. (기존 `SeminarContractTest.createMatchesContract`의 capacity/target drift는 이 계획 범위 밖의 알려진 이슈로, 이번 변경이 새로 깨뜨리지 않았는지만 확인 — 통과 상태 유지.)

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/jaram/be/contract/SeminarContractTest.java \
        src/test/java/com/jaram/be/contract/ScheduleContractTest.java
git commit -m "test(contract): cover seminar approval and schedule endpoints"
```

---

## Self-Review

**Spec coverage** — 설계 문서 §엔드포인트의 11 operation 매핑:
- `GET /api/seminars`(APPROVED-only) → Task 2 ✓
- `GET /api/seminars/{id}`(가시성) → Task 3 ✓
- `PATCH /api/seminars/{id}`(재제출) → Task 4 ✓
- `POST /api/admin/seminars/{id}/approve|reject` → Task 5 ✓
- `GET /api/schedules` → Task 7 ✓
- `POST .../claim`, `DELETE .../slots/{index}` → Task 8 ✓
- `POST .../slots/{index}/seminar` → Task 9 ✓
- `POST /api/admin/schedules`, `PATCH .../lock`, `DELETE(admin) .../slots/{index}` → Task 10 ✓
- 엔티티 3필드 + ApprovalStatus + 전이 → Task 1 ✓; Schedule 도메인 → Task 6 ✓; 슬롯 파생필드 → Task 7 ✓; 보안 라우팅 → Task 3/7 ✓; 계약 테스트 → Task 11 ✓.

**결정 사항 반영:** 임원 create→APPROVED(Task 2), 강제해제 좁힌 게이트(REJECTED 통과, Task 10), 재제출 시 slot 연동은 Schedule 값 유지(Task 4), 409 code `"CONFLICT"`(Task 8 헬퍼).

**Type consistency:** `ScheduleService.toResponse(Schedule)`는 Task 7에서 정의되어 8/9/10에서 그대로 사용. `submitFromSlot`/`submitSeminar` 시그니처는 Task 9 정의와 호출 일치. `ApprovalStatus`는 seminar 패키지 단일 enum으로 slot 응답까지 일관. `SeminarService.toResponse`는 Task 2에서 public 승격 후 Task 9에서 재사용.

**주의점(실행자 유의):** 목록 필터링(Task 2)은 기존 `SeminarListTest`/`SeminarContractTest` fixture를 승인 처리해야 통과한다 — Step에 포함됨. `slot_index` 컬럼명은 SQL 예약어 회피용(`index` 프로퍼티는 JPQL `@OrderBy`에서 사용).
