# 스터디 ② 운영과 출석 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 스터디가 굴러가는 동안 일어나는 일 — 신청을 받고, 출석을 찍고, 커리큘럼을 조정하고, 그것을 보는 '내 스터디' 화면 — 을 전부 만든다.

**Architecture:** 출석은 `study_attendance` 행의 **존재**로 표현하고 `weekId` 에 맨다. 스터디장의 편집 권한은 `StudyWeek.takenAt + 24h` 라는 창 하나로 출석 저장과 주차 삭제를 함께 가르며, 그 판정은 `AttendanceWindow` 한 곳에만 있다. 화면은 `relation`(LEADER/MEMBER/APPLIED/REJECTED)을 서버가 붙여 내려보내고, '내 스터디'는 그 값 하나로 카드와 모달을 가른다.

**Tech Stack:** Spring Boot 3 · JPA(`ddl-auto: update`) · PostgreSQL 16 · JUnit 5 + RestAssured + Testcontainers · React 19 + react-query + Vite

**Spec:** `docs/superpowers/specs/2026-09-15-study-operations-design.md`

## Global Constraints

- **브랜치 이름은 두 레포 모두 `feat/study-operations`.** 계약 CI 가 브랜치 이름으로 짝을 찾는다.
- **계약 PR 이 먼저다.** `home-jaram-fe` 의 `docs/api/openapi.yaml` 이 두 레포의 단일 계약이고 BE 는 symlink 로 읽는다. `OpenApiValidationFilter` 가 **선언되지 않은 응답 필드를 거부**하므로, Task 1 이 머지되기 전에는 BE 의 계약 테스트가 빨갛다.
- **① 이 먼저 머지되어야 한다.** ② 의 코드는 ① 의 `StudyAccess`·`StudyWeek`·`StudyStatus` 위에 선다. 순서는 ①계약 → ①BE → ②계약 → ②BE.
- **새 `Permission` 을 만들지 않는다.** 게이트는 언제나 `@studyAccess.<소유자조건>(...) or hasAuthority('<기존 권한>')` 모양이다.
- **이행 SQL 을 쓰지 않는다.** `study_attendance` 는 빈 테이블 신설이고 `study_week.taken_at` 은 nullable 이라 `ddl-auto: update` 가 둘 다 만든다. `docs/migrations/` 에 파일을 두지 않는다.
- **에러 코드는 문자열로 고정한다**: `ATTENDANCE_LOCKED`, `WEEK_NOT_LAST`, `WEEK_MIN`, `STUDY_FINISHED`, `NOT_REJECTED`. 전부 `409 CONFLICT` 다. 값 검증 실패는 `422` + `VALIDATION`.
- **출석 명단·신청 목록에 학번을 싣지 않는다.** 이름과 기수뿐이다.
- **`takenAt` 은 첫 저장에만 박힌다.** 두 번째 저장에 갱신하면 편집 창이 무한히 연장된다.

### 테스트 실행 (이 호스트)

호스트에 JDK 17 밖에 없고 Docker Engine 29 라 두 가지 우회가 필요하다. init 스크립트가 없으면 먼저 만든다:

```bash
mkdir -p "$CLAUDE_JOB_DIR/tmp"
cat > "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" <<'GRADLE'
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'api.version', '1.44'
    }
}
GRADLE
```

그리고 언제나 이렇게 돌린다:

```bash
export JAVA_HOME=/home/ksb/.local/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test --tests 'com.jaram.be.study.StudyAttendanceTest'
```

FE 는 `pnpm` 이 PATH 에 없다. `./node_modules/.bin/eslint .` 과 `./node_modules/.bin/tsc --noEmit` 를 직접 부른다.

---

## File Structure

### BE — 새로 만드는 파일

| 파일 | 책임 |
|---|---|
| `study/StudyAttendance.java` | 출석 한 행. `(weekId, memberId)` 유니크, `at` 타임스탬프 |
| `study/StudyAttendanceRepository.java` | 주차별·멤버별 조회와 주차 단위 삭제 |
| `study/AttendanceWindow.java` | **편집 창 판정 한 곳.** 출석 저장과 주차 삭제가 같이 쓴다 |
| `study/StudyAttendanceService.java` | 출석 읽기(board·me)와 쓰기 |
| `study/StudyAttendanceController.java` | `/api/studies/{id}/attendance*`, `/weeks/{n}/attendance` |
| `study/StudyWeekService.java` | 주차 추가·수정·삭제 |
| `study/StudyWeekController.java` | `/api/studies/{id}/weeks*` |
| `study/AttendanceState.java` | `PRESENT`/`ABSENT`/`NOT_TAKEN` |
| `study/dto/StudyApplicantEntry.java`, `dto/StudyApplicantList.java` | 스터디장용 신청 목록 (D26) |
| `study/dto/AttendanceWeek.java`, `dto/AttendanceMember.java`, `dto/AttendanceBoard.java` | 출석 격자 |
| `study/dto/MyAttendanceWeek.java`, `dto/MyAttendance.java` | 멤버 자신의 출석 |
| `study/dto/AttendanceUpdate.java` | `{ present: [...] }` |
| `study/dto/WeekUpsert.java` | `{ title, content }` — 주차 추가·수정 공용 |

### BE — 고치는 파일

| 파일 | 무엇을 |
|---|---|
| `study/StudyWeek.java` | `takenAt` 필드와 `markTaken()` 추가 |
| `study/StudyWeekRepository.java` | `findFirstByStudyIdOrderByWeekNoDesc`, `countByStudyId`, `findByStudyIdAndWeekNo` 추가 |
| `study/StudyAccess.java` | `isMember`, `isApplicant` 추가 |
| `study/StudyController.java` | `GET /{id}/applicants`, `DELETE /applicants/{id}` 추가. `my()` 는 그대로 |
| `study/dto/MyStudy.java` | `pendingApplicants` 필드 하나 추가 (D24) |
| `study/StudyService.java` | `myActivity` 에 대기 신청 수를 채운다. `applicantsOf(studyId)`, `deleteApplication` 추가 |
| `study/StudyApplicationRepository.java` | `findByStudyIdAndStatus`, `findByApplicantIdAndStatus` 추가 |

### BE — 지우는 파일

없다. 초안은 `MyActivity`·`MyApp`·`MyStudy` 를 폐기하려 했으나 철회했다 — 관계는 화면이 두 배열에서 읽고, 서버는 `pendingApplicants` 하나만 더한다 (D23·D24).

### FE — 새로 만드는 파일

| 파일 | 책임 |
|---|---|
| `features/study/views/MyStudyView.jsx` | 두 구역('내 스터디' / '지난 신청') 그리드와 정렬 |
| `features/study/views/MyStudyCard.jsx` | 관계 칩 · 상태 배지 · 관계별 한 줄 · 관계별 버튼 |
| `features/study/views/ManageStudyModal.jsx` | 스터디장 모달 A(신청 관리)·B(출석/정보) |
| `features/study/views/MyAttendanceModal.jsx` | 멤버 모달 C(읽기 전용) |

### FE — 고치는 파일

| 파일 | 무엇을 |
|---|---|
| `features/study/study.api.js` | 새 엔드포인트 함수 추가. `listMyActivity` 는 그대로 |
| `features/study/study.queries.js` | 새 쿼리 키와 훅, 무효화 대상 갱신 |
| `features/study/study.data.js` | `relationOf()`(두 배열 → 관계), `RELATION_CHIP`, `RELATION_ACTION`, `ATTENDANCE_LABEL`, `relationLine()` 추가 |
| `features/study/StudyPage.jsx` | 탭 `내 활동` → `내 스터디`, `MyActivityView` → `MyStudyView`, 모달 배선 |

### FE — 지우는 파일

`features/study/views/MyActivityView.jsx` — `MyStudyView` 가 대신한다. 이 컴포넌트가 계약에 없는 `item.message`/`tone`/`badge` 를 읽던 어긋남이 여기서 없어진다.

---

## Task 1: 계약 — openapi.yaml 을 먼저 머지한다

**레포가 다르다.** 이 과제만 `home-jaram-fe` 에서 한다.

**Files:**
- Modify: `/home/ksb/Dev/home-jaram/home-jaram-fe/docs/api/openapi.yaml`

**Interfaces:**
- Consumes: 없음 (첫 과제)
- Produces: 새 스키마 이름 `StudyApplicantList`, `StudyApplicantEntry`, `AttendanceBoard`, `AttendanceWeek`, `AttendanceMember`, `MyAttendance`, `MyAttendanceWeek`, `AttendanceState`, `AttendanceUpdate`, `WeekUpsert`. 그리고 기존 `MyStudy` 에 `pendingApplicants` 필드. 이후 모든 BE 과제가 이 이름으로 DTO 를 만든다.

**기존 스키마를 지우지 않는다.** `MyActivity`·`MyApp`·`MyStudy` 는 ① 이 굳힌 계약이고 그대로 둔다 (D24). 이 과제는 **더하기만** 한다.

- [ ] **Step 1: 브랜치를 판다**

`home-jaram-fe` 에서 `develop` 을 최신으로 당긴 뒤 `feat/study-operations` 브랜치를 만든다. 브랜치 이름은 BE 와 같아야 한다 — 계약 CI 가 이름으로 짝을 찾는다.

- [ ] **Step 2: `MyStudy` 스키마를 찾는다**

```bash
grep -n '    MyApp:\|    MyStudy:\|    MyActivity:' docs/api/openapi.yaml
```

Expected: 세 이름이 각각 한 번씩 나온다. 지우지 않는다 — `MyStudy` 에 필드를 더할 자리를 찾는 것이다.

- [ ] **Step 3: `MyStudy` 에 `pendingApplicants` 를 더한다**

`MyStudy` 의 `properties:` 아래에 넣는다. `required` 는 건드리지 않는다 — nullable 이다.

```yaml
        pendingApplicants:
          type: integer
          nullable: true
          description: 상태가 RECRUITING 일 때 대기 중인 신청 수. 그 외에는 null
```

`/api/studies/my` 의 응답 `$ref` 는 `MyActivity` 그대로 둔다. `summary` 만 `내 활동` → `내 스터디` 로 바꾼다 — 탭 이름이 바뀌었다(D22).

- [ ] **Step 4: 새 경로 7개를 `paths:` 에 더한다**

```yaml
  /api/studies/{id}/applicants:
    get:
      tags: [study]
      summary: 그 스터디의 신청 목록 (스터디장 또는 임원)
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/StudyApplicantList' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /api/studies/{id}/attendance:
    get:
      tags: [study]
      summary: 출석 격자 (스터디장 또는 임원)
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/AttendanceBoard' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /api/studies/{id}/attendance/me:
    get:
      tags: [study]
      summary: 내 주차별 출석 (참여 멤버)
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/MyAttendance' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /api/studies/{id}/weeks:
    post:
      tags: [study]
      summary: 커리큘럼 주차를 맨 뒤에 더한다
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/WeekUpsert' }
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: { $ref: '#/components/schemas/StudyWeek' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }
        '422': { $ref: '#/components/responses/UnprocessableEntity' }

  /api/studies/{id}/weeks/{weekNo}:
    put:
      tags: [study]
      summary: 주차의 제목·내용을 고친다 (언제나 가능)
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
        - { name: weekNo, in: path, required: true, schema: { type: integer } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/WeekUpsert' }
      responses:
        '204': { description: No Content }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }
        '422': { $ref: '#/components/responses/UnprocessableEntity' }
    delete:
      tags: [study]
      summary: 맨 뒤 주차를 지운다 (출석이 기록된 주차는 409)
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
        - { name: weekNo, in: path, required: true, schema: { type: integer } }
      responses:
        '204': { description: No Content }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }

  /api/studies/{id}/weeks/{weekNo}/attendance:
    put:
      tags: [study]
      summary: 그 주차의 출석을 통째로 바꾼다
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
        - { name: weekNo, in: path, required: true, schema: { type: integer } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/AttendanceUpdate' }
      responses:
        '204': { description: No Content }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }
        '422': { $ref: '#/components/responses/UnprocessableEntity' }

  /api/studies/applicants/{id}:
    delete:
      tags: [study]
      summary: 반려된 내 신청을 지운다 (지우면 재신청할 수 있다)
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
      responses:
        '204': { description: No Content }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }
```

`/api/studies/applicants/{id}` 는 새 최상위 키다 — 기존 파일에는 `/api/studies/applicants/{id}/approve` 와 `/reject` 만 있다. `components/responses` 에 `Forbidden`·`NotFound`·`Conflict`·`UnprocessableEntity` 가 실제로 있는지 먼저 확인한다:

```bash
sed -n '/^  responses:/,/^  schemas:/p' docs/api/openapi.yaml | grep -n '^    [A-Z]'
```

없는 이름이 있으면 그 응답만 인라인으로 쓴다 — 기존 `/api/studies/{id}/close-recruiting` 의 `'409'` 선언 모양을 그대로 베낀다.

- [ ] **Step 5: 새 스키마 10개를 `components/schemas` 에 더한다**

```yaml
    AttendanceState:
      type: string
      description: PRESENT=출석, ABSENT=결석, NOT_TAKEN=아직 기록하지 않은 주차
      enum: [PRESENT, ABSENT, NOT_TAKEN]

    StudyApplicantEntry:
      type: object
      required: [applicationId, name]
      properties:
        applicationId: { type: string }
        name:          { type: string }
        gen:           { type: integer, nullable: true }
        motive:
          type: string
          nullable: true
          description: approved 묶음에서는 언제나 null

    StudyApplicantList:
      type: object
      required: [pending, approved]
      properties:
        pending:  { type: array, items: { $ref: '#/components/schemas/StudyApplicantEntry' } }
        approved: { type: array, items: { $ref: '#/components/schemas/StudyApplicantEntry' } }

    AttendanceWeek:
      type: object
      required: [weekNo, title, editable]
      properties:
        weekNo:  { type: integer }
        title:   { type: string }
        takenAt: { type: string, format: date-time, nullable: true }
        editable:
          type: boolean
          description: 호출한 사람 기준. 임원은 언제나 true

    AttendanceMember:
      type: object
      required: [memberId, name, leader, present]
      properties:
        memberId: { type: string }
        name:     { type: string }
        gen:      { type: integer, nullable: true }
        leader:   { type: boolean }
        present:
          type: array
          items: { type: integer }
          description: 출석한 weekNo 들

    AttendanceBoard:
      type: object
      required: [weeks, members]
      properties:
        weeks:   { type: array, items: { $ref: '#/components/schemas/AttendanceWeek' } }
        members: { type: array, items: { $ref: '#/components/schemas/AttendanceMember' } }

    MyAttendanceWeek:
      type: object
      required: [weekNo, title, state]
      properties:
        weekNo: { type: integer }
        title:  { type: string }
        state:  { $ref: '#/components/schemas/AttendanceState' }

    MyAttendance:
      type: object
      required: [attended, taken, weeks]
      properties:
        attended: { type: integer, description: 출석한 횟수 }
        taken:
          type: integer
          description: 출석을 기록한 주차 수. 출석률의 분모다
        weeks: { type: array, items: { $ref: '#/components/schemas/MyAttendanceWeek' } }

    AttendanceUpdate:
      type: object
      required: [present]
      properties:
        present:
          type: array
          items: { type: string }
          description: 이 주차에 출석한 memberId 들. 빈 배열은 전원 결석이다.

    WeekUpsert:
      type: object
      required: [title]
      properties:
        title:   { type: string, minLength: 1 }
        content: { type: string, nullable: true }
```

- [ ] **Step 6: 파싱과 내용을 확인한다**

```bash
python3 - <<'PY'
import yaml
d = yaml.safe_load(open('docs/api/openapi.yaml'))
need = ['StudyApplicantList','StudyApplicantEntry',
        'AttendanceBoard','AttendanceWeek','AttendanceMember','MyAttendance','MyAttendanceWeek',
        'AttendanceState','AttendanceUpdate','WeekUpsert']
schemas = d['components']['schemas']
print('missing        :', [n for n in need if n not in schemas])
print('must-survive   :', [n for n in ('MyActivity','MyApp','MyStudy') if n not in schemas])
print('new field      :', 'pendingApplicants' in schemas['MyStudy']['properties'])
print('new paths      :', sorted(p for p in d['paths']
      if 'attendance' in p or p.endswith('/weeks') or '/weeks/{weekNo}' in p
      or p == '/api/studies/applicants/{id}' or p.endswith('/{id}/applicants')))
print('my response    :', d['paths']['/api/studies/my']['get']['responses']['200']
      ['content']['application/json']['schema'])
PY
```

Expected:
```
missing        : []
must-survive   : []
new field      : True
new paths      : ['/api/studies/{id}/applicants', '/api/studies/{id}/attendance', '/api/studies/{id}/attendance/me', '/api/studies/{id}/weeks', '/api/studies/{id}/weeks/{weekNo}', '/api/studies/{id}/weeks/{weekNo}/attendance', '/api/studies/applicants/{id}']
my response    : {'$ref': '#/components/schemas/MyActivity'}
```

- [ ] **Step 7: 린트가 그대로인지 본다**

```bash
./node_modules/.bin/eslint .
```

Expected: exit 0. YAML 만 고쳤으므로 달라질 것이 없다 — 달라지면 다른 것을 건드린 것이다.

- [ ] **Step 8: 커밋하고 PR 을 연다**

커밋 메시지 본문:

```
feat(contract): 스터디 운영과 출석 엔드포인트를 선언한다

출석·주차·신청 목록 엔드포인트 7개와 그 응답 스키마 10개를 더한다.
기존 스키마는 지우지 않는다. 내 스터디 화면이 쓰는 관계 네 값은 /my 가
이미 주는 두 배열에서 읽히고, 분야·일정·스터디장은 GET /api/studies 에
있다. 어디에도 없는 값은 대기 신청 수 하나뿐이라 MyStudy 에 그 필드만
더한다.

AttendanceWeek.editable 은 호출한 사람 기준이다. 화면이 takenAt 에 24h 를
더해 스스로 계산하게 두면 시계 차이로 화면과 서버가 다른 답을 낸다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

푸시한 뒤 `feat/study-operations → develop` PR 을 연다. PR 본문 끝에 다음 두 줄을 붙인다:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

**이 PR 이 머지되기 전에는 BE 의 계약 테스트가 빨갛다.** 그것이 정상이다.

---

## Task 2: 출석을 담을 자리 — 엔티티와 `takenAt`

여기부터 `home-jaram-be` 다. 작업 브랜치는 `feat/study-operations`.

**Files:**
- Create: `src/main/java/com/jaram/be/study/StudyAttendance.java`
- Create: `src/main/java/com/jaram/be/study/StudyAttendanceRepository.java`
- Modify: `src/main/java/com/jaram/be/study/StudyWeek.java`
- Modify: `src/main/java/com/jaram/be/study/StudyWeekRepository.java`
- Test: `src/test/java/com/jaram/be/study/StudyAttendanceRepositoryTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `StudyAttendance.create(String weekId, String memberId, Instant at) → StudyAttendance`, 게터 `getId()`·`getWeekId()`·`getMemberId()`·`getAt()`
  - `StudyAttendanceRepository`: `findByWeekId(String)`, `findByWeekIdIn(Collection<String>)`, `deleteByWeekId(String)`, `existsByWeekId(String)`
  - `StudyWeek.markTaken(Instant now)` — 첫 저장에만 박는다, `StudyWeek.getTakenAt() → Instant`
  - `StudyWeekRepository`: `findByStudyIdAndWeekNo(String, int)`, `findFirstByStudyIdOrderByWeekNoDesc(String)`, `countByStudyId(String)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyAttendanceRepositoryTest.java`:

```java
package com.jaram.be.study;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class StudyAttendanceRepositoryTest extends PostgresTest {

    @Autowired StudyAttendanceRepository attendance;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyRepository studies;

    private Study study;

    @BeforeEach void clean() {
        attendance.deleteAllInBatch();
        weeks.deleteAllInBatch();
        studies.deleteAllInBatch();
        study = studies.save(Study.create("알고리즘", List.of("PS"), 6,
                null, null, null, null, null, "leader-1"));
    }

    @Test
    void oneRowPerMemberPerWeek() {
        StudyWeek w = weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        attendance.save(StudyAttendance.create(w.getId(), "member-1", Instant.now()));

        assertThatThrownBy(() -> {
            attendance.save(StudyAttendance.create(w.getId(), "member-1", Instant.now()));
            attendance.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void readsByWeekAndClearsAWholeWeek() {
        StudyWeek w1 = weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        StudyWeek w2 = weeks.save(StudyWeek.create(study.getId(), 2, "그리디", null));
        attendance.save(StudyAttendance.create(w1.getId(), "m1", Instant.now()));
        attendance.save(StudyAttendance.create(w1.getId(), "m2", Instant.now()));
        attendance.save(StudyAttendance.create(w2.getId(), "m1", Instant.now()));

        assertThat(attendance.findByWeekId(w1.getId())).hasSize(2);
        assertThat(attendance.findByWeekIdIn(List.of(w1.getId(), w2.getId()))).hasSize(3);
        assertThat(attendance.existsByWeekId(w2.getId())).isTrue();

        attendance.deleteByWeekId(w1.getId());
        assertThat(attendance.findByWeekId(w1.getId())).isEmpty();
        assertThat(attendance.findByWeekId(w2.getId())).hasSize(1);
    }

    /** 첫 저장에만 박힌다. 갱신하면 편집 창이 무한히 연장된다. */
    @Test
    void takenAtIsStampedOnceAndNeverMoves() {
        StudyWeek w = weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        assertThat(w.getTakenAt()).isNull();

        Instant first = Instant.parse("2026-09-15T10:00:00Z");
        w.markTaken(first);
        w.markTaken(Instant.parse("2026-09-20T10:00:00Z"));
        weeks.save(w);

        assertThat(weeks.findById(w.getId()).orElseThrow().getTakenAt()).isEqualTo(first);
    }

    @Test
    void findsTheLastWeekAndCounts() {
        weeks.save(StudyWeek.create(study.getId(), 1, "1주", null));
        weeks.save(StudyWeek.create(study.getId(), 2, "2주", null));
        weeks.save(StudyWeek.create(study.getId(), 3, "3주", null));

        assertThat(weeks.findFirstByStudyIdOrderByWeekNoDesc(study.getId())
                .orElseThrow().getWeekNo()).isEqualTo(3);
        assertThat(weeks.countByStudyId(study.getId())).isEqualTo(3);
        assertThat(weeks.findByStudyIdAndWeekNo(study.getId(), 2)
                .orElseThrow().getTitle()).isEqualTo("2주");
        assertThat(weeks.findByStudyIdAndWeekNo(study.getId(), 9)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
export JAVA_HOME=/home/ksb/.local/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyAttendanceRepositoryTest'
```

Expected: 컴파일 실패 — `StudyAttendance`, `StudyAttendanceRepository`, `markTaken`, `getTakenAt`, `findFirstByStudyIdOrderByWeekNoDesc` 가 없다.

- [ ] **Step 3: `StudyAttendance` 를 만든다**

```java
package com.jaram.be.study;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 출석 한 행. 존재가 곧 출석이다 — seminar/Attendance 와 같은 모양이다.
 *
 * boolean 으로 저장하지 않는 이유: "아직 안 찍음"을 표현할 세 번째 값이 필요해지고,
 * 행이 없는 것과 false 인 것의 차이를 누구도 기억하지 못한다. 안 찍은 주차는
 * StudyWeek.takenAt 이 null 인 것으로 표현한다.
 *
 * weekNo 가 아니라 weekId 에 맨다. 번호는 화면이 보여주는 표시값이지 신원이 아니다.
 */
@Entity
@Table(name = "study_attendance",
        uniqueConstraints = @UniqueConstraint(columnNames = {"week_id", "member_id"}))
public class StudyAttendance {

    @Id
    private String id;

    @Column(name = "week_id")
    private String weekId;

    @Column(name = "member_id")
    private String memberId;

    private Instant at;

    protected StudyAttendance() { }

    public static StudyAttendance create(String weekId, String memberId, Instant at) {
        StudyAttendance a = new StudyAttendance();
        a.id = UUID.randomUUID().toString();
        a.weekId = weekId;
        a.memberId = memberId;
        a.at = at;
        return a;
    }

    public String getId() { return id; }
    public String getWeekId() { return weekId; }
    public String getMemberId() { return memberId; }
    public Instant getAt() { return at; }
}
```

- [ ] **Step 4: `StudyAttendanceRepository` 를 만든다**

```java
package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StudyAttendanceRepository extends JpaRepository<StudyAttendance, String> {
    List<StudyAttendance> findByWeekId(String weekId);
    List<StudyAttendance> findByWeekIdIn(Collection<String> weekIds);
    boolean existsByWeekId(String weekId);
    void deleteByWeekId(String weekId);
}
```

- [ ] **Step 5: `StudyWeek` 에 `takenAt` 을 더한다**

`private String content;` 선언 바로 아래에 넣는다:

```java
    /**
     * 이 주차의 출석을 **처음** 저장한 시각. null 이면 아직 한 번도 찍지 않았다.
     * 편집 창(+24h)의 기점이고, 출석률의 분모를 가르는 값이기도 하다.
     */
    @Column(name = "taken_at")
    private Instant takenAt;
```

`setContent` 아래에 넣는다:

```java
    /**
     * 첫 저장에만 박는다. 두 번째 저장에 갱신하면 편집 창이 저장할 때마다
     * 24시간씩 밀려 사실상 무한히 열린다.
     */
    public void markTaken(Instant now) {
        if (takenAt == null) takenAt = now;
    }

    public Instant getTakenAt() { return takenAt; }
```

`import java.time.Instant;` 를 더한다.

- [ ] **Step 6: `StudyWeekRepository` 에 조회 셋을 더한다**

```java
package com.jaram.be.study;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudyWeekRepository extends JpaRepository<StudyWeek, String> {
    List<StudyWeek> findByStudyIdOrderByWeekNoAsc(String studyId);
    Optional<StudyWeek> findByStudyIdAndWeekNo(String studyId, int weekNo);
    Optional<StudyWeek> findFirstByStudyIdOrderByWeekNoDesc(String studyId);
    long countByStudyId(String studyId);
}
```

- [ ] **Step 7: 통과를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyAttendanceRepositoryTest'
```

Expected: PASS (4개)

- [ ] **Step 8: 커밋한다**

`git add` 대상: 새 파일 둘, `StudyWeek.java`, `StudyWeekRepository.java`, 새 테스트.

```
feat(study): 출석을 담을 자리를 만든다

존재=출석이다. boolean 으로 저장하면 "아직 안 찍음"을 표현할 세 번째 값이
필요해지고, 행이 없는 것과 false 인 것의 차이를 누구도 기억하지 못한다.
안 찍은 주차는 StudyWeek.takenAt 이 null 인 것으로 표현한다.

행은 weekNo 가 아니라 weekId 에 맨다 — 번호는 화면이 보여주는 표시값이지
신원이 아니다. takenAt 은 첫 저장에만 박힌다. 갱신하면 편집 창이 저장할
때마다 밀려 사실상 무한히 열린다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

---

## Task 3: 편집 창 — 판정을 한 곳에 둔다

**Files:**
- Create: `src/main/java/com/jaram/be/study/AttendanceWindow.java`
- Test: `src/test/java/com/jaram/be/study/AttendanceWindowTest.java`

**Interfaces:**
- Consumes: `StudyWeek.getTakenAt()` (Task 2)
- Produces:
  - `AttendanceWindow.isOpen(StudyWeek week, boolean officer, Instant now) → boolean`
  - `AttendanceWindow.requireOpen(StudyWeek week, boolean officer)` — 닫혀 있으면 `ApiException(409, "ATTENDANCE_LOCKED", …)`
  - 상수 `AttendanceWindow.WINDOW = Duration.ofHours(24)`

출석 저장(Task 4)과 주차 삭제(Task 6)가 같은 규칙을 쓴다. 두 곳에 같은 조건문을 쓰면 한쪽만 고쳐지는 날이 온다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/study/AttendanceWindowTest.java`:

```java
package com.jaram.be.study;

import com.jaram.be.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 창 판정만 본다 — 스프링 컨텍스트가 필요 없다. */
class AttendanceWindowTest {

    private final AttendanceWindow window = new AttendanceWindow();

    private static final Instant TAKEN = Instant.parse("2026-09-15T10:00:00Z");

    private StudyWeek weekTakenAt(Instant at) {
        StudyWeek w = StudyWeek.create("study-1", 1, "완전탐색", null);
        if (at != null) w.markTaken(at);
        return w;
    }

    @Test
    void neverTakenIsAlwaysOpen() {
        assertThat(window.isOpen(weekTakenAt(null), false, TAKEN.plusSeconds(999_999))).isTrue();
    }

    @Test
    void openWithinTwentyFourHours() {
        StudyWeek w = weekTakenAt(TAKEN);
        assertThat(window.isOpen(w, false, TAKEN.plusSeconds(60))).isTrue();
        assertThat(window.isOpen(w, false, TAKEN.plusSeconds(24 * 3600 - 1))).isTrue();
    }

    @Test
    void closedAtExactlyTwentyFourHours() {
        StudyWeek w = weekTakenAt(TAKEN);
        assertThat(window.isOpen(w, false, TAKEN.plusSeconds(24 * 3600))).isFalse();
    }

    /** 임원은 창을 무시한다. 이의가 생겼을 때 고칠 손이 어딘가에는 있어야 한다. */
    @Test
    void officerIgnoresTheWindow() {
        StudyWeek w = weekTakenAt(TAKEN);
        assertThat(window.isOpen(w, true, TAKEN.plusSeconds(999_999))).isTrue();
    }

    /**
     * 403 이 아니라 409 다. 스터디장은 이 주차에 대한 권한을 갖고 있다 —
     * 시간이 지났을 뿐이다. 403 으로 내면 화면이 "권한이 없습니다"를 띄운다.
     */
    @Test
    void lockedRaisesConflictNotForbidden() {
        StudyWeek w = weekTakenAt(Instant.now().minusSeconds(48 * 3600));
        assertThatThrownBy(() -> window.requireOpen(w, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("출석 수정 기간")
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("ATTENDANCE_LOCKED");
                });
    }

    @Test
    void openWeekPassesRequireOpen() {
        window.requireOpen(weekTakenAt(null), false);   // 아무것도 던지지 않는다
    }
}
```

`ApiException` 의 게터 이름이 `getStatus()`/`getCode()` 가 아니면 실제 이름으로 맞춘다 — `cat src/main/java/com/jaram/be/common/ApiException.java` 로 먼저 본다.

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.AttendanceWindowTest'
```

Expected: 컴파일 실패 — `AttendanceWindow` 가 없다.

- [ ] **Step 3: `AttendanceWindow` 를 만든다**

```java
package com.jaram.be.study;

import com.jaram.be.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 편집 창 — 규칙 하나가 출석 저장과 주차 삭제를 다 덮는다.
 *
 * 스터디장은 아직 안 찍은 주차이거나 첫 저장으로부터 24시간 안일 때만 그 주차를
 * 건드린다. 두 곳에 같은 조건문을 쓰면 한쪽만 고쳐지는 날이 오므로 여기 한 곳에 둔다.
 *
 * 임원(STUDY_EDIT)은 창을 무시한다. 창 판정에 권한 조회를 넣지 않고 boolean 으로
 * 받는 이유는, 이 클래스가 SecurityContext 를 알면 단위 테스트가 보안 컨텍스트를
 * 세워야 하기 때문이다. 부르는 쪽(컨트롤러)이 CurrentMember.can(STUDY_EDIT) 를 넘긴다.
 */
@Component
public class AttendanceWindow {

    public static final Duration WINDOW = Duration.ofHours(24);

    public boolean isOpen(StudyWeek week, boolean officer, Instant now) {
        if (officer) return true;
        Instant taken = week.getTakenAt();
        if (taken == null) return true;
        return now.isBefore(taken.plus(WINDOW));
    }

    /**
     * 409 다. 403 이 아니다 — 스터디장은 이 주차에 대한 권한을 갖고 있고 시간이
     * 지났을 뿐이다. 403 으로 내면 화면이 "권한이 없습니다"를 띄우고, 그것은 틀린
     * 설명이라 사용자가 관리자에게 권한을 요청하게 만든다.
     */
    public void requireOpen(StudyWeek week, boolean officer) {
        if (isOpen(week, officer, Instant.now())) return;
        throw new ApiException(HttpStatus.CONFLICT, "ATTENDANCE_LOCKED",
                "출석 수정 기간이 지났습니다. 임원에게 요청하세요.");
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.AttendanceWindowTest'
```

Expected: PASS (6개)

- [ ] **Step 5: 커밋한다**

