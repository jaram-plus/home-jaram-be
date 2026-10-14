# 스터디 ① 상태 기계와 모집 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 스터디의 상태를 파생값에서 저장값으로 바꾸고, 그 상태를 움직이는 손잡이(개설 승인 · 모집 완료 · 종료 · 임원의 임의 지정)와 모집 토글을 전부 만든다.

**Architecture:** 승인축(`approvalStatus`)을 생애축(`status`)으로 접어 5값 열거형 하나로 만든다. 스터디장은 `Role` 이 아니라 `@PreAuthorize` 의 소유자 조건 빈(`@studyAccess`)이고, 게이트는 언제나 `소유자 조건 or hasAuthority(...)` 모양이다. 모집 토글은 `AdminSettings` 가 아니라 스터디 도메인의 단일 행 엔티티다 — 학술부장이 `SETTINGS_*` 를 하나도 갖지 않기 때문이다.

**Tech Stack:** Java 21 · Spring Boot 3.4.1 · Spring Data JPA (`ddl-auto: update`) · PostgreSQL 16 · Spring Security (method security) · JUnit 5 + RestAssured + Testcontainers

**Spec:** `docs/superpowers/specs/2026-09-15-study-lifecycle-design.md`

---

## Global Constraints

이 절의 값은 모든 Task 의 요구사항에 묵시적으로 포함된다.

**브랜치:** `feat/study-lifecycle` (BE). 계약은 home-jaram-fe 의 **같은 이름** 브랜치.

**테스트 실행 (이 호스트 전용 우회 둘):**

```bash
export JAVA_HOME=/home/ksb/.local/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon -I <init-script> test --tests '<클래스>'
```

`<init-script>` 는 아래 내용을 가진 파일이다. 호스트 Docker Engine 이 29 라
Testcontainers 1.20.2 가 고정한 docker-java API 1.32 를 거부하는데, 환경변수
`DOCKER_API_VERSION` 은 무시되고 Gradle 은 `-D` 를 워커에 전달하지 않는다:

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'api.version', '1.44'
    }
}
```

**응답 코드 규약:**

| 상황 | 코드 |
|---|---|
| Bean Validation 실패 (`@NotBlank` 등) | `422` `VALIDATION` — `GlobalExceptionHandler` |
| 우리가 던지는 입력 오류 | `422` `VALIDATION` (`ApiException`) |
| 상태·중복 충돌 | `409` + 고유 코드 |
| `@PreAuthorize` 거부 | `403` `FORBIDDEN` |
| 미인증 | `401` |

`GlobalExceptionHandler` 의 마지막 핸들러는 `Exception → 500` 이다. **처리되지 않은
예외는 전부 500 이 된다** — 예컨대 `?status=` 를 `StudyStatus` 열거형 파라미터로
바인딩하면 오타 하나가 `MethodArgumentTypeMismatchException` 을 거쳐 500 이 된다.
그래서 이 계획은 그 파라미터를 `String` 으로 받아 직접 파싱한다.

**스키마:** `ddl-auto: update` 다. **새 컬럼·테이블에 `@Column(nullable = false)` 를
쓰지 않는다.** Postgres 는 행이 있는 테이블의 `ADD COLUMN ... not null` 을 거부하고,
Hibernate 의 `SchemaUpdate` 는 그 예외를 로그 한 줄로 삼킨 뒤 기동을 끝낸다 — 컬럼이
없는 채로 트래픽을 받아 그 테이블 질의가 전부 500 이 된다. 필수 여부는 요청 DTO 의
검증과 도메인 불변식이 지킨다.

**계약:** `openapi.yaml` 은 home-jaram-fe 에만 있고 BE 의 두 경로는 심볼릭 링크다.
BE CI 는 **BE 브랜치와 같은 이름의 FE 브랜치**에서 스펙을 집고, 없으면 조용히 FE
`develop` 으로 떨어진다. 검증기는 스키마에 없는 응답 필드를 거부한다. **Task 1 이
머지되기 전에는 Task 2 이후의 BE PR 이 CI 에서 빨간불**이며, 그것은 정상이다.

**커밋 꼬리말** (모든 커밋):

```
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

---

## File Structure

### 새로 만드는 파일

| 경로 | 책임 |
|---|---|
| `study/StudyWeek.java` | 커리큘럼 주차 엔티티. `(studyId, weekNo)` 유일 |
| `study/StudyWeekRepository.java` | 주차 조회 |
| `study/StudyRecruitment.java` | 모집 토글 단일 행 엔티티 |
| `study/StudyRecruitmentRepository.java` | 토글 조회 |
| `study/StudyAccess.java` | 소유자 조건 빈 (`@Component("studyAccess")`) |
| `study/dto/StudyList.java` | `{recruiting, items[]}` 목록 응답 |
| `study/dto/StudyDetail.java` | 상세 응답 |
| `study/dto/WeekEntry.java` | 상세의 주차 항목 |
| `study/dto/RosterEntry.java` | 상세의 지원 인원 항목 (마스킹된 학번) |
| `study/dto/RecruitmentUpdate.java` | 토글 요청 본문 |
| `security/AuthorizationCoverageTest.java` (test) | 애너테이션 누락 탐지. 기존 admin 전용 테스트를 대체 |
| `docs/migrations/2026-09-15-study-lifecycle.sql` | 손으로 돌릴 이행 SQL |

### 고치는 파일

| 경로 | 무엇이 |
|---|---|
| `study/StudyStatus.java` | 3값 → 5값 |
| `study/ApprovalStatus.java` | **삭제** |
| `study/Study.java` | `+status +place +contact`, `-approvalStatus -period`, 전이 메서드 |
| `study/StudyRepository.java` | `findByStatus…`, `findByStatusIn…` |
| `study/StudyService.java` | 축 접기 · 정원 검사 제거 · 상세 · 토글 · 전이 |
| `study/StudyController.java` | 상세 · 전이 둘 · 토글 |
| `study/StudyApplicationRepository.java` | `findByStudyIdAndStatusIn` |
| `study/dto/StudyResponse.java` | `-period`, `+leaderGen +intro` |
| `study/dto/StudyCreateRequest.java` | `-period`, `+place +contact +weeks[]`, 필수 강화 |
| `study/dto/MyStudy.java` | `-approvalStatus` |
| `study/dto/PendingStudy.java` | `-period` |
| `admin/AdminResourceService.java` | `studyRow` 의 `approvalStatus` → `status` |
| `admin/AdminBatchExecutor.java` | `updateStudy` 에 `status` 분기 |

### 고치는 테스트

| 경로 | 줄 | 왜 |
|---|---|---|
| `study/StudyTest.java` | 318 | `CAPACITY_FULL`·정원 기반 `RECRUIT_CLOSED`·`apply == "CLOSED"` 단언을 D9 이 전제째 지운다 |
| `contract/StudyContractTest.java` | 141 | 목록 응답이 배열 → 객체 |
| `study/StudyPermissionTest.java` | 70 | 새 엔드포인트 넷의 게이트 |
| `study/StudyRepositoryTest.java` | 54 | `findByApprovalStatus…` 를 직접 부른다 |
| `security/AdminAuthorizationCoverageTest.java` | 51 | **삭제** — Task 9 가 대체한다 |

---

## Task 1: 계약 — openapi.yaml 을 먼저 머지한다

**저장소가 다르다.** 이 Task 만 home-jaram-fe 에서 한다. BE 브랜치와 **정확히 같은
이름**의 브랜치여야 BE CI 가 이 스펙을 집는다.

**Files:**
- Modify: `home-jaram-fe/docs/api/openapi.yaml`

**Interfaces:**
- Produces: BE 가 이후 모든 Task 에서 검증받는 스키마 — `StudyStatus`(5값),
  `Study`(= `StudyResponse`), `StudyList`, `StudyDetail`, `StudyWeek`,
  `StudyRosterEntry`, `StudyCreateRequest`, `RecruitmentUpdate`, `MyStudy`,
  `PendingStudy`

- [ ] **Step 1: 브랜치를 만든다**

```bash
cd ~/Dev/home-jaram/home-jaram-fe
git fetch origin
git switch -c feat/study-lifecycle origin/develop
```

- [ ] **Step 2: `StudyStatus` 를 5값으로 바꾼다**

`components.schemas.StudyStatus` 를 통째로 교체한다.

```yaml
    StudyStatus:
      type: string
      enum: [PENDING, REJECTED, RECRUITING, ONGOING, FINISHED]
      description: >
        스터디 생애축. 개설 승인 대기(PENDING) → 승인(RECRUITING) → 모집 완료(ONGOING)
        → 종료(FINISHED). 개설 반려는 REJECTED. 옛 CLOSED(정원 마감)는 없어졌다.
```

`ApprovalStatus` 스키마는 **지우지 않는다** — 세미나가 `approvalStatus` 와
`seminarApprovalStatus` 두 곳에서 같은 스키마를 쓴다.

- [ ] **Step 3: `ApplyState` 의 description 을 새 뜻으로 고친다**

값은 그대로 두고 설명만 바꾼다.

```yaml
    ApplyState:
      type: [string, 'null']
      enum: [OPEN, APPLIED, CLOSED, JOINED, null]
      description: >
        사용자별 파생. 미인증 시 null. OPEN=스터디가 RECRUITING 이고 내 신청 기록이
        없다, APPLIED=내 신청이 대기, JOINED=승인됨 또는 내가 스터디장,
        CLOSED=RECRUITING 이 아니거나 내가 반려당했다. 정원은 더 이상 보지 않는다.
```

- [ ] **Step 4: `Study` 스키마에서 `period` 를 빼고 `leaderGen`·`intro` 를 넣는다**

```yaml
    Study:
      type: object
      required: [id, title, fields, leader, cur, cap, status]
      properties:
        id: { type: string }
        title: { type: string }
        fields:
          type: array
          items: { type: string }
        leader: { type: string, description: leader 이름 }
        leaderGen: { type: [integer, 'null'], description: leader 기수. 미승인 회원이면 null }
        intro: { type: [string, 'null'], description: 카드에 뜨는 설명 }
        schedule: { type: [string, 'null'] }
        mode: { type: [string, 'null'] }
        cur: { type: integer, description: 승인되어 확정된 인원 }
        cap: { type: integer, description: 개설 시 목표한 희망 인원. 상한이 아니다 }
        status: { $ref: '#/components/schemas/StudyStatus' }
        apply: { $ref: '#/components/schemas/ApplyState' }
```

- [ ] **Step 5: `StudyList`·`StudyDetail`·`StudyWeek`·`StudyRosterEntry` 를 더한다**

`Study` 바로 뒤에 넣는다.

```yaml
    StudyList:
      type: object
      required: [recruiting, items]
      properties:
        recruiting: { type: boolean, description: 스터디 개설 신청을 받는 중인가 (모집 토글) }
        items:
          type: array
          items: { $ref: '#/components/schemas/Study' }
    StudyWeek:
      type: object
      required: [weekNo, title]
      properties:
        weekNo: { type: integer, minimum: 1 }
        title: { type: string }
        content: { type: [string, 'null'] }
    StudyRosterEntry:
      type: object
      required: [studentId, name]
      properties:
        studentId: { type: string, description: 마스킹된 학번 (예 2022*****9) }
        gen: { type: [integer, 'null'] }
        name: { type: string }
    StudyDetail:
      type: object
      required: [id, title, fields, leader, cur, cap, status, weeks, roster]
      properties:
        id: { type: string }
        title: { type: string }
        fields:
          type: array
          items: { type: string }
        intro: { type: [string, 'null'] }
        leader: { type: string }
        leaderGen: { type: [integer, 'null'] }
        schedule: { type: [string, 'null'] }
        place: { type: [string, 'null'] }
        mode: { type: [string, 'null'] }
        contact: { type: [string, 'null'] }
        cur: { type: integer }
        cap: { type: integer }
        status: { $ref: '#/components/schemas/StudyStatus' }
        apply: { $ref: '#/components/schemas/ApplyState' }
        weeks:
          type: array
          items: { $ref: '#/components/schemas/StudyWeek' }
        roster:
          type: array
          items: { $ref: '#/components/schemas/StudyRosterEntry' }
```

- [ ] **Step 6: `StudyCreateRequest` 를 새 폼으로 바꾼다**

```yaml
    StudyCreateRequest:
      type: object
      required: [title, fields, capacity, intro, schedule, place, mode, contact, weeks]
      properties:
        title: { type: string }
        fields:
          type: array
          items: { type: string }
          minItems: 1
        capacity: { type: integer, minimum: 1, description: 희망 인원 }
        intro: { type: string }
        schedule: { type: string, description: 일시 }
        place: { type: string, description: 장소 }
        mode: { type: string, description: 진행 방식 }
        contact: { type: string, description: 문의 연락처 }
        weeks:
          type: array
          minItems: 1
          items: { $ref: '#/components/schemas/StudyWeekInput' }
    StudyWeekInput:
      type: object
      required: [weekNo, title]
      properties:
        weekNo: { type: integer, minimum: 1 }
        title: { type: string }
        content: { type: [string, 'null'], maxLength: 2000 }
    RecruitmentUpdate:
      type: object
      required: [open]
      properties:
        open: { type: boolean }
```

