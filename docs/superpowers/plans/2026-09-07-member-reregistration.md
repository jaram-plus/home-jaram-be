# 학기 전환 재등록 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 학기가 넘어가면 활동 회원을 '재등록 필요'로 넘기고, 재등록하지 않은 회원과 탈퇴 후 6개월이 지난 회원의 개인정보를 자동으로 파기한다.

**Architecture:** `MemberStatus`에 `REREGISTER` 값을 더한다. 하루 한 번 도는 스윕이 학기 경계를 넘었는지 `admin_settings.lastRollover`로 판정해 전환과 파기를 함께 처리한다. 파기는 이력이 있으면 개인정보만 지우고 행을 남기며, 임원이 누르는 삭제도 같은 규칙을 쓴다.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Gradle, JPA + PostgreSQL(`ddl-auto: update`), JUnit 5 + AssertJ + RestAssured + Testcontainers. FE는 Vite + React(JSX), 테스트 러너 없음.

**Spec:** `docs/superpowers/specs/2026-09-07-member-reregistration-design.md`

## Global Constraints

- **선행 작업:** BE PR #8 (`feat/settings-period`)가 먼저 머지되어야 한다. `AdminSettings.autoTerm`과 학기 override가 거기 있다. 이 브랜치는 그 위에서 갈라져 있다.
- **저장소가 둘이다.** Task 10·15·16은 `/home/ksb/Dev/home-jaram/home-jaram-fe`에서, 나머지는 `/home/ksb/Dev/home-jaram/home-jaram-be`에서 커밋한다. 각 태스크에 명시했다.
- **`docs/api/openapi.yaml`은 FE 소유다.** BE 저장소의 사본 두 개(`docs/api/openapi.yaml`, `src/test/resources/openapi/openapi.yaml`)는 FE 저장소를 가리키는 심링크다. **BE에서 절대 편집하지 않는다.**
- **Lombok을 쓰지 않는다.** 접근자는 손으로 쓴다.
- **주석은 한국어**로, "왜"를 적는다. 코드가 이미 말하는 "무엇"은 적지 않는다.
- **날짜에 의존하는 계산은 `LocalDate`를 인자로 받는다.** 시계를 직접 읽으면 3월 1일과 9월 1일에만 깨지는 테스트가 된다 (`AdminSettingsPeriodTest`가 쓰는 방식).
- **커밋 메시지 꼬리말** (BE·FE 공통):
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
  ```
- **BE 테스트 실행:** `./gradlew test --tests '<클래스>'`. 통합 테스트는 `PostgresTest`를 상속하며 Testcontainers가 Postgres 16을 띄운다(Docker 필요).
- **FE 검증:** 테스트 러너가 없다. `npm run lint && npm run typecheck && npm run build` 셋이 전부 통과해야 한다. `pnpm`이 PATH에 없으면 `export PATH="/home/ksb/.nvm/versions/node/v20.20.2/bin:$PATH"` 후 `npm`을 쓴다.
- **FE 디자인 규칙:** `src/design-system`의 토큰·컴포넌트만 쓴다. 새 색·폰트·간격을 임의로 만들지 않는다. 그라데이션·이모지 금지. 문구는 한국어 존댓말.

---

### Task 1: `Semester` 값 객체

**Files:**
- Create: `src/main/java/com/jaram/be/admin/Semester.java`
- Test: `src/test/java/com/jaram/be/admin/SemesterTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `public record Semester(int year, int term) implements Comparable<Semester>`, `public static Semester autoAt(LocalDate on)`

**저장소:** BE

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/SemesterTest.java`:

```java
package com.jaram.be.admin;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학기 경계는 3월 1일과 9월 1일이다. 새해 첫날이 아니다.
 */
class SemesterTest {

    @Test
    void marchStartsFirstTerm() {
        assertThat(Semester.autoAt(LocalDate.of(2026, 3, 1))).isEqualTo(new Semester(2026, 1));
        assertThat(Semester.autoAt(LocalDate.of(2026, 8, 31))).isEqualTo(new Semester(2026, 1));
    }

    @Test
    void septemberStartsSecondTerm() {
        assertThat(Semester.autoAt(LocalDate.of(2026, 9, 1))).isEqualTo(new Semester(2026, 2));
        assertThat(Semester.autoAt(LocalDate.of(2026, 12, 31))).isEqualTo(new Semester(2026, 2));
    }

    /** 1~2월은 직전 2학기의 연장이라 학년도를 하나 물린다. */
    @Test
    void januaryBelongsToPreviousSecondTerm() {
        assertThat(Semester.autoAt(LocalDate.of(2027, 1, 1))).isEqualTo(new Semester(2026, 2));
        assertThat(Semester.autoAt(LocalDate.of(2027, 2, 28))).isEqualTo(new Semester(2026, 2));
    }

    /** 이 한 줄이 "새해 첫날에 전원이 재등록 대상이 된다"를 막는다. */
    @Test
    void newYearIsNotASemesterBoundary() {
        assertThat(Semester.autoAt(LocalDate.of(2027, 1, 1)))
                .isEqualTo(Semester.autoAt(LocalDate.of(2026, 12, 31)));
    }