```
feat(study): 편집 창 판정을 한 곳에 둔다

출석 저장과 주차 삭제가 같은 규칙을 쓴다 — 첫 저장 +24h, 임원은 무시.
두 곳에 같은 조건문을 쓰면 한쪽만 고쳐지는 날이 온다.

403 이 아니라 409 다. 스터디장은 이 주차에 대한 권한을 갖고 있고 시간이
지났을 뿐이다. 403 으로 내면 화면이 "권한이 없습니다"를 띄우고, 그것은
틀린 설명이라 사용자가 관리자에게 권한을 요청하게 만든다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

---

## Task 4: 출석을 쓴다 — 주차 단위 전체 교체

**Files:**
- Create: `src/main/java/com/jaram/be/study/dto/AttendanceUpdate.java`
- Create: `src/main/java/com/jaram/be/study/StudyAttendanceService.java`
- Create: `src/main/java/com/jaram/be/study/StudyAttendanceController.java`
- Test: `src/test/java/com/jaram/be/study/StudyAttendanceTest.java`

**Interfaces:**
- Consumes: `AttendanceWindow.requireOpen(StudyWeek, boolean)` (Task 3), `StudyAttendanceRepository` · `StudyWeek.markTaken(Instant)` (Task 2), `StudyAccess.isLeader(String, Authentication)` (① 이 이미 만든 것)
- Produces:
  - `AttendanceUpdate(List<String> present)` — 계약 스키마 `AttendanceUpdate`
  - `StudyAttendanceService.save(String studyId, int weekNo, List<String> present, boolean officer)`
  - `StudyAttendanceService.memberIdsOf(Study study) → List<String>` — 출석 대상(승인 신청자 + 스터디장). Task 5 가 다시 쓴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyAttendanceTest.java`:

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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyAttendanceTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyAttendanceRepository attendance;
    @Autowired Actors actors;

    private Member leader, joined, outsider;
    private String leaderToken, outsiderToken;
    private Study study;
    private StudyWeek week1;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendance.deleteAll();
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        leader   = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr"));
        joined   = members.save(approved("참여", "2023000002", "joined@hanyang.ac.kr"));
        outsider = members.save(approved("남", "2023000003", "out@hanyang.ac.kr"));
        leaderToken   = actors.tokenFor(leader);
        outsiderToken = actors.tokenFor(outsider);

        study = ongoing(leader.getId());
        StudyApplication a = StudyApplication.create(study.getId(), joined.getId(), "하고 싶습니다");
        a.approve();
        applications.save(a);

        week1 = weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        weeks.save(StudyWeek.create(study.getId(), 2, "그리디", null));
    }

    private Member approved(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        return m;
    }

    private Study ongoing(String leaderId) {
        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leaderId);
        s.approve();
        s.closeRecruiting();
        return studies.save(s);
    }

    private int putAttendance(String token, int weekNo, List<String> present) {
        return given().header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(Map.of("present", present))
                .when().put("/api/studies/" + study.getId() + "/weeks/" + weekNo + "/attendance")
                .then().extract().statusCode();
    }

    @Test
    void leaderSavesAttendanceAndTakenAtIsStamped() {
        assertThat(putAttendance(leaderToken, 1, List.of(joined.getId()))).isEqualTo(204);

        assertThat(attendance.findByWeekId(week1.getId()))
                .extracting(StudyAttendance::getMemberId)
                .containsExactly(joined.getId());
        assertThat(weeks.findById(week1.getId()).orElseThrow().getTakenAt()).isNotNull();
    }

    /** 전체 교체다 — 두 번째 저장이 첫 번째를 덮는다. 체크 해제가 이렇게 표현된다. */
    @Test
    void savingAgainReplacesTheWholeWeek() {
        putAttendance(leaderToken, 1, List.of(joined.getId(), leader.getId()));
        putAttendance(leaderToken, 1, List.of(leader.getId()));

        assertThat(attendance.findByWeekId(week1.getId()))
                .extracting(StudyAttendance::getMemberId)
                .containsExactly(leader.getId());
    }

    /** takenAt 은 두 번째 저장에 갱신되지 않는다 — 갱신하면 창이 무한히 연장된다. */
    @Test
    void takenAtDoesNotMoveOnSecondSave() {
        putAttendance(leaderToken, 1, List.of(joined.getId()));
        Instant first = weeks.findById(week1.getId()).orElseThrow().getTakenAt();

        putAttendance(leaderToken, 1, List.of());
        assertThat(weeks.findById(week1.getId()).orElseThrow().getTakenAt()).isEqualTo(first);
    }

    /** 전원 결석과 "아직 안 찍음"은 다른 것이다. */
    @Test
    void emptyPresentIsEveryoneAbsentNotUntaken() {
        assertThat(putAttendance(leaderToken, 1, List.of())).isEqualTo(204);

        assertThat(attendance.findByWeekId(week1.getId())).isEmpty();
        assertThat(weeks.findById(week1.getId()).orElseThrow().getTakenAt()).isNotNull();
    }

    /** 조용히 무시하면 화면이 저장에 성공했다고 믿고 잘못된 명단을 계속 보여준다. */
    @Test
    void outsiderIdInPresentIsRejectedAndNothingIsSaved() {
        assertThat(putAttendance(leaderToken, 1, List.of(joined.getId(), outsider.getId())))
                .isEqualTo(422);

        assertThat(attendance.findByWeekId(week1.getId())).isEmpty();
        assertThat(weeks.findById(week1.getId()).orElseThrow().getTakenAt()).isNull();
    }

    @Test
    void windowClosesAfterTwentyFourHours() {
        putAttendance(leaderToken, 1, List.of(joined.getId()));

        StudyWeek w = weeks.findById(week1.getId()).orElseThrow();
        weeks.save(backdate(w, Instant.now().minusSeconds(25 * 3600)));

        assertThat(putAttendance(leaderToken, 1, List.of())).isEqualTo(409);
    }

    @Test
    void officerSavesEvenAfterTheWindowClosed() {
        Member officer = members.save(approved("임원", "2022000001", "officer@hanyang.ac.kr"));
        String officerToken = actors.tokenFor(officer, Role.ACADEMIC_LEAD);

        putAttendance(leaderToken, 1, List.of(joined.getId()));
        StudyWeek w = weeks.findById(week1.getId()).orElseThrow();
        weeks.save(backdate(w, Instant.now().minusSeconds(25 * 3600)));

        assertThat(putAttendance(officerToken, 1, List.of())).isEqualTo(204);
    }

    @Test
    void outsiderCannotSaveAttendance() {
        assertThat(putAttendance(outsiderToken, 1, List.of())).isEqualTo(403);
    }

    @Test
    void finishedStudyIsReadOnly() {
        study.finish();
        studies.save(study);

        assertThat(putAttendance(leaderToken, 1, List.of())).isEqualTo(409);
    }

    @Test
    void unknownWeekIsNotFound() {
        assertThat(putAttendance(leaderToken, 9, List.of())).isEqualTo(404);
    }

    /**
     * takenAt 은 markTaken 이 한 번만 박으므로 테스트에서 뒤로 당길 길이 없다.
     * 리플렉션으로 필드를 직접 쓴다 — 엔티티에 테스트 전용 setter 를 뚫는 것보다
     * 이쪽이 프로덕션 코드를 깨끗하게 둔다.
     */
    private StudyWeek backdate(StudyWeek w, Instant at) {
        try {
            var f = StudyWeek.class.getDeclaredField("takenAt");
            f.setAccessible(true);
            f.set(w, at);
            return w;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`Actors.tokenFor(Member, Role)` 의 시그니처를 먼저 확인한다: `cat src/test/java/com/jaram/be/support/Actors.java`. 역할을 주는 오버로드 이름이 다르면 그 이름으로 맞춘다 — `ACADEMIC_LEAD` 가 `STUDY_EDIT` 을 갖는다는 것은 ① 이 정해 둔 것이다.

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyAttendanceTest'
```

Expected: 컴파일 실패 — `AttendanceUpdate`·서비스·컨트롤러가 없다.

- [ ] **Step 3: `AttendanceUpdate` 를 만든다**

```java
package com.jaram.be.study.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 계약 AttendanceUpdate. 그 주차의 출석을 통째로 바꾼다.
 *
 * 개별 토글이 아니라 전체 교체인 이유: 체크 해제가 자연스럽게 표현되고, 10명 체크에
 * 요청이 하나라 중간에 끊겨 화면과 서버가 갈라지는 일이 없다.
 *
 * 빈 배열은 "전원 결석"이다. "아직 안 찍음"은 StudyWeek.takenAt 이 null 인 것이다.
 */
public record AttendanceUpdate(@NotNull List<String> present) { }
```

- [ ] **Step 4: `StudyAttendanceService` 의 쓰기 부분을 만든다**

```java
package com.jaram.be.study;

import com.jaram.be.common.ApiException;
import com.jaram.be.study.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 출석 읽기와 쓰기. 편집 창 판정은 AttendanceWindow 한 곳에만 있다.
 *
 * StudyService 에 넣지 않은 이유: 그 클래스는 이미 신청·개설·승인 흐름으로 360줄이고,
 * 출석은 그 흐름과 공유하는 상태가 study 조회뿐이다.
 */
@Service
public class StudyAttendanceService {

    private final StudyRepository studies;
    private final StudyWeekRepository weeks;
    private final StudyAttendanceRepository attendance;
    private final StudyApplicationRepository applications;
    private final AttendanceWindow window;

    public StudyAttendanceService(StudyRepository studies, StudyWeekRepository weeks,
                                  StudyAttendanceRepository attendance,
                                  StudyApplicationRepository applications,
                                  AttendanceWindow window) {
        this.studies = studies;
        this.weeks = weeks;
        this.attendance = attendance;
        this.applications = applications;
        this.window = window;
    }

    @Transactional
    public void save(String studyId, int weekNo, List<String> present, boolean officer) {
        Study study = loadStudy(studyId);
        requireNotFinished(study);
        StudyWeek week = loadWeek(studyId, weekNo);
        window.requireOpen(week, officer);

        List<String> eligible = memberIdsOf(study);
        List<String> unknown = present.stream().filter(id -> !eligible.contains(id)).toList();
        if (!unknown.isEmpty()) {
            // 조용히 무시하면 화면이 저장에 성공했다고 믿고 잘못된 명단을 계속 보여준다.
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION",
                    "이 스터디의 참여자가 아닌 사람이 있습니다.",
                    Map.of("present", "알 수 없는 멤버 %d명".formatted(unknown.size())));
        }

        attendance.deleteByWeekId(week.getId());
        Instant now = Instant.now();
        present.stream().distinct()
                .forEach(id -> attendance.save(StudyAttendance.create(week.getId(), id, now)));

        week.markTaken(now);
        weeks.save(week);
    }

    /** 출석 대상 — 승인된 신청자 + 스터디장. 스터디장을 빼면 "늘 출석"이라는 암묵 규칙이 생긴다. */
    List<String> memberIdsOf(Study study) {
        List<String> ids = new ArrayList<>();
        ids.add(study.getLeaderId());
        applications.findByStudyIdAndStatus(study.getId(), ApplicationStatus.APPROVED)
                .forEach(a -> ids.add(a.getApplicantId()));
        return ids;
    }

    Study loadStudy(String id) {
        return studies.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "스터디를 찾을 수 없습니다."));
    }

    private StudyWeek loadWeek(String studyId, int weekNo) {
        return weeks.findByStudyIdAndWeekNo(studyId, weekNo).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "그런 주차가 없습니다."));
    }

    /** 끝난 스터디의 기록이 나중에 바뀌면 그 기록을 근거로 한 것이 전부 흔들린다. */
    static void requireNotFinished(Study study) {
        if (study.getStatus() == StudyStatus.FINISHED) {
            throw new ApiException(HttpStatus.CONFLICT, "STUDY_FINISHED",
                    "종료된 스터디는 고칠 수 없습니다.");
        }
    }
}
```

- [ ] **Step 5: `StudyAttendanceController` 를 만든다**

```java
package com.jaram.be.study;

import com.jaram.be.security.CurrentMember;
import com.jaram.be.security.authz.Permission;
import com.jaram.be.study.dto.AttendanceUpdate;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/studies/{id}")
public class StudyAttendanceController {

    private final StudyAttendanceService service;

    public StudyAttendanceController(StudyAttendanceService service) { this.service = service; }

    /**
     * 그 주차의 출석을 통째로 바꾼다.
     *
     * 게이트는 "스터디장 또는 임원"만 가른다. 편집 창은 시간의 문제라 권한 표현식으로
     * 쓸 수 없으므로 서비스가 본다 — 임원인지를 여기서 읽어 넘긴다. 서비스가
     * SecurityContextHolder 를 직접 읽으면 서비스 테스트가 보안 컨텍스트를 세워야 한다.
     */
    @PutMapping("/weeks/{weekNo}/attendance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public void saveAttendance(@PathVariable String id,
                               @PathVariable int weekNo,
                               @Valid @RequestBody AttendanceUpdate req,
                               @AuthenticationPrincipal CurrentMember me) {
        service.save(id, weekNo, req.present(), me.can(Permission.STUDY_EDIT));
    }
}
```

`Permission.STUDY_EDIT` 의 정확한 이름은 `grep -n 'STUDY_' src/main/java/com/jaram/be/security/authz/Permission.java` 로 확인한다.

- [ ] **Step 6: 통과를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyAttendanceTest' \
  --tests 'com.jaram.be.security.AuthorizationCoverageTest'
```

Expected: 둘 다 PASS. 권한 그물이 같이 도는 이유는, 새 핸들러가 게이트 없이 들어오면 **그 테스트가 깨지는 것이 설계**이기 때문이다 — 지금 통과한다는 것이 게이트가 붙었다는 증거다.

- [ ] **Step 7: 커밋한다**

```
feat(study): 주차 출석을 통째로 바꾸는 손잡이를 만든다

개별 토글이 아니라 전체 교체다. 체크 해제가 자연스럽게 표현되고, 10명
체크에 요청이 하나라 중간에 끊겨 화면과 서버가 갈라지는 일이 없다.

present 에 이 스터디 사람이 아닌 id 가 섞이면 422 로 막고 아무것도 쓰지
않는다. 조용히 무시하면 화면이 저장에 성공했다고 믿고 잘못된 명단을 계속
보여준다.

편집 창은 시간의 문제라 권한 표현식으로 쓸 수 없다. 게이트는 "스터디장
또는 임원"만 가르고, 임원인지는 컨트롤러가 읽어 서비스에 넘긴다 —
서비스가 SecurityContextHolder 를 읽으면 서비스 테스트가 보안 컨텍스트를
세워야 한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

---

## Task 5: 출석을 읽는다 — 격자와 내 출석

**Files:**
- Create: `src/main/java/com/jaram/be/study/AttendanceState.java`
- Create: `src/main/java/com/jaram/be/study/dto/AttendanceWeek.java`
- Create: `src/main/java/com/jaram/be/study/dto/AttendanceMember.java`
- Create: `src/main/java/com/jaram/be/study/dto/AttendanceBoard.java`
- Create: `src/main/java/com/jaram/be/study/dto/MyAttendanceWeek.java`
- Create: `src/main/java/com/jaram/be/study/dto/MyAttendance.java`
- Modify: `src/main/java/com/jaram/be/study/StudyAccess.java`
- Modify: `src/main/java/com/jaram/be/study/StudyAttendanceService.java`
- Modify: `src/main/java/com/jaram/be/study/StudyAttendanceController.java`
- Test: `src/test/java/com/jaram/be/study/StudyAttendanceReadTest.java`

**Interfaces:**
- Consumes: Task 4 의 `StudyAttendanceService.memberIdsOf`·`loadStudy`, `AttendanceWindow.isOpen`
- Produces:
  - `AttendanceState` = `PRESENT` / `ABSENT` / `NOT_TAKEN`
  - `AttendanceBoard(List<AttendanceWeek> weeks, List<AttendanceMember> members)`
  - `AttendanceWeek(int weekNo, String title, Instant takenAt, boolean editable)`
  - `AttendanceMember(String memberId, String name, Integer gen, boolean leader, List<Integer> present)`
  - `MyAttendance(int attended, int taken, List<MyAttendanceWeek> weeks)`
  - `MyAttendanceWeek(int weekNo, String title, AttendanceState state)`
  - `StudyAccess.isMember(String studyId, Authentication auth) → boolean`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyAttendanceReadTest.java`:

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
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyAttendanceReadTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyAttendanceRepository attendance;
    @Autowired Actors actors;

    private Member leader, joined, outsider;
    private String leaderToken, joinedToken, outsiderToken;
    private Study study;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendance.deleteAll();
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        leader   = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr", 40));
        joined   = members.save(approved("참여", "2023000002", "joined@hanyang.ac.kr", 41));
        outsider = members.save(approved("남",  "2023000003", "out@hanyang.ac.kr", 41));
        leaderToken   = actors.tokenFor(leader);
        joinedToken   = actors.tokenFor(joined);
        outsiderToken = actors.tokenFor(outsider);

        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leader.getId());
        s.approve();
        s.closeRecruiting();
        study = studies.save(s);

        StudyApplication a = StudyApplication.create(study.getId(), joined.getId(), "하고 싶습니다");
        a.approve();
        applications.save(a);

        weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        weeks.save(StudyWeek.create(study.getId(), 2, "그리디", null));
        weeks.save(StudyWeek.create(study.getId(), 3, "DP", null));

        // 1주차만 찍는다: 리더 출석, 참여자 결석
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json")
                .body(Map.of("present", List.of(leader.getId())))
                .when().put("/api/studies/" + study.getId() + "/weeks/1/attendance")
                .then().statusCode(204);
    }

    private Member approved(String name, String sid, String email, int gen) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGen(gen);
        return m;
    }

    @Test
    void leaderSeesTheGridWithEditableFlags() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/attendance")
                .then().statusCode(200)
                .body("weeks", hasSize(3))
                .body("weeks[0].weekNo", equalTo(1))
                .body("weeks[0].takenAt", notNullValue())
                .body("weeks[0].editable", equalTo(true))     // 방금 찍었으니 창 안이다
                .body("weeks[1].takenAt", nullValue())
                .body("weeks[1].editable", equalTo(true))     // 안 찍은 주차는 언제나 열려 있다
                .body("members", hasSize(2))
                .body("members[0].leader", equalTo(true))     // 스터디장이 맨 앞
                .body("members[0].present", contains(1))
                .body("members[1].present", hasSize(0));
    }

    /** 명단에 학번이 없다. 필요 없는 값을 실어 보내면 언젠가 샌다. */
    @Test
    void theGridCarriesNoStudentId() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/attendance")
                .then().statusCode(200)
                .body("members.findAll { it.containsKey('studentId') }", hasSize(0));
    }

    @Test
    void outsiderCannotSeeTheGrid() {
        given().header("Authorization", "Bearer " + outsiderToken)
                .when().get("/api/studies/" + study.getId() + "/attendance")
                .then().statusCode(403);
    }

    /** 분모는 기록된 주차 수다. 전체 주차로 나누면 1주차를 마친 모두가 33%로 보인다. */
    @Test
    void memberSeesOwnAttendanceWithTakenAsDenominator() {
        given().header("Authorization", "Bearer " + joinedToken)
                .when().get("/api/studies/" + study.getId() + "/attendance/me")
                .then().statusCode(200)
                .body("attended", equalTo(0))
                .body("taken", equalTo(1))
                .body("weeks", hasSize(3))
                .body("weeks[0].state", equalTo("ABSENT"))
                .body("weeks[1].state", equalTo("NOT_TAKEN"))
                .body("weeks[2].state", equalTo("NOT_TAKEN"));
    }

    @Test
    void leaderIsAlsoAMemberForOwnAttendance() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/attendance/me")
                .then().statusCode(200)
                .body("attended", equalTo(1))
                .body("taken", equalTo(1))
                .body("weeks[0].state", equalTo("PRESENT"));
    }

    @Test
    void outsiderCannotSeeMyAttendance() {
        given().header("Authorization", "Bearer " + outsiderToken)
                .when().get("/api/studies/" + study.getId() + "/attendance/me")
                .then().statusCode(403);
    }
}
```

`Member.setGen(int)` 이 없으면 `grep -n 'gen' src/main/java/com/jaram/be/member/Member.java` 로 실제 설정 방법을 확인해 맞춘다.

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyAttendanceReadTest'
```

Expected: 컴파일 실패 — 응답 DTO 와 `isMember` 가 없다.

- [ ] **Step 3: 열거형과 DTO 를 만든다**

`AttendanceState.java`:

```java
package com.jaram.be.study;

/**
 * 계약 AttendanceState.
 *
 * NOT_TAKEN 을 따로 두는 이유: 화면이 takenAt 의 유무로 세 번째 상태를 유추하게 두면
 * 매번 같은 실수를 부른다 — 아직 안 찍은 주차가 결석으로 보인다.
 */
public enum AttendanceState { PRESENT, ABSENT, NOT_TAKEN }
```

`dto/AttendanceWeek.java`:

```java
package com.jaram.be.study.dto;

import java.time.Instant;

/**
 * 계약 AttendanceWeek. editable 은 **호출한 사람 기준**이다 — 임원이면 언제나 true.
 * 화면이 takenAt 에 24h 를 더해 스스로 계산하게 두면 시계 차이로 화면과 서버가
 * 다른 답을 낸다.
 */
public record AttendanceWeek(int weekNo, String title, Instant takenAt, boolean editable) { }
```

`dto/AttendanceMember.java`:

```java
package com.jaram.be.study.dto;

import java.util.List;

/** 계약 AttendanceMember. 학번을 싣지 않는다 — 이름과 기수면 격자가 성립한다. */
public record AttendanceMember(String memberId, String name, Integer gen,
                               boolean leader, List<Integer> present) { }
```

`dto/AttendanceBoard.java`:

```java
package com.jaram.be.study.dto;

import java.util.List;

/** 계약 AttendanceBoard. 주차 x 멤버 격자를 한 번에 내려보낸다. */
public record AttendanceBoard(List<AttendanceWeek> weeks, List<AttendanceMember> members) { }
```

`dto/MyAttendanceWeek.java`:

```java
package com.jaram.be.study.dto;

import com.jaram.be.study.AttendanceState;

/** 계약 MyAttendanceWeek. */
public record MyAttendanceWeek(int weekNo, String title, AttendanceState state) { }
```

`dto/MyAttendance.java`:

```java
package com.jaram.be.study.dto;

import java.util.List;

/**
 * 계약 MyAttendance. taken 이 출석률의 분모다 — 아직 안 찍은 주차는 결석이 아니라
 * 일어나지 않은 일이다.
 */
public record MyAttendance(int attended, int taken, List<MyAttendanceWeek> weeks) { }
```

- [ ] **Step 4: `StudyAccess.isMember` 를 더한다**

`isLeaderOfApplication` 아래에 넣는다:

```java
    /**
     * 승인된 신청자이거나 스터디장. 출석 조회의 소유자 조건이다.
     *
     * 스터디장을 포함하는 이유는 출석 대상에 포함하는 이유와 같다 — 스터디장도
     * 자기 스터디에 나오고, 자기 출석을 본다.
     */
    public boolean isMember(String studyId, Authentication auth) {
        String me = idOf(auth);
        if (me == null) return false;
        if (studies.findById(studyId).map(s -> me.equals(s.getLeaderId())).orElse(false)) return true;
        return applications.findByStudyIdAndApplicantId(studyId, me)
                .map(a -> a.getStatus() == ApplicationStatus.APPROVED)
                .orElse(false);
    }
```

- [ ] **Step 5: 서비스에 읽기 둘을 더한다**

`StudyAttendanceService` 에 `MemberRepository members` 를 생성자 주입으로 더하고(기존 필드 아래, 생성자 인자 끝에), 다음 두 메서드를 넣는다:

```java
    @Transactional(readOnly = true)
    public AttendanceBoard board(String studyId, boolean officer) {
        Study study = loadStudy(studyId);
        List<StudyWeek> all = weeks.findByStudyIdOrderByWeekNoAsc(studyId);
        Instant now = Instant.now();

        List<AttendanceWeek> weekRows = all.stream()
                .map(w -> new AttendanceWeek(w.getWeekNo(), w.getTitle(), w.getTakenAt(),
                        window.isOpen(w, officer, now)))
                .toList();

        Map<String, Integer> weekNoById = all.stream()
                .collect(Collectors.toMap(StudyWeek::getId, StudyWeek::getWeekNo));
        Map<String, List<Integer>> presentByMember = attendance
                .findByWeekIdIn(weekNoById.keySet()).stream()
                .collect(Collectors.groupingBy(StudyAttendance::getMemberId,
                        Collectors.mapping(a -> weekNoById.get(a.getWeekId()),
                                Collectors.toList())));

        List<AttendanceMember> memberRows = members.findAllById(memberIdsOf(study)).stream()
                .sorted(memberOrder(study.getLeaderId()))
                .map(m -> new AttendanceMember(m.getId(), m.getName(), m.getGen(),
                        m.getId().equals(study.getLeaderId()),
                        presentByMember.getOrDefault(m.getId(), List.of()).stream().sorted().toList()))
                .toList();

        return new AttendanceBoard(weekRows, memberRows);
    }

    @Transactional(readOnly = true)
    public MyAttendance mine(String studyId, String userId) {
        loadStudy(studyId);
        List<StudyWeek> all = weeks.findByStudyIdOrderByWeekNoAsc(studyId);
        Set<String> myWeekIds = attendance
                .findByWeekIdIn(all.stream().map(StudyWeek::getId).toList()).stream()
                .filter(a -> a.getMemberId().equals(userId))
                .map(StudyAttendance::getWeekId)
                .collect(Collectors.toSet());

        List<MyAttendanceWeek> rows = all.stream().map(w -> new MyAttendanceWeek(
                w.getWeekNo(), w.getTitle(),
                w.getTakenAt() == null ? AttendanceState.NOT_TAKEN
                        : myWeekIds.contains(w.getId()) ? AttendanceState.PRESENT
                        : AttendanceState.ABSENT)).toList();

        int taken = (int) all.stream().filter(w -> w.getTakenAt() != null).count();
        int attended = (int) rows.stream().filter(r -> r.state() == AttendanceState.PRESENT).count();
        return new MyAttendance(attended, taken, rows);
    }

    /** 스터디장이 맨 앞, 나머지는 기수 → 이름. StudyService.roster 와 같은 규칙이다. */
    private static Comparator<Member> memberOrder(String leaderId) {
        return Comparator
                .comparing((Member m) -> m.getId().equals(leaderId) ? 0 : 1)
                .thenComparing(Member::getGen, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Member::getName, Comparator.nullsLast(Comparator.naturalOrder()));
    }
```

import 를 더한다: `com.jaram.be.member.Member`, `com.jaram.be.member.MemberRepository`,
`java.util.Comparator`, `java.util.Set`, `java.util.stream.Collectors`.

- [ ] **Step 6: 컨트롤러에 읽기 둘을 더한다**

`StudyAttendanceController` 에 넣는다:

```java
    @GetMapping("/attendance")
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public AttendanceBoard board(@PathVariable String id,
                                 @AuthenticationPrincipal CurrentMember me) {
        return service.board(id, me.can(Permission.STUDY_EDIT));
    }

    /**
     * 자기 출석. 게이트가 isMember 다 — 같은 스터디 사람만 본다.
     *
     * /attendance/me 가 /attendance 보다 먼저 선언될 필요는 없다. Spring 은 리터럴
     * 세그먼트를 경로 변수보다 먼저 맞추고, 여기에는 겹치는 경로 변수가 없다.
     */
    @GetMapping("/attendance/me")
    @PreAuthorize("@studyAccess.isMember(#id, authentication)")
    public MyAttendance mine(@PathVariable String id,
                             @AuthenticationPrincipal CurrentMember me) {
        return service.mine(id, me.id());
    }
```

import 에 `com.jaram.be.study.dto.AttendanceBoard`, `com.jaram.be.study.dto.MyAttendance` 를 더한다.

- [ ] **Step 7: 통과를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyAttendanceReadTest' \
  --tests 'com.jaram.be.study.StudyAttendanceTest' \
  --tests 'com.jaram.be.security.AuthorizationCoverageTest'
```

Expected: 셋 다 PASS

- [ ] **Step 8: 커밋한다**

```
feat(study): 출석 격자와 자기 출석을 읽는 길을 낸다

editable 을 서버가 계산해 내려보낸다. 화면이 takenAt 에 24h 를 더해 스스로
계산하게 두면 시계 차이로 화면과 서버가 다른 답을 낸다.

출석률의 분모는 기록된 주차 수다. 전체 주차로 나누면 8주 스터디의 1주차를
마친 모두가 12%로 보인다. 아직 안 찍은 주차는 결석이 아니라 일어나지 않은
일이라, 그것을 NOT_TAKEN 이라는 값으로 화면에 그대로 준다.

격자에 학번을 싣지 않는다. 이름과 기수면 격자가 성립하고, 필요 없는 값을
실어 보내면 언젠가 샌다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

---

## Task 6: 주차 편집 — D14 를 집행한다

**Files:**
- Create: `src/main/java/com/jaram/be/study/dto/WeekUpsert.java`
- Create: `src/main/java/com/jaram/be/study/StudyWeekService.java`
- Create: `src/main/java/com/jaram/be/study/StudyWeekController.java`
- Test: `src/test/java/com/jaram/be/study/StudyWeekEditTest.java`

**Interfaces:**
- Consumes: `AttendanceWindow.requireOpen` (Task 3), `StudyAttendanceRepository.existsByWeekId`·`deleteByWeekId` (Task 2), `StudyWeekRepository.findByStudyIdAndWeekNo`·`findFirstByStudyIdOrderByWeekNoDesc`·`countByStudyId` (Task 2), `StudyAttendanceService.requireNotFinished(Study)` (Task 4)
- Produces:
  - `WeekUpsert(String title, String content)`
  - `StudyWeekService.add(String studyId, WeekUpsert req) → WeekEntry`
  - `StudyWeekService.edit(String studyId, int weekNo, WeekUpsert req)`
  - `StudyWeekService.remove(String studyId, int weekNo, boolean officer)`

`WeekEntry` 는 ① 이 이미 만든 `study/dto/WeekEntry.java`(`weekNo`, `title`, `content`) 를 그대로 쓴다 — 계약의 `StudyWeek` 스키마가 그것이다.

**규칙은 D14 가 이미 정했다. 다시 열지 않는다.**

| 동작 | 규칙 | 어기면 |
|---|---|---|
| 제목·내용 수정 | 언제나 가능. 출석이 기록된 주차도 마찬가지 | — |
| 추가 | 맨 뒤에만. `weekNo = max + 1` | — |
| 삭제 | 맨 뒤에서만 | `409 WEEK_NOT_LAST` |
| 삭제 | 출석이 기록된 주차는 못 자른다 | `409 ATTENDANCE_LOCKED` (창이 닫혔을 때) |
| 삭제 | 마지막 한 주차는 못 자른다 (D5) | `409 WEEK_MIN` |
| 전부 | `FINISHED` 는 읽기 전용 | `409 STUDY_FINISHED` |

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyWeekEditTest.java`:

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
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyWeekEditTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired StudyWeekRepository weeks;
    @Autowired StudyAttendanceRepository attendance;
    @Autowired Actors actors;

    private Member leader;
    private String leaderToken;
    private Study study;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendance.deleteAll();
        weeks.deleteAll();
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        leader = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr"));
        leaderToken = actors.tokenFor(leader);

        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leader.getId());
        s.approve();
        s.closeRecruiting();
        study = studies.save(s);

        weeks.save(StudyWeek.create(study.getId(), 1, "완전탐색", null));
        weeks.save(StudyWeek.create(study.getId(), 2, "그리디", null));
    }

    private Member approved(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        return m;
    }

    private String base() { return "/api/studies/" + study.getId() + "/weeks"; }

    private int addWeek(String title) {
        return given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json")
                .body(Map.of("title", title))
                .when().post(base())
                .then().extract().statusCode();
    }

    private int deleteWeek(int weekNo) {
        return given().header("Authorization", "Bearer " + leaderToken)
                .when().delete(base() + "/" + weekNo)
                .then().extract().statusCode();
    }

    @Test
    void addsAtTheTailOnly() {
        assertThat(addWeek("DP")).isEqualTo(201);

        assertThat(weeks.findByStudyIdOrderByWeekNoAsc(study.getId()))
                .extracting(StudyWeek::getWeekNo)
                .containsExactly(1, 2, 3);
        assertThat(weeks.findByStudyIdAndWeekNo(study.getId(), 3).orElseThrow().getTitle())
                .isEqualTo("DP");
    }

    /** 제목·내용 수정은 언제나 된다 — 출석이 기록된 주차도 마찬가지다(D14). */
    @Test
    void editsTitleEvenAfterAttendanceWasTaken() {
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("present", List.of(leader.getId())))
                .when().put(base() + "/1/attendance").then().statusCode(204);

        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("title", "완전탐색(개정)"))
                .when().put(base() + "/1").then().statusCode(204);

        assertThat(weeks.findByStudyIdAndWeekNo(study.getId(), 1).orElseThrow().getTitle())
                .isEqualTo("완전탐색(개정)");
    }

    @Test
    void deletesTheTailOnly() {
        assertThat(deleteWeek(2)).isEqualTo(204);
        assertThat(weeks.findByStudyIdOrderByWeekNoAsc(study.getId()))
                .extracting(StudyWeek::getWeekNo).containsExactly(1);
    }

    /** 번호를 재배열하지 않으므로 중간 삭제를 아예 막는다. */
    @Test
    void middleWeekCannotBeDeleted() {
        assertThat(deleteWeek(1)).isEqualTo(409);
        assertThat(weeks.countByStudyId(study.getId())).isEqualTo(2);
    }

    /** D5 가 최소 1주차를 요구한다. */
    @Test
    void theLastRemainingWeekCannotBeDeleted() {
        deleteWeek(2);
        assertThat(deleteWeek(1)).isEqualTo(409);
        assertThat(weeks.countByStudyId(study.getId())).isEqualTo(1);
    }

    /** 출석이 찍힌 주차를 지우는 것은 그 출석을 지우는 것이다 — 같은 창을 쓴다. */
    @Test
    void weekWithAttendanceCannotBeDeletedOnceTheWindowClosed() {
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("present", List.of(leader.getId())))
                .when().put(base() + "/2/attendance").then().statusCode(204);

        StudyWeek w = weeks.findByStudyIdAndWeekNo(study.getId(), 2).orElseThrow();
        weeks.save(backdate(w, java.time.Instant.now().minusSeconds(25 * 3600)));

        assertThat(deleteWeek(2)).isEqualTo(409);
        assertThat(weeks.countByStudyId(study.getId())).isEqualTo(2);
    }

    /** 창 안이면 출석까지 같이 지워진다. */
    @Test
    void deletingInsideTheWindowAlsoRemovesItsAttendance() {
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("present", List.of(leader.getId())))
                .when().put(base() + "/2/attendance").then().statusCode(204);

        String weekId = weeks.findByStudyIdAndWeekNo(study.getId(), 2).orElseThrow().getId();
        assertThat(deleteWeek(2)).isEqualTo(204);
        assertThat(attendance.findByWeekId(weekId)).isEmpty();
    }

    @Test
    void finishedStudyRejectsEveryWrite() {
        study.finish();
        studies.save(study);

        assertThat(addWeek("DP")).isEqualTo(409);
        assertThat(deleteWeek(2)).isEqualTo(409);
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("title", "x"))
                .when().put(base() + "/1").then().statusCode(409);
    }

    @Test
    void blankTitleIsRejected() {
        given().header("Authorization", "Bearer " + leaderToken)
                .contentType("application/json").body(Map.of("title", "  "))
                .when().post(base()).then().statusCode(422);
    }

    private StudyWeek backdate(StudyWeek w, java.time.Instant at) {
        try {
            var f = StudyWeek.class.getDeclaredField("takenAt");
            f.setAccessible(true);
            f.set(w, at);
            return w;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyWeekEditTest'
```

Expected: 컴파일 실패 — `WeekUpsert`·`StudyWeekService`·`StudyWeekController` 가 없다.

- [ ] **Step 3: `WeekUpsert` 를 만든다**

```java
package com.jaram.be.study.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 계약 WeekUpsert. 주차 추가와 수정이 같은 본문을 쓴다.
 *
 * weekNo 를 받지 않는다. 추가는 언제나 max + 1 이고 수정은 경로가 이미 말한다 —
 * 받으면 본문과 경로가 어긋났을 때 무엇을 믿을지 정해야 한다.
 */
public record WeekUpsert(@NotBlank String title, String content) { }
```

- [ ] **Step 4: `StudyWeekService` 를 만든다**

```java
package com.jaram.be.study;

import com.jaram.be.common.ApiException;
import com.jaram.be.study.dto.WeekEntry;
import com.jaram.be.study.dto.WeekUpsert;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 커리큘럼 주차 편집. 규칙은 설계 D14 가 정했고 여기서는 집행만 한다.
 *
 * 맨 뒤에서만 늘리고 줄인다. 중간 삽입·삭제를 허용하면 뒤 번호를 당길지 정해야 하는데,
 * 당기면 이미 출석이 기록된 "3주차"가 가리키던 모임이 슬그머니 바뀌고, 안 당기면
 * [1,2,4] 같은 구멍이 생겨 화면의 "가장 빠른 빈 주차"가 흔들린다.
 */
@Service
public class StudyWeekService {

    private final StudyRepository studies;
    private final StudyWeekRepository weeks;
    private final StudyAttendanceRepository attendance;
    private final AttendanceWindow window;

    public StudyWeekService(StudyRepository studies, StudyWeekRepository weeks,
                            StudyAttendanceRepository attendance, AttendanceWindow window) {
        this.studies = studies;
        this.weeks = weeks;
        this.attendance = attendance;
        this.window = window;
    }

    @Transactional
    public WeekEntry add(String studyId, WeekUpsert req) {
        Study study = loadStudy(studyId);
        StudyAttendanceService.requireNotFinished(study);

        int next = weeks.findFirstByStudyIdOrderByWeekNoDesc(studyId)
                .map(w -> w.getWeekNo() + 1)
                .orElse(1);
        StudyWeek saved = weeks.save(StudyWeek.create(studyId, next, req.title(), req.content()));
        return new WeekEntry(saved.getWeekNo(), saved.getTitle(), saved.getContent());
    }

    /** 제목·내용은 언제나 고칠 수 있다 — 출석이 기록된 주차도 마찬가지다(D14). */
    @Transactional
    public void edit(String studyId, int weekNo, WeekUpsert req) {
        Study study = loadStudy(studyId);
        StudyAttendanceService.requireNotFinished(study);

        StudyWeek week = loadWeek(studyId, weekNo);
        week.setTitle(req.title());
        week.setContent(req.content());
        weeks.save(week);
    }

    @Transactional
    public void remove(String studyId, int weekNo, boolean officer) {
        Study study = loadStudy(studyId);
        StudyAttendanceService.requireNotFinished(study);

        StudyWeek last = weeks.findFirstByStudyIdOrderByWeekNoDesc(studyId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "그런 주차가 없습니다."));
        if (last.getWeekNo() != weekNo) {
            throw new ApiException(HttpStatus.CONFLICT, "WEEK_NOT_LAST",
                    "맨 마지막 주차만 삭제할 수 있습니다.");
        }
        if (weeks.countByStudyId(studyId) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "WEEK_MIN",
                    "커리큘럼은 최소 1주차가 있어야 합니다.");
        }
        // 출석이 찍힌 주차를 지우는 것은 그 출석을 지우는 것이다 — 같은 창을 쓴다.
        window.requireOpen(last, officer);

        attendance.deleteByWeekId(last.getId());
        weeks.delete(last);
    }

    private Study loadStudy(String id) {
        return studies.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "스터디를 찾을 수 없습니다."));
    }

    private StudyWeek loadWeek(String studyId, int weekNo) {
        return weeks.findByStudyIdAndWeekNo(studyId, weekNo).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "그런 주차가 없습니다."));
    }
}
```

- [ ] **Step 5: `StudyWeekController` 를 만든다**

```java
package com.jaram.be.study;

import com.jaram.be.security.CurrentMember;
import com.jaram.be.security.authz.Permission;
import com.jaram.be.study.dto.WeekEntry;
import com.jaram.be.study.dto.WeekUpsert;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/studies/{id}/weeks")
public class StudyWeekController {

    private final StudyWeekService service;

    public StudyWeekController(StudyWeekService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public WeekEntry add(@PathVariable String id, @Valid @RequestBody WeekUpsert req) {
        return service.add(id, req);
    }

    @PutMapping("/{weekNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public void edit(@PathVariable String id, @PathVariable int weekNo,
                     @Valid @RequestBody WeekUpsert req) {
        service.edit(id, weekNo, req);
    }

    /** 삭제만 편집 창을 본다 — 출석이 딸려 사라지기 때문이다. */
    @DeleteMapping("/{weekNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public void remove(@PathVariable String id, @PathVariable int weekNo,
                       @AuthenticationPrincipal CurrentMember me) {
        service.remove(id, weekNo, me.can(Permission.STUDY_EDIT));
    }
}
```

**주의:** `StudyAttendanceController` 가 `@RequestMapping("/api/studies/{id}")` 에
`@PutMapping("/weeks/{weekNo}/attendance")` 를 갖고, 이 컨트롤러가
`@RequestMapping("/api/studies/{id}/weeks")` 에 `@PutMapping("/{weekNo}")` 를 갖는다.
두 패턴은 세그먼트 수가 달라 겹치지 않는다. Step 6 이 그것을 실제로 확인한다.

- [ ] **Step 6: 통과를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyWeekEditTest' \
  --tests 'com.jaram.be.study.StudyAttendanceTest' \
  --tests 'com.jaram.be.security.AuthorizationCoverageTest'
```

Expected: 셋 다 PASS. `StudyAttendanceTest` 를 같이 도는 이유는 경로 충돌이 있으면
기동 시점에 `IllegalStateException: Ambiguous mapping` 으로 터지기 때문이다.

- [ ] **Step 7: 커밋한다**

```
feat(study): 커리큘럼 주차를 도중에 늘리고 줄인다

맨 뒤에서만 늘리고 줄인다. 중간 삽입·삭제를 허용하면 뒤 번호를 당길지
정해야 하는데, 당기면 이미 출석이 기록된 "3주차"가 가리키던 모임이
슬그머니 바뀌고, 안 당기면 [1,2,4] 같은 구멍이 생겨 화면의 "가장 빠른
빈 주차"가 흔들린다. 한 주 쉼은 뒤에 한 주 더하기, 일찍 접음은 뒤에서
자르기로 실제 시나리오가 다 덮인다.