- [ ] **Step 7: `MyStudy` 에서 `approvalStatus` 를, `PendingStudy` 에서 `period` 를 뺀다**

```yaml
    MyStudy:
      type: object
      required: [id, title, status]
      properties:
        id: { type: string }
        title: { type: string }
        status: { $ref: '#/components/schemas/StudyStatus' }
        reason: { type: [string, 'null'], description: 반려 사유 }
```

`PendingStudy` 에서는 `period: { type: [string, 'null'] }` 줄만 지운다.

- [ ] **Step 8: `GET /api/studies` 의 응답과 쿼리 파라미터를 고친다**

```yaml
  /api/studies:
    get:
      tags: [study]
      summary: 스터디 목록 조회 (UC-T1)
      description: >
        파라미터가 없으면 RECRUITING + ONGOING. status 로 하나만 고를 수 있고
        PENDING·REJECTED 는 고를 수 없다. 인증 시 사용자별 apply 상태 포함. public.
      security:
        - {}
        - { bearerAuth: [] }
      parameters:
        - name: status
          in: query
          required: false
          schema:
            type: string
            enum: [RECRUITING, ONGOING, FINISHED]
      responses:
        '200':
          description: 모집 토글 상태와 스터디 목록
          content:
            application/json:
              schema: { $ref: '#/components/schemas/StudyList' }
        '422': { $ref: '#/components/responses/Validation' }
        '5XX': { $ref: '#/components/responses/ServerError' }
```

- [ ] **Step 9: 새 경로 셋을 더한다**

`/api/studies/{id}/apply` 앞에 넣는다.

```yaml
  /api/studies/recruitment:
    put:
      tags: [study]
      summary: 스터디 개설 모집 토글 (STUDY_EDIT)
      description: 개설 신청 버튼의 표시와 동작만 가른다. 어떤 스터디의 status 도 바뀌지 않는다.
      security: [{ bearerAuth: [] }]
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RecruitmentUpdate' }
      responses:
        '204': { description: 저장됨 }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '422': { $ref: '#/components/responses/Validation' }
        '5XX': { $ref: '#/components/responses/ServerError' }

  /api/studies/{id}:
    get:
      tags: [study]
      summary: 스터디 상세 (UC-T9)
      description: 커리큘럼과 지원 인원 명단을 포함한다. 학번은 서버가 마스킹한다. 로그인 필수.
      security: [{ bearerAuth: [] }]
      parameters:
        - name: id
          in: path
          required: true
          schema: { type: string }
      responses:
        '200':
          description: 스터디 상세
          content:
            application/json:
              schema: { $ref: '#/components/schemas/StudyDetail' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
        '5XX': { $ref: '#/components/responses/ServerError' }

  /api/studies/{id}/close-recruiting:
    post:
      tags: [study]
      summary: 모집 완료 (스터디장 or STUDY_EDIT)
      description: RECRUITING 에서만 통과한다. 아니면 409 INVALID_STATE.
      security: [{ bearerAuth: [] }]
      parameters:
        - name: id
          in: path
          required: true
          schema: { type: string }
      responses:
        '204': { description: ONGOING 으로 바뀜 }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }
        '5XX': { $ref: '#/components/responses/ServerError' }

  /api/studies/{id}/finish:
    post:
      tags: [study]
      summary: 스터디 종료 (스터디장 or STUDY_EDIT)
      description: ONGOING 에서만 통과한다. 아니면 409 INVALID_STATE.
      security: [{ bearerAuth: [] }]
      parameters:
        - name: id
          in: path
          required: true
          schema: { type: string }
      responses:
        '204': { description: FINISHED 로 바뀜 }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }
        '5XX': { $ref: '#/components/responses/ServerError' }
```

`Forbidden`·`NotFound`·`Conflict` 응답 컴포넌트가 실제로 그 이름인지 먼저 확인한다:

```bash
grep -n "^  responses:" -A 40 docs/api/openapi.yaml | grep -n "^\s*[A-Z][A-Za-z]*:"
```

이름이 다르면 이 파일이 이미 쓰는 이름을 그대로 쓴다.

- [ ] **Step 10: 스펙이 문법적으로 온전한지 확인한다**

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('docs/api/openapi.yaml')); print('yaml ok')"
```

기대: `yaml ok`

- [ ] **Step 11: 커밋하고 PR 을 올려 머지한다**

```bash
git add docs/api/openapi.yaml
git commit -m "feat(contract): 스터디 생애축과 모집·상세·전이 엔드포인트를 선언한다"
git push -u origin feat/study-lifecycle
gh pr create --fill
```

**이 PR 이 `develop` 에 머지되기 전에는 BE 의 계약 테스트가 빨간불이다.** 이후 Task
들은 로컬에서는 초록이지만 CI 에서는 이 머지를 기다린다.

---

## Task 2: 승인축을 생애축으로 접는다

한 번에 해야 하는 변경이다 — `approvalStatus` 를 지우는 순간 그것을 읽던 자리가 전부
깨지므로, 중간 상태로 커밋할 수 없다. 대신 단계를 잘게 쪼갠다.

**Files:**
- Modify: `src/main/java/com/jaram/be/study/StudyStatus.java`
- Modify: `src/main/java/com/jaram/be/study/Study.java`
- Delete: `src/main/java/com/jaram/be/study/ApprovalStatus.java`
- Modify: `src/main/java/com/jaram/be/study/StudyRepository.java`
- Modify: `src/main/java/com/jaram/be/study/StudyService.java`
- Modify: `src/main/java/com/jaram/be/study/dto/MyStudy.java`
- Modify: `src/main/java/com/jaram/be/study/dto/PendingStudy.java`
- Modify: `src/main/java/com/jaram/be/study/dto/StudyResponse.java`
- Modify: `src/main/java/com/jaram/be/study/dto/StudyCreateRequest.java`
- Modify: `src/main/java/com/jaram/be/admin/AdminResourceService.java:183`
- Test: `src/test/java/com/jaram/be/study/StudyRepositoryTest.java`
- Test: `src/test/java/com/jaram/be/study/StudyTest.java`

**Interfaces:**
- Produces:
  - `StudyStatus.{PENDING, REJECTED, RECRUITING, ONGOING, FINISHED}`
  - `Study.getStatus()` / `Study.setStatus(StudyStatus)` / `Study.closeRecruiting()` / `Study.finish()`
  - `Study.getPlace()` / `Study.getContact()`
  - `Study.create(String title, List<String> fields, Integer capacity, String schedule, String place, String mode, String intro, String contact, String leaderId)`
  - `StudyRepository.findByStatusOrderByCreatedAtDesc(StudyStatus)`
  - `StudyRepository.findByStatusInOrderByCreatedAtDesc(Collection<StudyStatus>)`
  - `StudyResponse(String id, String title, List<String> fields, String leader, Integer leaderGen, String intro, String schedule, String mode, int cur, int cap, StudyStatus status, ApplyState apply)`
  - `MyStudy(String id, String title, StudyStatus status, String reason)`

- [ ] **Step 1: 실패하는 테스트를 쓴다 — 상태 전이**

`src/test/java/com/jaram/be/study/StudyTest.java` 의 `approvedStudy` 헬퍼를 새 시그니처로 바꾸고, 전이를 확인하는 테스트를 더한다.

```java
    private Study pendingStudy(String leaderId, int cap) {
        return studies.save(Study.create(
                "알고리즘", List.of("PS"), cap,
                "매주 화 19:00", "공학관 401", "오프라인", "함께 풉니다", "010-0000-0000",
                leaderId));
    }

    private Study approvedStudy(String leaderId, int cap) {
        Study s = pendingStudy(leaderId, cap);
        s.approve();
        return studies.save(s);
    }

    @Test
    void approveMovesStudyToRecruitingAndRejectRecordsReason() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Study a = pendingStudy(leader.getId(), 6);
        Study b = pendingStudy(leader.getId(), 6);

        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/studies/" + a.getId() + "/approve")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of("reason", "주제 중복"))
                .when().post("/api/studies/" + b.getId() + "/reject")
                .then().statusCode(200);

        assertThat(studies.findById(a.getId()).orElseThrow().getStatus())
                .isEqualTo(StudyStatus.RECRUITING);
        Study rejected = studies.findById(b.getId()).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(StudyStatus.REJECTED);
        assertThat(rejected.getReason()).isEqualTo("주제 중복");
    }

    @Test
    void pendingAndRejectedStudiesNeverLeakIntoTheList() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        pendingStudy(leader.getId(), 6);
        Study rejected = pendingStudy(leader.getId(), 6);
        rejected.reject("중복");
        studies.save(rejected);

        given().when().get("/api/studies").then().statusCode(200).body("size()", equalTo(0));
    }
```

`import org.assertj.core.api.Assertions.assertThat` 가 이미 있는지 확인하고 없으면 더한다.

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.StudyTest'
```

기대: 컴파일 실패 — `Study.create` 의 인자 개수가 다르고 `getStatus()` 가 없다.

- [ ] **Step 3: `StudyStatus` 를 5값으로 바꾼다**

```java
package com.jaram.be.study;

/**
 * 스터디 생애축. 선언 순서가 곧 생애 순서다.
 *
 * 옛 approvalStatus(PENDING/APPROVED/REJECTED)가 여기로 접혔다. APPROVED 는 따로
 * 남지 않는다 — 승인된 스터디는 곧바로 RECRUITING 이고, "승인되었다"는 사실은
 * RECRUITING 이상의 어느 값이든 그 자체로 말해 준다.
 *
 * 옛 CLOSED 는 버렸다. 그 값은 "정원이 찼다"는 뜻이었고 그 개념 자체가 없어졌다.
 * 뜻이 달라진 값을 같은 이름으로 남기면 계약을 읽는 사람이 옛 뜻으로 읽는다.
 *
 * Wire = enum name.
 */
public enum StudyStatus {
    PENDING,      // 개설 승인 대기
    REJECTED,     // 개설 반려 (reason 이 채워진다)
    RECRUITING,   // 모집 중
    ONGOING,      // 진행 중
    FINISHED      // 종료
}
```

- [ ] **Step 4: `Study` 엔티티를 바꾼다**

`period` 필드와 게터를 지우고, `approvalStatus` 를 `status` 로 접고, `place`·`contact` 를 더한다.

```java
    private String schedule;      // nullable
    private String place;         // nullable — 이행 이전 스터디는 비어 있다
    private String mode;          // nullable
    @Column(length = 2000)
    private String intro;         // nullable
    private String contact;       // nullable — 이행 이전 스터디는 비어 있다

    /**
     * 생애축. @Column(nullable = false) 를 쓰지 않는다 — 행이 있는 테이블에 NOT NULL
     * 컬럼을 붙이면 Postgres 가 거부하고 Hibernate 는 그 예외를 로그로 삼켜, 컬럼이
     * 없는 채로 기동한다. 컬럼은 손으로 먼저 만든다 (docs/migrations/).
     */
    @Enumerated(EnumType.STRING)
    private StudyStatus status = StudyStatus.PENDING;
```

`create` 와 전이 메서드:

```java
    public static Study create(String title, List<String> fields, Integer capacity,
                               String schedule, String place, String mode, String intro,
                               String contact, String leaderId) {
        Study s = new Study();
        s.id = UUID.randomUUID().toString();
        s.title = title;
        s.fields = new ArrayList<>(fields);
        s.capacity = capacity;
        s.schedule = schedule;
        s.place = place;
        s.mode = mode;
        s.intro = intro;
        s.contact = contact;
        s.leaderId = leaderId;
        s.status = StudyStatus.PENDING;
        s.createdAt = Instant.now();
        return s;
    }

    public void approve() { this.status = StudyStatus.RECRUITING; }

    public void reject(String reason) {
        this.status = StudyStatus.REJECTED;
        this.reason = reason;
    }

    public void closeRecruiting() { this.status = StudyStatus.ONGOING; }

    public void finish() { this.status = StudyStatus.FINISHED; }
```

게터·세터 — `getPeriod`·`getApprovalStatus` 를 지우고 넣는다:

```java
    public String getPlace() { return place; }
    public String getContact() { return contact; }
    public StudyStatus getStatus() { return status; }
    public void setStatus(StudyStatus v) { this.status = v; }   // 관리자 일괄 편집 (D12)
```