    @Test
    void ordersByYearThenTerm() {
        assertThat(new Semester(2026, 2)).isGreaterThan(new Semester(2026, 1));
        assertThat(new Semester(2027, 1)).isGreaterThan(new Semester(2026, 2));
        assertThat(new Semester(2026, 1)).isEqualByComparingTo(new Semester(2026, 1));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.SemesterTest'`
Expected: 컴파일 실패 — `cannot find symbol: class Semester`

- [ ] **Step 3: 최소 구현을 쓴다**

`src/main/java/com/jaram/be/admin/Semester.java`:

```java
package com.jaram.be.admin;

import java.time.LocalDate;

/**
 * 학년도와 학기(1|2).
 *
 * 학기 경계는 3월 1일과 9월 1일이며 1월 1일이 아니다 — 1~2월은 직전 2학기의
 * 연장이라 학년도를 하나 물린다. 이걸 놓치면 새해 첫날이 학기 경계로 판정되어
 * 회원 전원이 재등록 대상이 된다.
 */
public record Semester(int year, int term) implements Comparable<Semester> {

    public static Semester autoAt(LocalDate on) {
        int month = on.getMonthValue();
        if (month >= 3 && month <= 8) return new Semester(on.getYear(), 1);
        if (month >= 9) return new Semester(on.getYear(), 2);
        return new Semester(on.getYear() - 1, 2);
    }

    @Override
    public int compareTo(Semester o) {
        return year != o.year ? Integer.compare(year, o.year) : Integer.compare(term, o.term);
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.SemesterTest'`
Expected: PASS (5개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/admin/Semester.java src/test/java/com/jaram/be/admin/SemesterTest.java
git commit -m "$(cat <<'EOF'
feat(admin): 학기를 값 객체로 비교 가능하게 한다

학기 경계는 3월 1일과 9월 1일이다. autoTerm 은 1월에 2를 돌려주는데 거기에
today.getYear() 를 붙이면 1월 1일이 경계가 되어 버린다 — 1~2월은 직전 2학기의
연장이므로 학년도를 하나 물린다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 2: `REREGISTER` 상태와 전환 대상 판정

**Files:**
- Modify: `src/main/java/com/jaram/be/member/MemberStatus.java`
- Modify: `src/main/java/com/jaram/be/member/Member.java`
- Test: `src/test/java/com/jaram/be/member/MemberRolloverTargetTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `MemberStatus.REREGISTER`, `Member.isRolloverTarget() -> boolean`, `Member.markReregistrationRequired() -> void`

**저장소:** BE

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/member/MemberRolloverTargetTest.java`:

```java
package com.jaram.be.member;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학기 전환 대상 판정. 휴학·OB·현직 임원은 면제한다 — 한 칸에 값 하나만
 * 들어가므로 "휴학이면서 재등록 필요"를 표현할 수 없다.
 */
class MemberRolloverTargetTest {

    private Member member() {
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        return m;
    }

    @Test
    void activeMemberIsTarget() {
        assertThat(member().isRolloverTarget()).isTrue();
    }

    @Test
    void onLeaveMemberIsExempt() {
        Member m = member();
        m.setStatus(MemberStatus.ON_LEAVE);
        assertThat(m.isRolloverTarget()).isFalse();
    }

    @Test
    void obIsExempt() {
        Member m = member();
        m.setGrade(MemberGrade.OB);
        assertThat(m.isRolloverTarget()).isFalse();
    }

    @Test
    void sittingOfficerIsExempt() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        assertThat(m.isRolloverTarget()).isFalse();
    }

    /** 임기가 끝난 전 임원은 다시 대상이다 — 이력만 남았을 뿐 현직이 아니다. */
    @Test
    void pastOfficerIsTargetAgain() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        m.endCurrentTerm(42);
        assertThat(m.isRolloverTarget()).isTrue();
    }

    /** 이미 재등록 필요인 회원을 두 번 넘기지 않는다. */
    @Test
    void alreadyRequiredIsNotTargetAgain() {
        Member m = member();
        m.markReregistrationRequired();
        assertThat(m.getStatus()).isEqualTo(MemberStatus.REREGISTER);
        assertThat(m.isRolloverTarget()).isFalse();
    }

    @Test
    void withdrawnIsExempt() {
        Member m = member();
        m.setStatus(MemberStatus.WITHDRAWN);
        assertThat(m.isRolloverTarget()).isFalse();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.member.MemberRolloverTargetTest'`
Expected: 컴파일 실패 — `cannot find symbol: method isRolloverTarget()`

- [ ] **Step 3: 최소 구현을 쓴다**

`MemberStatus.java`를 통째로 바꾼다:

```java
package com.jaram.be.member;

// 활동 상태 (승인축과 별개). 가입 시 SignupRequest.enrolled로 파생
// (true→ACTIVE, false→ON_LEAVE), 이후 admin이 변경. Wire = enum name (UPPER_CASE).
// REREGISTER는 학기 전환 스윕만 설정한다 — admin이 표에서 직접 고를 수 없다.
public enum MemberStatus { ACTIVE, ON_LEAVE, REREGISTER, WITHDRAWN }
```

`Member.java`의 `getAuthority()` 바로 아래에 더한다:

```java
    /** 학기 전환 대상. 휴학·OB·현직 임원은 면제한다. */
    public boolean isRolloverTarget() {
        return status == MemberStatus.ACTIVE
                && grade != MemberGrade.OB
                && currentTerm().isEmpty();
    }

    public void markReregistrationRequired() { this.status = MemberStatus.REREGISTER; }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.member.MemberRolloverTargetTest'`
Expected: PASS (7개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/member/MemberStatus.java src/main/java/com/jaram/be/member/Member.java src/test/java/com/jaram/be/member/MemberRolloverTargetTest.java
git commit -m "$(cat <<'EOF'
feat(member): 재등록 상태와 학기 전환 대상 판정을 더한다

MemberStatus 에 REREGISTER 를 더했다. 한 칸에 값 하나만 들어가므로 '휴학이면서
재등록 필요'를 쓸 수 없어, 휴학은 OB·현직 임원과 함께 전환에서 면제한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 3: 생애주기 칸과 전이 메서드

**Files:**
- Modify: `src/main/java/com/jaram/be/member/Member.java`
- Test: `src/test/java/com/jaram/be/member/MemberLifecycleFieldsTest.java`

**Interfaces:**
- Consumes: Task 2의 `MemberStatus.REREGISTER`, `markReregistrationRequired()`
- Produces: `Member.getReregisterRequestedAt() -> Instant`, `getWithdrawnAt() -> Instant`, `getPurgedAt() -> Instant`, `requestReregistration(Instant) -> void`, `completeReregistration() -> void`, `withdraw(Instant) -> void`, `hasHistory() -> boolean`, `purge(Instant) -> void`

**저장소:** BE

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/member/MemberLifecycleFieldsTest.java`:

```java
package com.jaram.be.member;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MemberLifecycleFieldsTest {

    private static final Instant T1 = Instant.parse("2026-09-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-05T00:00:00Z");

    private Member member() {
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setGen(41);
        m.setFaculty("컴퓨터학부");
        m.setPhone("010-1234-5678");
        m.setBio("안녕하세요");
        m.setGithubUrl("https://github.com/hong");
        m.setBlogUrl("https://hong.dev");
        return m;
    }

    /** 재신청은 멱등하다 — 두 번 눌러도 처음 시각이 남는다. */
    @Test
    void reregistrationRequestIsIdempotent() {
        Member m = member();
        m.markReregistrationRequired();
        m.requestReregistration(T1);
        m.requestReregistration(T2);
        assertThat(m.getReregisterRequestedAt()).isEqualTo(T1);
    }

    @Test
    void completingReregistrationReturnsToActive() {
        Member m = member();
        m.markReregistrationRequired();
        m.requestReregistration(T1);
        m.completeReregistration();
        assertThat(m.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(m.getReregisterRequestedAt()).isNull();
    }

    @Test
    void withdrawRecordsTime() {
        Member m = member();
        m.withdraw(T1);
        assertThat(m.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(m.getWithdrawnAt()).isEqualTo(T1);
    }

    @Test
    void hasHistoryFollowsTermsAndContributor() {
        Member m = member();
        assertThat(m.hasHistory()).isFalse();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        assertThat(m.hasHistory()).isTrue();
    }

    @Test
    void contributorAloneCountsAsHistory() {
        Member m = member();
        m.setContributor(true);
        assertThat(m.hasHistory()).isTrue();
    }

    @Test
    void purgeClearsPersonalDataButKeepsNameAndTerms() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        m.purge(T2);

        assertThat(m.getName()).isEqualTo("홍길동");
        assertThat(m.getGen()).isEqualTo(41);
        assertThat(m.getTerms()).hasSize(1);
        assertThat(m.getPurgedAt()).isEqualTo(T2);

        assertThat(m.getStudentId()).isNull();
        assertThat(m.getEmail()).isNull();
        assertThat(m.getPasswordHash()).isNull();
        assertThat(m.getPhone()).isNull();
        assertThat(m.getFaculty()).isNull();
        assertThat(m.getBio()).isNull();
        assertThat(m.getGithubUrl()).isNull();
        assertThat(m.getBlogUrl()).isNull();
    }

    @Test
    void freshMemberHasNoLifecycleTimestamps() {
        Member m = member();
        assertThat(m.getReregisterRequestedAt()).isNull();
        assertThat(m.getWithdrawnAt()).isNull();
        assertThat(m.getPurgedAt()).isNull();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.member.MemberLifecycleFieldsTest'`
Expected: 컴파일 실패 — `cannot find symbol: method requestReregistration(Instant)`

- [ ] **Step 3: 최소 구현을 쓴다**

`Member.java`의 `private Instant createdAt = Instant.now();` 위에 칸을 더한다:

```java
    // 생애주기. 전부 nullable 이며 스윕(MemberLifecycleService)과 본인 요청이 채운다.
    private Instant reregisterRequestedAt;   // null = 재등록 미신청
    private Instant withdrawnAt;             // 6개월 뒤 파기의 기준
    private Instant purgedAt;                // 개인정보 파기 시각. 이력만 남은 회원
```

Task 2에서 더한 `markReregistrationRequired()` 아래에 이어 쓴다:

```java
    public Instant getReregisterRequestedAt() { return reregisterRequestedAt; }
    public Instant getWithdrawnAt() { return withdrawnAt; }
    public Instant getPurgedAt() { return purgedAt; }

    /** 재등록 신청. 이미 신청했으면 시각을 덮지 않는다 — 다시 눌러도 처음 신청이 남는다. */
    public void requestReregistration(Instant at) {
        if (reregisterRequestedAt == null) reregisterRequestedAt = at;
    }

    public void completeReregistration() {
        this.status = MemberStatus.ACTIVE;
        this.reregisterRequestedAt = null;
    }

    public void withdraw(Instant at) {
        this.status = MemberStatus.WITHDRAWN;
        this.withdrawnAt = at;
    }

    /** 남길 이력이 있는가. 있으면 행을 지우지 않고 개인정보만 파기한다. */
    public boolean hasHistory() { return contributor || !terms.isEmpty(); }

    /**
     * 개인정보 파기. 이름·기수·임기 이력은 남는다 — 임기 기록과 출석·신청 기록의
     * 참조가 끊기지 않게 하는 것이 목적이다.
     *
     * email 이 비면 findByEmail 로 찾히지 않아 로그인이 막힌다. studentId·email 은
     * UNIQUE 지만 PostgreSQL 은 NULL 을 중복으로 보지 않아 여러 행이 비어 있어도 된다.
     */
    public void purge(Instant at) {
        this.studentId = null;
        this.email = null;
        this.passwordHash = null;
        this.phone = null;
        this.faculty = null;
        this.bio = null;
        this.githubUrl = null;
        this.blogUrl = null;
        this.purgedAt = at;
    }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.member.MemberLifecycleFieldsTest'`
Expected: PASS (7개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/member/Member.java src/test/java/com/jaram/be/member/MemberLifecycleFieldsTest.java
git commit -m "$(cat <<'EOF'
feat(member): 재등록·탈퇴·파기 시각과 전이를 더한다

파기는 이름과 임기 이력을 남기고 개인정보만 지운다. email 을 비우면 로그인이
자동으로 막히고, UNIQUE 제약은 PostgreSQL 이 NULL 을 중복으로 보지 않아 여러 행이
동시에 비어 있어도 된다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 4: `AdminSettings`에 마지막 전환 학기

**Files:**
- Modify: `src/main/java/com/jaram/be/admin/AdminSettings.java`
- Test: `src/test/java/com/jaram/be/admin/AdminSettingsRolloverTest.java`

**Interfaces:**
- Consumes: Task 1의 `Semester`
- Produces: `AdminSettings.lastRollover() -> Semester` (package-private, null 가능), `AdminSettings.setLastRollover(Semester) -> void` (package-private)

**저장소:** BE

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/AdminSettingsRolloverTest.java`:

```java
package com.jaram.be.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSettingsRolloverTest {

    /** 아직 한 번도 돌지 않았다는 뜻. 스윕은 이 경우 전환하지 않고 초기화만 한다. */
    @Test
    void defaultsHaveNoRollover() {
        assertThat(AdminSettings.defaults().lastRollover()).isNull();
    }

    @Test
    void rolloverRoundTrips() {
        AdminSettings s = AdminSettings.defaults();
        s.setLastRollover(new Semester(2026, 2));
        assertThat(s.lastRollover()).isEqualTo(new Semester(2026, 2));
    }

    @Test
    void rolloverIsOverwritable() {
        AdminSettings s = AdminSettings.defaults();
        s.setLastRollover(new Semester(2026, 2));
        s.setLastRollover(new Semester(2027, 1));
        assertThat(s.lastRollover()).isEqualTo(new Semester(2027, 1));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.AdminSettingsRolloverTest'`
Expected: 컴파일 실패 — `cannot find symbol: method lastRollover()`

- [ ] **Step 3: 최소 구현을 쓴다**

`AdminSettings.java`의 `private boolean autoPromote;` 위에 칸을 더한다:

```java
    // 마지막으로 학기 전환 스윕을 실행한 학기. 둘 다 null 이면 아직 한 번도 돌지 않았다.
    private Integer lastRolloverYear;
    private Integer lastRolloverTerm;
```

`defaults()` 안, `s.semesterTermSetOn = null;` 다음 줄에 더한다:

```java
        s.lastRolloverYear = null;
        s.lastRolloverTerm = null;
```

`effectiveTerm(LocalDate)` 아래에 더한다:

```java
    /** 마지막으로 전환을 실행한 학기. null 이면 아직 한 번도 돌지 않았다. */
    Semester lastRollover() {
        if (lastRolloverYear == null || lastRolloverTerm == null) return null;
        return new Semester(lastRolloverYear, lastRolloverTerm);
    }

    void setLastRollover(Semester s) {
        this.lastRolloverYear = s.year();
        this.lastRolloverTerm = s.term();
    }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.AdminSettingsRolloverTest'`
Expected: PASS (3개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/admin/AdminSettings.java src/test/java/com/jaram/be/admin/AdminSettingsRolloverTest.java
git commit -m "$(cat <<'EOF'
feat(admin): 마지막으로 전환한 학기를 설정에 남긴다

스윕이 멱등해진다. 같은 학기에 여러 번 돌아도 한 번만 전환하고, 서버가 며칠
꺼져 있었어도 켜질 때 밀린 전환을 따라잡는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 5: `MemberPurger` — 파기와 참조 정리

**Files:**
- Create: `src/main/java/com/jaram/be/admin/MemberPurger.java`
- Modify: `src/main/java/com/jaram/be/schedule/ScheduleRepository.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarRepository.java`
- Modify: `src/main/java/com/jaram/be/seminar/Seminar.java`
- Test: `src/test/java/com/jaram/be/admin/MemberPurgerTest.java`

**Interfaces:**
- Consumes: Task 3의 `Member.hasHistory()`, `Member.purge(Instant)`
- Produces: `MemberPurger.purge(Member m, Instant at) -> MemberPurger.Outcome`, `enum Outcome { PURGED, DELETED, SKIPPED_LEADER }`; `ScheduleRepository.findBySlotsMemberId(String) -> List<Schedule>`; `SeminarRepository.findByCreatedById(String) -> List<Seminar>`; `Seminar.detachCreator() -> void`

**저장소:** BE

이 프로젝트는 회원을 가리키는 참조 대부분이 FK 없는 `varchar`라 DB가 무결성을 막아 주지 않는다. 기존 `AdminBatchExecutor.delete`는 `attendance`와 `study_application`만 지우고 `schedule_slot.memberId`·`seminar.createdById`는 손대지 않는다. 스윕이 돌기 시작하면 끊어진 참조가 정기적으로 생기므로 여기서 함께 정리한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/MemberPurgerTest.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.study.Study;
import com.jaram.be.study.StudyRepository;
import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MemberPurgerTest extends PostgresTest {

    private static final Instant AT = Instant.parse("2027-03-01T00:00:00Z");

    @Autowired MemberPurger purger;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;

    @BeforeEach void setup() {
        studies.deleteAll();
        members.deleteAll();
    }

    private Member saved(String name, String studentId, String email) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setGen(41);
        return members.save(m);
    }

    /** 이력이 없는 회원은 행째 사라진다. */
    @Test
    void memberWithoutHistoryIsDeleted() {
        Member m = saved("김없음", "2024011111", "none@hanyang.ac.kr");
        assertThat(purger.purge(m, AT)).isEqualTo(MemberPurger.Outcome.DELETED);
        assertThat(members.findById(m.getId())).isEmpty();
    }

    /** 임기 이력이 있으면 행이 남고 개인정보만 지워진다. */
    @Test
    void memberWithTermIsPurgedNotDeleted() {
        Member m = saved("이임원", "2022022222", "exec@hanyang.ac.kr");
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        members.save(m);

        assertThat(purger.purge(m, AT)).isEqualTo(MemberPurger.Outcome.PURGED);

        Member found = members.findById(m.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("이임원");
        assertThat(found.getTerms()).hasSize(1);
        assertThat(found.getEmail()).isNull();
        assertThat(found.getPurgedAt()).isEqualTo(AT);
    }

    /** 스터디 리더는 건너뛴다 — 지금도 삭제가 막혀 있다. */
    @Test
    void studyLeaderIsSkipped() {
        Member m = saved("박리더", "2023033333", "leader@hanyang.ac.kr");
        studies.save(Study.create("자바 스터디", List.of("백엔드"), 6,
                "월 19시", "2026-2학기", "온라인", "함께 읽어요", m.getId()));

        assertThat(purger.purge(m, AT)).isEqualTo(MemberPurger.Outcome.SKIPPED_LEADER);
        assertThat(members.findById(m.getId())).isPresent();
        assertThat(members.findById(m.getId()).orElseThrow().getEmail()).isNotNull();
    }

    /** 두 번 파기해도 결과가 같다 — 스윕이 매일 도는 것을 견뎌야 한다. */
    @Test
    void purgingTwiceIsHarmless() {
        Member m = saved("최중복", "2022044444", "dup@hanyang.ac.kr");
        m.setContributor(true);
        members.save(m);

        assertThat(purger.purge(m, AT)).isEqualTo(MemberPurger.Outcome.PURGED);
        Member again = members.findById(m.getId()).orElseThrow();
        assertThat(purger.purge(again, AT)).isEqualTo(MemberPurger.Outcome.PURGED);
        assertThat(members.findById(m.getId()).orElseThrow().getName()).isEqualTo("최중복");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.MemberPurgerTest'`
Expected: 컴파일 실패 — `cannot find symbol: class MemberPurger`

- [ ] **Step 3: 최소 구현을 쓴다**

`ScheduleRepository.java`에 한 줄 더한다:

```java
    // 회원 삭제 시 슬롯의 역참조를 끊기 위해. 슬롯은 Schedule을 통해서만 저장한다.
    List<Schedule> findBySlotsMemberId(String memberId);
```

`SeminarRepository.java`에 한 줄 더한다:

```java
    // 회원 삭제 시 개설자 역참조를 끊기 위해. 세미나 자체는 남긴다.
    List<Seminar> findByCreatedById(String createdById);
```

`Seminar.java`의 `getCreatedById()` 아래에 더한다:

```java
    /** 개설자만 떼고 세미나는 남긴다 — 연 사람이 사라져도 기록은 남아야 한다. */
    public void detachCreator() { this.createdById = null; }
```

`src/main/java/com/jaram/be/admin/MemberPurger.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.schedule.Schedule;
import com.jaram.be.schedule.ScheduleRepository;
import com.jaram.be.schedule.ScheduleSlot;
import com.jaram.be.seminar.AttendanceRepository;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.study.StudyApplicationRepository;
import com.jaram.be.study.StudyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 회원 정리의 단일 규칙. 스윕(자동)과 임원의 삭제 버튼(수동)이 같은 것을 쓴다 —
 * 어느 쪽이 돌았느냐에 따라 기여자 목록이 달라지면 안 된다.
 *
 * 이 프로젝트는 회원을 가리키는 참조 대부분이 FK 없는 varchar 라 DB 가 막아 주지
 * 않는다. 행을 지울 때 끊어진 참조가 남지 않도록 여기서 손으로 정리한다.
 */
@Component
public class MemberPurger {

    public enum Outcome { PURGED, DELETED, SKIPPED_LEADER }

    private final MemberRepository members;
    private final StudyRepository studies;
    private final StudyApplicationRepository applications;
    private final AttendanceRepository attendances;
    private final ScheduleRepository schedules;
    private final SeminarRepository seminars;

    public MemberPurger(MemberRepository members, StudyRepository studies,
                        StudyApplicationRepository applications, AttendanceRepository attendances,
                        ScheduleRepository schedules, SeminarRepository seminars) {
        this.members = members;
        this.studies = studies;
        this.applications = applications;
        this.attendances = attendances;
        this.schedules = schedules;
        this.seminars = seminars;
    }

    @Transactional
    public Outcome purge(Member m, Instant at) {
        if (!studies.findByLeaderIdOrderByCreatedAtDesc(m.getId()).isEmpty()) {
            return Outcome.SKIPPED_LEADER;
        }
        if (m.hasHistory()) {
            m.purge(at);
            members.save(m);
            return Outcome.PURGED;
        }
        detachReferences(m.getId());
        applications.deleteAll(applications.findByApplicantIdOrderByCreatedAtDesc(m.getId()));
        attendances.deleteAll(attendances.findByMemberId(m.getId()));
        members.deleteById(m.getId());
        return Outcome.DELETED;
    }

    /** 행을 지울 때만 필요하다. 파기(행 보존)는 참조가 끊기지 않는다. */
    private void detachReferences(String memberId) {
        for (Schedule s : schedules.findBySlotsMemberId(memberId)) {
            s.getSlots().stream()
                    .filter(slot -> memberId.equals(slot.getMemberId()))
                    .forEach(ScheduleSlot::release);
            schedules.save(s);
        }
        for (Seminar s : seminars.findByCreatedById(memberId)) {
            s.detachCreator();
            seminars.save(s);
        }
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.MemberPurgerTest'`
Expected: PASS (4개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/admin/MemberPurger.java src/main/java/com/jaram/be/schedule/ScheduleRepository.java src/main/java/com/jaram/be/seminar/SeminarRepository.java src/main/java/com/jaram/be/seminar/Seminar.java src/test/java/com/jaram/be/admin/MemberPurgerTest.java
git commit -m "$(cat <<'EOF'
feat(admin): 회원 파기 규칙을 한 곳으로 모은다

이력이 있으면 개인정보만 지우고 행을 남기고, 없으면 행을 지운다. 행을 지울 때는
schedule_slot.memberId 와 seminar.createdById 도 끊는다 — 회원 참조 대부분이 FK 가
아니라 varchar 라 DB 가 막아 주지 않고, 지금까지 이 두 곳은 정리되지 않았다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 6: 스윕 — 학기 전환

**Files:**
- Create: `src/main/java/com/jaram/be/admin/MemberLifecycleService.java`
- Modify: `src/main/java/com/jaram/be/admin/AdminSettingsRepository.java` (변경 없음 — 기존 `findById` 사용)
- Test: `src/test/java/com/jaram/be/admin/MemberRolloverSweepTest.java`

**Interfaces:**
- Consumes: Task 1의 `Semester.autoAt`, Task 2의 `Member.isRolloverTarget()`·`markReregistrationRequired()`, Task 4의 `AdminSettings.lastRollover()`·`setLastRollover(Semester)`
- Produces: `MemberLifecycleService.sweep(LocalDate today) -> void` (package-private, 테스트 진입점)

**저장소:** BE

파기는 아직 붙이지 않는다. Task 7이 이어 붙인다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/MemberRolloverSweepTest.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MemberRolloverSweepTest extends PostgresTest {

    private static final LocalDate OCT_2026 = LocalDate.of(2026, 10, 1);
    private static final LocalDate MAR_2027 = LocalDate.of(2027, 3, 1);
    private static final LocalDate JAN_2027 = LocalDate.of(2027, 1, 1);

    @Autowired MemberLifecycleService lifecycle;
    @Autowired MemberRepository members;
    @Autowired AdminSettingsRepository settings;

    @BeforeEach void setup() {
        members.deleteAll();
        settings.deleteAll();
    }

    private Member active(String name, String studentId, String email) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setStatus(MemberStatus.ACTIVE);
        return members.save(m);
    }

    private MemberStatus statusOf(Member m) {
        return members.findById(m.getId()).orElseThrow().getStatus();
    }

    /** 첫 실행은 초기화만 한다 — 이게 없으면 배포 직후 전원이 재등록 대상이 된다. */
    @Test
    void firstSweepInitialisesWithoutRolling() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        assertThat(statusOf(m)).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void nextSemesterRollsActiveMembers() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);   // 초기화
        lifecycle.sweep(MAR_2027);   // 2026-2 → 2027-1
        assertThat(statusOf(m)).isEqualTo(MemberStatus.REREGISTER);
    }

    @Test
    void exemptMembersAreUntouched() {
        Member onLeave = active("휴학생", "2023011111", "leave@hanyang.ac.kr");
        onLeave.setStatus(MemberStatus.ON_LEAVE);
        members.save(onLeave);

        Member ob = active("졸업생", "2019022222", "ob@hanyang.ac.kr");
        ob.setGrade(MemberGrade.OB);
        members.save(ob);

        Member officer = active("임원", "2022033333", "exec@hanyang.ac.kr");
        officer.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        members.save(officer);

        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027);

        assertThat(statusOf(onLeave)).isEqualTo(MemberStatus.ON_LEAVE);
        assertThat(statusOf(ob)).isEqualTo(MemberStatus.ACTIVE);
        assertThat(statusOf(officer)).isEqualTo(MemberStatus.ACTIVE);
    }

    /** 새해 첫날은 학기 경계가 아니다. */
    @Test
    void newYearDoesNotRoll() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(JAN_2027);
        assertThat(statusOf(m)).isEqualTo(MemberStatus.ACTIVE);
    }

    /** 같은 학기에 여러 번 돌아도 한 번만 전환한다. */
    @Test
    void sweepIsIdempotentWithinASemester() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027);
        lifecycle.sweep(MAR_2027.plusDays(10));

        Member found = members.findById(m.getId()).orElseThrow();
        found.completeReregistration();
        members.save(found);

        lifecycle.sweep(MAR_2027.plusDays(20));
        assertThat(statusOf(m)).isEqualTo(MemberStatus.ACTIVE);
    }

    /** 서버가 한 학기 내내 꺼져 있었어도 켜질 때 한 번에 따라잡는다. */
    @Test
    void catchesUpAfterSkippedSemesters() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(LocalDate.of(2028, 3, 1));
        assertThat(statusOf(m)).isEqualTo(MemberStatus.REREGISTER);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.MemberRolloverSweepTest'`
Expected: 컴파일 실패 — `cannot find symbol: class MemberLifecycleService`

- [ ] **Step 3: 최소 구현을 쓴다**

`src/main/java/com/jaram/be/admin/MemberLifecycleService.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 학기 전환과 파기를 맡는 스윕.
 *
 * 학기 판정은 Semester.autoAt 만 쓴다 — 설정 탭의 학기 override 는 표시와 임기
 * 전환에만 쓰고 여기서는 보지 않는다. 4월에 실수로 2학기를 눌렀다고 사람이
 * 지워지면 안 된다.
 */
@Service
public class MemberLifecycleService {

    private final MemberRepository members;
    private final AdminSettingsRepository settings;

    public MemberLifecycleService(MemberRepository members, AdminSettingsRepository settings) {
        this.members = members;
        this.settings = settings;
    }

    @Transactional
    void sweep(LocalDate today) {
        Semester now = Semester.autoAt(today);
        AdminSettings s = settings.findById(AdminSettings.SINGLETON_ID)
                .orElseGet(() -> settings.save(AdminSettings.defaults()));
        Semester last = s.lastRollover();

        // 마지막 전환 학기를 모르는 것은 '지금 전환해야 한다'는 뜻이 아니다.
        // 초기화만 하고 다음 학기 경계부터 규칙이 돈다.
        if (last == null) {
            s.setLastRollover(now);
            return;
        }
        if (now.compareTo(last) <= 0) return;   // != 가 아니다 — 시계가 뒤로 가도 되돌리지 않는다

        for (Member m : members.findAll()) {
            if (m.isRolloverTarget()) m.markReregistrationRequired();
        }
        s.setLastRollover(now);
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.MemberRolloverSweepTest'`
Expected: PASS (6개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/admin/MemberLifecycleService.java src/test/java/com/jaram/be/admin/MemberRolloverSweepTest.java
git commit -m "$(cat <<'EOF'
feat(admin): 학기가 넘어가면 활동 회원을 재등록 필요로 넘긴다

첫 실행은 초기화만 한다. lastRollover 가 비어 있는 것을 '지금 전환해야 한다'로
읽으면 배포 직후 전원이 재등록 대상이 된다.

비교는 != 가 아니라 > 다. 시계가 뒤로 가도 전환을 되돌리지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 7: 스윕 — 파기 연결

**Files:**
- Modify: `src/main/java/com/jaram/be/admin/MemberLifecycleService.java`
- Test: `src/test/java/com/jaram/be/admin/MemberPurgeSweepTest.java`

**Interfaces:**
- Consumes: Task 5의 `MemberPurger.purge(Member, Instant)`, Task 6의 `MemberLifecycleService.sweep(LocalDate)`
- Produces: 없음 (기존 `sweep` 확장)

**저장소:** BE

전환 시 `REREGISTER` 회원을 **먼저** 파기하고 그 다음에 새 대상을 넘긴다. 그래야 "한 학기를 더 못 넘긴다"가 회원마다 학기를 저장하지 않고도 성립한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/MemberPurgeSweepTest.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MemberPurgeSweepTest extends PostgresTest {

    private static final LocalDate OCT_2026 = LocalDate.of(2026, 10, 1);
    private static final LocalDate MAR_2027 = LocalDate.of(2027, 3, 1);
    private static final LocalDate SEP_2027 = LocalDate.of(2027, 9, 1);

    @Autowired MemberLifecycleService lifecycle;
    @Autowired MemberRepository members;
    @Autowired AdminSettingsRepository settings;

    @BeforeEach void setup() {
        members.deleteAll();
        settings.deleteAll();
    }

    private Member active(String name, String studentId, String email) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setStatus(MemberStatus.ACTIVE);
        return members.save(m);
    }

    private static Instant atStartOf(LocalDate d) {
        return d.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    /** 재등록하지 않고 한 학기를 더 넘기면 사라진다. */
    @Test
    void unrenewedMemberIsPurgedAtNextRollover() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);   // 초기화
        lifecycle.sweep(MAR_2027);   // REREGISTER 로
        assertThat(members.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.REREGISTER);

        lifecycle.sweep(SEP_2027);   // 파기
        assertThat(members.findById(m.getId())).isEmpty();
    }

    /** 방금 넘어간 회원이 같은 스윕에서 지워지면 안 된다 — 파기가 전환보다 먼저다. */
    @Test
    void memberRolledInThisSweepSurvivesIt() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027);
        assertThat(members.findById(m.getId())).isPresent();
    }

    /** 재등록을 마친 회원은 다음 전환에서 다시 한 학기를 얻는다. */
    @Test
    void renewedMemberIsNotPurged() {
        Member m = active("홍길동", "2023012345", "hong@hanyang.ac.kr");
        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027);

        Member found = members.findById(m.getId()).orElseThrow();
        found.completeReregistration();
        members.save(found);

        lifecycle.sweep(SEP_2027);
        assertThat(members.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.REREGISTER);
    }

    /** 탈퇴 6개월 하루 전에는 살아 있다. */
    @Test
    void withdrawnMemberSurvivesJustBeforeSixMonths() {
        Member m = active("이탈퇴", "2023099999", "bye@hanyang.ac.kr");
        m.withdraw(atStartOf(MAR_2027));
        members.save(m);

        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027.plusMonths(6).minusDays(1));
        assertThat(members.findById(m.getId())).isPresent();
    }

    /** 6개월이 되는 날 파기된다. */
    @Test
    void withdrawnMemberIsPurgedAtSixMonths() {
        Member m = active("이탈퇴", "2023099999", "bye@hanyang.ac.kr");
        m.withdraw(atStartOf(MAR_2027));
        members.save(m);

        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027.plusMonths(6));
        assertThat(members.findById(m.getId())).isEmpty();
    }

    /** 이미 파기된 회원을 다시 건드리지 않는다. */
    @Test
    void alreadyPurgedMemberIsLeftAlone() {
        Member m = active("박기여", "2021088888", "contrib@hanyang.ac.kr");
        m.setContributor(true);
        m.withdraw(atStartOf(MAR_2027));
        members.save(m);

        lifecycle.sweep(OCT_2026);
        lifecycle.sweep(MAR_2027.plusMonths(6));
        Instant first = members.findById(m.getId()).orElseThrow().getPurgedAt();
        assertThat(first).isNotNull();

        lifecycle.sweep(MAR_2027.plusMonths(7));
        assertThat(members.findById(m.getId()).orElseThrow().getPurgedAt()).isEqualTo(first);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.MemberPurgeSweepTest'`
Expected: FAIL — `unrenewedMemberIsPurgedAtNextRollover`에서 회원이 남아 있다

- [ ] **Step 3: 최소 구현을 쓴다**

`MemberLifecycleService.java`를 통째로 바꾼다:

```java
package com.jaram.be.admin;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 학기 전환과 파기를 맡는 스윕.
 *
 * 학기 판정은 Semester.autoAt 만 쓴다 — 설정 탭의 학기 override 는 표시와 임기
 * 전환에만 쓰고 여기서는 보지 않는다. 4월에 실수로 2학기를 눌렀다고 사람이
 * 지워지면 안 된다.
 */
@Service
public class MemberLifecycleService {

    private static final int WITHDRAWAL_RETENTION_MONTHS = 6;

    private final MemberRepository members;
    private final AdminSettingsRepository settings;
    private final MemberPurger purger;

    public MemberLifecycleService(MemberRepository members, AdminSettingsRepository settings,
                                  MemberPurger purger) {
        this.members = members;
        this.settings = settings;
        this.purger = purger;
    }

    @Transactional
    void sweep(LocalDate today) {
        rollover(today);
        purgeWithdrawn(today);
    }

    private void rollover(LocalDate today) {
        Semester now = Semester.autoAt(today);
        AdminSettings s = settings.findById(AdminSettings.SINGLETON_ID)
                .orElseGet(() -> settings.save(AdminSettings.defaults()));
        Semester last = s.lastRollover();

        // 마지막 전환 학기를 모르는 것은 '지금 전환해야 한다'는 뜻이 아니다.
        // 초기화만 하고 다음 학기 경계부터 규칙이 돈다.
        if (last == null) {
            s.setLastRollover(now);
            return;
        }
        if (now.compareTo(last) <= 0) return;   // != 가 아니다 — 시계가 뒤로 가도 되돌리지 않는다

        Instant at = atStartOf(today);
        // 파기가 전환보다 먼저다. 그래야 '한 학기를 더 못 넘긴다'가 회원마다 학기를
        // 저장하지 않고도 성립하고, 이번에 넘어간 회원이 같은 스윕에서 지워지지 않는다.
        for (Member m : List.copyOf(members.findAll())) {
            if (m.getStatus() == MemberStatus.REREGISTER) purger.purge(m, at);
        }
        for (Member m : members.findAll()) {
            if (m.isRolloverTarget()) m.markReregistrationRequired();
        }
        s.setLastRollover(now);
    }

    private void purgeWithdrawn(LocalDate today) {
        Instant cutoff = atStartOf(today.minusMonths(WITHDRAWAL_RETENTION_MONTHS));
        Instant at = atStartOf(today);
        for (Member m : List.copyOf(members.findAll())) {
            if (m.getStatus() != MemberStatus.WITHDRAWN) continue;
            if (m.getPurgedAt() != null) continue;
            if (m.getWithdrawnAt() == null || m.getWithdrawnAt().isAfter(cutoff)) continue;
            purger.purge(m, at);
        }
    }

    private static Instant atStartOf(LocalDate d) {
        return d.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.MemberPurgeSweepTest' --tests 'com.jaram.be.admin.MemberRolloverSweepTest'`
Expected: PASS (12개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/admin/MemberLifecycleService.java src/test/java/com/jaram/be/admin/MemberPurgeSweepTest.java
git commit -m "$(cat <<'EOF'
feat(admin): 스윕이 재등록 미이행과 탈퇴 6개월을 파기한다

전환 시 파기를 먼저 하고 그 다음에 새 대상을 넘긴다. 그래야 '한 학기를 더 못
넘긴다'가 회원마다 등록 학기를 저장하지 않고도 성립하고, 이번에 넘어간 회원이
같은 스윕에서 지워지지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 8: 스케줄러 배선

**Files:**
- Modify: `src/main/java/com/jaram/be/JaramBeApplication.java`
- Modify: `src/main/java/com/jaram/be/admin/MemberLifecycleService.java`
- Test: `src/test/java/com/jaram/be/admin/MemberLifecycleScheduleTest.java`

**Interfaces:**
- Consumes: Task 7의 `sweep(LocalDate)`
- Produces: `MemberLifecycleService.sweepToday() -> void` (`@Scheduled`)

**저장소:** BE

이 프로젝트의 첫 스케줄러다. 실제로 일하는 건 1년에 두 번뿐이고 나머지 날은 설정 로우 한 줄을 읽고 끝난다. 주기를 정하는 건 탈퇴 6개월 파기 쪽이며, 그 정밀도의 하한이 하루다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/MemberLifecycleScheduleTest.java`:

```java
package com.jaram.be.admin;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄 배선은 스프링이 뜬 뒤에야 도는 코드라 통합 테스트로 잡기 어렵다.
 * 애노테이션이 붙어 있는지, 주기가 하루인지만 여기서 못 박는다.
 */
class MemberLifecycleScheduleTest {

    @Test
    void sweepRunsOnceADay() throws NoSuchMethodException {
        Method m = MemberLifecycleService.class.getMethod("sweepToday");
        Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 0 4 * * *");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.MemberLifecycleScheduleTest'`
Expected: FAIL — `NoSuchMethodException: sweepToday`

- [ ] **Step 3: 최소 구현을 쓴다**

`JaramBeApplication.java`에 `@EnableScheduling`을 더한다:

```java
package com.jaram.be;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // MemberLifecycleService.sweepToday
public class JaramBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(JaramBeApplication.class, args);
    }
}
```

> 기존 파일에 다른 애노테이션이나 코드가 있으면 지우지 말고 `@EnableScheduling`만 더한다.

`MemberLifecycleService.java`의 `sweep(LocalDate today)` **위에** 더한다:

```java
    /**
     * 하루 한 번. 학기 전환이 실제로 일하는 건 1년에 두 번뿐이고 나머지 날은 설정
     * 로우 한 줄을 읽고 끝난다. 주기를 정하는 건 탈퇴 6개월 파기 쪽이다 — 주 1회로
     * 늘리면 파기가 최대 7일 늦어진다.
     *
     * 인스턴스가 여럿이면 같은 날 여러 번 돌 수 있지만 lastRollover 비교가 멱등해
     * 무해하다. 서버가 며칠 꺼져 있었어도 켜질 때 밀린 전환을 따라잡는다.
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void sweepToday() {
        sweep(LocalDate.now());
    }
```

import를 더한다: `import org.springframework.scheduling.annotation.Scheduled;`

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.MemberLifecycleScheduleTest' --tests 'com.jaram.be.JaramBeApplicationTests'`
Expected: PASS (2개) — 컨텍스트가 뜨고 스케줄이 배선된다

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/JaramBeApplication.java src/main/java/com/jaram/be/admin/MemberLifecycleService.java src/test/java/com/jaram/be/admin/MemberLifecycleScheduleTest.java
git commit -m "$(cat <<'EOF'
feat(admin): 스윕을 하루 한 번 돌린다

이 프로젝트의 첫 스케줄러다. 학기 전환은 1년에 두 번만 일하지만, 탈퇴 6개월
파기가 매일 대상을 만든다 — 주기를 정하는 건 그쪽이고 정밀도의 하한이 하루다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 9: 임원의 삭제도 같은 규칙으로

**Files:**
- Modify: `src/main/java/com/jaram/be/admin/AdminBatchExecutor.java`
- Test: `src/test/java/com/jaram/be/admin/AdminMemberDeleteRuleTest.java`

**Interfaces:**
- Consumes: Task 5의 `MemberPurger`, Task 2의 `MemberStatus.REREGISTER`
- Produces: 없음 (기존 동작 변경)

**저장소:** BE

**기존 동작의 변경이다.** 지금까지 임기 이력이 있는 회원을 삭제하면 이력까지 사라졌다. 그대로 두면 "임원이 누른 삭제"와 "스윕이 한 파기"가 같은 이름으로 다르게 동작한다. 함께, 상태 select에서 `REREGISTER`를 직접 고르지 못하게 막는다 — 손으로 고르면 스윕과 어긋난 상태가 생긴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/AdminMemberDeleteRuleTest.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.admin.dto.AdminBatchRequest;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AdminMemberDeleteRuleTest extends PostgresTest {

    @Autowired AdminBatchExecutor executor;
    @Autowired MemberRepository members;

    @BeforeEach void setup() { members.deleteAll(); }

    private Member saved(String name, String studentId, String email) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        return members.save(m);
    }

    /** 임기 이력이 있으면 임원이 눌러도 이력이 남는다 — 스윕의 파기와 같은 규칙이다. */
    @Test
    void deletingMemberWithHistoryPurgesInstead() {
        Member m = saved("이임원", "2022022222", "exec@hanyang.ac.kr");
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        members.save(m);

        executor.deleteRow(AdminResource.members, m.getId());

        Member found = members.findById(m.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("이임원");
        assertThat(found.getEmail()).isNull();
        assertThat(found.getPurgedAt()).isNotNull();
    }

    @Test
    void deletingMemberWithoutHistoryRemovesTheRow() {
        Member m = saved("김없음", "2024011111", "none@hanyang.ac.kr");
        executor.deleteRow(AdminResource.members, m.getId());
        assertThat(members.findById(m.getId())).isEmpty();
    }

    /** 재등록 상태는 스윕만 설정한다. 사람이 표에서 고르면 스윕과 어긋난다. */
    @Test
    void statusCannotBeSetToReregisterByHand() {
        Member m = saved("홍길동", "2023012345", "hong@hanyang.ac.kr");
        AdminBatchRequest.Update u = new AdminBatchRequest.Update(
                m.getId(), m.getVersion(), Map.of("status", "REREGISTER"));

        AdminBatchExecutor.UpdateOutcome outcome = executor.updateRow(AdminResource.members, u);

        assertThat(outcome).isInstanceOf(AdminBatchExecutor.Invalid.class);
        assertThat(((AdminBatchExecutor.Invalid) outcome).fieldErrors()).containsKey("status");
        assertThat(members.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void otherStatusesStillWork() {
        Member m = saved("홍길동", "2023012345", "hong@hanyang.ac.kr");
        AdminBatchRequest.Update u = new AdminBatchRequest.Update(
                m.getId(), m.getVersion(), Map.of("status", "ON_LEAVE"));

        assertThat(executor.updateRow(AdminResource.members, u))
                .isInstanceOf(AdminBatchExecutor.Applied.class);
        assertThat(members.findById(m.getId()).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.ON_LEAVE);
    }
}
```

> `AdminBatchRequest.Update`의 생성자 인자 순서가 다르면 `src/main/java/com/jaram/be/admin/dto/AdminBatchRequest.java`를 열어 실제 시그니처에 맞춘다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.AdminMemberDeleteRuleTest'`
Expected: FAIL — `deletingMemberWithHistoryPurgesInstead`에서 회원이 사라져 있다

- [ ] **Step 3: 최소 구현을 쓴다**

`AdminBatchExecutor.java`에 생성자 주입을 더한다. 필드에:

```java
    private final MemberPurger purger;
```

생성자 파라미터 끝에 `MemberPurger purger`를 더하고 본문에 `this.purger = purger;`를 더한다.

`delete` 메서드의 `case members ->` 블록을 통째로 바꾼다:

```java
            case members -> {
                Member m = members.findById(id).orElse(null);
                if (m == null) { errors.put("id", "대상을 찾을 수 없습니다."); return errors; }
                // 스윕의 파기와 같은 규칙을 쓴다 — 어느 쪽이 돌았느냐에 따라
                // 기여자 목록이 달라지면 안 된다.
                if (purger.purge(m, Instant.now()) == MemberPurger.Outcome.SKIPPED_LEADER) {
                    errors.put("id", "스터디 리더인 회원은 삭제할 수 없습니다.");
                }
            }
```

`updateMember`의 `case "status"` 줄을 바꾼다:

```java
                case "status" -> enumField(MemberStatus.class, v, errors, k, m::setStatus, actions, false);
```

를 아래로:

```java
                case "status" -> {
                    // 재등록 상태는 학기 전환 스윕만 설정한다. 손으로 고르면 스윕과 어긋난다.
                    if (MemberStatus.REREGISTER.name().equals(String.valueOf(v))) {
                        errors.put(k, "재등록 상태는 직접 지정할 수 없습니다.");
                    } else {
                        enumField(MemberStatus.class, v, errors, k, m::setStatus, actions, false);
                    }
                }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.AdminMemberDeleteRuleTest' --tests 'com.jaram.be.admin.AdminResourceTest'`
Expected: PASS. `AdminResourceTest`가 "임기 있는 회원 삭제 시 행이 사라진다"를 기대하고 있으면 그 테스트를 새 규칙(파기)에 맞게 고친다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/admin/AdminBatchExecutor.java src/test/java/com/jaram/be/admin/AdminMemberDeleteRuleTest.java src/test/java/com/jaram/be/admin/AdminResourceTest.java
git commit -m "$(cat <<'EOF'
fix(admin): 임원이 누르는 삭제도 스윕과 같은 규칙을 쓴다

기존 동작의 변경이다. 지금까지는 임기 이력이 있는 회원을 삭제하면 이력까지
사라졌다. 규칙이 둘이면 어느 쪽이 돌았느냐에 따라 기여자 목록이 달라진다.

상태 칸에서 재등록을 직접 고르는 것도 막았다. 스윕만 설정하는 값이라 손으로
넣으면 등록 시점과 어긋난다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 10: 계약

**Files:**
- Modify: `docs/api/openapi.yaml` (**FE 저장소**)

**Interfaces:**
- Consumes: 없음
- Produces: 와이어 계약 — `MemberStatus`에 `REREGISTER`, `PendingMember.kind`·`PendingMember.requestedAt`, 엔드포인트 3개

**저장소:** **FE** (`/home/ksb/Dev/home-jaram/home-jaram-fe`). BE 저장소의 사본은 심링크이므로 여기서만 고친다.

BE의 계약 테스트(`src/test/resources/openapi/openapi.yaml` 심링크)가 이 파일을 읽으므로, Task 11·12를 하기 전에 먼저 들어가야 한다.

- [ ] **Step 1: FE 저장소에 브랜치를 판다**

```bash
cd /home/ksb/Dev/home-jaram/home-jaram-fe
git checkout develop && git pull
git checkout -b feat/member-reregistration
```

- [ ] **Step 2: `MemberStatus` enum에 값을 더한다**

`docs/api/openapi.yaml:1218-1219` 근처를 바꾼다:

```yaml
    MemberStatus:
      type: string
      description: >
        활동 상태(승인축과 별개). 가입 시 SignupRequest.enrolled로 파생
        (true→ACTIVE, false→ON_LEAVE), 이후 admin이 변경.
        REREGISTER는 학기 전환 스윕만 설정한다 — admin이 표에서 직접 고를 수 없다.
        REREGISTER 회원은 로그인·조회는 되지만 신청류가 막힌다.
      enum: [ACTIVE, ON_LEAVE, REREGISTER, WITHDRAWN]
```

- [ ] **Step 3: `PendingMember`를 넓힌다**

`docs/api/openapi.yaml:1661-1669`을 바꾼다:

```yaml
    PendingMember:
      type: object
      description: >
        '가입 신청·승인' 화면의 한 줄. 가입 승인 대기(SIGNUP)와 재등록 필요
        (REREGISTER)가 같은 목록에 섞인다.
      required: [id, name, studentId, email, createdAt, kind]
      properties:
        id: { type: string }
        name: { type: string }
        studentId: { type: string }
        email: { type: string, format: email }
        createdAt: { type: string, format: date-time }
        kind:
          type: string
          description: SIGNUP = 가입 승인 대기, REREGISTER = 재등록 필요
          enum: [SIGNUP, REREGISTER]
        requestedAt:
          type: [string, 'null']
          format: date-time
          description: 재등록 신청 시각. 아직 신청하지 않았으면 null. kind=SIGNUP이면 항상 null
```

- [ ] **Step 4: 엔드포인트 셋을 더한다**

`/api/admin/members/{id}/approve` 블록(584행 근처) 바로 뒤에 더한다:

```yaml
  /api/admin/members/{id}/reregister:
    post:
      tags: [admin]
      summary: 재등록 승인
      description: status=REREGISTER 회원을 ACTIVE로 되돌린다. 재등록 신청 시각은 비워진다.
      security: [{ bearerAuth: [] }]
      parameters:
        - $ref: '#/components/parameters/MemberId'
      responses:
        '204': { description: "승인됨 (status=ACTIVE)" }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }
        '5XX': { $ref: '#/components/responses/ServerError' }
```

`/api/me` 블록 뒤에 더한다:

```yaml
  /api/me/reregister:
    post:
      tags: [me]
      summary: 재등록 신청
      description: >
        status=REREGISTER인 본인이 재등록을 신청한다. 임원이 승인해야 완료된다.
        멱등하다 — 이미 신청했으면 시각을 덮지 않는다.
      security: [{ bearerAuth: [] }]
      responses:
        '204': { description: 신청됨 }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '409': { $ref: '#/components/responses/Conflict' }
        '5XX': { $ref: '#/components/responses/ServerError' }

  /api/me/withdraw:
    post:
      tags: [me]
      summary: 탈퇴
      description: >
        본인 탈퇴. status=WITHDRAWN이 되고 로그인이 막힌다. 개인정보는 6개월 뒤
        파기된다. 현직 임기가 있으면 409 — 임기를 먼저 정리해야 한다.
      security: [{ bearerAuth: [] }]
      responses:
        '204': { description: 탈퇴됨 }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '409': { $ref: '#/components/responses/Conflict' }
        '5XX': { $ref: '#/components/responses/ServerError' }
```

- [ ] **Step 5: 로그인 설명의 사실 오류를 고친다**

`docs/api/openapi.yaml:35`가 "status≠ACTIVE 회원은 차단"이라고 하는데 `AuthService.login`은 `approval`만 본다. 재등록 회원은 로그인이 **되어야** 하므로 실제 규칙으로 고친다:

```yaml
      description: 성공 시 JWT 발급. 승인 대기·반려(approval≠APPROVED)와 탈퇴(WITHDRAWN) 회원은 차단.
```

- [ ] **Step 6: 문법을 확인한다**

```bash
npx --yes @redocly/cli lint docs/api/openapi.yaml
```
Expected: 오류 0건 (경고는 무시)

`npx`를 쓸 수 없으면 `python3 -c "import yaml,sys; yaml.safe_load(open('docs/api/openapi.yaml'))"`로 YAML 문법만이라도 확인한다.

- [ ] **Step 7: 커밋**

```bash
git add docs/api/openapi.yaml
git commit -m "$(cat <<'EOF'
contract: 재등록 상태와 신청·승인·탈퇴 경로를 넣는다

MemberStatus 에 REREGISTER 를 더하고, 가입 신청·승인 목록이 가입과 재등록을 함께
싣도록 PendingMember 에 kind 를 뒀다.

로그인 설명도 고쳤다. 'status≠ACTIVE 차단'이라고 적혀 있었는데 실제 코드는
approval 만 본다 — 재등록 회원은 로그인이 되어야 하므로 실제 규칙으로 맞췄다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 11: 회원 경로 — 재등록 신청과 탈퇴

**Files:**
- Modify: `src/main/java/com/jaram/be/me/MeController.java`
- Modify: `src/main/java/com/jaram/be/me/MeService.java`
- Modify: `src/main/java/com/jaram/be/auth/AuthService.java`
- Test: `src/test/java/com/jaram/be/me/MeReregistrationTest.java`

**Interfaces:**
- Consumes: Task 3의 `requestReregistration(Instant)`·`withdraw(Instant)`, Task 10의 계약
- Produces: `POST /api/me/reregister`, `POST /api/me/withdraw`

**저장소:** BE

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/me/MeReregistrationTest.java`:

```java
package com.jaram.be.me;

import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
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
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeReregistrationTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private Member me;
    private String token;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        me = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        me.setApproval(MemberApproval.APPROVED);
        me.setGrade(MemberGrade.ASSOCIATE);
        me.setStatus(MemberStatus.ACTIVE);
        me.setGen(41);
        members.save(me);
        token = jwt.generate(me.getId(), me.getName(), me.getEmail(), Authority.MEMBER);
    }

    private Member reload() { return members.findById(me.getId()).orElseThrow(); }

    private void becomeReregister() {
        Member m = reload();
        m.markReregistrationRequired();
        members.save(m);
    }

    @Test
    void reregistrationRequestRecordsTime() {
        becomeReregister();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/reregister")
                .then().statusCode(204);
        assertThat(reload().getReregisterRequestedAt()).isNotNull();
        assertThat(reload().getStatus()).isEqualTo(MemberStatus.REREGISTER);
    }

    /** 두 번 눌러도 처음 신청 시각이 남는다. */
    @Test
    void reregistrationRequestIsIdempotent() {
        becomeReregister();
        given().header("Authorization", "Bearer " + token).post("/api/me/reregister");
        var first = reload().getReregisterRequestedAt();
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/reregister")
                .then().statusCode(204);
        assertThat(reload().getReregisterRequestedAt()).isEqualTo(first);
    }

    @Test
    void activeMemberCannotRequestReregistration() {
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/reregister")
                .then().statusCode(409);
    }

    @Test
    void withdrawMarksMemberAndRecordsTime() {
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/withdraw")
                .then().statusCode(204);
        assertThat(reload().getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(reload().getWithdrawnAt()).isNotNull();
    }

    /** 현직 임기가 있으면 막는다 — 임기를 먼저 정리해야 한다. */
    @Test
    void sittingOfficerCannotWithdraw() {
        Member m = reload();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        members.save(m);

        given().header("Authorization", "Bearer " + token)
                .when().post("/api/me/withdraw")
                .then().statusCode(409);
        assertThat(reload().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    /** 탈퇴하면 로그인이 막힌다. */
    @Test
    void withdrawnMemberCannotLogIn() {
        given().header("Authorization", "Bearer " + token).post("/api/me/withdraw");
        given().contentType("application/json")
                .body(Map.of("email", "hong@hanyang.ac.kr", "password", "pw"))
                .when().post("/api/auth/login")
                .then().statusCode(403);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.me.MeReregistrationTest'`
Expected: FAIL — 404 (경로가 없다)

- [ ] **Step 3: 최소 구현을 쓴다**

`MeService.java`에 더한다 (import: `com.jaram.be.member.MemberStatus`, `java.time.Instant`):

```java
    /** 재등록 신청. 임원이 승인해야 완료된다. 이미 신청했으면 시각을 덮지 않는다. */
    @Transactional
    public void requestReregistration(String memberId) {
        Member m = find(memberId);
        if (m.getStatus() != MemberStatus.REREGISTER) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "재등록 대상이 아닙니다.");
        }
        m.requestReregistration(Instant.now());
    }

    /** 본인 탈퇴. 개인정보는 6개월 뒤 스윕이 파기한다. */
    @Transactional
    public void withdraw(String memberId) {
        Member m = find(memberId);
        if (m.currentTerm().isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT",
                    "현직 임기가 있어 탈퇴할 수 없습니다. 임기를 먼저 정리해 주세요.");
        }
        m.withdraw(Instant.now());
    }
```

`MeController.java`에 더한다 (import: `org.springframework.http.HttpStatus`, `org.springframework.web.bind.annotation.ResponseStatus`):

```java
    @PostMapping("/reregister")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reregister(@AuthenticationPrincipal CurrentMember me) {
        service.requestReregistration(me.id());
    }

    @PostMapping("/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal CurrentMember me) {
        service.withdraw(me.id());
    }
```

`AuthService.login`의 `approval` 검사 바로 아래에 더한다:

```java
        if (m.getStatus() == MemberStatus.WITHDRAWN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WITHDRAWN", "탈퇴한 계정입니다.");
        }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.me.*' --tests 'com.jaram.be.auth.LoginTest' --tests 'com.jaram.be.contract.MeContractTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/me/ src/main/java/com/jaram/be/auth/AuthService.java src/test/java/com/jaram/be/me/MeReregistrationTest.java
git commit -m "$(cat <<'EOF'
feat(me): 재등록 신청과 본인 탈퇴 경로를 연다

탈퇴하면 로그인도 막는다. 지금까지 login 은 approval 만 봐서 탈퇴 회원도 들어올
수 있었다 — 계약 설명은 차단한다고 적혀 있었지만 코드가 그렇지 않았다.

현직 임기가 있으면 탈퇴를 막는다. 임기가 남은 채 사라지면 임원 목록이 빈 자리를
가리키게 된다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 12: 임원 경로 — 재등록 승인과 목록 확장

**Files:**
- Modify: `src/main/java/com/jaram/be/admin/AdminMemberController.java`
- Modify: `src/main/java/com/jaram/be/admin/AdminMemberService.java`
- Modify: `src/main/java/com/jaram/be/admin/dto/PendingMember.java`
- Test: `src/test/java/com/jaram/be/admin/AdminReregistrationTest.java`

**Interfaces:**
- Consumes: Task 3의 `completeReregistration()`, Task 10의 계약
- Produces: `POST /api/admin/members/{id}/reregister`; `PendingMember(String id, String name, String studentId, String email, String createdAt, String kind, String requestedAt)`

**저장소:** BE

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/AdminReregistrationTest.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminReregistrationTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        officerToken = jwt.generate("officer-id", "임원", "exec@hanyang.ac.kr", Authority.OFFICER);
    }

    private Member saved(String name, String studentId, String email, MemberStatus status,
                         MemberApproval approval) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(approval);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setStatus(status);
        m.setGen(41);
        return members.save(m);
    }

    /** 가입 대기와 재등록 필요가 한 목록에 kind 로 구분되어 실린다. */
    @Test
    void pendingListCarriesBothKinds() {
        saved("신입", "2026011111", "new@hanyang.ac.kr", MemberStatus.ACTIVE, MemberApproval.PENDING);
        saved("재등록", "2023022222", "re@hanyang.ac.kr", MemberStatus.REREGISTER, MemberApproval.APPROVED);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/pending")
                .then().statusCode(200)
                .body("kind", containsInAnyOrder("SIGNUP", "REREGISTER"));
    }

    /** 신청하지 않은 재등록 대상은 requestedAt 이 null 이다. */
    @Test
    void unrequestedReregistrationHasNullRequestedAt() {
        saved("재등록", "2023022222", "re@hanyang.ac.kr", MemberStatus.REREGISTER, MemberApproval.APPROVED);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/pending")
                .then().statusCode(200)
                .body("find { it.kind == 'REREGISTER' }.requestedAt", nullValue());
    }

    @Test
    void requestedReregistrationCarriesTime() {
        Member m = saved("재등록", "2023022222", "re@hanyang.ac.kr",
                MemberStatus.REREGISTER, MemberApproval.APPROVED);
        m.requestReregistration(Instant.parse("2027-03-05T00:00:00Z"));
        members.save(m);

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/pending")
                .then().statusCode(200)
                .body("find { it.kind == 'REREGISTER' }.requestedAt", notNullValue());
    }

    @Test
    void approvingReregistrationReturnsMemberToActive() {
        Member m = saved("재등록", "2023022222", "re@hanyang.ac.kr",
                MemberStatus.REREGISTER, MemberApproval.APPROVED);
        m.requestReregistration(Instant.parse("2027-03-05T00:00:00Z"));
        members.save(m);

        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/members/" + m.getId() + "/reregister")
                .then().statusCode(204);

        Member found = members.findById(m.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(found.getReregisterRequestedAt()).isNull();
    }

    @Test
    void approvingNonReregisterMemberConflicts() {
        Member m = saved("홍길동", "2023012345", "hong@hanyang.ac.kr",
                MemberStatus.ACTIVE, MemberApproval.APPROVED);

        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/members/" + m.getId() + "/reregister")
                .then().statusCode(409);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.AdminReregistrationTest'`
Expected: FAIL — `kind` 필드가 없다

- [ ] **Step 3: 최소 구현을 쓴다**

`src/main/java/com/jaram/be/admin/dto/PendingMember.java`를 통째로 바꾼다:

```java
package com.jaram.be.admin.dto;

/**
 * '가입 신청·승인' 화면의 한 줄. 가입 승인 대기(SIGNUP)와 재등록 필요(REREGISTER)가
 * 한 목록에 섞이므로 kind 로 구분한다. requestedAt 은 재등록 신청 시각이며,
 * 아직 신청하지 않았거나 가입 대기면 null 이다.
 */
public record PendingMember(String id, String name, String studentId, String email,
                            String createdAt, String kind, String requestedAt) { }
```

`AdminMemberService.java`의 `listPending`을 바꾸고 승인 메서드를 더한다:

```java
    @Transactional(readOnly = true)
    public List<PendingMember> listPending() {
        List<PendingMember> rows = new ArrayList<>();
        for (Member m : members.findByApproval(MemberApproval.PENDING)) {
            rows.add(new PendingMember(m.getId(), m.getName(), m.getStudentId(), m.getEmail(),
                    m.getCreatedAt().toString(), "SIGNUP", null));
        }
        for (Member m : members.findByApprovalAndStatus(MemberApproval.APPROVED, MemberStatus.REREGISTER)) {
            rows.add(new PendingMember(m.getId(), m.getName(), m.getStudentId(), m.getEmail(),
                    m.getCreatedAt().toString(), "REREGISTER",
                    m.getReregisterRequestedAt() == null ? null : m.getReregisterRequestedAt().toString()));
        }
        return rows;
    }

    /** 재등록 승인. 활동축만 되돌리고 승인축은 건드리지 않는다. */
    @Transactional
    public void approveReregistration(String id) {
        Member m = load(id);
        if (m.getStatus() != MemberStatus.REREGISTER) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "재등록 대상이 아닙니다.");
        }
        m.completeReregistration();
    }
```

import를 더한다: `com.jaram.be.member.MemberStatus`, `java.util.ArrayList`.

`MemberRepository.java`에 한 줄 더한다:

```java
    List<Member> findByApprovalAndStatus(MemberApproval approval, MemberStatus status);
```

`AdminMemberController.java`에 더한다:

```java
    @PostMapping("/{id}/reregister")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reregister(@PathVariable String id) { service.approveReregistration(id); }
```

import를 더한다: `org.springframework.http.HttpStatus`, `org.springframework.web.bind.annotation.ResponseStatus`.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.AdminReregistrationTest' --tests 'com.jaram.be.admin.AdminMemberTest' --tests 'com.jaram.be.contract.AdminContractTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/admin/ src/main/java/com/jaram/be/member/MemberRepository.java src/test/java/com/jaram/be/admin/AdminReregistrationTest.java
git commit -m "$(cat <<'EOF'
feat(admin): 가입 신청·승인이 재등록도 함께 다룬다

한 목록에 가입 대기와 재등록 필요가 kind 로 구분되어 실린다. 신청 여부는
requestedAt 으로 드러나 임원이 누가 눌렀는지 보고 판단할 수 있다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 13: 조회 규칙

**Files:**
- Modify: `src/main/java/com/jaram/be/people/PeopleService.java`
- Modify: `src/main/java/com/jaram/be/admin/AdminResourceService.java`
- Test: `src/test/java/com/jaram/be/people/PeopleVisibilityTest.java`

**Interfaces:**
- Consumes: Task 2·3의 상태와 `getPurgedAt()`
- Produces: 없음

**저장소:** BE

`PeopleService`가 쓰는 `status != WITHDRAWN`은 `!=` 비교라 값이 늘어도 컴파일 에러가 나지 않는다. 이 자리를 놓치면 재등록 필요 회원이 사람들 탭에 계속 노출된다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/people/PeopleVisibilityTest.java`:

```java
package com.jaram.be.people;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
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
class PeopleVisibilityTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    private Member saved(String name, String studentId, String email, MemberStatus status) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setStatus(status);
        m.setGen(41);
        m.setContributor(true);
        return members.save(m);
    }

    /** 재등록 필요 회원은 사람들 탭에서 빠진다. */
    @Test
    void reregisterMemberIsHidden() {
        saved("재등록", "2023022222", "re@hanyang.ac.kr", MemberStatus.REREGISTER);
        given().when().get("/api/people")
                .then().statusCode(200)
                .body("contrib.groups.flatten().name", not(hasItem("재등록")));
    }

    @Test
    void activeAndOnLeaveMembersAreVisible() {
        saved("활동", "2023011111", "active@hanyang.ac.kr", MemberStatus.ACTIVE);
        saved("휴학", "2023033333", "leave@hanyang.ac.kr", MemberStatus.ON_LEAVE);
        given().when().get("/api/people")
                .then().statusCode(200)
                .body("contrib.groups.flatten().name", hasItems("활동", "휴학"));
    }

    /** 파기된 회원은 이력이 남아 있어도 공개 목록에 나오지 않는다. */
    @Test
    void purgedMemberIsHidden() {
        Member m = saved("파기됨", "2021044444", "purged@hanyang.ac.kr", MemberStatus.ACTIVE);
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        m.endCurrentTerm(42);
        m.purge(Instant.parse("2027-03-01T00:00:00Z"));
        members.save(m);

        given().when().get("/api/people")
                .then().statusCode(200)
                .body("contrib.groups.flatten().name", not(hasItem("파기됨")));
    }
}
```

> 응답 구조(`contrib.groups`)가 다르면 `PeopleResponse`/`PeopleTab`을 열어 실제 필드 이름에 맞춘다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.people.PeopleVisibilityTest'`
Expected: FAIL — `reregisterMemberIsHidden`에서 이름이 나온다

- [ ] **Step 3: 최소 구현을 쓴다**

`PeopleService.list()`의 필터를 바꾼다:

```java
        // 승인된 회원 중 현 회원(재학/휴학)만 노출. 재등록 필요·탈퇴는 빠지고,
        // 파기된 회원은 이력이 남아 있어도 공개 목록에 내지 않는다.
        List<Member> active = members.findByApproval(MemberApproval.APPROVED).stream()
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE || m.getStatus() == MemberStatus.ON_LEAVE)
                .filter(m -> m.getPurgedAt() == null)
                .toList();
```

`AdminResourceService.list`의 `case members ->` 갈래에 파기 필터를 더한다:

```java
            case members -> members.findAll().stream()
                    .filter(m -> m.getPurgedAt() == null)   // 파기된 회원은 관리 표에도 내지 않는다
                    .filter(m -> matchesMemberTab(m, tab))
                    .map(this::memberRow).toList();
```

`allRows`의 `case members ->`에도 같은 필터를 더한다:

```java
            case members -> members.findAll().stream()
                    .filter(m -> m.getPurgedAt() == null)
                    .map(this::memberRow).toList();
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.people.*' --tests 'com.jaram.be.admin.AdminResourceTest' --tests 'com.jaram.be.contract.PeopleContractTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/people/PeopleService.java src/main/java/com/jaram/be/admin/AdminResourceService.java src/test/java/com/jaram/be/people/PeopleVisibilityTest.java
git commit -m "$(cat <<'EOF'
fix(people): 재등록 필요와 파기된 회원을 목록에서 뺀다

PeopleService 는 status != WITHDRAWN 으로 걸렀다. != 비교라 상태 값이 늘어도
컴파일 에러가 나지 않아, 그대로 뒀으면 재등록 필요 회원이 사람들 탭에 계속
노출됐을 것이다. 이제 활동·휴학만 통과한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 14: 신청 차단

**Files:**
- Create: `src/main/java/com/jaram/be/member/MemberActivityGuard.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java` (출석)
- Modify: `src/main/java/com/jaram/be/schedule/ScheduleService.java` (슬롯 점유·세미나 제출)
- Modify: `src/main/java/com/jaram/be/study/StudyService.java` (개설·신청)
- Test: `src/test/java/com/jaram/be/member/ReregistrationBlocksActivityTest.java`

**Interfaces:**
- Consumes: Task 2의 `MemberStatus.REREGISTER`
- Produces: `MemberActivityGuard.requireRegistered(String memberId) -> void` (403 `REREGISTRATION_REQUIRED`)

**저장소:** BE

경로 기반 필터로 하지 않는다. 다섯 개와 그 예외를 `SecurityConfig`에 문자열로 다시 적어야 하고, 경로가 바뀔 때 조용히 어긋난다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/member/ReregistrationBlocksActivityTest.java`:

```java
package com.jaram.be.member;

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

/**
 * 재등록 필요 회원은 조회는 되지만 신청류가 막힌다 — 팝업을 닫아도 재등록할
 * 이유가 남아야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReregistrationBlocksActivityTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String token;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.markReregistrationRequired();
        m.setGen(41);
        members.save(m);
        token = jwt.generate(m.getId(), m.getName(), m.getEmail(), Authority.MEMBER);
    }

    @Test
    void studyApplicationIsBlocked() {
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("motive", "배우고 싶습니다"))
                .when().post("/api/studies/any-id/apply")
                .then().statusCode(403).body("code", org.hamcrest.Matchers.equalTo("REREGISTRATION_REQUIRED"));
    }

    @Test
    void seminarAttendanceIsBlocked() {
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("code", "1234"))
                .when().post("/api/seminars/any-id/attend")
                .then().statusCode(403);
    }

    @Test
    void slotClaimIsBlocked() {
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/schedules/any-id/slots/0/claim")
                .then().statusCode(403);
    }

    /** 조회는 열려 있다. */
    @Test
    void readingIsAllowed() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/me")
                .then().statusCode(200);
    }

    /** 프로필 수정도 막지 않는다 — 신청이 아니다. */
    @Test
    void profileEditIsAllowed() {
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(Map.of("bio", "안녕하세요", "githubUrl", "", "blogUrl", ""))
                .when().patch("/api/me")
                .then().statusCode(200);
    }
}
```

> 요청 본문 모양이 다르면 각 컨트롤러의 요청 DTO를 열어 맞춘다. 존재하지 않는 id를 쓰는 이유는 **차단이 조회보다 먼저** 일어나야 하기 때문이다 — 404가 나오면 가드가 늦게 걸린 것이다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.member.ReregistrationBlocksActivityTest'`
Expected: FAIL — 403이 아니라 404가 돌아온다

- [ ] **Step 3: 최소 구현을 쓴다**

`src/main/java/com/jaram/be/member/MemberActivityGuard.java`:

```java
package com.jaram.be.member;

import com.jaram.be.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재등록 필요 회원의 신청류를 막는다. 조회와 프로필 수정은 막지 않는다 —
 * 팝업을 닫아도 재등록할 이유가 남게 하는 것이 목적이지 사이트를 잠그는 게 아니다.
 *
 * 경로 기반 필터로 두지 않은 이유는, 막을 다섯 개와 그 예외를 SecurityConfig 에
 * 문자열로 다시 적어야 하고 경로가 바뀔 때 조용히 어긋나기 때문이다.
 */
@Component
public class MemberActivityGuard {

    private final MemberRepository members;

    public MemberActivityGuard(MemberRepository members) { this.members = members; }

    @Transactional(readOnly = true)
    public void requireRegistered(String memberId) {
        members.findById(memberId)
                .filter(m -> m.getStatus() == MemberStatus.REREGISTER)
                .ifPresent(m -> {
                    throw new ApiException(HttpStatus.FORBIDDEN, "REREGISTRATION_REQUIRED",
                            "재등록이 승인되어야 이용할 수 있습니다.");
                });
    }
}
```

세 서비스에 `MemberActivityGuard guard`를 생성자 주입하고, 아래 다섯 메서드의 **첫 줄**에 가드를 넣는다. 첫 줄이어야 한다 — 대상을 조회한 뒤에 걸면 없는 id에 404가 먼저 나가고, 있는 id면 조회 비용을 헛되이 치른다.

| 파일 | 메서드 (현재 줄) | 넣을 코드 |
|---|---|---|
| `seminar/SeminarService.java` | `attend(String seminarId, String memberId, String code)` (:193) | `guard.requireRegistered(memberId);` |
| `schedule/ScheduleService.java` | `claim(String scheduleId, int index, String memberId)` (:59) | `guard.requireRegistered(memberId);` |
| `schedule/ScheduleService.java` | `submitSeminar(String scheduleId, int index, String memberId, ...)` (:94) | `guard.requireRegistered(memberId);` |
| `study/StudyService.java` | `create(StudyCreateRequest req, String leaderId)` (:36) | `guard.requireRegistered(leaderId);` |
| `study/StudyService.java` | `apply(String studyId, String applicantId, String motive)` (:53) | `guard.requireRegistered(applicantId);` |

넣지 **않는** 곳:

- `ScheduleService.cancel` (:74) — 점유 취소는 신청이 아니다
- `MeService.update` — 프로필 수정은 신청이 아니다
- `StudyService`의 `approveStudy`·`rejectStudy`·`approveApplicant`·`rejectApplicant`, `ScheduleService`의 `create`·`delete`·`lock`·`unlock`·`forceRelease` — 임원 전용이고, 현직 임원은 애초에 전환에서 면제된다
- 모든 조회 메서드 (`list`, `myActivity`, `pending`, `applicants`, `attendeePreview`)

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.member.ReregistrationBlocksActivityTest' --tests 'com.jaram.be.seminar.*' --tests 'com.jaram.be.study.*' --tests 'com.jaram.be.schedule.*'`
Expected: PASS

- [ ] **Step 5: 커밋 후 전체 테스트**

```bash
git add src/main/java/com/jaram/be/member/MemberActivityGuard.java src/main/java/com/jaram/be/seminar/ src/main/java/com/jaram/be/schedule/ src/main/java/com/jaram/be/study/ src/test/java/com/jaram/be/member/ReregistrationBlocksActivityTest.java
git commit -m "$(cat <<'EOF'
feat(member): 재등록 전에는 신청류를 막는다

조회와 프로필 수정은 열어 둔다. 팝업을 닫아도 재등록할 이유가 남게 하는 것이
목적이지 사이트를 잠그는 게 아니다.

경로 기반 필터로 두지 않았다. 막을 다섯 개와 예외를 SecurityConfig 에 문자열로
다시 적어야 하고, 경로가 바뀔 때 조용히 어긋난다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
./gradlew test
```
Expected: BUILD SUCCESSFUL — 전체 초록

---

### Task 15: FE — 인원 관리 상태 라벨과 기본 필터

**Files:**
- Modify: `src/features/admin/admin.data.js`
- Modify: `src/features/admin/admin.api.js:158-173` (`fetchMembers`)
- Test: 없음 (러너 미도입)

**Interfaces:**
- Consumes: Task 10의 계약 (`MemberStatus.REREGISTER`)
- Produces: `STATUS_LABEL.REREGISTER = '재등록'`

**저장소:** **FE** (Task 10에서 만든 `feat/member-reregistration` 브랜치)

기본 목록에서 재등록·탈퇴가 안 보이고, 상태 필터에서 명시적으로 골라야 나온다. '전체'에서도 안 보인다.

- [ ] **Step 1: 상태 라벨과 필터 옵션을 더한다**

`admin.data.js:18`:

```js
export const STATUS_LABEL = { ACTIVE: '활동', ON_LEAVE: '휴학', REREGISTER: '재등록', WITHDRAWN: '탈퇴' };
```

`SCHEMAS.member.filters`의 상태 줄을 바꾼다:

```js
      { key: 'status', label: '상태', options: ['전체', '활동', '휴학', '재등록', '탈퇴'] },
```

`SCHEMAS.member.cols`의 상태 칸에서는 **'재등록'을 빼 둔다** — 서버가 거부하는 값이라 고를 수 있으면 안 된다:

```js
      // '재등록'은 학기 전환 스윕만 설정한다. 서버가 직접 지정을 거부하므로 옵션에 두지 않는다.
      { key: 'status', label: '상태', type: 'select', width: '0.8fr', options: ['활동', '휴학', '탈퇴'] },
```

- [ ] **Step 2: 기본 필터를 바꾼다**

`admin.api.js`의 `fetchMembers`를 바꾼다:

```js
/**
 * 회원 명단(member 탭). 서버 목록은 tab·q·sort·page 만 처리하고 등급·기수·상태
 * 필터는 무시하므로(BE AdminResourceService.list), 전체를 받아 검색·필터·정렬·페이지를
 * 이 계층에서 처리한다. 회원 규모(수백)에서 안전하다.
 * 승인 대기·반려 회원은 '가입 신청·승인' 화면이 다루므로 명단에서 뺀다.
 *
 * 재등록·탈퇴는 상태 필터로 명시해야 보인다 — '전체'에서도 나오지 않는다.
 * 평소 명단을 훑을 때 떠난 사람이 섞이지 않게 하는 것이 이 화면의 목적이다.
 */
async function fetchMembers(params = {}) {
  const { data } = await client.get('/api/admin/members', {
    params: { tab: 'member', page: 1, size: ALL_ROWS_SIZE },
  });
  const picked = params.filters?.status;
  const rows = (data.items || [])
    .filter((m) => m.approval === 'APPROVED')
    .map((m) => fromWire('member', m))
    .filter((r) => (picked && picked !== '전체'
      ? r.status === picked
      : r.status === STATUS_LABEL.ACTIVE || r.status === STATUS_LABEL.ON_LEAVE));
  return queryLocally(rows, params);
}
```

`admin.api.js` 상단 import에 `STATUS_LABEL`을 더한다 (`admin.data`에서 가져온다).

- [ ] **Step 3: 검증**

```bash
export PATH="/home/ksb/.nvm/versions/node/v20.20.2/bin:$PATH"
npm run lint && npm run typecheck && npm run build
```
Expected: 셋 다 통과. 청크 크기 경고는 기존 것이라 무시한다.

- [ ] **Step 4: 눈으로 확인한다**

`npm run dev` 후 관리자 → 인원 관리 → 회원 탭에서:
- 기본 목록에 활동·휴학만 보인다
- 상태 필터에서 '재등록'을 고르면 재등록 회원만 보인다
- 상태 칸 드롭다운에 '재등록'이 **없다**

- [ ] **Step 5: 커밋**

```bash
git add src/features/admin/admin.data.js src/features/admin/admin.api.js
git commit -m "$(cat <<'EOF'
feat(admin): 인원 관리에서 재등록·탈퇴를 기본으로 감춘다

명시적으로 상태를 골라야 보인다. '전체'에서도 나오지 않는다 — 평소 명단을 훑을
때 떠난 사람이 섞이지 않게 하는 것이 이 화면의 목적이다.

상태 칸 드롭다운에는 '재등록'을 넣지 않았다. 서버가 직접 지정을 거부한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

### Task 16: FE — 승인 탭의 재등록 행과 프로필 탈퇴

**Files:**
- Modify: `src/features/admin/admin.data.js` (`SCHEMAS.applications`)
- Modify: `src/features/admin/admin.api.js` (`fetchPendingApplications`, 액션 함수 추가)
- Modify: `src/features/profile/views/ProfileView.jsx`
- Modify: `src/features/profile/profile.api.js`
- Test: 없음 (러너 미도입)

**Interfaces:**
- Consumes: Task 10의 계약 (`PendingMember.kind`·`requestedAt`, 엔드포인트 3개)
- Produces: `approveReregistration(id)`, `withdrawMe()`

**저장소:** **FE**

- [ ] **Step 1: 승인 탭 스키마에 구분 칸을 더한다**

`admin.data.js`의 `SCHEMAS.applications`를 바꾼다:

```js
  applications: {
    // 수기 등록(creates)은 대응 엔드포인트가 없어 추가 버튼을 두지 않는다 — 가입은 신청 절차로만.
    eyebrow: 'JOIN', title: '가입 신청 · 승인', addLabel: '',
    desc: '대기 중인 가입 신청과 재등록 대상을 검토하세요. 가입 승인 시 기수 기준으로 등급이 자동 부여됩니다.',
    filters: [{ key: 'kind', label: '구분', options: ['전체', '가입', '재등록'] }],
    cols: [
      { key: 'kind', label: '구분', type: 'tag', width: '0.7fr', align: 'center' },
      { key: 'name', label: '신청자', type: 'static', width: '1fr' },
      { key: 'studentId', label: '학번', type: 'static', width: '1fr' },
      { key: 'appliedAt', label: '신청일', type: 'static', width: '1fr', align: 'center' },
      { key: 'status', label: '상태', type: 'tag', width: '0.8fr', align: 'center' },
      { key: '__act', label: '', type: 'actions', width: '1.2fr', align: 'center', actions: ['approve', 'reject'] },
    ],
  },
```

라벨 맵을 더한다 (`APPLICATION_STATUS_LABEL` 아래):

```js
/** 승인 탭 한 줄의 구분. 재등록은 신청 여부까지 상태 칸에 드러난다. */
export const PENDING_KIND_LABEL = { SIGNUP: '가입', REREGISTER: '재등록' };
```

- [ ] **Step 2: 목록 매핑을 바꾼다**

`admin.api.js`의 `fetchPendingApplications`를 바꾼다:

```js
/**
 * 가입 신청·승인 목록. 가입 대기(SIGNUP)와 재등록 필요(REREGISTER)가 한 목록에
 * kind 로 구분되어 온다. 재등록은 본인이 신청했는지가 requestedAt 으로 드러나,
 * 임원이 누가 눌렀는지 보고 재등록/삭제를 판단한다.
 * 전용 엔드포인트를 쓴다: GET /api/admin/members/pending → PendingMember[]
 */
async function fetchPendingApplications(params = {}) {
  const { data } = await client.get('/api/admin/members/pending');
  const rows = (data || []).map((m) => ({
    id: m.id,
    kind: PENDING_KIND_LABEL[m.kind] ?? m.kind,
    name: m.name,
    studentId: m.studentId,
    appliedAt: (m.requestedAt ?? m.createdAt ?? '').slice(0, 10),
    status: m.kind === 'REREGISTER'
      ? (m.requestedAt ? '재등록 신청' : '미신청')
      : APPLICATION_STATUS_LABEL.PENDING,
    _kind: m.kind,
  }));
  return queryLocally(rows, params);
}
```

import에 `PENDING_KIND_LABEL`을 더한다.

- [ ] **Step 3: 액션 함수를 더한다**

`admin.api.js`의 `approveApplication` 옆에 더한다:

```js
/** 재등록 승인. status=REREGISTER 회원을 활동으로 되돌린다. */
export async function approveReregistration(id) {
  await client.post(`/api/admin/members/${id}/reregister`);
}
```

승인/반려를 부르는 자리(`admin.api.js:405-406` 근처)에서 행의 `_kind`를 보고 갈라 준다:

```js
      if (status === APPLICATION_STATUS_LABEL.APPROVED) {
        // 재등록은 가입 승인과 다른 경로다 — 승인축이 아니라 활동축을 되돌린다.
        if (u.fields?._kind === 'REREGISTER') await approveReregistration(u.id);
        else await approveApplication(u.id);
      } else if (status === APPLICATION_STATUS_LABEL.REJECTED) {
        await rejectApplication(u.id, u.fields?.reason || '관리자 반려');
      }
```

> 이 자리의 실제 코드 모양이 다르면 `_kind`를 읽을 수 있는 형태로만 맞춘다. 재등록 행의 '반려'는 삭제(`:batch`의 `deletes`)로 이어져야 하므로, 반려 처리 분기에서도 `_kind === 'REREGISTER'`면 `saveBatch('member', { deletes: [u.id] })`를 부른다.

- [ ] **Step 4: 프로필에 탈퇴를 더한다**

`profile.api.js`에 더한다:

```js
/** 본인 탈퇴. 개인정보는 6개월 뒤 서버가 파기한다. */
export async function withdrawMe() {
  await client.post('/api/me/withdraw');
}
```

`ProfileView`는 콜백만 받는 표현 컴포넌트다(`onEdit`·`onLogout` 패턴). 상태와 호출은 `ProfilePage`가 갖는다.

`profile.data.js`에 문구를 더한다 — 문구는 데이터 파일에 모여 있다:

```js
export const ACTIONS = { admin: '관리자 콘솔', edit: '수정', logout: '로그아웃', withdraw: '탈퇴' };
```

`MESSAGES`에 더한다:

```js
  withdrawConfirm: '정말 탈퇴하시겠습니까? 탈퇴하면 다시 로그인할 수 없고, 등록하신 개인정보는 6개월 뒤 삭제됩니다.',
  withdrawBlocked: '현직 임기가 있어 탈퇴할 수 없습니다. 임원진에게 임기 정리를 요청해 주세요.',
  withdrawError: '탈퇴에 실패했습니다. 잠시 후 다시 시도해 주세요.',
```

`ProfileView.jsx`: 시그니처에 `onWithdraw`를 더하고, `onLogout` 버튼 옆에 둔다. **`danger` variant는 없다** — `Button.jsx`가 아는 값은 `primary`·`outline`·`secondary`·`ghost`뿐이다. 새로 만들지 말고 `ghost`를 쓴다.

```jsx
<Button size="sm" variant="ghost" onClick={onWithdraw}>{ACTIONS.withdraw}</Button>
```

`ProfilePage.jsx`: `logout`을 넘기는 자리 옆에 핸들러를 더한다. 확인은 `window.confirm`으로 충분하다 — 프로필에는 다이얼로그 컴포넌트가 없고, 이 한 곳을 위해 들여올 이유가 없다.

```jsx
  // 되돌릴 수 없는 동작이라 확인을 한 번 받는다.
  const handleWithdraw = useCallback(async () => {
    if (!window.confirm(MESSAGES.withdrawConfirm)) return;
    try {
      await withdrawMe();
      logout();
    } catch (e) {
      setToast(e?.response?.status === 409 ? MESSAGES.withdrawBlocked : MESSAGES.withdrawError);
    }
  }, [logout]);
```

`<ProfileView ... onWithdraw={handleWithdraw} />`로 넘긴다. import에 `withdrawMe`를 더한다.

> `setToast`가 문자열이 아니라 객체를 받으면 그 모양에 맞춘다(`ProfilePage.jsx`의 다른 토스트 호출을 보고 따른다).

- [ ] **Step 5: 검증하고 커밋**

```bash
export PATH="/home/ksb/.nvm/versions/node/v20.20.2/bin:$PATH"
npm run lint && npm run typecheck && npm run build
```
Expected: 셋 다 통과

`npm run dev`로 확인:
- 가입 신청·승인 탭에 '구분' 칸이 보이고 가입/재등록이 섞여 있다
- 재등록 행의 상태가 '재등록 신청' 또는 '미신청'이다
- 프로필에 탈퇴 버튼이 있고 확인 다이얼로그가 뜬다

```bash
git add src/features/admin/ src/features/profile/
git commit -m "$(cat <<'EOF'
feat(admin): 승인 탭에서 재등록을 판단하고, 프로필에 탈퇴를 둔다

재등록 행은 본인이 신청했는지를 상태 칸에 드러낸다 — 임원이 누가 눌렀는지 보고
재등록과 삭제를 고를 수 있어야 한다.

탈퇴는 되돌릴 수 없어 확인을 한 번 받는다. 개인정보가 6개월 뒤 삭제된다는 것도
그 자리에서 알린다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VGfTXP5ucWzGVcTEHfysp7
EOF
)"
```

---

## 머지 순서

두 저장소가 얽혀 있다. 이 순서를 지키지 않으면 화면이 빈다.

1. **BE PR #8** (`feat/settings-period`) — 학기 계산. 이미 열려 있다
2. **FE PR** (`feat/member-reregistration`, Task 10) — 계약. BE 계약 테스트가 이 파일을 심링크로 읽는다
3. **BE PR** (`feat/member-reregistration`, Task 1~9·11~14)
4. **FE PR** 나머지 (Task 15·16) — 2번과 같은 브랜치라면 3번 뒤에 머지

각 줄에서 BE가 FE보다 먼저 들어가야 한다. FE가 먼저 머지되면 서버가 아직 안 내려주는 값을 읽어 화면이 빈다.

## 배포 후 확인

1. 로그를 보고 스윕이 04:00에 한 번 돌았는지 확인한다
2. `select last_rollover_year, last_rollover_term from admin_settings;` — 현재 학기로 초기화되어 있어야 한다
3. `select status, count(*) from member group by status;` — `REREGISTER`가 **0이어야 한다.** 0이 아니면 §8의 초기화가 안 걸린 것이다
4. 사람들 탭과 인원 관리 명단이 배포 전과 같은지 눈으로 확인한다