삭제만 편집 창을 본다 — 출석이 딸려 사라지기 때문이다. 제목과 내용은
언제나 고칠 수 있다. 중간 주에 무엇을 했는지가 바뀌는 것은 그것으로 끝난다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

---

## Task 7: 스터디장이 자기 신청 목록을 본다

① 이 남긴 구멍이다. 승인·반려 손잡이는 있는데 누구를 승인할지 볼 목록이 없다.

**Files:**
- Create: `src/main/java/com/jaram/be/study/dto/StudyApplicantEntry.java`
- Create: `src/main/java/com/jaram/be/study/dto/StudyApplicantList.java`
- Modify: `src/main/java/com/jaram/be/study/StudyService.java`
- Modify: `src/main/java/com/jaram/be/study/StudyController.java`
- Test: `src/test/java/com/jaram/be/study/StudyApplicantListTest.java`

**Interfaces:**
- Consumes: `StudyApplicationRepository.findByStudyIdAndStatus` (이미 있다), `StudyAccess.isLeader` (① )
- Produces:
  - `StudyApplicantEntry(String applicationId, String name, Integer gen, String motive)`
  - `StudyApplicantList(List<StudyApplicantEntry> pending, List<StudyApplicantEntry> approved)`
  - `StudyService.applicantsOf(String studyId) → StudyApplicantList`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyApplicantListTest.java`:

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
class StudyApplicantListTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired Actors actors;

    private String leaderToken, outsiderToken;
    private Study study;

    @BeforeEach void setup() {
        RestAssured.port = port;
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        Member leader   = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr", 40));
        Member waiting  = members.save(approved("대기", "2023000002", "w@hanyang.ac.kr", 41));
        Member accepted = members.save(approved("확정", "2023000003", "a@hanyang.ac.kr", 40));
        Member turned   = members.save(approved("반려", "2023000004", "r@hanyang.ac.kr", 41));
        Member outsider = members.save(approved("남",  "2023000005", "o@hanyang.ac.kr", 41));
        leaderToken   = actors.tokenFor(leader);
        outsiderToken = actors.tokenFor(outsider);

        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leader.getId());
        s.approve();
        study = studies.save(s);

        applications.save(StudyApplication.create(study.getId(), waiting.getId(), "하고 싶습니다"));

        StudyApplication ok = StudyApplication.create(study.getId(), accepted.getId(), "저도요");
        ok.approve();
        applications.save(ok);

        StudyApplication no = StudyApplication.create(study.getId(), turned.getId(), "저도요");
        no.reject("이번엔 어렵습니다");
        applications.save(no);
    }

    private Member approved(String name, String sid, String email, int gen) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGen(gen);
        return m;
    }

    @Test
    void leaderSeesPendingAndApprovedButNotRejected() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/applicants")
                .then().statusCode(200)
                .body("pending", hasSize(1))
                .body("pending[0].name", equalTo("대기"))
                .body("pending[0].motive", equalTo("하고 싶습니다"))
                .body("approved", hasSize(1))
                .body("approved[0].name", equalTo("확정"));
    }

    /** 승인이 끝난 사람의 지원동기를 계속 보여줄 이유가 없다. */
    @Test
    void approvedEntriesCarryNoMotive() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/applicants")
                .then().statusCode(200)
                .body("approved[0].motive", nullValue());
    }

    /** 학번은 애초에 싣지 않는다 — 가려서 싣는 것보다 짧다. */
    @Test
    void noStudentIdAnywhere() {
        given().header("Authorization", "Bearer " + leaderToken)
                .when().get("/api/studies/" + study.getId() + "/applicants")
                .then().statusCode(200)
                .body("pending.findAll { it.containsKey('studentId') }", hasSize(0))
                .body("approved.findAll { it.containsKey('studentId') }", hasSize(0));
    }

    @Test
    void anotherMemberCannotSeeTheList() {
        given().header("Authorization", "Bearer " + outsiderToken)
                .when().get("/api/studies/" + study.getId() + "/applicants")
                .then().statusCode(403);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyApplicantListTest'
```

Expected: 404 또는 컴파일 실패 — 엔드포인트가 없다.

- [ ] **Step 3: DTO 둘을 만든다**

`dto/StudyApplicantEntry.java`:

```java
package com.jaram.be.study.dto;

/**
 * 계약 StudyApplicantEntry. 스터디장이 신청을 가려내는 데 쓰는 것은 이름·기수·동기다.
 *
 * 학번을 싣지 않는다 — 상세 모달의 명단(① §7)은 지원자 전체를 보이므로 마스킹이
 * 필요했지만, 여기는 애초에 싣지 않는 편이 짧다.
 *
 * approved 묶음에서는 motive 가 null 이다. 승인이 끝난 사람의 지원동기를 계속
 * 보여줄 이유가 없다.
 */
public record StudyApplicantEntry(String applicationId, String name, Integer gen, String motive) { }
```

`dto/StudyApplicantList.java`:

```java
package com.jaram.be.study.dto;

import java.util.List;

/** 계약 StudyApplicantList. 모달이 두 묶음으로 그리므로 두 묶음으로 내려보낸다. */
public record StudyApplicantList(List<StudyApplicantEntry> pending,
                                 List<StudyApplicantEntry> approved) { }
```

- [ ] **Step 4: `StudyService.applicantsOf` 를 더한다**

`applicants()`(임원용 전체 목록) 바로 아래에 넣는다:

```java
    /**
     * 그 스터디의 신청 목록. ① 이 스터디장에게 승인·반려 손잡이는 주고 목록은 주지
     * 않아, 누구를 승인할지 모르는 채로 승인 버튼만 있었다.
     *
     * 반려는 싣지 않는다. 스터디장이 이미 내린 판단이고, 다시 보여 주면 그 목록이
     * 길어지기만 한다 — 신청자 본인은 자기 '내 스터디'에서 반려 사유를 본다.
     */
    @Transactional(readOnly = true)
    public StudyApplicantList applicantsOf(String studyId) {
        loadStudy(studyId);
        return new StudyApplicantList(
                entries(studyId, ApplicationStatus.PENDING, true),
                entries(studyId, ApplicationStatus.APPROVED, false));
    }

    private List<StudyApplicantEntry> entries(String studyId, ApplicationStatus status,
                                              boolean withMotive) {
        List<StudyApplication> rows = applications.findByStudyIdAndStatus(studyId, status);
        Map<String, Member> byId = members.findAllById(
                        rows.stream().map(StudyApplication::getApplicantId).toList()).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        return rows.stream()
                .filter(a -> byId.containsKey(a.getApplicantId()))
                .sorted(Comparator.comparing(StudyApplication::getCreatedAt))
                .map(a -> {
                    Member m = byId.get(a.getApplicantId());
                    return new StudyApplicantEntry(a.getId(), m.getName(), m.getGen(),
                            withMotive ? a.getMotive() : null);
                })
                .toList();
    }
```

`pending` 을 신청 순으로 세우는 것은 의도다 — ① §7 의 상세 명단이 기수 순인 것은
숨긴 승인 상태가 순서에서 새는 것을 막기 위해서였는데, 이 목록은 승인 상태가 이미
두 묶음으로 드러나 있으므로 그 이유가 없다. 먼저 신청한 사람을 먼저 보는 편이 낫다.

- [ ] **Step 5: `StudyController` 에 핸들러를 더한다**

`applicants()` 아래에 넣는다:

```java
    // 그 스터디의 신청 목록. 임원 전체 목록(/applicants)과 달리 스터디장이 자기 것만 본다.
    @GetMapping("/{id}/applicants")
    @PreAuthorize("@studyAccess.isLeader(#id, authentication)"
            + " or hasAuthority('STUDY_APPLICANT_MANAGE')")
    public StudyApplicantList applicantsOf(@PathVariable String id) {
        return service.applicantsOf(id);
    }
```

`/api/studies/applicants`(리터럴)와 `/api/studies/{id}/applicants`(세그먼트 3개)는
길이가 달라 겹치지 않는다.

- [ ] **Step 6: 통과를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyApplicantListTest' \
  --tests 'com.jaram.be.security.AuthorizationCoverageTest'
```

Expected: 둘 다 PASS

- [ ] **Step 7: 커밋한다**

```
feat(study): 스터디장에게 자기 스터디의 신청 목록을 준다

① 이 승인·반려 손잡이는 주고 목록은 주지 않았다. /api/studies/applicants
는 임원용 전체 목록이고 상세의 roster 는 승인 여부를 의도적으로 숨기므로,
스터디장은 누구를 승인할지 모르는 채로 승인 버튼만 갖고 있었다.

대기와 확정을 두 묶음으로 나눠 내려보낸다 — 모달이 그 모양으로 그린다.
반려는 싣지 않는다. 확정된 사람의 지원동기도 싣지 않는다. 학번은 애초에
넣지 않는다 — 가려서 싣는 것보다 짧다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

---

## Task 8: 반려된 신청을 지운다 — D11 을 집행한다

**Files:**
- Modify: `src/main/java/com/jaram/be/study/StudyAccess.java`
- Modify: `src/main/java/com/jaram/be/study/StudyService.java`
- Modify: `src/main/java/com/jaram/be/study/StudyController.java`
- Test: `src/test/java/com/jaram/be/study/StudyApplicationDeleteTest.java`