클래스 Javadoc 첫 줄도 고친다: `approvalStatus는 개설 승인축` → `status 는 생애축 하나다 (PENDING 기본)`. `cur·apply 는 저장하지 않고 서비스에서 파생` 은 그대로 두되 `status` 는 목록에서 뺀다.

- [ ] **Step 5: `ApprovalStatus` 를 지운다**

```bash
rm src/main/java/com/jaram/be/study/ApprovalStatus.java
```

`com.jaram.be.seminar.ApprovalStatus` 는 이름만 같은 별개 열거형이다 — **손대지 않는다.**

- [ ] **Step 6: `StudyRepository` 의 질의를 바꾼다**

```java
package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface StudyRepository extends JpaRepository<Study, String> {
    List<Study> findByStatusOrderByCreatedAtDesc(StudyStatus status);
    List<Study> findByStatusInOrderByCreatedAtDesc(Collection<StudyStatus> statuses);
    List<Study> findByLeaderIdOrderByCreatedAtDesc(String leaderId);
}
```

- [ ] **Step 7: DTO 넷을 바꾼다**

`MyStudy` — 축이 하나가 됐으니 필드가 하나 준다:

```java
package com.jaram.be.study.dto;

import com.jaram.be.study.StudyStatus;

// 계약 MyStudy. 내가 개설한 스터디. status 하나가 승인축과 생애축을 다 말한다.
public record MyStudy(
        String id,
        String title,
        StudyStatus status,
        String reason) {
}
```

`PendingStudy` — `String period` 줄과 생성자 인자에서 그것을 지운다.

`StudyResponse` — `period` 를 빼고 `leaderGen`·`intro` 를 더한다:

```java
package com.jaram.be.study.dto;

import com.jaram.be.study.ApplyState;
import com.jaram.be.study.StudyStatus;
import java.util.List;

// 계약 Study. leader=이름, leaderGen=기수(미승인 회원이면 null), cur=확정 인원,
// cap=희망 인원(상한이 아니다), apply=사용자별 상태(미인증 null).
public record StudyResponse(
        String id,
        String title,
        List<String> fields,
        String leader,
        Integer leaderGen,
        String intro,
        String schedule,
        String mode,
        int cur,
        int cap,
        StudyStatus status,
        ApplyState apply) {
}
```

`StudyCreateRequest` — 이 Task 에서는 `period` 만 지운다. 새 필수 필드는 Task 3 이 더한다.

- [ ] **Step 8: `StudyService` 를 새 축으로 옮긴다**

`create` 는 `place`·`contact` 자리에 아직 `null` 을 넘긴다 (Task 3 이 채운다):

```java
    @Transactional
    public StudyResponse create(StudyCreateRequest req, String leaderId) {
        eligibility.requireActive(leaderId);
        Study saved = studies.save(Study.create(
                req.title(), req.fields(), req.capacity(),
                req.schedule(), null, req.mode(), req.intro(), null, leaderId));
        return toResponse(saved, members.findById(leaderId).orElse(null), leaderId);
    }
```

목록은 저장된 상태를 읽고, 기본은 `RECRUITING + ONGOING` 이다 (`?status=` 는 Task 5):

```java
    private static final List<StudyStatus> DEFAULT_LIST =
            List.of(StudyStatus.RECRUITING, StudyStatus.ONGOING);

    @Transactional(readOnly = true)
    public List<StudyResponse> list(String userId) {
        List<Study> rows = studies.findByStatusInOrderByCreatedAtDesc(DEFAULT_LIST);
        Map<String, Member> leaders = leadersOf(rows);
        return rows.stream()
                .map(s -> toResponse(s, leaders.get(s.getLeaderId()), userId))
                .toList();
    }
```

신청은 저장된 상태 하나만 본다. **정원 검사를 지운다** (D9):

```java
    @Transactional
    public void apply(String studyId, String applicantId, String motive) {
        eligibility.requireActive(applicantId);   // 조회보다 먼저다 — 없는 id 에 404 가 앞서면 안 된다
        Study study = loadStudy(studyId);
        if (study.getStatus() != StudyStatus.RECRUITING) {
            throw new ApiException(HttpStatus.CONFLICT, "RECRUIT_CLOSED", "모집 중인 스터디가 아닙니다.");
        }
        if (applicantId.equals(study.getLeaderId())) {
            throw new ApiException(HttpStatus.CONFLICT, "LEADER_SELF", "개설자는 지원할 수 없습니다.");
        }
        applications.findByStudyIdAndApplicantId(studyId, applicantId).ifPresent(a -> {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_APPLIED", "이미 지원한 스터디입니다.");
        });
        applications.save(StudyApplication.create(studyId, applicantId, motive));
    }
```

지원 승인도 정원을 보지 않는다 — 스터디장이 판단한다 (D9):

```java
    @Transactional
    public void approveApplicant(String applicationId) {
        loadApplication(applicationId).approve();
    }
```

`myActivity` 의 `MyStudy` 조립:

```java
        List<MyStudy> myStudies = studies.findByLeaderIdOrderByCreatedAtDesc(userId).stream()
                .map(s -> new MyStudy(s.getId(), s.getTitle(), s.getStatus(), s.getReason()))
                .toList();
```

`pending` 은 새 질의와 `period` 없는 DTO 로:

```java
    @Transactional(readOnly = true)
    public List<PendingStudy> pending() {
        List<Study> rows = studies.findByStatusOrderByCreatedAtDesc(StudyStatus.PENDING);
        Map<String, String> names = memberNames(rows.stream().map(Study::getLeaderId).toList());
        return rows.stream().map(s -> new PendingStudy(
                s.getId(), s.getTitle(), s.getFields(),
                names.getOrDefault(s.getLeaderId(), null),
                cap(s), s.getSchedule(), s.getIntro(),
                s.getCreatedAt().toString())).toList();
    }
```

파생 헬퍼 — `deriveStatus` 둘을 **지우고**, `toResponse` 는 리더를 통째로 받는다:

```java
    private Map<String, Member> leadersOf(List<Study> rows) {
        return members.findAllById(rows.stream().map(Study::getLeaderId).distinct().toList())
                .stream().collect(Collectors.toMap(Member::getId, Function.identity()));
    }

    private StudyResponse toResponse(Study s, Member leader, String userId) {
        return new StudyResponse(
                s.getId(), s.getTitle(), s.getFields(),
                leader == null ? null : leader.getName(),
                leader == null ? null : leader.getGen(),
                s.getIntro(), s.getSchedule(), s.getMode(),
                approvedCount(s.getId()), cap(s),
                s.getStatus(), deriveApply(s, userId));
    }

    // 미인증 → null. leader/승인됨 → JOINED, 대기 → APPLIED, 반려·모집 아님 → CLOSED, 그 외 OPEN.
    private ApplyState deriveApply(Study s, String userId) {
        if (userId == null) return null;
        if (userId.equals(s.getLeaderId())) return ApplyState.JOINED;
        StudyApplication mine = applications.findByStudyIdAndApplicantId(s.getId(), userId).orElse(null);
        if (mine != null) {
            if (mine.getStatus() == ApplicationStatus.APPROVED) return ApplyState.JOINED;
            if (mine.getStatus() == ApplicationStatus.PENDING) return ApplyState.APPLIED;
            // REJECTED: ② 의 '삭제하기'가 이 행을 지우면 다시 OPEN 이 된다 (D11).
            return ApplyState.CLOSED;
        }
        return s.getStatus() == StudyStatus.RECRUITING ? ApplyState.OPEN : ApplyState.CLOSED;
    }
```

클래스 Javadoc 의 `status(모집 상태)는 저장하지 않고 여기서 파생` 을 지운다 — 이제 저장한다.

- [ ] **Step 9: `AdminResourceService.studyRow` 를 고친다**

```java
        r.put("capacity", s.getCapacity());
        r.put("status", s.getStatus() == null ? null : s.getStatus().name());
```

`null` 방어가 필요하다 — 이행 SQL 을 돌리기 전 기동하면 이 값이 비어 있고, `.name()`
이 그 자리에서 NPE 를 내면 관리자 표 전체가 500 이 된다.

- [ ] **Step 10: `StudyRepositoryTest` 를 새 질의로 고친다**

```java
        assertThat(studies.findByStatusOrderByCreatedAtDesc(StudyStatus.PENDING)).hasSize(1);
        assertThat(studies.findByStatusOrderByCreatedAtDesc(StudyStatus.RECRUITING)).isEmpty();
```

`Study.create` 호출부도 새 인자 순서로 고친다.

- [ ] **Step 11: 전제가 사라진 기존 테스트를 지운다**

`StudyTest` 에서 아래 셋을 **삭제**한다. 정원이 상태를 정한다는 전제가 없어졌다.

- `CAPACITY_FULL` 을 기대하는 테스트 (파일 끝 `:300` 부근)
- 정원이 차서 `RECRUIT_CLOSED` 를 기대하는 테스트 (`:180` 부근)
- `apply` 가 `"CLOSED"` 로 파생되는 것을 기대하는 테스트 (`:195` 부근)

`:215` 의 `.body("studies[0].approvalStatus", equalTo("PENDING"))` 는
`.body("studies[0].status", equalTo("PENDING"))` 로 고친다.

- [ ] **Step 12: 테스트를 돌려 초록인지 본다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.*'
```

기대: PASS

- [ ] **Step 13: 전체 스위트를 돌린다**

```bash
./gradlew --no-daemon -I <init-script> test
```

기대: `StudyContractTest` 만 실패 (목록 응답이 아직 배열이고 계약은 객체를 기대한다).
그 실패는 Task 5 가 고친다 — 지금은 다른 도메인이 깨지지 않았다는 것만 확인한다.

- [ ] **Step 14: 커밋**

```bash
git add -A
git commit -m "feat(study): 승인축을 생애축으로 접어 상태를 저장값으로 만든다

approvalStatus 를 status 안으로 접어 5값 열거형 하나로 만든다. 정원이 상태를
정하던 파생(cur >= cap -> CLOSED)과 그것이 만들던 정원 마감 판정을 함께
걷어낸다 - 정원은 상한이 아니라 희망 인원이다.

status 컬럼에 nullable=false 를 쓰지 않는다. 행이 있는 테이블에 NOT NULL 을
붙이면 Postgres 가 거부하고 Hibernate 가 그 예외를 삼켜, 컬럼 없이 기동한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq"
```

---

## Task 3: 커리큘럼 주차와 개설 폼

**Files:**
- Create: `src/main/java/com/jaram/be/study/StudyWeek.java`
- Create: `src/main/java/com/jaram/be/study/StudyWeekRepository.java`
- Modify: `src/main/java/com/jaram/be/study/dto/StudyCreateRequest.java`
- Modify: `src/main/java/com/jaram/be/study/StudyService.java`
- Test: `src/test/java/com/jaram/be/study/StudyTest.java`

**Interfaces:**
- Consumes: Task 2 의 `Study.create(...)` 9인자 시그니처
- Produces:
  - `StudyWeek.create(String studyId, int weekNo, String title, String content)`
  - `StudyWeekRepository.findByStudyIdOrderByWeekNoAsc(String)`
  - `StudyCreateRequest(String title, List<String> fields, Integer capacity, String intro, String schedule, String place, String mode, String contact, List<StudyCreateRequest.WeekInput> weeks)`
  - `StudyCreateRequest.WeekInput(Integer weekNo, String title, String content)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
    private Map<String, Object> createBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "알고리즘");
        body.put("fields", List.of("PS"));
        body.put("capacity", 6);
        body.put("intro", "함께 풉니다");
        body.put("schedule", "매주 화 19:00");
        body.put("place", "공학관 401");
        body.put("mode", "오프라인");
        body.put("contact", "010-0000-0000");
        body.put("weeks", List.of(
                Map.of("weekNo", 1, "title", "완전탐색"),
                Map.of("weekNo", 2, "title", "이분탐색", "content", "파라메트릭 서치까지")));
        return body;
    }

    @Test
    void createStoresCurriculumWeeks() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        String id = given().header("Authorization", "Bearer " + token(leader))
                .contentType("application/json").body(createBody())
                .when().post("/api/studies")
                .then().statusCode(201).extract().path("id");

        assertThat(weeks.findByStudyIdOrderByWeekNoAsc(id))
                .extracting(StudyWeek::getWeekNo, StudyWeek::getTitle)
                .containsExactly(tuple(1, "완전탐색"), tuple(2, "이분탐색"));
    }

    @Test
    void createRejectsEmptyCurriculum() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Map<String, Object> body = createBody();
        body.put("weeks", List.of());
        given().header("Authorization", "Bearer " + token(leader))
                .contentType("application/json").body(body)
                .when().post("/api/studies")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void createRejectsWeekNumbersWithAGap() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Map<String, Object> body = createBody();
        body.put("weeks", List.of(
                Map.of("weekNo", 1, "title", "a"),
                Map.of("weekNo", 2, "title", "b"),
                Map.of("weekNo", 4, "title", "d")));
        given().header("Authorization", "Bearer " + token(leader))
                .contentType("application/json").body(body)
                .when().post("/api/studies")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void createRejectsMissingContact() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Map<String, Object> body = createBody();
        body.remove("contact");
        given().header("Authorization", "Bearer " + token(leader))
                .contentType("application/json").body(body)
                .when().post("/api/studies")
                .then().statusCode(422);
    }
```

`@Autowired StudyWeekRepository weeks;` 를 더하고, `@BeforeEach` 의 정리 순서 맨 앞에
`weeks.deleteAll();` 을 넣는다 (스터디보다 먼저 지워야 한다). `org.assertj.core.api.Assertions.tuple`
과 `java.util.LinkedHashMap` 을 import 한다.

앞선 Task 2 의 테스트들이 `createBody()` 를 쓰도록 `Map.of("title", ...)` 호출부도 함께 옮긴다.

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.StudyTest'
```

기대: 컴파일 실패 — `StudyWeek` 과 `StudyWeekRepository` 가 없다.

- [ ] **Step 3: `StudyWeek` 엔티티를 만든다**

```java
package com.jaram.be.study;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * 커리큘럼 주차. ② 단계의 출석이 이 행을 그대로 대상으로 삼는다 — 커리큘럼 주차와
 * 출석 주차를 따로 두면 화면의 "3주차"가 같은 것을 가리키는지 보장할 수 없다.
 *
 * weekNo 는 1부터 빈칸 없이 이어진다. 그 불변식은 저장 시점에 검사한다.
 */
@Entity
@Table(name = "study_week",
        uniqueConstraints = @UniqueConstraint(columnNames = {"study_id", "week_no"}))
public class StudyWeek {

    @Id
    private String id;

    @Column(name = "study_id")
    private String studyId;

    @Column(name = "week_no")
    private int weekNo;

    private String title;

    @Column(length = 2000)
    private String content;   // nullable

    protected StudyWeek() { }

    public static StudyWeek create(String studyId, int weekNo, String title, String content) {
        StudyWeek w = new StudyWeek();
        w.id = UUID.randomUUID().toString();
        w.studyId = studyId;
        w.weekNo = weekNo;
        w.title = title;
        w.content = content;
        return w;
    }

    public String getId() { return id; }
    public String getStudyId() { return studyId; }
    public int getWeekNo() { return weekNo; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
}
```

- [ ] **Step 4: `StudyWeekRepository` 를 만든다**

```java
package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StudyWeekRepository extends JpaRepository<StudyWeek, String> {
    List<StudyWeek> findByStudyIdOrderByWeekNoAsc(String studyId);
}
```

- [ ] **Step 5: `StudyCreateRequest` 를 새 폼으로 바꾼다**

```java
package com.jaram.be.study.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

// 계약 StudyCreateRequest. 전부 필수다 — 상세 모달이 이 값들로 스터디를 소개한다.
// period 는 받지 않는다: 커리큘럼 주차 수가 대신한다.
public record StudyCreateRequest(
        @NotBlank String title,
        @NotEmpty List<String> fields,
        @NotNull @Min(1) Integer capacity,
        @NotBlank String intro,
        @NotBlank String schedule,
        @NotBlank String place,
        @NotBlank String mode,
        @NotBlank String contact,
        @NotEmpty @Valid List<WeekInput> weeks) {

    public record WeekInput(
            @NotNull @Min(1) Integer weekNo,
            @NotBlank String title,
            @Size(max = 2000) String content) {
    }
}
```

- [ ] **Step 6: `StudyService.create` 가 주차를 저장하게 한다**

생성자에 `StudyWeekRepository weeks` 를 더하고 (필드·할당 포함):

```java
    @Transactional
    public StudyResponse create(StudyCreateRequest req, String leaderId) {
        eligibility.requireActive(leaderId);
        requireContiguousWeeks(req.weeks());
        Study saved = studies.save(Study.create(
                req.title(), req.fields(), req.capacity(),
                req.schedule(), req.place(), req.mode(), req.intro(), req.contact(), leaderId));
        req.weeks().forEach(w -> weeks.save(
                StudyWeek.create(saved.getId(), w.weekNo(), w.title(), w.content())));
        return toResponse(saved, members.findById(leaderId).orElse(null), leaderId);
    }

    /**
     * 1부터 빈칸 없이. 구멍이 있으면 ② 의 "가장 빠른 빈 주차" 가 흔들리고, 중복이 있으면
     * unique 제약이 500 으로 터진다 — 둘 다 여기서 422 로 막는다.
     */
    private void requireContiguousWeeks(List<StudyCreateRequest.WeekInput> input) {
        List<Integer> nos = input.stream().map(StudyCreateRequest.WeekInput::weekNo).sorted().toList();
        for (int i = 0; i < nos.size(); i++) {
            if (nos.get(i) != i + 1) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION",
                        "커리큘럼 주차는 1부터 빈칸 없이 이어져야 합니다.",
                        Map.of("weeks", "주차 번호가 1..%d 가 아닙니다.".formatted(nos.size())));
            }
        }
    }
```

- [ ] **Step 7: 테스트가 통과하는지 본다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.StudyTest'
```

기대: PASS

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "feat(study): 커리큘럼 주차를 개설 시 필수로 받는다

주차는 출석의 대상이기도 하다. 커리큘럼 주차와 출석 주차를 따로 두면 화면의
'3주차'가 같은 것을 가리키는지 보장할 수 없어, 하나로 둔다.

장소와 문의처도 개설 시 필수가 된다. 상세 모달이 이 값들로 스터디를 소개한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq"
```

---

## Task 4: 모집 토글

**Files:**
- Create: `src/main/java/com/jaram/be/study/StudyRecruitment.java`
- Create: `src/main/java/com/jaram/be/study/StudyRecruitmentRepository.java`
- Create: `src/main/java/com/jaram/be/study/dto/RecruitmentUpdate.java`
- Modify: `src/main/java/com/jaram/be/study/StudyService.java`
- Modify: `src/main/java/com/jaram/be/study/StudyController.java`
- Test: `src/test/java/com/jaram/be/study/StudyRecruitmentTest.java` (새 파일)

**Interfaces:**
- Produces:
  - `StudyService.recruitmentOpen()` → `boolean`
  - `StudyService.setRecruitmentOpen(boolean)`
  - `PUT /api/studies/recruitment`, 본문 `{"open": true}`, `204`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyRecruitmentTest.java`:

```java
package com.jaram.be.study;

import com.jaram.be.security.authz.Role;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyRecruitmentTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired StudyRepository studies;
    @Autowired StudyRecruitmentRepository recruitment;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        studies.deleteAll();
        recruitment.deleteAll();
    }

    private void put(String token, boolean open, int expected) {
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("open", open))
                .when().put("/api/studies/recruitment")
                .then().statusCode(expected);
    }

    @Test
    void academicLeadCanFlipTheToggle() {
        put(actors.token(Role.ACADEMIC_LEAD), true, 204);
        given().when().get("/api/studies").then().statusCode(200)
                .body("recruiting", equalTo(true));
    }

    @Test
    void prLeadAndPlainMemberAreRefused() {
        put(actors.token(Role.PR_LEAD), true, 403);
        put(actors.member(), true, 403);
    }

    @Test
    void defaultsToClosedWhenNobodyHasFlippedIt() {
        given().when().get("/api/studies").then().statusCode(200)
                .body("recruiting", equalTo(false));
    }

    @Test
    void togglingNeverMovesAnyStudyStatus() {
        Study s = studies.save(Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", "leader-id"));
        s.approve();
        studies.save(s);

        String token = actors.token(Role.ACADEMIC_LEAD);
        put(token, true, 204);
        put(token, false, 204);

        assertThat(studies.findById(s.getId()).orElseThrow().getStatus())
                .isEqualTo(StudyStatus.RECRUITING);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.StudyRecruitmentTest'
```

기대: 컴파일 실패 — `StudyRecruitmentRepository` 가 없다.

- [ ] **Step 3: `StudyRecruitment` 엔티티를 만든다**

```java
package com.jaram.be.study;

import jakarta.persistence.*;

/**
 * 스터디 개설 모집 토글. 단일 행이다.
 *
 * AdminSettings 에 두지 않은 이유: 학술부장(ACADEMIC_LEAD)은 STUDY_EDIT 은 갖지만
 * SETTINGS_* 를 하나도 갖지 않는다. SettingsAccess.canApply 가 마지막에 설정 권한
 * 하나를 요구하므로, 거기 필드를 더하면 스터디 관리 탭의 주인이 자기 토글에서 403 을
 * 받는다. 값이 스터디 쪽에 있으면 게이트가 hasAuthority('STUDY_EDIT') 한 줄이다.
 *
 * 이 값은 '스터디 개설' 버튼의 표시와 동작만 가른다. 어떤 스터디의 status 도
 * 건드리지 않는다 - 모집을 끝내는 판단은 스터디마다 다르고 스터디장이 한다.
 */
@Entity
@Table(name = "study_recruitment")
public class StudyRecruitment {

    static final String SINGLETON_ID = "SINGLETON";

    @Id
    private String id = SINGLETON_ID;

    /** 'open' 은 SQL 표준 예약어라 컬럼 이름을 피한다. */
    @Column(name = "is_open")
    private boolean open;

    protected StudyRecruitment() { }

    static StudyRecruitment closed() {
        StudyRecruitment r = new StudyRecruitment();
        r.id = SINGLETON_ID;
        r.open = false;
        return r;
    }

    public boolean isOpen() { return open; }
    public void setOpen(boolean v) { this.open = v; }
}
```

- [ ] **Step 4: 리포지토리와 요청 DTO 를 만든다**

```java
package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudyRecruitmentRepository extends JpaRepository<StudyRecruitment, String> {
}
```

```java
package com.jaram.be.study.dto;

import jakarta.validation.constraints.NotNull;

// 계약 RecruitmentUpdate. 값이 하나라 부분 수정할 것이 없다 — PUT 이다.
public record RecruitmentUpdate(@NotNull Boolean open) {
}
```

- [ ] **Step 5: `StudyService` 에 읽기·쓰기를 더한다**

생성자에 `StudyRecruitmentRepository recruitment` 를 더하고:

```java
    /** 행이 없으면 '닫혀 있다'. 기본을 열어 두면 아무도 안 눌렀을 때 개설이 열린다. */
    @Transactional(readOnly = true)
    public boolean recruitmentOpen() {
        return recruitment.findById(StudyRecruitment.SINGLETON_ID)
                .map(StudyRecruitment::isOpen)
                .orElse(false);
    }

    @Transactional
    public void setRecruitmentOpen(boolean open) {
        StudyRecruitment r = recruitment.findById(StudyRecruitment.SINGLETON_ID)
                .orElseGet(StudyRecruitment::closed);
        r.setOpen(open);
        recruitment.save(r);
    }
```

`StudyRecruitment.SINGLETON_ID` 와 `closed()` 는 package-private 이라 같은 패키지의 서비스에서 보인다.

- [ ] **Step 6: 개설 신청을 토글로 막는다**

`create` 의 맨 앞, `eligibility.requireActive` **다음**에:

```java
        if (!recruitmentOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "RECRUIT_CLOSED",
                    "지금은 스터디 개설 신청을 받지 않습니다.");
        }
```

화면이 버튼을 숨기는 것은 통제가 아니다 — 서버가 거절해야 한다.

- [ ] **Step 7: 컨트롤러에 토글 엔드포인트를 더한다**

`list` 바로 아래에 둔다. `@PathVariable` 경로들보다 **먼저** 선언해 읽는 사람이 literal
경로임을 알게 한다 (Spring 의 매칭 우선순위 자체는 선언 순서와 무관하다).

```java
    // 모집 토글. 스터디 관리 탭의 손잡이라 STUDY_EDIT 으로 가른다.
    @PutMapping("/recruitment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('STUDY_EDIT')")
    public void recruitment(@Valid @RequestBody RecruitmentUpdate req) {
        service.setRecruitmentOpen(req.open());
    }
```

- [ ] **Step 8: `StudyTest` 의 개설 테스트가 토글을 켜게 한다**

Task 2·3 에서 쓴 개설 테스트들이 이제 `409` 를 받는다. 토글을 켜 두어야 한다.
`@Autowired StudyService studyService;` 를 더하고 `@BeforeEach` 의 정리 다음에:

```java
        recruitment.deleteAll();
        studyService.setRecruitmentOpen(true);
```

그리고 토글이 실제로 막는지 보는 테스트를 하나 더한다:

```java
    @Test
    void createIsRefusedWhileRecruitmentIsClosed() {
        studyService.setRecruitmentOpen(false);
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        given().header("Authorization", "Bearer " + token(leader))
                .contentType("application/json").body(createBody())
                .when().post("/api/studies")
                .then().statusCode(409).body("code", equalTo("RECRUIT_CLOSED"));
    }
```

`@Autowired StudyRecruitmentRepository recruitment;` 도 함께 더한다.

- [ ] **Step 9: 테스트를 돌린다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.*'
```

기대: PASS. `StudyRecruitmentTest.academicLeadCanFlipTheToggle` 의
`body("recruiting", ...)` 단언은 **Task 5 전까지 실패한다** — 목록이 아직 배열이다.
그 두 단언(`recruiting` 을 보는 두 테스트)은 `@Disabled("Task 5")` 로 표시해 두고
Task 5 에서 되살린다.

- [ ] **Step 10: 커밋**

```bash
git add -A
git commit -m "feat(study): 모집 토글을 스터디 도메인에 두고 STUDY_EDIT 으로 가른다

AdminSettings 에 두면 SettingsAccess 가 마지막에 설정 권한을 요구해, STUDY_EDIT
만 가진 학술부장이 자기 관리 탭의 토글에서 403 을 받는다. 값을 스터디 쪽으로
옮겨 게이트를 hasAuthority('STUDY_EDIT') 한 줄로 만든다.

토글은 개설 신청만 열고 닫는다. 어떤 스터디의 status 도 건드리지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq"
```

---

## Task 5: 목록을 감싸고 상태 칩으로 거른다

**Files:**
- Create: `src/main/java/com/jaram/be/study/dto/StudyList.java`
- Modify: `src/main/java/com/jaram/be/study/StudyService.java`
- Modify: `src/main/java/com/jaram/be/study/StudyController.java`
- Test: `src/test/java/com/jaram/be/study/StudyTest.java`
- Test: `src/test/java/com/jaram/be/contract/StudyContractTest.java`

**Interfaces:**
- Consumes: Task 4 의 `StudyService.recruitmentOpen()`
- Produces: `StudyList(boolean recruiting, List<StudyResponse> items)`;
  `StudyService.list(String userId, String statusParam)` → `StudyList`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
    @Test
    void listWrapsItemsWithTheRecruitingFlagAndHidesFinished() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        approvedStudy(leader.getId(), 6);                       // RECRUITING
        Study ongoing = approvedStudy(leader.getId(), 6);
        ongoing.closeRecruiting();
        studies.save(ongoing);                                   // ONGOING
        Study done = approvedStudy(leader.getId(), 6);
        done.closeRecruiting();
        done.finish();
        studies.save(done);                                      // FINISHED

        given().when().get("/api/studies").then().statusCode(200)
                .body("recruiting", equalTo(true))
                .body("items.size()", equalTo(2));

        given().queryParam("status", "FINISHED").when().get("/api/studies")
                .then().statusCode(200).body("items.size()", equalTo(1));

        given().queryParam("status", "RECRUITING").when().get("/api/studies")
                .then().statusCode(200).body("items.size()", equalTo(1));
    }

    @Test
    void listRefusesStatusesThatMustNotBeBrowsable() {
        given().queryParam("status", "PENDING").when().get("/api/studies")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
        given().queryParam("status", "REJECTED").when().get("/api/studies")
                .then().statusCode(422);
        given().queryParam("status", "NOT_A_STATUS").when().get("/api/studies")
                .then().statusCode(422);
    }

    @Test
    void listCarriesTheLeaderGenerationAndIntro() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        leader.setGen(40);
        members.save(leader);
        approvedStudy(leader.getId(), 6);

        given().when().get("/api/studies").then().statusCode(200)
                .body("items[0].leader", equalTo("리더"))
                .body("items[0].leaderGen", equalTo(40))
                .body("items[0].intro", equalTo("함께 풉니다"));
    }