**Interfaces:**
- Consumes: `StudyApplicationRepository.findById`·`delete`
- Produces:
  - `StudyAccess.isApplicant(String applicationId, Authentication auth) → boolean`
  - `StudyService.deleteApplication(String applicationId)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyApplicationDeleteTest.java`:

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
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyApplicationDeleteTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;
    @Autowired StudyApplicationRepository applications;
    @Autowired Actors actors;

    private Member applicant, other;
    private String applicantToken, otherToken;
    private Study study;
    private StudyApplication rejected;

    @BeforeEach void setup() {
        RestAssured.port = port;
        applications.deleteAll();
        studies.deleteAll();
        members.deleteAll();

        Member leader = members.save(approved("리더", "2023000001", "leader@hanyang.ac.kr"));
        applicant     = members.save(approved("신청", "2023000002", "a@hanyang.ac.kr"));
        other         = members.save(approved("남",  "2023000003", "o@hanyang.ac.kr"));
        applicantToken = actors.tokenFor(applicant);
        otherToken     = actors.tokenFor(other);

        Study s = Study.create("알고리즘", List.of("PS"), 6,
                "화 19:00", "401호", "오프라인", "소개", "010-0000-0000", leader.getId());
        s.approve();
        study = studies.save(s);

        StudyApplication a = StudyApplication.create(study.getId(), applicant.getId(), "하고 싶습니다");
        a.reject("이번엔 어렵습니다");
        rejected = applications.save(a);
    }

    private Member approved(String name, String sid, String email) {
        Member m = Member.newPending(name, sid, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        return m;
    }

    private int delete(String token, String applicationId) {
        return given().header("Authorization", "Bearer " + token)
                .when().delete("/api/studies/applicants/" + applicationId)
                .then().extract().statusCode();
    }

    /** 삭제가 재신청을 연다 — deriveApply 에 새 분기가 생기지 않는다. */
    @Test
    void deletingARejectedApplicationReopensApplying() {
        assertThat(delete(applicantToken, rejected.getId())).isEqualTo(204);
        assertThat(applications.findById(rejected.getId())).isEmpty();

        given().header("Authorization", "Bearer " + applicantToken)
                .contentType("application/json").body(Map.of("motive", "다시 신청합니다"))
                .when().post("/api/studies/" + study.getId() + "/apply")
                .then().statusCode(201);
    }

    @Test
    void anotherMemberCannotDeleteMyApplication() {
        assertThat(delete(otherToken, rejected.getId())).isEqualTo(403);
        assertThat(applications.findById(rejected.getId())).isPresent();
    }

    @Test
    void pendingApplicationCannotBeDeleted() {
        StudyApplication pending = applications.save(
                StudyApplication.create(study.getId(), other.getId(), "저도요"));

        assertThat(delete(otherToken, pending.getId())).isEqualTo(409);
        assertThat(applications.findById(pending.getId())).isPresent();
    }

    /** 승인된 신청을 본인이 지울 수 있으면 그것은 탈퇴이고, 탈퇴는 이 단계에 없다. */
    @Test
    void approvedApplicationCannotBeDeleted() {
        StudyApplication ok = StudyApplication.create(study.getId(), other.getId(), "저도요");
        ok.approve();
        applications.save(ok);

        assertThat(delete(otherToken, ok.getId())).isEqualTo(409);
    }

    @Test
    void unknownApplicationIsForbiddenNotFound() {
        // 없는 신청은 소유자 조건이 false 라 게이트에서 막힌다. 존재 여부가 새지 않는다.
        assertThat(delete(applicantToken, "no-such-id")).isEqualTo(403);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyApplicationDeleteTest'
```

Expected: 실패 — 엔드포인트가 없다.

- [ ] **Step 3: `StudyAccess.isApplicant` 를 더한다**

`isMember` 아래에 넣는다:

```java
    /**
     * 그 신청의 본인. 반려된 자기 신청을 지우는 소유자 조건이다.
     *
     * 경로 변수가 스터디 id 가 아니라 **신청 id** 다 — isLeaderOfApplication 과 같은
     * 함정이다. 스터디 id 로 착각하면 언제나 false 가 되어 아무도 자기 신청을 지우지
     * 못하고, 403 만 나오고 이유는 안 보인다.
     */
    public boolean isApplicant(String applicationId, Authentication auth) {
        String me = idOf(auth);
        if (me == null) return false;
        return applications.findById(applicationId)
                .map(a -> me.equals(a.getApplicantId()))
                .orElse(false);
    }
```

- [ ] **Step 4: `StudyService.deleteApplication` 을 더한다**

```java
    /**
     * 반려된 자기 신청을 하드 삭제한다(D11). (studyId, applicantId) 유니크가 풀려
     * 재신청이 열린다 — deriveApply 가 신청 기록을 보고 CLOSED 를 내던 것이 기록이
     * 사라지면 저절로 OPEN 이 된다. 새 분기가 생기지 않는다.
     */
    @Transactional
    public void deleteApplication(String applicationId) {
        StudyApplication a = applications.findById(applicationId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "신청을 찾을 수 없습니다."));
        if (a.getStatus() != ApplicationStatus.REJECTED) {
            // 승인된 신청을 본인이 지울 수 있으면 그것은 탈퇴이고, 탈퇴는 이 단계에 없다.
            throw new ApiException(HttpStatus.CONFLICT, "NOT_REJECTED",
                    "반려된 신청만 삭제할 수 있습니다.");
        }
        applications.delete(a);
    }
```

- [ ] **Step 5: `StudyController` 에 핸들러를 더한다**

`rejectApplicant` 아래에 넣는다:

```java
    // 반려된 내 신청을 지운다. 지우면 그 스터디에 다시 신청할 수 있다 (D11).
    @DeleteMapping("/applicants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@studyAccess.isApplicant(#id, authentication)")
    public void deleteApplication(@PathVariable String id) {
        service.deleteApplication(id);
    }
```

- [ ] **Step 6: 통과를 확인한다**

```bash
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyApplicationDeleteTest' \
  --tests 'com.jaram.be.study.StudyTest' \
  --tests 'com.jaram.be.security.AuthorizationCoverageTest'
```

Expected: 셋 다 PASS. `StudyTest` 를 같이 도는 이유는 ① 의
`rejectedApplicantSeesApplyClosedNotOpen` 이 여전히 살아 있어야 하기 때문이다 —
**신청을 지우지 않은** 반려자는 그대로 `CLOSED` 다.

- [ ] **Step 7: 커밋한다**

```
feat(study): 반려된 신청을 지워 재신청을 연다

행을 하드 삭제해 (studyId, applicantId) 유니크를 푼다. deriveApply 에 새
분기가 생기지 않는다 — 신청 기록을 보고 CLOSED 를 내던 함수가, 기록이
사라지면 저절로 OPEN 을 낸다. 삭제가 재신청을 여는 것이지 재신청 경로가
따로 생기는 것이 아니다.

경로를 /applicants/{id} 에 둔다. ① 이 이미 그 자리를 신청 id 로 쓰고 있어,
applications 라는 두 번째 이름을 만들면 같은 것을 두 이름으로 부르게 된다.

승인된 신청은 못 지운다. 그것은 탈퇴이고, 탈퇴는 이 단계에 없다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

---

## Task 9: `/my` 에 대기 신청 수를 싣는다

**초안에서 줄었다.** 처음에는 `/my` 를 `MyStudyList{items[]}` 로 갈아엎고 `MyActivity`·`MyApp`·`MyStudy` 를 폐기하려 했다. 철회했다(D23·D24) — 관계 네 값은 두 배열에서 화면이 읽고, 분야·일정·스터디장은 `GET /api/studies` 가 준다. 어디에도 없는 값은 대기 신청 수 하나뿐이다.

**Files:**
- Modify: `src/main/java/com/jaram/be/study/dto/MyStudy.java`
- Modify: `src/main/java/com/jaram/be/study/StudyService.java`
- Test: `src/test/java/com/jaram/be/study/StudyTest.java` (기존 `myActivityReturnsAppsAndLedStudies` 옆에 더한다)

**Interfaces:**
- Consumes: `StudyApplicationRepository.countByStudyIdAndStatus(String, ApplicationStatus)` — **이미 있다.** 새로 만들지 않는다
- Produces:
  - `MyStudy(String id, String title, StudyStatus status, String reason, Integer pendingApplicants)`
  - `MyActivity`·`MyApp` 은 그대로. `StudyService.myActivity(String userId) → MyActivity` 도 시그니처 그대로

**지우는 것도 새 파일도 없다.** 이 과제는 레코드 한 줄, 서비스 한 줄, 테스트 하나다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyTest.java` 의 `myActivityReturnsAppsAndLedStudies` 바로 아래에 더한다. 기존 테스트는 손대지 않는다 — `pendingApplicants` 가 없던 자리는 `null` 이라 기존 단언이 그대로 통과한다.

```java
    @Test
    void myActivityCountsPendingApplicantsOnRecruitingStudies() {
        Member leader = member("l", "리더", "2023000001", "leader@hanyang.ac.kr");
        Member a1 = member("a1", "지원1", "2023000002", "a1@hanyang.ac.kr");
        Member a2 = member("a2", "지원2", "2023000003", "a2@hanyang.ac.kr");
        Member a3 = member("a3", "지원3", "2023000004", "a3@hanyang.ac.kr");

        Study recruiting = approvedStudy(leader.getId(), 5);
        applications.save(StudyApplication.create(recruiting.getId(), a1.getId(), "동기1"));
        applications.save(StudyApplication.create(recruiting.getId(), a2.getId(), "동기2"));
        StudyApplication approved = StudyApplication.create(recruiting.getId(), a3.getId(), "동기3");
        approved.approve();
        applications.save(approved);

        // 아직 승인 전인 스터디는 신청을 받을 수 없으므로 셀 것도 없다
        Study pending = studies.save(Study.create(
                "대기스터디", List.of("x"), 5, null, null, null, null, null, leader.getId()));

        given().header("Authorization", "Bearer " + token(leader))
                .when().get("/api/studies/my").then().statusCode(200)
                .body("studies.size()", equalTo(2))
                // 승인 대기 2건만 센다. 승인된 1건은 빠진다
                .body("studies.find { it.id == '" + recruiting.getId() + "' }.pendingApplicants",
                        equalTo(2))
                // RECRUITING 이 아니면 null — "0건 대기"와 "셀 수 없음"은 다르다
                .body("studies.find { it.id == '" + pending.getId() + "' }.pendingApplicants",
                        nullValue());
    }
```

`nullValue()` 는 이미 쓸 수 있다 — `StudyTest` 가 `import static org.hamcrest.Matchers.*;` 를 갖고 있다. 새 import 는 없다.

- [ ] **Step 2: 테스트가 실패하는 것을 본다**

```bash
export JAVA_HOME=/home/ksb/.local/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyTest.myActivityCountsPendingApplicantsOnRecruitingStudies'
```

Expected: FAIL. `pendingApplicants` 가 응답에 없으므로 `find{}.pendingApplicants` 가 `null` 이고, 첫 단언이 `Expected: <2> but: was null` 로 깨진다.

- [ ] **Step 3: `MyStudy` 에 필드를 더한다**

`src/main/java/com/jaram/be/study/dto/MyStudy.java` 전문:

```java
package com.jaram.be.study.dto;

import com.jaram.be.study.StudyStatus;

// 계약 MyStudy. 내가 개설한 스터디. status 하나가 승인축과 생애축을 다 말한다.
// pendingApplicants 는 RECRUITING 일 때만 채운다 — 0 과 null 이 다른 뜻이다.
// 0 은 "대기 중인 신청이 없다", null 은 "셀 단계가 아니다".
public record MyStudy(
        String id,
        String title,
        StudyStatus status,
        String reason,
        Integer pendingApplicants) {
}
```

- [ ] **Step 4: `myActivity` 가 그 값을 채우게 한다**

`StudyService.myActivity` 의 `myStudies` 를 만드는 대목만 바꾼다. 나머지(`apps` 를 만드는 부분, 반환문)는 그대로 둔다.

```java
        List<MyStudy> myStudies = studies.findByLeaderIdOrderByCreatedAtDesc(userId).stream()
                .map(s -> new MyStudy(s.getId(), s.getTitle(), s.getStatus(), s.getReason(),
                        pendingCount(s)))
                .toList();
```

같은 클래스에 private 헬퍼를 더한다:

```java
    /**
     * 모집 중인 스터디의 대기 신청 수. 그 외에는 null 이다.
     *
     * '내 스터디' 카드가 스터디장에게 "지금 할 일이 있는가"를 말하는 유일한 값이다(② §9).
     * RECRUITING 이 아닐 때 0 을 주면 화면이 "대기 0건"으로 읽어 버린다 — 셀 단계가
     * 아니라는 뜻이므로 null 이어야 한다.
     */
    private Integer pendingCount(Study s) {
        if (s.getStatus() != StudyStatus.RECRUITING) return null;
        return applications.countByStudyIdAndStatus(s.getId(), ApplicationStatus.PENDING);
    }
```

import 가 필요하면 더한다: `com.jaram.be.study.ApplicationStatus` 는 같은 패키지라 필요 없고, `StudyStatus` 도 같은 패키지다. **새 import 는 없다.**

- [ ] **Step 5: 테스트가 통과하는 것을 본다**

```bash
export JAVA_HOME=/home/ksb/.local/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.study.StudyTest'
```

Expected: PASS. 새 테스트 하나와 기존 `StudyTest` 전부가 초록이다. 기존 `myActivityReturnsAppsAndLedStudies` 가 깨지면 레코드에 필드를 더한 것이 아니라 순서를 바꾼 것이다.

- [ ] **Step 6: 계약 검사까지 돌린다**

```bash
export JAVA_HOME=/home/ksb/.local/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon -I "$CLAUDE_JOB_DIR/tmp/docker-api.init.gradle" test \
  --tests 'com.jaram.be.contract.StudyContractTest' \
  --tests 'com.jaram.be.study.StudyDetailTest'
```

Expected: PASS. `OpenApiValidationFilter` 는 **선언되지 않은 응답 필드를 거부한다** — Task 1 의 계약이 머지되어 있지 않으면 여기서 `pendingApplicants` 때문에 깨진다. 깨지면 계약 PR 부터 머지한다.

- [ ] **Step 7: 커밋한다**

```bash
git add src/main/java/com/jaram/be/study/dto/MyStudy.java src/main/java/com/jaram/be/study/StudyService.java src/test/java/com/jaram/be/study/StudyTest.java
```

커밋 메시지 본문:

```
feat(study): 내 스터디 카드가 쓸 대기 신청 수를 싣는다

스터디장이 카드만 보고 지금 할 일이 있는지 아는 유일한 값이다. 모달을
열어야 보이면 모집 중 카드가 전부 똑같이 생긴다.

RECRUITING 이 아니면 0 이 아니라 null 이다. 0 은 "대기 중인 신청이 없다"
이고 null 은 "셀 단계가 아니다" 라, 화면이 둘을 다르게 읽어야 한다.
```


## Task 10: FE — 배선과 '내 스터디' 화면

여기부터 다시 `home-jaram-fe` 다. Task 1 에서 판 `feat/study-operations` 브랜치를 이어 쓴다.

**Files:**
- Modify: `src/features/study/study.api.js`
- Modify: `src/features/study/study.queries.js`
- Modify: `src/features/study/study.data.js`
- Create: `src/features/study/views/MyStudyCard.jsx`
- Create: `src/features/study/views/MyStudyView.jsx`
- Modify: `src/features/study/views/index.js`
- Modify: `src/features/study/StudyPage.jsx`
- Delete: `src/features/study/views/MyActivityView.jsx`

**Interfaces:**
- Consumes: Task 9 의 `GET /api/studies/my` → `MyActivity{apps[], studies[]}` (`studies[].pendingApplicants` 가 새로 들어 있다), Task 8 의 `DELETE /api/studies/applicants/{id}`
- Produces:
  - `api.deleteApplication({ applicationId })`
  - `api.listStudyApplicants({ studyId })`, `api.attendanceBoard({ studyId })`, `api.myAttendance({ studyId })`, `api.saveAttendance({ studyId, weekNo, present })`, `api.addWeek({ studyId, title, content })`, `api.editWeek({ studyId, weekNo, title, content })`, `api.deleteWeek({ studyId, weekNo })`, `api.closeRecruiting({ studyId })`, `api.finishStudy({ studyId })`
  - `studyKeys.my`, `studyKeys.applicantsOf(studyId)`, `studyKeys.attendance(studyId)`, `studyKeys.myAttendance(studyId)`
  - `useDeleteApplication(options)` — `useMyActivity()` 는 그대로 쓴다
  - `toMyStudyItems(myActivity, browseItems) → item[]` — 두 배열과 둘러보기 목록을 카드 한 벌로 합친다
  - `<MyStudyView items onManage onAttendance onDelete />`, `<MyStudyCard item onManage onAttendance onDelete />`
  - `RELATION_CHIP`, `RELATION_ACTION`, `ATTENDANCE_LABEL`, `relationLine(item)` — Task 11·12 가 다시 쓴다

**서버 응답 모양은 ① 그대로다**(D24). 관계는 화면이 두 배열에서 읽고, 분야·일정은 이 페이지가 어차피 부르는 `useStudies()` 에서 붙인다. `study.api.js` 의 `listMyActivity` 와 `study.queries.js` 의 `useMyActivity` 는 **지우지 않는다**.

- [ ] **Step 1: `study.api.js` 에 함수를 더한다**

`listMyActivity` 는 그대로 둔다 — 경로도 응답 모양도 안 바뀐다. 아래를 더하기만 한다:

```js
// 그 스터디의 신청 목록 — { pending, approved }
export async function listStudyApplicants({ studyId }) {
  const { data } = await client.get(`/api/studies/${studyId}/applicants`);
  return data;
}

// 출석 격자 — { weeks, members }
export async function attendanceBoard({ studyId }) {
  const { data } = await client.get(`/api/studies/${studyId}/attendance`);
  return data;
}

// 내 주차별 출석 — { attended, taken, weeks }
export async function myAttendance({ studyId }) {
  const { data } = await client.get(`/api/studies/${studyId}/attendance/me`);
  return data;
}

// 그 주차의 출석을 통째로 바꾼다. present 는 출석한 memberId 배열.
export async function saveAttendance({ studyId, weekNo, present }) {
  await client.put(`/api/studies/${studyId}/weeks/${weekNo}/attendance`, { present });
}

export async function addWeek({ studyId, title, content }) {
  const { data } = await client.post(`/api/studies/${studyId}/weeks`, { title, content });
  return data;
}

export async function editWeek({ studyId, weekNo, title, content }) {
  await client.put(`/api/studies/${studyId}/weeks/${weekNo}`, { title, content });
}

export async function deleteWeek({ studyId, weekNo }) {
  await client.delete(`/api/studies/${studyId}/weeks/${weekNo}`);
}

// 반려된 내 신청을 지운다. 지우면 그 스터디에 다시 신청할 수 있다.
export async function deleteApplication({ applicationId }) {
  await client.delete(`/api/studies/applicants/${applicationId}`);
}

export async function closeRecruiting({ studyId }) {
  await client.post(`/api/studies/${studyId}/close-recruiting`);
}

export async function finishStudy({ studyId }) {
  await client.post(`/api/studies/${studyId}/finish`);
}
```

파일 머리의 주석 중 "Backend not built yet — endpoint paths are a proposed REST contract" 문장은 이제 거짓이다. 다음으로 바꾼다:

```js
/**
 * Study API — talks to the Spring backend via the shared axios client.
 * 경로와 응답 모양은 docs/api/openapi.yaml 이 단일 출처다.
 */
```

- [ ] **Step 2: `study.queries.js` 에 키와 훅을 더한다**

`studyKeys` 를 바꾼다:

```js
export const studyKeys = {
  studies: ['studies'],
  pending: ['studies', 'pending'],
  applicants: ['studies', 'applicants'],
  my: ['studies', 'my'],
  applicantsOf: (studyId) => ['studies', studyId, 'applicants'],
  attendance: (studyId) => ['studies', studyId, 'attendance'],
  myAttendance: (studyId) => ['studies', studyId, 'attendance', 'me'],
};
```

`useMyActivity` 는 그대로 둔다. 아래를 더한다:

```js
/** studyId 가 없으면 돌지 않는다 — 모달이 닫혀 있을 때 부르지 않기 위해서다. */
export function useStudyApplicants(studyId) {
  return useQuery({
    queryKey: studyKeys.applicantsOf(studyId),
    queryFn: () => api.listStudyApplicants({ studyId }),
    enabled: !!studyId,
  });
}

export function useAttendanceBoard(studyId) {
  return useQuery({
    queryKey: studyKeys.attendance(studyId),
    queryFn: () => api.attendanceBoard({ studyId }),
    enabled: !!studyId,
  });
}

export function useMyAttendance(studyId) {
  return useQuery({
    queryKey: studyKeys.myAttendance(studyId),
    queryFn: () => api.myAttendance({ studyId }),
    enabled: !!studyId,
  });
}

export function useSaveAttendance(studyId, options) {
  return useInvalidatingMutation(
    api.saveAttendance, [studyKeys.attendance(studyId), studyKeys.my], options);
}

export function useAddWeek(studyId, options) {
  return useInvalidatingMutation(
    api.addWeek, [studyKeys.attendance(studyId), studyKeys.my], options);
}

export function useEditWeek(studyId, options) {
  return useInvalidatingMutation(api.editWeek, [studyKeys.attendance(studyId)], options);
}

export function useDeleteWeek(studyId, options) {
  return useInvalidatingMutation(
    api.deleteWeek, [studyKeys.attendance(studyId), studyKeys.my], options);
}

export function useDeleteApplication(options) {
  return useInvalidatingMutation(api.deleteApplication, [studyKeys.my, studyKeys.studies], options);
}

export function useCloseRecruiting(options) {
  return useInvalidatingMutation(api.closeRecruiting, [studyKeys.my, studyKeys.studies], options);
}

export function useFinishStudy(options) {
  return useInvalidatingMutation(api.finishStudy, [studyKeys.my, studyKeys.studies], options);
}
```

`useApplyStudy`·`useApproveApplicant`·`useRejectApplicant` 의 무효화 목록에
`studyKeys.my` 를 더한다 — 신청 승인·반려가 '내 스터디' 카드의 `pendingApplicants`
숫자를 바꾼다. `useApproveApplicant`/`useRejectApplicant` 는 스터디별 목록도
무효화해야 하므로 호출부에서 `studyId` 를 받도록 바꾼다:

```js
export function useApproveApplicant(studyId, options) {
  return useInvalidatingMutation(api.approveApplicant,
    [studyKeys.applicants, studyKeys.applicantsOf(studyId), studyKeys.my], options);
}

export function useRejectApplicant(studyId, options) {
  return useInvalidatingMutation(api.rejectApplicant,
    [studyKeys.applicants, studyKeys.applicantsOf(studyId), studyKeys.my], options);
}
```

`StudyPage.jsx` 의 기존 호출 두 곳에 `null` 을 넘긴다(관리 탭은 스터디별 목록을
쓰지 않는다): `useApproveApplicant(null, { … })`.

- [ ] **Step 3: `study.data.js` 에 관계 표와 합치는 함수를 더한다**

먼저 두 배열을 카드 한 벌로 합치는 순수 함수다. 서버가 관계를 내려보내지 않으므로
(D23) 여기가 관계를 정하는 유일한 자리다.

```js
/**
 * /my 의 두 배열을 '내 스터디' 카드 한 벌로 합친다.
 *
 * 서버는 관계를 내려보내지 않는다 — 두 배열이 이미 관계를 말하고 있어서, 같은 뜻을
 * 서버에도 두면 두 곳이 어긋날 자리만 는다(② D23).
 *
 * 분야·일정·스터디장과 '신청한 스터디의 현재 상태'는 둘러보기 목록(`useStudies`)에서
 * 붙인다. 그 목록은 RECRUITING·ONGOING 을 다 싣고 이 페이지가 어차피 부른다.
 * 내가 개설한 PENDING·REJECTED 스터디는 그 목록에 없지만, 그 카드가 쓰는 값
 * (제목·상태·반려 사유)은 전부 `MyStudy` 안에 있다.
 *
 * @param my   { apps, studies } — GET /api/studies/my
 * @param browse StudyResponse[] — GET /api/studies 의 items
 */
export function toMyStudyItems(my, browse = []) {
  const byId = new Map(browse.map((s) => [s.id, s]));
  const detail = (id) => byId.get(id) ?? {};

  const led = (my?.studies ?? []).map((s) => ({
    ...detail(s.id),
    id: s.id,
    title: s.title,
    status: s.status,           // MyStudy 가 권위다. 둘러보기 목록보다 최신이다
    reason: s.reason,
    pendingApplicants: s.pendingApplicants,
    relation: 'LEADER',
    applicationId: null,
  }));

  const applied = (my?.apps ?? []).map((a) => {
    const d = detail(a.studyId);
    return {
      ...d,
      id: a.studyId,
      title: a.title ?? d.title,
      // 신청의 상태(PENDING/APPROVED/REJECTED)가 아니라 스터디의 상태다.
      // 목록에 없으면(= 종료·삭제) 카드가 상태 배지를 그리지 않는다.
      status: d.status ?? null,
      reason: a.reason,
      pendingApplicants: null,
      relation: { APPROVED: 'MEMBER', PENDING: 'APPLIED', REJECTED: 'REJECTED' }[a.status],
      applicationId: a.id,      // 삭제하기가 쓰는 id — 스터디 id 가 아니다
    };
  });

  return [...led, ...applied].filter(keep);
}

/**
 * 종료된 스터디는 어느 관계로도 '내 스터디'에 오지 않는다(① §14). 끝난 것이 계속
 * 쌓이면 이 화면이 이력 목록이 된다.
 *
 * 서버는 /my 에서 거르지 않으므로(② D24 로 응답을 안 건드렸다) 여기서 거른다.
 * 둘러보기 목록이 RECRUITING·ONGOING 만 실으므로, 신청 카드의 status 가 null 이면
 * 그 스터디는 종료됐거나 사라진 것이다.
 */
function keep(item) {
  if (item.status === 'FINISHED') return false;
  // 반려된 신청은 이력이라 남긴다 — 지워야 그 스터디에 다시 신청할 수 있다.
  if (item.relation === 'REJECTED') return true;
  if (item.relation !== 'LEADER' && item.status == null) return false;
  return true;
}
```

**`applicationId` 는 신청 id 다.** 카드의 `id` 는 스터디 id 라 둘이 다르다. 삭제하기가
스터디 id 를 보내면 서버가 언제나 `403` 을 준다(② §12 가 짚은 것과 같은 자리다).

```js
/**
 * 관계 칩 — 이 화면의 구조다. 왜 이 카드가 저 카드와 다르게 생겼는지를 설명하는
 * 유일한 값이므로 장식이 아니라 정보다.
 */
export const RELATION_CHIP = {
  LEADER:   { label: '스터디장', tone: 'brand' },
  MEMBER:   { label: '참여 중', tone: 'seal' },
  APPLIED:  { label: '신청 대기', tone: 'outline' },
  REJECTED: { label: '반려됨', tone: 'neutral' },
};

/** 카드 가운데 한 줄. 관계 x 상태가 정한다. */
export function relationLine(item) {
  const { relation, status } = item;
  if (relation === 'LEADER') {
    if (status === 'PENDING') return '개설 승인을 기다리는 중입니다';
    if (status === 'REJECTED') return item.reason || '개설이 반려되었습니다';
    if (status === 'RECRUITING') {
      return item.pendingApplicants
        ? `신청 ${item.pendingApplicants}건이 기다리고 있습니다`
        : '새 신청이 없습니다';
    }
    // 진행 중 카드에는 숫자가 없다. 출석 수·기록된 주차 수를 실으려면 /my 에 필드가
    // 셋 더 붙어야 하는데 이번 단계는 pendingApplicants 하나만 더했다(② D24).
    // 숫자는 '관리하기'를 열면 나온다.
    if (status === 'ONGOING') return '진행 중입니다';
  }
  if (relation === 'MEMBER') {
    if (status === 'ONGOING') return '참여 중입니다';
    return '참여가 확정됐습니다. 곧 시작합니다';
  }
  if (relation === 'APPLIED') return '신청이 검토 중입니다';
  return item.reason || '신청이 반려되었습니다';
}

/** 멤버에게 '관리하기'는 거짓말이다 — 관리할 것이 없고 자기 출석을 볼 뿐이다. */
export const RELATION_ACTION = {
  LEADER: '관리하기',
  MEMBER: '출석 보기',
  REJECTED: '삭제하기',
};

export const ATTENDANCE_LABEL = {
  PRESENT: '출석',
  ABSENT: '결석',
  NOT_TAKEN: '아직',
};
```

- [ ] **Step 4: `MyStudyCard` 를 만든다**

`src/features/study/views/MyStudyCard.jsx`:

```jsx
import React from 'react';
import { Button, Tag } from '@/design-system';
import { FieldChip } from './parts';
import { STATUS_BADGE, RELATION_CHIP, RELATION_ACTION, relationLine } from '../study.data';

/** 이 카드에 버튼이 붙는 관계·상태인가. */
function actionOf(item) {
  const { relation, status } = item;
  if (relation === 'LEADER' && (status === 'RECRUITING' || status === 'ONGOING')) return 'manage';
  if (relation === 'MEMBER' && status === 'ONGOING') return 'attendance';
  if (relation === 'REJECTED') return 'delete';
  return null;
}

/**
 * '내 스터디'의 카드 한 장. 관계 칩(좌)과 상태 배지(우)가 머리에 있고,
 * 가운데 한 줄과 버튼이 관계 x 상태로 갈린다.
 */
export function MyStudyCard({ item, onManage, onAttendance, onDelete }) {
  const chip = RELATION_CHIP[item.relation];
  const badge = STATUS_BADGE[item.status];
  const action = actionOf(item);

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        background: 'var(--surface-card)',
        border: '1px solid var(--border)',
        borderTop: '3px solid var(--brand)',
        borderRadius: 'var(--radius-lg)',
        boxShadow: 'var(--shadow-sm)',
        padding: 24,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
        <Tag tone={chip.tone} size="sm" style={{ flex: 'none' }}>{chip.label}</Tag>
        {badge && <Tag tone={badge.tone} size="sm" style={{ flex: 'none' }}>{badge.label}</Tag>}
      </div>

      <h3 style={{ margin: '14px 0 0', fontFamily: 'var(--font-serif)', fontSize: 'var(--fs-title-3)', fontWeight: 'var(--w-bold)', color: 'var(--text-strong)', lineHeight: 1.3 }}>
        {item.title}
      </h3>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 10 }}>
        {(item.fields ?? []).map((f) => <FieldChip key={f}>{f}</FieldChip>)}
      </div>

      <div
        style={{
          marginTop: 18,
          paddingTop: 16,
          borderTop: '1px solid var(--border-soft)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 14,
          flexWrap: 'wrap',
        }}
      >
        <span style={{ fontFamily: 'var(--font-sans)', fontSize: 'var(--fs-sm)', color: 'var(--text-muted)', lineHeight: 'var(--lh-normal)' }}>
          {relationLine(item)}
        </span>
        {action === 'manage' && (
          <Button size="sm" onClick={() => onManage(item)}>{RELATION_ACTION.LEADER}</Button>
        )}
        {action === 'attendance' && (
          <Button size="sm" variant="secondary" onClick={() => onAttendance(item)}>
            {RELATION_ACTION.MEMBER}
          </Button>
        )}
        {action === 'delete' && (
          <Button size="sm" variant="secondary" onClick={() => onDelete(item)}>
            {RELATION_ACTION.REJECTED}
          </Button>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 5: `MyStudyView` 를 만든다**

`src/features/study/views/MyStudyView.jsx`:

```jsx
import React from 'react';
import { Button } from '@/design-system';
import { EmptyState } from './parts';
import { MyStudyCard } from './MyStudyCard';

/**
 * 내가 할 일이 있는 것부터. 대기 신청이 있는 스터디장 카드 → 나머지 스터디장 →
 * 참여 중 → 신청 대기.
 *
 * 주차를 안 찍은 스터디를 위로 올리려면 weeksTaken 이 있어야 하는데 카드가 그 값을
 * 받지 않는다(② §9).
 */
function rank(item) {
  if (item.relation === 'LEADER') {
    return item.status === 'RECRUITING' && item.pendingApplicants > 0 ? 0 : 1;
  }
  if (item.relation === 'MEMBER') return 2;
  return 3;   // APPLIED
}

function Section({ title, children }) {
  return (
    <div>
      <h2 style={{ margin: '0 0 18px', fontFamily: 'var(--font-serif)', fontSize: 'var(--fs-title-3)', fontWeight: 'var(--w-bold)', color: 'var(--text-strong)' }}>
        {title}
      </h2>
      <div style={{ display: 'grid', gap: 16, gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))' }}>
        {children}
      </div>
    </div>
  );
}

/**
 * '내 스터디' — 지금 살아 있는 관계(스터디장·참여 중·신청 대기)가 위, 반려된 신청이
 * 아래. 반려는 내 스터디가 아니라 이력이라, 같은 그리드에 섞으면 탭 이름이 거짓이 된다.
 *
 * 종료된 스터디는 서버가 아예 내려보내지 않는다.
 */
export function MyStudyView({ items = [], onManage, onAttendance, onDelete, onBrowse }) {
  const live = items.filter((i) => i.relation !== 'REJECTED').sort((a, b) => rank(a) - rank(b));
  const past = items.filter((i) => i.relation === 'REJECTED');

  if (live.length === 0 && past.length === 0) {
    return (
      <div className="jr-anim">
        <EmptyState>아직 참여 중인 스터디가 없습니다.</EmptyState>
        <div style={{ display: 'flex', justifyContent: 'center' }}>
          <Button onClick={onBrowse}>스터디 둘러보기</Button>
        </div>
      </div>
    );
  }

  return (
    <div className="jr-anim" style={{ display: 'grid', gap: 40 }}>
      {live.length > 0 && (
        <Section title="내 스터디">
          {live.map((i) => (
            <MyStudyCard key={i.id + i.relation} item={i}
              onManage={onManage} onAttendance={onAttendance} onDelete={onDelete} />
          ))}
        </Section>
      )}
      {past.length > 0 && (
        <Section title="지난 신청">
          {past.map((i) => (
            <MyStudyCard key={i.id + i.relation} item={i}
              onManage={onManage} onAttendance={onAttendance} onDelete={onDelete} />
          ))}
        </Section>
      )}
    </div>
  );
}
```

- [ ] **Step 6: `views/index.js` 를 갈아 끼운다**

`export { MyActivityView } from './MyActivityView';` 를 지우고 넣는다:

```js
export { MyStudyView } from './MyStudyView';
```

그리고 파일을 지운다:

```bash
rm src/features/study/views/MyActivityView.jsx
```

- [ ] **Step 7: `StudyPage.jsx` 를 배선한다**

1. `SUB_NAV` 의 두 번째 항목 라벨을 바꾼다: `{ key: 'mine', label: '내 스터디' }`
2. 페이지 제목 아래 설명은 그대로 둔다 — 첫 탭(둘러보기)의 것이다.
3. import 를 바꾼다: `MyActivityView` → `MyStudyView`, 그리고 `useDeleteApplication`
   과 `toMyStudyItems` 를 더한다. **`useMyActivity` 는 그대로 쓴다.**
4. 쿼리는 그대로다 — `studiesQ`·`myActivityQ` 둘 다 이미 이 페이지에 있다. 합치는
   줄만 더한다:

```jsx
  const myItems = useMemo(
    () => toMyStudyItems(myActivityQ.data, studiesQ.data?.items ?? []),
    [myActivityQ.data, studiesQ.data],
  );
```

`useMemo` 가 import 되어 있지 않으면 `react` import 에 더한다.
5. 삭제 확인 상태와 뮤테이션을 더한다:

```jsx
  const [deleting, setDeleting] = useState(null);   // 반려 신청 카드

  const deleteApplicationM = useDeleteApplication({
    onSuccess: () => { setDeleting(null); showToast(TOAST.applicationDeleted); },
  });
```

`study.data.js` 의 `TOAST` 에 한 줄을 더한다:

```js
  applicationDeleted: '신청을 삭제했습니다. 다시 신청할 수 있습니다.',
```

6. `view === 'mine'` 가지를 바꾼다:

```jsx
        {view === 'mine' && (
          myActivityQ.isLoading || studiesQ.isLoading ? (
            <Notice>불러오는 중…</Notice>
          ) : myActivityQ.isError ? (
            <Notice>내 스터디를 불러오지 못했습니다.</Notice>
          ) : (
            <MyStudyView
              items={myItems}
              onManage={setManaging}
              onAttendance={setViewingAttendance}
              onDelete={setDeleting}
              onBrowse={() => go('browse')}
            />
          )
        )}
```

`managing`·`viewingAttendance` 상태는 Task 11·12 가 쓴다. 이 과제에서는 자리만 둔다:

```jsx
  const [managing, setManaging] = useState(null);            // Task 11
  const [viewingAttendance, setViewingAttendance] = useState(null);  // Task 12
```

7. 삭제 확인 모달을 `<Toast …/>` 바로 위에 둔다. **삭제하면 재신청이 열린다는 것이
이 버튼을 누르는 이유인데, 안 쓰면 그냥 지우는 것으로 읽힌다.**

```jsx
      {deleting && (
        <ModalShell
          title="신청 삭제"
          lead={`'${deleting.title}' 신청 기록을 지웁니다. 삭제하면 이 스터디에 다시 신청할 수 있습니다.`}
          onClose={() => setDeleting(null)}
        >
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 28 }}>
            <Button variant="secondary" onClick={() => setDeleting(null)}>취소</Button>
            <Button
              onClick={() => deleteApplicationM.mutate({ applicationId: deleting.applicationId })}
              disabled={deleteApplicationM.isPending}
            >
              삭제하기
            </Button>
          </div>
        </ModalShell>
      )}
```

`ModalShell` 을 import 한다: `import { ModalShell } from './views/ModalShell';`

8. `useApproveApplicant`/`useRejectApplicant` 호출에 첫 인자 `null` 을 넣는다(Step 2).

- [ ] **Step 8: 린트·타입·빌드를 돌린다**

```bash
cd /home/ksb/Dev/home-jaram/home-jaram-fe
./node_modules/.bin/eslint .
./node_modules/.bin/tsc --noEmit
./node_modules/.bin/vite build
```

Expected: 셋 다 성공. `MyActivityView` 를 참조하는 자리가 남아 있으면 여기서 잡힌다.

- [ ] **Step 9: 커밋한다**

```
feat(study): 내 스터디 화면을 관계 축으로 다시 그린다

옛 '내 활동'은 신청 현황과 개설 목록을 한 줄짜리 상태 행으로 쌓았고, 계약에
없는 message·tone·badge 를 읽고 있었다. 화면을 카드로 바꾸면서 그 어긋남이
없어진다.

관계 칩이 이 화면의 구조다 — 왜 이 카드가 저 카드와 다르게 생겼는지를
설명하는 유일한 값이라 장식이 아니라 정보다. 정렬도 관계가 아니라 내가 할
일이 있는 순이다.

버튼 이름이 관계에 따라 다르다. 멤버에게 '관리하기'는 거짓말이다 —
관리할 것이 없고 자기 출석을 볼 뿐이다.

반려된 신청은 '지난 신청'으로 따로 뺀다. 내 스터디가 아니라 이력이라,
같은 그리드에 섞으면 탭 이름이 거짓이 된다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

---

## Task 11: FE — 관리하기 모달 (신청 관리 · 출석 · 정보)

**Files:**
- Create: `src/features/study/views/ManageStudyModal.jsx`
- Modify: `src/features/study/views/index.js`
- Modify: `src/features/study/StudyPage.jsx`

**Interfaces:**
- Consumes: Task 10 의 `useStudyApplicants`·`useAttendanceBoard`·`useSaveAttendance`·`useAddWeek`·`useEditWeek`·`useDeleteWeek`·`useCloseRecruiting`·`useFinishStudy`·`useApproveApplicant`·`useRejectApplicant`, `ModalShell`
- Produces: `<ManageStudyModal study onClose onToast />`

상태가 무엇을 열지 정한다. `RECRUITING` 이면 신청 관리, `ONGOING` 이면 출석/정보 탭.

- [ ] **Step 1: `ManageStudyModal` 을 만든다**

`src/features/study/views/ManageStudyModal.jsx`:

```jsx
import React, { useState, useMemo } from 'react';
import { Button, Tag } from '@/design-system';
import { ModalShell } from './ModalShell';
import { Pill } from './parts';
import {
  useStudyApplicants, useAttendanceBoard, useSaveAttendance,
  useAddWeek, useEditWeek, useDeleteWeek,
  useCloseRecruiting, useFinishStudy,
  useApproveApplicant, useRejectApplicant,
} from '../study.queries';

/** 가장 빠른 빈 주차 — 아직 안 찍은 첫 주차. 없으면 마지막 주차. */
function firstUntaken(weeks) {
  const open = weeks.find((w) => !w.takenAt);
  return (open ?? weeks[weeks.length - 1])?.weekNo ?? 1;
}

/** 편집 창이 언제 닫히는지 사람이 읽는 말로. */
function remaining(takenAt) {
  const closesAt = new Date(takenAt).getTime() + 24 * 3600 * 1000;
  const left = closesAt - Date.now();
  if (left <= 0) return null;
  const hours = Math.floor(left / 3600000);
  return hours >= 1 ? `${hours}시간 남음` : `${Math.max(1, Math.floor(left / 60000))}분 남음`;
}

/* ── 모집 중: 신청 관리 ─────────────────────────────────────────── */

function ApplicantsPane({ study, onToast }) {
  const q = useStudyApplicants(study.id);
  const [rejecting, setRejecting] = useState(null);
  const [reason, setReason] = useState('');

  const approveM = useApproveApplicant(study.id, {
    onSuccess: (_d, vars) => onToast(`${vars.name} 님을 승인했습니다.`),
  });
  const rejectM = useRejectApplicant(study.id, {
    onSuccess: () => { setRejecting(null); setReason(''); onToast('신청을 반려했습니다.'); },
  });

  if (q.isLoading) return <Note>불러오는 중…</Note>;
  if (q.isError) return <Note>신청 목록을 불러오지 못했습니다.</Note>;

  const { pending = [], approved = [] } = q.data ?? {};

  return (
    <div style={{ display: 'grid', gap: 28, marginTop: 24 }}>
      <div>
        <GroupTitle>대기 중인 신청 {pending.length}건</GroupTitle>
        {pending.length === 0 ? (
          <Note>새 신청이 없습니다.</Note>
        ) : (
          <div style={{ display: 'grid', gap: 10 }}>
            {pending.map((a) => (
              <div key={a.applicationId} style={rowStyle}>
                <div>
                  <strong style={{ color: 'var(--text-strong)' }}>{a.name}</strong>
                  <span style={{ marginLeft: 8, color: 'var(--text-faint)' }}>{a.gen}기</span>
                  <p style={{ margin: '6px 0 0', color: 'var(--text-muted)', fontSize: 'var(--fs-sm)', lineHeight: 'var(--lh-normal)' }}>
                    {a.motive}
                  </p>
                  {rejecting === a.applicationId && (
                    <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
                      <input
                        id={`reject-reason-${a.applicationId}`}
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                        placeholder="반려 사유"
                        style={inputStyle}
                      />
                      <Button size="sm" onClick={() =>
                        rejectM.mutate({ applicantId: a.applicationId, reason })}>보내기</Button>
                      <Button size="sm" variant="secondary"
                        onClick={() => { setRejecting(null); setReason(''); }}>취소</Button>
                    </div>
                  )}
                </div>
                {rejecting !== a.applicationId && (
                  <div style={{ display: 'flex', gap: 8, flex: 'none' }}>
                    <Button size="sm" onClick={() =>
                      approveM.mutate({ applicantId: a.applicationId, name: a.name })}>승인</Button>
                    <Button size="sm" variant="secondary"
                      onClick={() => setRejecting(a.applicationId)}>반려</Button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      <div>
        <GroupTitle>참여 확정 {approved.length}명</GroupTitle>
        {approved.length === 0 ? (
          <Note>아직 확정된 참여자가 없습니다.</Note>
        ) : (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {approved.map((a) => (
              <Tag key={a.applicationId} tone="outline" size="sm">{a.name} · {a.gen}기</Tag>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/* ── 진행 중: 출석 ──────────────────────────────────────────────── */

function AttendancePane({ study, onToast }) {
  const q = useAttendanceBoard(study.id);
  const [picked, setPicked] = useState(null);
  const [checked, setChecked] = useState(null);

  const saveM = useSaveAttendance(study.id, {
    onSuccess: () => { setChecked(null); onToast('출석을 저장했습니다.'); },
  });

  const weeks = q.data?.weeks ?? [];
  const members = q.data?.members ?? [];
  const weekNo = picked ?? (weeks.length ? firstUntaken(weeks) : null);
  const week = weeks.find((w) => w.weekNo === weekNo);

  const initial = useMemo(
    () => members.filter((m) => m.present.includes(weekNo)).map((m) => m.memberId),
    [members, weekNo],
  );
  const present = checked ?? initial;

  if (q.isLoading) return <Note>불러오는 중…</Note>;
  if (q.isError) return <Note>출석을 불러오지 못했습니다.</Note>;
  if (!week) return <Note>커리큘럼 주차가 없습니다. '정보' 탭에서 먼저 추가하세요.</Note>;

  const left = week.takenAt ? remaining(week.takenAt) : null;

  return (
    <div style={{ marginTop: 24 }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        {weeks.map((w) => (
          <Pill key={w.weekNo} active={w.weekNo === weekNo}
            onClick={() => { setPicked(w.weekNo); setChecked(null); }}>
            {w.weekNo}주 {w.takenAt ? '●' : '○'}
          </Pill>
        ))}
      </div>

      <p style={{ margin: '16px 0 0', fontFamily: 'var(--font-sans)', fontSize: 'var(--fs-sm)', color: 'var(--text-muted)' }}>
        {week.weekNo}주차 · {week.title}
        {week.takenAt && week.editable && left && ` · 수정 가능 ${left}`}
        {week.takenAt && !week.editable && ' · 수정 기간이 지났습니다. 임원에게 요청하세요.'}
        {!week.takenAt && ' · 아직 기록하지 않았습니다'}
      </p>

      <div style={{ display: 'grid', gap: 8, marginTop: 16 }}>
        {members.map((m) => (
          <label key={m.memberId} style={{ ...rowStyle, cursor: week.editable ? 'pointer' : 'default' }}>
            <span>
              <strong style={{ color: 'var(--text-strong)' }}>{m.name}</strong>
              <span style={{ marginLeft: 8, color: 'var(--text-faint)' }}>{m.gen}기</span>
              {m.leader && <Tag tone="brand" size="sm" style={{ marginLeft: 8 }}>스터디장</Tag>}
            </span>
            <input
              type="checkbox"
              id={`att-${weekNo}-${m.memberId}`}
              disabled={!week.editable}
              checked={present.includes(m.memberId)}
              onChange={(e) => setChecked(
                e.target.checked
                  ? [...present, m.memberId]
                  : present.filter((id) => id !== m.memberId))}
            />
          </label>
        ))}
      </div>

      {week.editable && (
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 20 }}>
          <Button
            disabled={saveM.isPending}
            onClick={() => saveM.mutate({ studyId: study.id, weekNo, present })}
          >
            저장
          </Button>
        </div>
      )}
    </div>
  );
}

/* ── 진행 중: 정보(커리큘럼) ────────────────────────────────────── */

function CurriculumPane({ study, onToast }) {
  const q = useAttendanceBoard(study.id);
  const [title, setTitle] = useState('');

  const addM = useAddWeek(study.id, { onSuccess: () => { setTitle(''); onToast('주차를 추가했습니다.'); } });
  const delM = useDeleteWeek(study.id, { onSuccess: () => onToast('주차를 삭제했습니다.') });

  if (q.isLoading) return <Note>불러오는 중…</Note>;
  const weeks = q.data?.weeks ?? [];
  const last = weeks[weeks.length - 1];

  return (
    <div style={{ marginTop: 24 }}>
      <div style={{ display: 'grid', gap: 10 }}>
        {weeks.map((w) => {
          const isLast = last && w.weekNo === last.weekNo;
          const blocked = !!w.takenAt && !w.editable;
          return (
            <div key={w.weekNo} style={rowStyle}>
              <span>
                <strong style={{ color: 'var(--text-strong)' }}>{w.weekNo}주차</strong>
                <span style={{ marginLeft: 10 }}>{w.title}</span>
              </span>
              {isLast && weeks.length > 1 && (
                <Button
                  size="sm" variant="secondary"
                  disabled={blocked || delM.isPending}
                  title={blocked ? '출석이 기록된 주차라 삭제 기간이 지났습니다' : undefined}
                  onClick={() => delM.mutate({ studyId: study.id, weekNo: w.weekNo })}
                >
                  삭제
                </Button>
              )}
            </div>
          );
        })}
      </div>

      <div style={{ display: 'flex', gap: 8, marginTop: 18 }}>
        <input
          id="new-week-title"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="새 주차 제목"
          style={inputStyle}
        />
        <Button
          size="sm"
          disabled={!title.trim() || addM.isPending}
          onClick={() => addM.mutate({ studyId: study.id, title, content: null })}
        >
          + 주차 추가
        </Button>
      </div>
      <p style={{ margin: '10px 0 0', fontFamily: 'var(--font-sans)', fontSize: 'var(--fs-xs)', color: 'var(--text-faint)' }}>
        주차는 맨 뒤에서만 늘리고 줄입니다. 한 주 쉬면 뒤에 한 주를 더하고, 일찍 접으면 뒤에서 자릅니다.
      </p>
    </div>
  );
}

/* ── 껍데기 ──────────────────────────────────────────────────────── */

const rowStyle = {
  display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14,
  background: 'var(--surface-card)', border: '1px solid var(--border)',
  borderRadius: 'var(--radius-md)', padding: '14px 18px',
  fontFamily: 'var(--font-sans)', fontSize: 'var(--fs-sm)', color: 'var(--text-body)',
};

const inputStyle = {
  flex: 1, padding: '9px 12px', borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border-strong)', background: 'var(--surface-card)',
  fontFamily: 'var(--font-sans)', fontSize: 'var(--fs-sm)', color: 'var(--text-body)',
};

function GroupTitle({ children }) {
  return (
    <h4 style={{ margin: '0 0 12px', fontFamily: 'var(--font-sans)', fontSize: 'var(--fs-sm)', fontWeight: 'var(--w-semibold)', color: 'var(--text-strong)' }}>
      {children}
    </h4>
  );
}

function Note({ children }) {
  return (
    <p style={{ margin: 0, padding: '18px 2px', fontFamily: 'var(--font-sans)', fontSize: 'var(--fs-sm)', color: 'var(--text-muted)' }}>
      {children}
    </p>
  );
}

/**
 * 스터디장의 '관리하기'. 스터디 상태가 무엇을 열지 정한다.
 *
 * 모집 중이면 신청 관리와 '모집 완료', 진행 중이면 출석/정보 탭과 '종료'.
 * 두 버튼 모두 확인을 한 단계 둔다 — 되돌릴 손잡이가 임원에게만 있고(D12),
 * '종료'는 이 스터디를 '내 스터디'에서 사라지게 한다.
 */
export function ManageStudyModal({ study, onClose, onToast }) {
  const [tab, setTab] = useState('attendance');
  const [confirming, setConfirming] = useState(false);

  const recruiting = study.status === 'RECRUITING';

  const closeM = useCloseRecruiting({
    onSuccess: () => { onClose(); onToast('모집을 완료했습니다. 이제 진행 중입니다.'); },
  });
  const finishM = useFinishStudy({
    onSuccess: () => { onClose(); onToast('스터디를 종료했습니다.'); },
  });

  const confirmText = recruiting
    ? '모집을 완료하면 더 이상 신청을 받지 않고 진행 중으로 넘어갑니다. 되돌리려면 임원에게 요청해야 합니다.'
    : '종료하면 이 스터디가 \'내 스터디\'에서 사라집니다. 출석 기록도 더 이상 고칠 수 없습니다.';

  return (
    <ModalShell title={study.title} lead={recruiting ? '신청 관리' : '출석과 커리큘럼'}
      onClose={onClose} maxWidth={720} align="top">

      {!recruiting && (
        <div style={{ display: 'flex', gap: 6, marginTop: 20 }}>
          <Pill active={tab === 'attendance'} onClick={() => setTab('attendance')}>출석</Pill>
          <Pill active={tab === 'info'} onClick={() => setTab('info')}>정보</Pill>
        </div>
      )}

      {recruiting && <ApplicantsPane study={study} onToast={onToast} />}
      {!recruiting && tab === 'attendance' && <AttendancePane study={study} onToast={onToast} />}
      {!recruiting && tab === 'info' && <CurriculumPane study={study} onToast={onToast} />}

      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 28, paddingTop: 20, borderTop: '1px solid var(--border-soft)' }}>
        {confirming ? (
          <>
            <span style={{ flex: 1, fontFamily: 'var(--font-sans)', fontSize: 'var(--fs-sm)', color: 'var(--text-muted)', lineHeight: 'var(--lh-normal)' }}>
              {confirmText}
            </span>
            <Button variant="secondary" onClick={() => setConfirming(false)}>취소</Button>
            <Button
              disabled={closeM.isPending || finishM.isPending}
              onClick={() => (recruiting ? closeM : finishM).mutate({ studyId: study.id })}
            >
              {recruiting ? '모집 완료' : '종료'}
            </Button>
          </>
        ) : (
          <Button variant="secondary" onClick={() => setConfirming(true)}>
            {recruiting ? '모집 완료' : '종료'}
          </Button>
        )}
      </div>
    </ModalShell>
  );
}
```

- [ ] **Step 2: `views/index.js` 에 내보낸다**

```js
export { ManageStudyModal } from './ManageStudyModal';
```

- [ ] **Step 3: `StudyPage.jsx` 에 배선한다**

`<Toast …/>` 위에 넣는다:

```jsx
      {managing && (
        <ManageStudyModal
          study={managing}
          onClose={() => setManaging(null)}
          onToast={showToast}
        />
      )}
```

import 에 `ManageStudyModal` 을 더한다.

- [ ] **Step 4: 린트·타입·빌드를 돌린다**

```bash
./node_modules/.bin/eslint .
./node_modules/.bin/tsc --noEmit
./node_modules/.bin/vite build
```

Expected: 셋 다 성공

- [ ] **Step 5: 커밋한다**

```
feat(study): 스터디장의 관리하기 모달을 만든다

스터디 상태가 무엇을 열지 정한다. 모집 중이면 신청 관리와 '모집 완료',
진행 중이면 출석/정보 탭과 '종료'다. ① 이 만든 close-recruiting·finish 에
버튼만 붙인다.

출석 탭의 기본 선택은 가장 빠른 빈 주차다. 저장된 주차는 남은 수정 시간을
같이 보여주고, 창이 닫히면 읽기 전용으로 잠그면서 이유를 쓴다 — 버튼만
죽이고 이유를 안 쓰면 고장으로 보인다.

두 전이 버튼에 확인을 한 단계 둔다. 되돌릴 손잡이가 임원에게만 있고,
'종료'는 이 스터디를 내 스터디에서 사라지게 한다 — 화면에서 사라지는 것이
그 버튼의 진짜 결과인데, 안 쓰면 눌러 보고 나서야 안다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

---

## Task 12: FE — 내 출석 모달

**Files:**
- Create: `src/features/study/views/MyAttendanceModal.jsx`
- Modify: `src/features/study/views/index.js`
- Modify: `src/features/study/StudyPage.jsx`

**Interfaces:**
- Consumes: Task 10 의 `useMyAttendance`, `ATTENDANCE_LABEL`, `ModalShell`
- Produces: `<MyAttendanceModal study onClose />`

- [ ] **Step 1: `MyAttendanceModal` 을 만든다**

`src/features/study/views/MyAttendanceModal.jsx`:

```jsx
import React from 'react';
import { Tag } from '@/design-system';
import { ModalShell } from './ModalShell';
import { useMyAttendance } from '../study.queries';
import { ATTENDANCE_LABEL } from '../study.data';

const TONE = { PRESENT: 'seal', ABSENT: 'neutral', NOT_TAKEN: 'outline' };

/**
 * 참여 멤버가 보는 자기 출석. 읽기 전용이다 — 멤버에게는 고칠 것이 없다.
 *
 * 아직 기록되지 않은 주차를 결석으로 쓰지 않는다. 그건 안 일어난 일이지
 * 빠진 게 아니고, 출석률의 분모도 같은 이유로 기록된 주차 수다.
 */
export function MyAttendanceModal({ study, onClose }) {
  const q = useMyAttendance(study.id);

  return (
    <ModalShell title={study.title} lead="내 출석 현황" onClose={onClose} maxWidth={520}>
      {q.isLoading && <Note>불러오는 중…</Note>}
      {q.isError && <Note>출석을 불러오지 못했습니다.</Note>}

      {q.data && (
        <>
          <p style={{ margin: '20px 0 0', fontFamily: 'var(--font-sans)', fontSize: 'var(--fs-body)', color: 'var(--text-strong)' }}>
            출석 <strong>{q.data.attended}</strong> / 기록된 {q.data.taken}주차
          </p>

          <div style={{ display: 'grid', gap: 8, marginTop: 18 }}>
            {q.data.weeks.map((w) => (
              <div
                key={w.weekNo}
                style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14,
                  border: '1px solid var(--border)', borderRadius: 'var(--radius-md)',
                  padding: '13px 18px',
                  fontFamily: 'var(--font-sans)', fontSize: 'var(--fs-sm)', color: 'var(--text-body)',
                }}
              >
                <span>
                  <strong style={{ color: 'var(--text-strong)' }}>{w.weekNo}주차</strong>
                  <span style={{ marginLeft: 10 }}>{w.title}</span>
                </span>
                <Tag tone={TONE[w.state]} size="sm" style={{ flex: 'none' }}>
                  {ATTENDANCE_LABEL[w.state]}
                </Tag>
              </div>
            ))}
          </div>
        </>
      )}
    </ModalShell>
  );
}

function Note({ children }) {
  return (
    <p style={{ margin: '20px 0 0', fontFamily: 'var(--font-sans)', fontSize: 'var(--fs-sm)', color: 'var(--text-muted)' }}>
      {children}
    </p>
  );
}
```

- [ ] **Step 2: `views/index.js` 에 내보낸다**

```js
export { MyAttendanceModal } from './MyAttendanceModal';
```

- [ ] **Step 3: `StudyPage.jsx` 에 배선한다**

```jsx
      {viewingAttendance && (
        <MyAttendanceModal
          study={viewingAttendance}
          onClose={() => setViewingAttendance(null)}
        />
      )}
```

import 에 `MyAttendanceModal` 을 더한다.

- [ ] **Step 4: 린트·타입·빌드를 돌린다**

```bash
./node_modules/.bin/eslint .
./node_modules/.bin/tsc --noEmit
./node_modules/.bin/vite build
```

Expected: 셋 다 성공

- [ ] **Step 5: 로컬 스택에서 눈으로 본다**

```bash
docker build -t jaram-fe:study-operations /home/ksb/Dev/home-jaram/home-jaram-fe
docker tag jaram-fe:study-operations jaram-fe:latest
docker compose -f /home/ksb/Dev/home-jaram/docker-compose.yml up -d --no-build --force-recreate jaram-fe
```

`http://whitewhale.iptime.org:8085/study` 로 연다. **`localhost:8085` 로 열면 안 된다** —
`CORS_ALLOWED_ORIGINS` 가 `http://whitewhale.iptime.org:8085` 로 고정돼 있어, 다른
오리진으로 열면 모든 질의가 네트워크 오류로 죽고 진짜 증상이 가려진다.

확인할 것: '내 스터디' 탭이 보이고, 관계 칩이 카드마다 다르고, 콘솔 오류가 0이다.

- [ ] **Step 6: 커밋하고 PR 을 연다**

```
feat(study): 참여 멤버가 자기 출석을 보는 모달을 만든다

읽기 전용이다 — 멤버에게는 고칠 것이 없다. 아직 기록되지 않은 주차를
결석으로 쓰지 않고 '아직'으로 쓴다. 그건 안 일어난 일이지 빠진 게 아니고,
출석률의 분모도 같은 이유로 기록된 주차 수다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
```

FE 의 화면 커밋(Task 10·11·12)은 계약 PR(Task 1)과 **다른 PR** 이다. 계약은 BE 보다
먼저 머지되어야 하고 화면은 BE 가 머지된 뒤에 의미가 있다. Task 1 의 브랜치를
`feat/study-operations` 로, 화면을 `feat/study-operations-ui` 로 나눈다.

---

## 머지 전 체크리스트

- [ ] ① 의 FE 계약 PR([#22](https://github.com/jaram-plus/home-jaram-fe/pull/22))이 머지됐다
- [ ] ① 의 BE PR([#25](https://github.com/jaram-plus/home-jaram-be/pull/25))이 머지됐고 배포가 끝났다
- [ ] ① 의 이행 SQL 1단계를 운영 DB 에 돌렸고 `must_be_zero` 가 0 이었다
- [ ] ① 의 이행 SQL 2단계를 배포 뒤에 돌렸다
- [ ] ② 의 FE 계약 PR(Task 1)이 머지됐다
- [ ] ② 의 BE PR(Task 2~9)이 초록불이다 — 계약이 먼저 머지되어야 계약 테스트가 통과한다
- [ ] ② 의 BE PR 이 머지되고 배포가 끝났다
- [ ] ② 의 FE 화면 PR(Task 10~12)을 머지한다

**② 에는 돌릴 SQL 이 없다.** `study_attendance` 는 빈 테이블 신설이고
`study_week.taken_at` 은 nullable 이라 `ddl-auto: update` 가 둘 다 만든다.

## 실행 순서 요약

| 순서 | 과제 | 레포 | 산출물 |
|---|---|---|---|
| 1 | Task 1 | fe | 계약 PR (먼저 머지) |
| 2 | Task 2 | be | 출석 엔티티·`takenAt` |
| 3 | Task 3 | be | 편집 창 |
| 4 | Task 4 | be | 출석 쓰기 |
| 5 | Task 5 | be | 출석 읽기 · `isMember` |
| 6 | Task 6 | be | 주차 편집 |
| 7 | Task 7 | be | 스터디장 신청 목록 |
| 8 | Task 8 | be | 반려 신청 삭제 · `isApplicant` |
| 9 | Task 9 | be | `/my` 에 `pendingApplicants` (BE PR 여기까지) |
| 10 | Task 10 | fe | 배선 + '내 스터디' 화면 |
| 11 | Task 11 | fe | 관리하기 모달 |
| 12 | Task 12 | fe | 내 출석 모달 (FE 화면 PR 여기까지) |

Task 2~5 는 순서를 지켜야 한다(뒤가 앞의 타입을 쓴다). Task 6·7·8·9 는 서로 독립이라
순서를 바꿔도 된다 — Task 9 가 쓰는 `countByStudyIdAndStatus` 는 ① 이 이미 만들어
두었다. Task 10 은 Task 9 가, Task 11 은 Task 4~7 이, Task 12 는 Task 5 가
머지되어 있어야 화면이 실제로 돈다.

## 자체 검토

**스펙 대조.** §3 관계 → Task 10(화면이 두 배열에서 읽는다. 서버 일급 값이 아니다).
§4 출석 모델 → Task 2. §5 쓰기 모양 → Task 4.
§6 편집 창 → Task 3. §7 주차 편집 → Task 6. §8 반려 삭제 → Task 8. §9 화면 →
Task 10. §10 모달 셋 → Task 11(A·B)·Task 12(C). §11 엔드포인트 8개 → Task 4(1)·
Task 5(2)·Task 6(3)·Task 7(1)·Task 8(1). `/my` 의 `pendingApplicants` → Task 9.
§12 권한 → Task 5(`isMember`)·Task 8(`isApplicant`). §13 계약 → Task 1. §14 이행 없음 → 머지 전 체크리스트.
§15 테스트 → 각 과제의 테스트 단계. 빠진 요구는 없다.

**D26 이 §11 표에 8개로 적혀 있고 과제에도 8개가 있다.** `GET /{id}/applicants`,
`GET /{id}/attendance`, `PUT /{id}/weeks/{n}/attendance`, `GET /{id}/attendance/me`,
`POST /{id}/weeks`, `PUT /{id}/weeks/{n}`, `DELETE /{id}/weeks/{n}`,
`DELETE /applicants/{id}`.

**이름 일관성.** `AttendanceWindow.requireOpen(StudyWeek, boolean)` 이 Task 3 에서
정의되고 Task 4·6 에서 같은 시그니처로 불린다. `StudyAttendanceService.requireNotFinished`
는 `static` 이라 Task 6 의 `StudyWeekService` 가 타입 이름으로 부른다.
`memberIdsOf` 는 Task 4 가 package-private 으로 정의하고 Task 5 가 같은 패키지에서 쓴다.
`WeekEntry` 는 ① 이 만든 것을 Task 6 이 그대로 쓴다.

**테스트가 실제로 실패하는 지점.** Task 2·3 은 컴파일 실패로, Task 4~9 는 404
또는 단언 실패로 시작한다. 각 과제의 Step 2 에 그 문구를 적어 두었다.

**철회한 것.** 초안의 D23(`StudyRelation` 을 서버 일급 값으로)과 D24(`/my` 를
`MyStudyList{items[]}` 로 교체, `MyActivity`·`MyApp`·`MyStudy` 폐기)를 걷어냈다.
이번 단계가 ① 이 굳힌 계약 스키마를 깨는 곳은 한 군데도 없다 — `MyStudy` 에
`pendingApplicants` 가 붙는 것이 전부이고, 그것도 nullable 필드 추가다.
그 대가로 진행 중 카드에서 출석 숫자가 빠졌다(§9). 필요해지면 `weeksTaken`·
`weeksTotal`·`myAttended` 를 같은 방식으로 얹을 수 있고, 필드 추가라 계약이
깨지지 않는다.