```

`StudyRecruitmentTest` 의 `@Disabled("Task 5")` 두 개를 지운다.

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.StudyTest'
```

기대: FAIL — 응답이 아직 배열이라 `recruiting` 이 없다.

- [ ] **Step 3: `StudyList` 를 만든다**

```java
package com.jaram.be.study.dto;

import java.util.List;

/**
 * 계약 StudyList. 목록을 감싸 모집 토글 상태를 함께 싣는다.
 *
 * 토글은 임원 전용 설정이 아니라 스터디 페이지가 첫 화면에서 알아야 하는 값이다.
 * 전용 GET 을 새로 내는 대신 어차피 부르는 이 응답에 얹는다.
 */
public record StudyList(boolean recruiting, List<StudyResponse> items) {
}
```

- [ ] **Step 4: 서비스의 `list` 를 필터와 감싸기로 바꾼다**

```java
    private static final List<StudyStatus> DEFAULT_LIST =
            List.of(StudyStatus.RECRUITING, StudyStatus.ONGOING);

    /** 목록에서 고를 수 있는 상태. PENDING·REJECTED 는 남의 개설 신청이라 새면 안 된다. */
    private static final Set<StudyStatus> BROWSABLE =
            EnumSet.of(StudyStatus.RECRUITING, StudyStatus.ONGOING, StudyStatus.FINISHED);

    @Transactional(readOnly = true)
    public StudyList list(String userId, String statusParam) {
        List<StudyStatus> want = statusParam == null || statusParam.isBlank()
                ? DEFAULT_LIST
                : List.of(browsable(statusParam));
        List<Study> rows = studies.findByStatusInOrderByCreatedAtDesc(want);
        Map<String, Member> leaders = leadersOf(rows);
        List<StudyResponse> items = rows.stream()
                .map(s -> toResponse(s, leaders.get(s.getLeaderId()), userId))
                .toList();
        return new StudyList(recruitmentOpen(), items);
    }

    /**
     * 파라미터를 열거형으로 바인딩하지 않고 직접 파싱한다. 바인딩에 맡기면 오타 하나가
     * MethodArgumentTypeMismatchException 이 되고, GlobalExceptionHandler 의 포괄
     * 핸들러가 그것을 500 으로 만든다.
     */
    private StudyStatus browsable(String raw) {
        StudyStatus s;
        try {
            s = StudyStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            s = null;
        }
        if (s == null || !BROWSABLE.contains(s)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION",
                    "조회할 수 없는 상태입니다.",
                    Map.of("status", "RECRUITING, ONGOING, FINISHED 중 하나여야 합니다."));
        }
        return s;
    }
```

`java.util.EnumSet` 과 `java.util.Set` 을 import 한다.

- [ ] **Step 5: 컨트롤러를 맞춘다**

```java
    // UC-T1: public. 미인증 시 principal null → apply 파생 null.
    @GetMapping
    public StudyList list(@RequestParam(required = false) String status,
                          @AuthenticationPrincipal CurrentMember me) {
        return service.list(me == null ? null : me.id(), status);
    }
```

- [ ] **Step 6: 계약 테스트를 새 모양으로 고친다**

이 테스트의 실제 그물은 단언이 아니라 `OpenApiValidationFilter` 다 — 스키마에 없는
응답 필드를 거부한다. 그래서 목록 테스트는 `statusCode(200)` 만 보고 있을 수 있다.
먼저 현행을 읽는다:

```bash
grep -n 'api/studies' -A 8 src/test/java/com/jaram/be/contract/StudyContractTest.java
```

`[0]`·`period` 를 경로로 쓰는 단언이 있으면 각각 `items[0]` 으로 바꾸고 `period` 는
지운다. 없으면 고칠 것이 없고, 검증기가 감싼 응답을 계약과 맞춰 본다.

- [ ] **Step 7: 테스트를 돌린다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.*' --tests 'com.jaram.be.contract.StudyContractTest'
```

기대: PASS (계약 PR 이 머지되어 있다면 CI 도 초록)

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "feat(study): 목록을 모집 상태와 함께 감싸고 상태로 거른다

파라미터가 없으면 RECRUITING + ONGOING 만 준다 - '전체' 칩은 지금 등록 가능한
것만 보여 준다는 뜻이고, 종료된 스터디는 '종료' 칩에서만 나온다.

PENDING·REJECTED 는 ?status= 로도 나오지 않는다. 남의 개설 신청과 반려 사유가
목록에 뜰 일은 없다.

status 를 열거형으로 바인딩하지 않는다. 바인딩 실패가 포괄 예외 핸들러를 타고
500 이 되기 때문이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq"
```

---

## Task 6: 소유자 조건과 상태 전이 API

**Files:**
- Create: `src/main/java/com/jaram/be/study/StudyAccess.java`
- Modify: `src/main/java/com/jaram/be/study/StudyService.java`
- Modify: `src/main/java/com/jaram/be/study/StudyController.java`
- Test: `src/test/java/com/jaram/be/study/StudyTransitionTest.java` (새 파일)

**Interfaces:**
- Produces:
  - `StudyAccess.isLeader(String studyId, Authentication auth)` → `boolean`
  - `StudyAccess.isLeaderOfApplication(String applicationId, Authentication auth)` → `boolean`
  - `StudyService.closeRecruiting(String id)` / `StudyService.finish(String id)`
  - `POST /api/studies/{id}/close-recruiting`, `POST /api/studies/{id}/finish` — 둘 다 `204`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyTransitionTest.java`:

```java
package com.jaram.be.study;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyTransitionTest extends PostgresTest {

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

    private void post(String token, String path, int expected) {
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/studies/" + study.getId() + path)
                .then().statusCode(expected);
    }

    @Test
    void leaderClosesRecruitingThenFinishes() {
        post(leaderToken, "/close-recruiting", 204);
        assertThat(studies.findById(study.getId()).orElseThrow().getStatus())
                .isEqualTo(StudyStatus.ONGOING);

        post(leaderToken, "/finish", 204);
        assertThat(studies.findById(study.getId()).orElseThrow().getStatus())
                .isEqualTo(StudyStatus.FINISHED);
    }

    @Test
    void anotherLeaderIsRefused() {
        Member other = members.save(approved("남", "2023000002", "other@hanyang.ac.kr"));
        recruiting(other.getId());   // 자기 스터디는 따로 있다
        post(actors.tokenFor(other), "/close-recruiting", 403);
    }

    @Test
    void plainMemberIsRefused() {
        post(actors.member(), "/close-recruiting", 403);
    }

    @Test
    void officerWithStudyEditMayActOnSomeoneElsesStudy() {
        post(actors.token(Role.ACADEMIC_LEAD), "/close-recruiting", 204);
    }

    @Test
    void transitionsRefuseTheWrongStartingState() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().post("/api/studies/" + study.getId() + "/finish")
                .then().statusCode(409).body("code", equalTo("INVALID_STATE"));

        post(leaderToken, "/close-recruiting", 204);

        given().header("Authorization", "Bearer " + leaderToken)
                .when().post("/api/studies/" + study.getId() + "/close-recruiting")
                .then().statusCode(409).body("code", equalTo("INVALID_STATE"));
    }

    @Test
    void applicantApprovalIsGatedByTheApplicationsOwnStudy() {
        Member applicant = members.save(approved("지원", "2023000003", "a@hanyang.ac.kr"));
        StudyApplication app = applications.save(
                StudyApplication.create(study.getId(), applicant.getId(), "하고 싶습니다"));

        Member other = members.save(approved("남", "2023000004", "b@hanyang.ac.kr"));
        given().header("Authorization", "Bearer " + actors.tokenFor(other))
                .when().post("/api/studies/applicants/" + app.getId() + "/approve")
                .then().statusCode(403);

        given().header("Authorization", "Bearer " + leaderToken)
                .when().post("/api/studies/applicants/" + app.getId() + "/approve")
                .then().statusCode(200);
    }
}
```

마지막 테스트가 이 Task 의 핵심이다 — `{id}` 가 신청 id 라는 사실을 못 박는다.

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.StudyTransitionTest'
```

기대: FAIL — 엔드포인트가 없어 404, 신청 승인은 `STUDY_APPLICANT_MANAGE` 가 없어 403.

- [ ] **Step 3: `StudyAccess` 를 만든다**

```java
package com.jaram.be.study;

import com.jaram.be.security.CurrentMember;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 3층 조건 — 권한이 아니라 리소스와의 관계를 본다. "내 스터디만 관리"는 Permission
 * 으로 표현할 수 없다. Role 은 member_term(부서·직책)에서 파생되는데 스터디장은
 * 스터디 한 건에 매인 관계라, 그 축에 올리면 "누구의 스터디장인가"가 사라진다.
 *
 * SeminarAccessPolicy 와 같은 모양이다.
 */
@Component("studyAccess")
public class StudyAccess {

    private final StudyRepository studies;
    private final StudyApplicationRepository applications;

    public StudyAccess(StudyRepository studies, StudyApplicationRepository applications) {
        this.studies = studies;
        this.applications = applications;
    }

    /** 경로 변수가 스터디 id 일 때 — 모집 완료·종료. */
    public boolean isLeader(String studyId, Authentication auth) {
        String me = idOf(auth);
        if (me == null) return false;
        return studies.findById(studyId)
                .map(s -> me.equals(s.getLeaderId()))
                .orElse(false);
    }

    /**
     * 경로 변수가 신청 id 일 때 — 신청 승인·반려.
     *
     * /api/studies/applicants/{id} 의 {id} 는 스터디 id 가 아니라 신청 id 다.
     * 여기에 isLeader(#id, ...) 를 걸면 신청 id 로 스터디를 조회하니 언제나 false 가
     * 되고, 스터디장은 자기 스터디의 신청을 하나도 처리하지 못한다 — 403 만 나오고
     * 이유는 안 보이는 종류의 버그다. 신청을 먼저 읽어 studyId 를 얻는다.
     */
    public boolean isLeaderOfApplication(String applicationId, Authentication auth) {
        String me = idOf(auth);
        if (me == null) return false;
        return applications.findById(applicationId)
                .flatMap(a -> studies.findById(a.getStudyId()))
                .map(s -> me.equals(s.getLeaderId()))
                .orElse(false);
    }

    private String idOf(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof CurrentMember me)) return null;
        return me.id();
    }
}
```

- [ ] **Step 4: 서비스에 전이를 더한다**

```java
    @Transactional
    public void closeRecruiting(String studyId) {
        Study s = loadStudy(studyId);
        requireState(s, StudyStatus.RECRUITING);
        s.closeRecruiting();
    }

    @Transactional
    public void finish(String studyId) {
        Study s = loadStudy(studyId);
        requireState(s, StudyStatus.ONGOING);
        s.finish();
    }

    /**
     * 전이는 한 칸씩만 간다. 건너뛰거나 되돌리는 것은 임원의 일괄 편집으로만 한다 —
     * 되돌릴 손이 하나 있으면 되고, 두 군데에 두면 규칙이 두 벌이 된다.
     */
    private void requireState(Study s, StudyStatus required) {
        if (s.getStatus() != required) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "지금 상태에서는 할 수 없는 동작입니다.");
        }
    }
```

- [ ] **Step 5: 컨트롤러에 전이 둘을 더하고 신청 승인·반려에 소유자 조건을 건다**

```java
    // 모집 완료 — RECRUITING 에서만. 스터디장이 자기 스터디의 모집을 닫는다.
    @PostMapping("/{id}/close-recruiting")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public void closeRecruiting(@PathVariable String id) { service.closeRecruiting(id); }

    // 종료 — ONGOING 에서만.
    @PostMapping("/{id}/finish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public void finish(@PathVariable String id) { service.finish(id); }
```

신청 승인·반려 둘의 애너테이션을 바꾼다. **`{id}` 는 신청 id 라 `isLeader` 가 아니다:**

```java
    @PostMapping("/applicants/{id}/approve")
    @PreAuthorize("@studyAccess.isLeaderOfApplication(#id, authentication)"
            + " or hasAuthority('STUDY_APPLICANT_MANAGE')")
    public void approveApplicant(@PathVariable String id) { service.approveApplicant(id); }

    @PostMapping("/applicants/{id}/reject")
    @PreAuthorize("@studyAccess.isLeaderOfApplication(#id, authentication)"
            + " or hasAuthority('STUDY_APPLICANT_MANAGE')")
    public void rejectApplicant(@PathVariable String id, @Valid @RequestBody RejectRequest req) {
        service.rejectApplicant(id, req.reason());
    }
```

**개설 승인·반려(`/{id}/approve`, `/{id}/reject`)에는 소유자 조건을 걸지 않는다.**
자기가 낸 개설 신청을 자기가 승인할 수 있으면 승인 절차 자체가 없는 것과 같다.
스터디장이라는 지위는 승인된 뒤에 생긴다.

- [ ] **Step 6: 자기 승인이 막히는지 확인하는 테스트를 더한다**

```java
    @Test
    void anApplicantCannotApproveTheirOwnStudyProposal() {
        Study mine = studies.save(Study.create("내 것", List.of("PS"), 6,
                "화", "401", "오프라인", "소개", "010", leader.getId()));
        given().header("Authorization", "Bearer " + leaderToken)
                .when().post("/api/studies/" + mine.getId() + "/approve")
                .then().statusCode(403);
    }
```

- [ ] **Step 7: 테스트를 돌린다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.*'
```

기대: PASS

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "feat(study): 상태를 움직이는 손잡이를 스터디장에게 준다

스터디장은 Role 이 아니라 소유자 조건이다. Role 은 member_term 에서 파생되는데
스터디장은 스터디 한 건에 매인 관계라, 그 축에 올리면 '누구의 스터디장인가'가
사라진다.

메서드가 둘인 이유: /api/studies/applicants/{id} 의 {id} 는 신청 id 다.
isLeader(#id, ...) 를 걸면 신청 id 로 스터디를 조회하니 언제나 false 가 되고,
스터디장이 자기 스터디의 신청을 하나도 처리하지 못한다.

개설 승인에는 소유자 조건을 걸지 않는다 - 자기 신청을 자기가 승인하면 승인
절차가 없는 것과 같다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq"
```

---

## Task 7: 상세와 지원 인원 명단

**Files:**
- Create: `src/main/java/com/jaram/be/study/dto/StudyDetail.java`
- Create: `src/main/java/com/jaram/be/study/dto/WeekEntry.java`
- Create: `src/main/java/com/jaram/be/study/dto/RosterEntry.java`
- Modify: `src/main/java/com/jaram/be/study/StudyApplicationRepository.java`
- Modify: `src/main/java/com/jaram/be/study/StudyService.java`
- Modify: `src/main/java/com/jaram/be/study/StudyController.java`
- Test: `src/test/java/com/jaram/be/study/StudyDetailTest.java` (새 파일)
- Test: `src/test/java/com/jaram/be/study/StudyMaskingTest.java` (새 파일)

**Interfaces:**
- Consumes: Task 3 의 `StudyWeekRepository`, Task 2 의 `StudyService.deriveApply`
- Produces:
  - `StudyService.detail(String id, String userId)` → `StudyDetail`
  - `StudyService.maskStudentId(String)` — package-private static, 단위 테스트가 직접 부른다
  - `GET /api/studies/{id}` — 인증 필수

- [ ] **Step 1: 마스킹 단위 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyMaskingTest.java`:

```java
package com.jaram.be.study;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 학번은 ^\d{8,10}$ 다. 길이를 보존한 채 앞 4자리와 뒤 1자리만 남긴다. */
class StudyMaskingTest {

    @Test
    void masksTenDigitStudentIds() {
        assertThat(StudyService.maskStudentId("2022123459")).isEqualTo("2022*****9");
    }

    @Test
    void masksEightDigitStudentIds() {
        assertThat(StudyService.maskStudentId("20231234")).isEqualTo("2023***4");
    }

    @Test
    void leavesTooShortOrNullValuesAlone() {
        assertThat(StudyService.maskStudentId(null)).isNull();
        assertThat(StudyService.maskStudentId("12345")).isEqualTo("12345");
    }
}
```

- [ ] **Step 2: 상세 통합 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyDetailTest.java`:

```java
package com.jaram.be.study;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyDetailTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyApplicationRepository applications;
    @Autowired Actors actors;

    private Study study;
    private String viewerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        Member leader = save("리더", "2022123459", "leader@hanyang.ac.kr", 40);
        study = Study.create("알고리즘", List.of("PS"), 8,
                "화 19:00", "공학관 401", "오프라인", "함께 풉니다", "010-1111-2222", leader.getId());
        study.approve();
        studies.save(study);
        weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        weeks.save(StudyWeek.create(study.getId(), 2, "이분탐색", "파라메트릭"));

        Member pending = save("가나", "20231111", "p@hanyang.ac.kr", 42);
        Member approved = save("다라", "20230000", "q@hanyang.ac.kr", 41);
        Member rejected = save("마바", "20239999", "r@hanyang.ac.kr", 41);
        applications.save(StudyApplication.create(study.getId(), pending.getId(), "동기"));
        StudyApplication ok = applications.save(
                StudyApplication.create(study.getId(), approved.getId(), "동기"));
        ok.approve();
        applications.save(ok);
        StudyApplication no = applications.save(
                StudyApplication.create(study.getId(), rejected.getId(), "동기"));
        no.reject("사유");
        applications.save(no);

        viewerToken = actors.member();
    }

    private Member save(String name, String sid, String email, int gen) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGen(gen);
        return members.save(m);
    }

    @Test
    void detailRequiresLoginWhileTheListStaysPublic() {
        given().when().get("/api/studies/" + study.getId()).then().statusCode(401);
        given().when().get("/api/studies").then().statusCode(200);
    }

    @Test
    void detailCarriesCurriculumContactAndPlace() {
        given().header("Authorization", "Bearer " + viewerToken)
                .when().get("/api/studies/" + study.getId())
                .then().statusCode(200)
                .body("title", equalTo("알고리즘"))
                .body("leader", equalTo("리더"))
                .body("leaderGen", equalTo(40))
                .body("place", equalTo("공학관 401"))
                .body("contact", equalTo("010-1111-2222"))
                .body("cur", equalTo(1))
                .body("cap", equalTo(8))
                .body("weeks.size()", equalTo(2))
                .body("weeks[0].weekNo", equalTo(1))
                .body("weeks[1].content", equalTo("파라메트릭"));
    }

    @Test
    void rosterHidesRejectedApplicantsTheLeaderAndEveryApprovalState() {
        given().header("Authorization", "Bearer " + viewerToken)
                .when().get("/api/studies/" + study.getId())
                .then().statusCode(200)
                // 대기 1 + 승인 1. 반려와 스터디장은 빠진다.
                .body("roster.size()", equalTo(2))
                // 기수 → 이름. 41기 '다라' 가 42기 '가나' 보다 앞이다.
                .body("roster[0].name", equalTo("다라"))
                .body("roster[1].name", equalTo("가나"))
                .body("roster[0].studentId", equalTo("2023***0"))
                .body("roster[1].studentId", equalTo("2023***1"))
                .body("roster[0]", not(hasKey("status")))
                .body("roster[0]", not(hasKey("applicationStatus")));
    }

    @Test
    void rosterNeverCarriesAFullStudentId() {
        String body = given().header("Authorization", "Bearer " + viewerToken)
                .when().get("/api/studies/" + study.getId())
                .then().statusCode(200).extract().asString();
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("20231111").doesNotContain("20230000")
                .doesNotContain("20239999").doesNotContain("2022123459");
    }
}
```

- [ ] **Step 3: 실패를 확인한다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.StudyDetailTest' --tests 'com.jaram.be.study.StudyMaskingTest'
```

기대: 컴파일 실패 — `maskStudentId` 가 없고 상세 엔드포인트가 없다.

- [ ] **Step 4: 응답 DTO 셋을 만든다**

```java
package com.jaram.be.study.dto;

// 계약 StudyWeek. 상세 모달의 커리큘럼 한 줄.
public record WeekEntry(int weekNo, String title, String content) {
}
```

```java
package com.jaram.be.study.dto;

/**
 * 계약 StudyRosterEntry. 상세 모달 하단의 지원 인원 한 줄.
 *
 * 승인 여부를 내려보내지 않는다 — 객체에 상태 필드 자체를 넣지 않는다. 쓰지 않는
 * 필드를 실어 보내면 언젠가 화면에 샌다. studentId 는 서버가 마스킹한 값이다.
 */
public record RosterEntry(String studentId, Integer gen, String name) {
}
```

```java
package com.jaram.be.study.dto;

import com.jaram.be.study.ApplyState;
import com.jaram.be.study.StudyStatus;
import java.util.List;

// 계약 StudyDetail. 목록 카드가 가진 것에 장소·문의·커리큘럼·지원 인원이 더해진다.
public record StudyDetail(
        String id,
        String title,
        List<String> fields,
        String intro,
        String leader,
        Integer leaderGen,
        String schedule,
        String place,
        String mode,
        String contact,
        int cur,
        int cap,
        StudyStatus status,
        ApplyState apply,
        List<WeekEntry> weeks,
        List<RosterEntry> roster) {
}
```

- [ ] **Step 5: 신청 조회 질의를 더한다**

`StudyApplicationRepository` 에:

```java
    List<StudyApplication> findByStudyIdAndStatusIn(String studyId, Collection<ApplicationStatus> statuses);
```

`java.util.Collection` 을 import 한다.

- [ ] **Step 6: 서비스에 상세와 마스킹을 더한다**

```java
    @Transactional(readOnly = true)
    public StudyDetail detail(String id, String userId) {
        Study s = loadStudy(id);
        Member leader = members.findById(s.getLeaderId()).orElse(null);
        List<WeekEntry> curriculum = weeks.findByStudyIdOrderByWeekNoAsc(id).stream()
                .map(w -> new WeekEntry(w.getWeekNo(), w.getTitle(), w.getContent()))
                .toList();
        return new StudyDetail(
                s.getId(), s.getTitle(), s.getFields(), s.getIntro(),
                leader == null ? null : leader.getName(),
                leader == null ? null : leader.getGen(),
                s.getSchedule(), s.getPlace(), s.getMode(), s.getContact(),
                approvedCount(id), cap(s), s.getStatus(), deriveApply(s, userId),
                curriculum, roster(s));
    }

    /**
     * 대기 + 승인. 반려와 스터디장은 뺀다 — 스터디장은 지원자가 아니고 이미 제목 아래에 있다.
     *
     * 정렬이 기수 → 이름인 것은 규칙이다. 신청 순으로 세우면 "먼저 신청했는데 아직 뒤에
     * 있다"가 순서에서 읽혀, 숨긴 승인 상태가 새어 나온다.
     */
    private List<RosterEntry> roster(Study s) {
        List<StudyApplication> rows = applications.findByStudyIdAndStatusIn(
                s.getId(), List.of(ApplicationStatus.PENDING, ApplicationStatus.APPROVED));
        Map<String, Member> byId = members.findAllById(
                        rows.stream().map(StudyApplication::getApplicantId).toList()).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        return rows.stream()
                .map(a -> byId.get(a.getApplicantId()))
                .filter(Objects::nonNull)
                .filter(m -> !m.getId().equals(s.getLeaderId()))
                .sorted(Comparator
                        .comparing(Member::getGen, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Member::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(m -> new RosterEntry(maskStudentId(m.getStudentId()), m.getGen(), m.getName()))
                .toList();
    }

    /**
     * 앞 4자리 + 가운데 전부 '*' + 뒤 1자리. 길이를 보존한다.
     *
     * 마스킹을 서버가 하는 이유: 전체 학번을 내려보내고 화면에서 가리면 개발자 도구로
     * 그대로 보인다.
     */
    static String maskStudentId(String id) {
        if (id == null || id.length() < 6) return id;   // 방어. 학번은 ^\d{8,10}$
        return id.substring(0, 4)
                + "*".repeat(id.length() - 5)
                + id.substring(id.length() - 1);
    }
```

`java.util.Comparator` 와 `java.util.Objects` 를 import 한다. 생성자에 이미
`StudyWeekRepository weeks` 가 있다 (Task 3).

- [ ] **Step 7: 컨트롤러에 상세를 더한다**

```java
    // UC-T9: 상세. 지원 인원 명단이 붙으므로 로그인 필수다.
    // SecurityConfig 에 GET /api/studies/* 를 permitAll 로 넣지 않는다 — 그 와일드카드가
    // /my·/pending·/applicants 까지 한 세그먼트로 잡는다.
    @GetMapping("/{id}")
    public StudyDetail detail(@PathVariable String id, @AuthenticationPrincipal CurrentMember me) {
        return service.detail(id, me.id());
    }
```

`/my`·`/pending`·`/applicants` 는 literal 세그먼트라 Spring 의 PathPattern 이
`{id}` 보다 먼저 고른다. 선언 순서와 무관하지만, Step 8 이 그 사실을 테스트로 고정한다.

- [ ] **Step 8: literal 경로가 `{id}` 에 먹히지 않는지 고정한다**

`StudyDetailTest` 에 더한다:

```java
    @Test
    void literalPathsStillWinOverTheIdPattern() {
        given().header("Authorization", "Bearer " + viewerToken)
                .when().get("/api/studies/my").then().statusCode(200);
        given().header("Authorization", "Bearer " + actors.officer())
                .when().get("/api/studies/pending").then().statusCode(200);
        given().header("Authorization", "Bearer " + actors.officer())
                .when().get("/api/studies/applicants").then().statusCode(200);
    }
```

- [ ] **Step 9: 테스트를 돌린다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.study.*'
```

기대: PASS

- [ ] **Step 10: 커밋**

```bash
git add -A
git commit -m "feat(study): 상세에 커리큘럼과 지원 인원 명단을 싣는다

명단은 대기와 승인을 함께 싣되 승인 여부는 내려보내지 않는다. 응답 객체에 상태
필드 자체를 두지 않는다 - 쓰지 않는 필드를 실어 보내면 언젠가 화면에 샌다.

정렬이 기수 -> 이름인 것도 같은 이유다. 신청 순으로 세우면 순서에서 승인 상태가
읽힌다.

마스킹은 서버가 한다. 전체 학번을 내려보내고 화면에서 가리면 개발자 도구로
그대로 보인다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq"
```

---

## Task 8: 임원이 상태를 고칠 손 (D12)

**Files:**
- Modify: `src/main/java/com/jaram/be/admin/AdminBatchExecutor.java:292`
- Test: `src/test/java/com/jaram/be/admin/AdminStudyBatchTest.java` (새 파일)

**Interfaces:**
- Consumes: Task 2 의 `Study.setStatus(StudyStatus)`
- Produces: `PATCH /api/admin/studies:batch` 가 `status` 필드를 받는다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/AdminStudyBatchTest.java`:

```java
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
        patch("RECRUITING").then().statusCode(200);
        assertThat(studies.findById(study.getId()).orElseThrow().getStatus())
                .isEqualTo(StudyStatus.RECRUITING);
    }

    @Test
    void anUnknownStatusIsRefusedAndNothingChanges() {
        patch("NOT_A_STATUS").then().statusCode(200);
        assertThat(studies.findById(study.getId()).orElseThrow().getStatus())
                .isEqualTo(StudyStatus.FINISHED);
    }
}
```

**요청 본문 모양을 먼저 확인한다.** `AdminResourceController#batch` 와 그 요청 DTO 를
읽어 `updates`/`id`/`version`/`fields` 키 이름이 실제와 같은지 맞춘다:

```bash
sed -n '1,80p' src/main/java/com/jaram/be/admin/AdminResourceController.java
ls src/main/java/com/jaram/be/admin/dto/
```

키가 다르면 **테스트를 실제 계약에 맞춘다** — 이 Task 는 일괄 편집의 모양을 바꾸지 않는다.
실패 응답 모양(행별 `errors` 인지 전체 4xx 인지)도 같은 방법으로 확인해 두 번째 테스트의
단언을 맞춘다.

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.admin.AdminStudyBatchTest'
```

기대: FAIL — `status` 가 "수정할 수 없는 필드입니다." 로 거부된다.

- [ ] **Step 3: `updateStudy` 에 분기를 더한다**

`AdminBatchExecutor.updateStudy` 의 `switch`:

```java
    private Map<String, String> updateStudy(Study s, Map<String, Object> f) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<Runnable> actions = new ArrayList<>();
        f.forEach((k, v) -> {
            switch (k) {
                case "title" -> actions.add(() -> s.setTitle(str(v)));
                case "capacity" -> intField(v, errors, k, s::setCapacity, actions);
                // 되돌릴 손. 스터디장이 '모집 완료'를 잘못 눌렀거나, 졸업으로 스터디장이
                // 사라졌거나, 학기 말 정리가 필요할 때 상태를 고칠 곳이 여기뿐이다.
                case "status" -> enumField(StudyStatus.class, v, errors, k, s::setStatus, actions, false);
                default -> errors.put(k, "수정할 수 없는 필드입니다.");
            }
        });
        if (errors.isEmpty()) actions.forEach(Runnable::run);
        return errors;
    }
```

`com.jaram.be.study.StudyStatus` 를 import 한다. `errors` 가 비지 않으면 `actions` 를
아예 실행하지 않는 기존 구조 덕분에, 잘못된 값이 오면 그 행의 다른 필드도 안 바뀐다.

- [ ] **Step 4: 테스트를 돌린다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.admin.*'
```

기대: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat(admin): 스터디 상태를 일괄 편집에서 고칠 수 있게 한다

전이 API 는 한 칸씩만 간다. 건너뛰거나 되돌리는 손이 하나는 있어야 하는데,
그것을 여기 둔다 - 게이트는 기존 AdminResourceAccess 라 새 권한이 없다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq"
```

---

## Task 9: 애너테이션 누락을 실제로 잡는 그물

지금 `AdminAuthorizationCoverageTest` 는 `/api/admin` 으로 시작하는 핸들러만 본다.
이 계획이 만든 `close-recruiting`·`finish`·`recruitment` 는 `/api/studies` 아래라 그
그물에 걸리지 않는다. `SecurityConfig` 의 마지막 줄이 `anyRequest().authenticated()`
이므로, 애너테이션을 빠뜨리면 **로그인한 아무나 남의 스터디를 종료**시킬 수 있다.

**Files:**
- Create: `src/test/java/com/jaram/be/security/AuthorizationCoverageTest.java`
- Delete: `src/test/java/com/jaram/be/security/AdminAuthorizationCoverageTest.java`

**Interfaces:**
- Consumes: Task 4·6·7 이 더한 핸들러들

- [ ] **Step 1: 지금 애너테이션이 없는 핸들러를 센다**

```bash
grep -rn "Mapping" --include='*Controller.java' src/main/java | wc -l
```

이 계획을 여기까지 따랐다면 `@PreAuthorize` 없는 핸들러는 **23개**다 — 공개 9개
(auth 4 + 목록 5)와 인증만 14개(`MeController` 4, `ScheduleController` 3,
`SeminarController` 3, `StudyController` 4). Step 2 의 두 집합이 그 23개다.
숫자가 다르면 먼저 왜 다른지 확인한다.

- [ ] **Step 2: 새 테스트를 쓴다**

`src/test/java/com/jaram/be/security/AuthorizationCoverageTest.java`:

```java
package com.jaram.be.security;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모든 핸들러가 @PreAuthorize 를 갖거나, 아래 두 집합에 이름이 적혀 있어야 한다.
 *
 * 예전 판은 /api/admin 으로 시작하는 핸들러만 봤다. 그래서 스터디의 상태 전이처럼
 * /api/admin 밖에 있는 권한 동작은 그물에 걸리지 않았고, 애너테이션을 빠뜨리면
 * SecurityConfig 의 anyRequest().authenticated() 때문에 "로그인한 아무나"가 됐다.
 *
 * 지켜야 할 경로를 열거하는 방식은 고르지 않았다 — 애너테이션을 잊는 것과 똑같이
 * 목록을 잊을 수 있어서, 잊었을 때 조용한 성질이 그대로 남는다. 기본값을 뒤집어
 * "권한이 필요 없는 것"을 적게 하면, 새 엔드포인트는 기본적으로 이 테스트를 깬다.
 * 작성자는 게이트를 달거나 아래에 한 줄을 더하면서 그 선택을 눈으로 확인하게 된다.
 *
 * 이 두 집합은 그대로 "누가 권한 없이 통과하는가"의 표다.
 */
@SpringBootTest
class AuthorizationCoverageTest extends PostgresTest {

    /** SecurityConfig 가 permitAll 로 여는 것들. 비로그인 방문자가 본다. */
    private static final Set<String> PUBLIC = Set.of(
            "AuthController#login",
            "AuthController#signup",
            "AuthController#resetRequest",
            "AuthController#resetConfirm",
            "PeopleController#list",
            "SeminarController#list",
            "StudyController#list",
            "ScheduleController#list",
            "SiteLinksController#get");

    /** 로그인만으로 통과하는 것이 의도인 핸들러. 권한이 아니라 1층 자격이 가른다. */
    private static final Set<String> AUTHENTICATED_ONLY = Set.of(
            "MeController#get",
            "MeController#update",
            "MeController#withdraw",
            "MeController#reregister",
            "ScheduleController#claim",
            "ScheduleController#cancel",
            "ScheduleController#submit",
            "SeminarController#getOne",
            "SeminarController#attend",
            "SeminarController#attendees",
            "StudyController#detail",
            "StudyController#create",
            "StudyController#apply",
            "StudyController#my");

    @Autowired RequestMappingHandlerMapping mapping;

    @Test
    void everyHandlerIsEitherGatedOrExplicitlyListed() {
        List<String> ungated = new ArrayList<>();

        mapping.getHandlerMethods().forEach((info, method) -> {
            String name = method.getBeanType().getSimpleName() + "#" + method.getMethod().getName();
            if (!method.getBeanType().getPackageName().startsWith("com.jaram.be")) return;

            boolean declared = method.getMethodAnnotation(PreAuthorize.class) != null
                    || method.getBeanType().getAnnotation(PreAuthorize.class) != null;
            if (declared || PUBLIC.contains(name) || AUTHENTICATED_ONLY.contains(name)) return;

            Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of()
                    : info.getPathPatternsCondition().getPatternValues();
            ungated.add(name + " " + patterns);
        });

        assertThat(ungated)
                .as("@PreAuthorize 도 없고 목록에도 없는 핸들러 — 로그인한 아무나 쓸 수 있게 된다. "
                        + "게이트를 달거나, 의도한 것이라면 PUBLIC/AUTHENTICATED_ONLY 에 적어라")
                .isEmpty();
    }

    @Test
    void theListsDoNotRotIntoNamesThatNoLongerExist() {
        Set<String> live = new java.util.HashSet<>();
        mapping.getHandlerMethods().forEach((info, method) -> {
            if (!method.getBeanType().getPackageName().startsWith("com.jaram.be")) return;
            live.add(method.getBeanType().getSimpleName() + "#" + method.getMethod().getName());
        });

        List<String> stale = new ArrayList<>();
        PUBLIC.forEach(n -> { if (!live.contains(n)) stale.add(n); });
        AUTHENTICATED_ONLY.forEach(n -> { if (!live.contains(n)) stale.add(n); });

        assertThat(stale).as("없어진 핸들러가 목록에 남아 있다 — 지워라").isEmpty();
    }
}
```

두 번째 테스트가 있는 이유: allowlist 는 방치되면 거짓말이 된다. 지워진 핸들러 이름이
목록에 남아 있으면, 나중에 같은 이름의 새 핸들러가 생겼을 때 조용히 통과한다.

`com.jaram.be` 패키지 필터는 스프링이 등록하는 에러 핸들러 같은 프레임워크 핸들러를
제외한다.

- [ ] **Step 3: 옛 테스트를 지운다**

```bash
rm src/test/java/com/jaram/be/security/AdminAuthorizationCoverageTest.java
```

새 테스트가 `/api/admin` 을 포함한 전부를 덮으므로 남겨 두면 같은 것을 두 번 센다.

- [ ] **Step 4: 그물이 실제로 잡는지 확인한다**

`StudyController#finish` 의 `@PreAuthorize` 를 잠깐 주석 처리하고 돌린다.

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.security.AuthorizationCoverageTest'
```

기대: FAIL, 메시지에 `StudyController#finish` 가 나온다. **확인했으면 주석을 되돌린다.**

- [ ] **Step 5: 되돌린 뒤 초록인지 본다**

```bash
./gradlew --no-daemon -I <init-script> test --tests 'com.jaram.be.security.AuthorizationCoverageTest'
```

기대: PASS

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "test(security): 권한 그물을 /api/admin 밖까지 넓히고 기본값을 뒤집는다

예전 판은 /api/admin 으로 시작하는 핸들러만 봤다. 스터디의 상태 전이처럼 그
밖에 있는 권한 동작은 걸리지 않았고, 애너테이션을 빠뜨리면 anyRequest()
.authenticated() 때문에 '로그인한 아무나'가 됐다.

지켜야 할 경로를 열거하는 대신 권한이 필요 없는 것을 적게 한다. 새 엔드포인트는
기본적으로 이 테스트를 깨고, 작성자는 게이트를 달거나 목록에 한 줄을 더하면서
그 선택을 눈으로 확인한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq"
```

---

## Task 10: 이행 SQL 과 배포 순서

**Files:**
- Create: `docs/migrations/2026-09-15-study-lifecycle.sql`

**Interfaces:**
- Consumes: Task 2 가 만든 `Study.status` 매핑

- [ ] **Step 1: 기존 마이그레이션 파일의 모양을 본다**

```bash
ls docs/migrations/
head -30 docs/migrations/2026-07-20-member-refactor.sql
```

머리말 주석의 관례(날짜·PR·실행 시점 표기)를 그대로 따른다.

- [ ] **Step 2: SQL 을 쓴다**

`docs/migrations/2026-09-15-study-lifecycle.sql`:

```sql
-- 2026-09-15 스터디 ① 상태 기계
-- 승인축(approval_status)을 생애축(status)으로 접는다.
--
-- ddl-auto: update 는 추가만 자동이고 삭제는 손이다. 그런데 status 는 추가도 손이다 —
-- 배포가 develop push 한 번에 compose pull + up 이라(.github/workflows/image.yml),
-- 컬럼 생성과 트래픽 수용이 같은 컨테이너 기동이다. "ddl-auto 가 만든 직후, 새 코드가
-- 받기 전"이라는 창이 존재하지 않는다. 그래서 컬럼을 먼저 만들고 채운 뒤 머지한다.
-- Hibernate 의 update 는 이미 있는 컬럼에 대해서는 아무 DDL 도 내지 않는다.

-- ========== 1단계: BE PR 을 develop 에 머지하기 전에 ==========

-- not null 도, check 제약도 붙이지 않는다.
--  * not null: 행이 있는 테이블에 붙이면 Postgres 가 거부한다.
--  * check: Hibernate 6.2+ 는 enum 컬럼을 만들 때 값 목록 check 를 같이 만들지만,
--    이미 있는 컬럼에 뒤늦게 붙이지는 않는다. 손으로 붙이면 나중에 enum 값이 늘 때
--    update 가 그 제약을 고쳐 주지 않아 INSERT 가 막힌다.
ALTER TABLE study ADD COLUMN IF NOT EXISTS status varchar(255);

-- 승인된 것은 진행 중으로 본다. 지난 학기 것들은 사실 FINISHED 에 가깝지만, 틀리면
-- 목록에서 사라져 눈에 안 띈다. ONGOING 으로 두면 '전체' 칩에 남아 있으니 임원이
-- 보고 일괄 편집으로 내릴 수 있다. 안 보이는 쪽으로 틀리는 것보다 보이는 쪽으로
-- 틀리는 편이 고치기 쉽다.
--
-- AND status IS NULL 이 멱등성을 만든다. 없으면 배포 뒤 이 파일을 한 번 더 돌렸을 때
-- 그동안 스터디장과 임원이 옮겨 놓은 상태가 전부 approval_status 로 되감긴다 —
-- 종료한 스터디가 다시 진행 중이 되고, 그 사실은 아무 데도 안 남는다.
UPDATE study SET status = 'PENDING'
    WHERE approval_status = 'PENDING'  AND status IS NULL;
UPDATE study SET status = 'REJECTED'
    WHERE approval_status = 'REJECTED' AND status IS NULL;
UPDATE study SET status = 'ONGOING'
    WHERE approval_status = 'APPROVED' AND status IS NULL;

-- 0 이 아니면 배포하지 않는다. 남은 NULL 은 status in (...) 필터에 하나도 걸리지 않아
-- 그 스터디들이 목록에서 조용히 사라진다.
SELECT count(*) AS must_be_zero FROM study WHERE status IS NULL;

-- ========== 2단계: 배포 후, 코드가 옛 컬럼을 읽지 않는 것을 확인한 뒤 ==========

-- 먼저 지우면 구버전 인스턴스가 뜨는 동안 매핑이 깨진다.
ALTER TABLE study DROP COLUMN IF EXISTS approval_status;
ALTER TABLE study DROP COLUMN IF EXISTS period;
```

`docs/migrations/README.md` 의 규칙이 **멱등**을 요구한다 — 두 번 실행해도 결과가
같아야 한다. `IF NOT EXISTS`/`IF EXISTS` 와 `AND status IS NULL` 이 그것을 지킨다.
2단계를 먼저 돌려 컬럼이 없어진 뒤 1단계를 다시 돌리면 `UPDATE` 가
`approval_status` 를 못 찾아 에러로 멈춘다 — 그때는 아무것도 바뀌지 않으므로
안전하다.

- [ ] **Step 3: 계획대로 컬럼이 만들어지는지 로컬에서 확인한다**

로컬 Postgres 에 이행 이전 모양의 행을 하나 만들어 1단계를 돌려 본다. 컨테이너가
이미 있다면:

```bash
docker run --rm -d --name jaram-mig -e POSTGRES_PASSWORD=jaram -e POSTGRES_USER=jaram \
    -e POSTGRES_DB=jaram -p 55432:5432 postgres:16-alpine
```

기동한 뒤 옛 스키마를 흉내 내고 1단계 SQL 을 그대로 붙여 넣어 `must_be_zero` 가 0 인지
본다. 확인이 끝나면 `docker rm -f jaram-mig`.

- [ ] **Step 3b: README 의 적용 이력 표에 줄을 더한다**

`docs/migrations/README.md` 의 표 맨 아래에:

```markdown
| 2026-09-15-study-lifecycle.sql | | | |
```

적용일·서버·실행자는 실제로 돌린 사람이 채운다.

- [ ] **Step 4: 커밋**

```bash
git add docs/migrations/2026-09-15-study-lifecycle.sql docs/migrations/README.md
git commit -m "docs(migrations): 스터디 승인축을 생애축으로 접는 SQL

컬럼 생성도 손으로 한다. 배포가 compose pull + up 하나라 ddl-auto 가 컬럼을
만든 직후와 트래픽 수용 사이에 창이 없다. 먼저 만들고 채운 뒤 머지한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq"
```

- [ ] **Step 5: 전체 스위트를 돌린다**

```bash
./gradlew --no-daemon -I <init-script> test
```

기대: 전부 PASS

- [ ] **Step 6: 푸시하고 PR 을 올린다**

```bash
git push -u origin feat/study-lifecycle
gh pr create --draft --title "feat(study): 스터디 상태 기계와 모집 ①" --body "$(cat <<'BODY'
`docs/superpowers/specs/2026-09-15-study-lifecycle-design.md` 의 ① 단계.

## 머지 전 체크리스트

- [ ] home-jaram-fe 의 `feat/study-lifecycle` 계약 PR 이 **먼저** 머지되었다
- [ ] 운영 DB 에 `docs/migrations/2026-09-15-study-lifecycle.sql` 의 **1단계**를 돌렸고
      `must_be_zero` 가 0 이었다
- [ ] 머지 후 배포가 끝나면 같은 파일의 **2단계**(DROP COLUMN 둘)를 돌린다

## 범위 밖

②(내 스터디·관리하기 모달·출석)와 ③(임원 스터디 관리 화면)은 다음 단계다.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
BODY
)"
```

---

## 실행 순서 요약

```
Task 1 (FE 계약)  ──머지──▶  Task 2 ─▶ Task 3 ─▶ Task 4 ─▶ Task 5
                                                              │
                              Task 10 ◀─ Task 9 ◀─ Task 8 ◀─ Task 6 ─▶ Task 7
```

Task 2~10 은 BE 브랜치에서 순서대로 간다. Task 1 의 머지가 늦어도 로컬 테스트는
초록이지만 **CI 는 빨간불**이고, 그것은 계약이 아직 없다는 뜻이지 코드가 틀렸다는
뜻이 아니다.

## 자체 검토

**스펙 적용 범위** — §3 상태 기계(Task 2·6), §4 모집 토글(Task 4), §5 데이터 모델
(Task 2·3·4), §6 신청 규칙(Task 2), §7 명단·마스킹(Task 7), §8 화면과 응답
(Task 5·7), §9 권한(Task 6·9), §10 엔드포인트(Task 4·5·6·7), §11 스키마 이행
(Task 10), §12 계약(Task 1), §13 테스트(각 Task 에 분산). 빠진 절이 없다.

**D 결정 대응** — D1(T2) D2(T2) D3(T4) D4(기존 유지, T6 이 소유자 조건을 **안** 거는
것으로 확인) D5(T3) D6(T3) D7(계약에 이미 `fields`) D8(T2) D9(T2) D10(T7) D11(② 단계,
T2 의 `deriveApply` 주석이 자리를 잡아 둠) D12(T8) D13(T6) D14(② 단계, T3 이
`weekNo` 연속 불변식만 세움) D15(T2 의 `cur`/`cap` 주석).

**이름 일관성** — `Study.create` 9인자 순서(`title, fields, capacity, schedule, place,
mode, intro, contact, leaderId`)가 Task 2·3·4·6·7·8 의 모든 호출부에서 같다.
`StudyList(recruiting, items)`·`StudyDetail`·`WeekEntry`·`RosterEntry` 의 필드 이름이
Task 1 의 계약 스키마와 일대일로 맞는다. `recruitmentOpen()`/`setRecruitmentOpen()` 이
Task 4 에서 정의되고 Task 5 에서만 쓰인다.

**알려진 미확정 하나** — Task 8 Step 1 의 일괄 편집 요청 본문 키(`updates`/`id`/
`version`/`fields`)와 실패 응답 모양은 실제 DTO 를 읽고 맞추라고 그 Step 안에 적어
두었다. 이 계획은 일괄 편집의 모양을 바꾸지 않으므로 테스트를 현행에 맞추면 된다.
