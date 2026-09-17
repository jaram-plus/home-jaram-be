# 회원 데이터 축 중복 제거 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Member` 엔티티에서 의미가 중복되는 두 축(직책/권한, 재학/활동상태)을 제거해 저장 축을 하나씩으로 줄이고, 그 과정에서 `Authority`가 영영 `MEMBER`에 머무는 결함을 해소한다.

**Architecture:** `MemberTitle`을 부서 무관한 직위 5개로 축소하고 표시 라벨을 `department + title`로 파생한다. `Authority`는 저장을 그만두고 `title != null`에서 파생한다. `enrolled` 컬럼을 지우고 `MemberStatus`를 활동축의 단일 진실원으로 삼는다. 응답 스키마는 `MemberTitle` enum 값 목록을 빼면 변하지 않는다.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Spring Data JPA / PostgreSQL(Testcontainers) / REST-assured / swagger-request-validator · React 19 / Vite 7 (FE)

**Spec:** `docs/superpowers/specs/2026-07-20-member-axis-dedup-design.md`

## Global Constraints

- **레포 2개.** BE `/home/ksb/Dev/home-jaram/home-jaram-be`, FE `/home/ksb/Dev/home-jaram/home-jaram-fe`. 두 레포 모두 브랜치 `develop`. 아래 경로는 각 태스크에 표기된 레포 기준 상대경로다.
- **커밋은 `/cavemancommit` 스킬로 한다.** 커밋 스텝에서 `git commit`을 직접 실행하지 말고 Skill 도구로 `cavemancommit`을 invoke한다. committer 서브에이전트를 쓰지 않는다.
- **Lombok 금지.** 엔티티는 protected 무인자 생성자 + 정적 팩토리 + 평범한 getter/setter.
- **enum 와이어 값 = enum name (UPPER_SNAKE).** `@Enumerated(EnumType.STRING)`.
- **계약이 법이다.** 단일 진실원은 `home-jaram-fe/docs/api/openapi.yaml`. BE의 `docs/api/openapi.yaml`은 그 파일을 가리키는 심볼릭 링크다. 계약을 고친 뒤에는 BE에서 `./scripts/sync-openapi.sh`를 실행해 `src/main/resources/openapi/openapi.yaml` 복사본을 갱신해야 계약 테스트가 새 계약을 본다.
- **사용자 노출 문구는 한국어 존댓말, 이모지 금지.**
- **BE 테스트:** `./gradlew test`. Testcontainers가 Docker를 요구한다.
- **FE 검증:** 테스트 러너가 없다. `npm run lint` · `npm run build`로 확인한다.
- **변경하지 않는 것:** `MemberDepartment`, `MemberGrade`, `MemberStatus`, `MemberApproval`, `MemberCategory`, `Authority` enum 자체, JWT `authority` claim, `MeProfile`/`UserSummary`/`SignupRequest`의 필드 구성.

---

### Task 1: 계약 — `MemberTitle` enum 축소

계약이 먼저 바뀌어야 BE가 새 enum 값을 내보낼 수 있다. `PersonMember.role`은 자유 문자열이고 `MeProfile.title`을 설정하는 테스트가 없으므로, 이 태스크만 적용해도 기존 테스트는 모두 통과한다.

**Files:**
- Modify (FE 레포): `docs/api/openapi.yaml:1003-1013`
- Modify (BE 레포, 스크립트가 생성): `src/main/resources/openapi/openapi.yaml`

**Interfaces:**
- Consumes: 없음
- Produces: `MemberTitle` 와이어 enum = `[PRESIDENT, VICE_PRESIDENT, LEAD, STAFF, SERVER_ADMIN, null]`. Task 2·6이 이 값 목록에 의존한다.

- [ ] **Step 1: FE 레포에서 `MemberTitle` 스키마 교체**

`/home/ksb/Dev/home-jaram/home-jaram-fe/docs/api/openapi.yaml`에서 아래 3줄을

```yaml
    MemberTitle:
      type: [string, 'null']
      description: 직책(임원 배정). 등급은 MemberGrade로 분리. enum name으로 전송 (라벨은 FE 매핑).
      enum: [PRESIDENT, VICE_PRESIDENT, ACADEMIC_LEAD, ACADEMIC_MEMBER, PR_LEAD, PR_MEMBER, FINANCE_LEAD, FINANCE_MEMBER, SERVER_ADMIN, null]
```

이렇게 바꾼다.

```yaml
    MemberTitle:
      type: [string, 'null']
      description: >
        직책. department와 조합해 표시 라벨을 만든다 (ACADEMIC+LEAD → 학술부장).
        LEADERSHIP은 PRESIDENT|VICE_PRESIDENT, ACADEMIC|PR|FINANCE는 LEAD|STAFF,
        INFRA는 SERVER_ADMIN만 허용. null이면 일반 회원.
      enum: [PRESIDENT, VICE_PRESIDENT, LEAD, STAFF, SERVER_ADMIN, null]
```

- [ ] **Step 2: 같은 파일에서 `Authority` 스키마 description 추가**

`Authority`는 값이 그대로이므로 enum은 건드리지 않는다. description 한 줄만 더한다.

```yaml
    Authority:
      type: string
      description: 권한. 서버가 title에서 파생한다 (title이 있으면 OFFICER, 없으면 MEMBER).
      enum: [MEMBER, OFFICER]
```

- [ ] **Step 3: BE로 계약 복사본 동기화**

BE 레포에서 실행한다.

Run: `cd /home/ksb/Dev/home-jaram/home-jaram-be && ./scripts/sync-openapi.sh`
Expected: `Synced contract -> .../src/main/resources/openapi/openapi.yaml`

- [ ] **Step 4: 계약 테스트가 여전히 통과하는지 확인**

Run: `cd /home/ksb/Dev/home-jaram/home-jaram-be && ./gradlew test --tests '*ContractTest'`
Expected: BUILD SUCCESSFUL. (`MeProfile.title`을 채우는 테스트가 없어 enum 축소가 아직 아무 응답에도 영향을 주지 않는다.)

- [ ] **Step 5: 커밋 (양쪽 레포)**

FE 레포와 BE 레포를 각각 커밋한다. 각 레포에서 Skill 도구로 `cavemancommit`을 invoke한다 (`git commit` 직접 실행 금지, committer 서브에이전트 사용 금지).

- FE 레포 `/home/ksb/Dev/home-jaram/home-jaram-fe`: `docs/api/openapi.yaml`
- BE 레포 `/home/ksb/Dev/home-jaram/home-jaram-be`: `src/main/resources/openapi/openapi.yaml`

---

### Task 2: BE — `MemberTitle` 축소와 라벨 파생

**Files:**
- Modify: `src/main/java/com/jaram/be/member/MemberTitle.java` (전면 교체)
- Modify: `src/main/java/com/jaram/be/people/PeopleService.java:85`
- Test: `src/test/java/com/jaram/be/member/MemberTitleTest.java` (신규)
- Test: `src/test/java/com/jaram/be/people/PeopleTest.java:47`
- Test: `src/test/java/com/jaram/be/contract/PeopleContractTest.java:42` 부근

**Interfaces:**
- Consumes: Task 1의 와이어 enum 값 목록
- Produces:
  - `MemberTitle` 상수: `PRESIDENT`, `VICE_PRESIDENT`, `LEAD`, `STAFF`, `SERVER_ADMIN`
  - `public String label(MemberDepartment d)` — 표시 라벨
  - `public boolean allowedIn(MemberDepartment d)` — 조합 허용 여부. Task 4가 쓴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

Create `src/test/java/com/jaram/be/member/MemberTitleTest.java`:

```java
package com.jaram.be.member;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberTitleTest {

    @Test
    void composesDepartmentLabelWithTitleSuffix() {
        assertThat(MemberTitle.LEAD.label(MemberDepartment.ACADEMIC)).isEqualTo("학술부장");
        assertThat(MemberTitle.STAFF.label(MemberDepartment.ACADEMIC)).isEqualTo("학술부원");
        assertThat(MemberTitle.LEAD.label(MemberDepartment.PR)).isEqualTo("홍보부장");
        assertThat(MemberTitle.STAFF.label(MemberDepartment.PR)).isEqualTo("홍보부원");
        assertThat(MemberTitle.LEAD.label(MemberDepartment.FINANCE)).isEqualTo("회계부장");
        assertThat(MemberTitle.STAFF.label(MemberDepartment.FINANCE)).isEqualTo("회계부원");
    }

    @Test
    void absoluteTitlesIgnoreDepartment() {
        assertThat(MemberTitle.PRESIDENT.label(MemberDepartment.LEADERSHIP)).isEqualTo("회장");
        assertThat(MemberTitle.VICE_PRESIDENT.label(MemberDepartment.LEADERSHIP)).isEqualTo("부회장");
        assertThat(MemberTitle.SERVER_ADMIN.label(MemberDepartment.INFRA)).isEqualTo("서버 관리자");
    }

    @Test
    void fallsBackWhenDepartmentMissing() {
        assertThat(MemberTitle.LEAD.label(null)).isEqualTo("부장");
        assertThat(MemberTitle.STAFF.label(null)).isEqualTo("부원");
        assertThat(MemberTitle.PRESIDENT.label(null)).isEqualTo("회장");
    }

    @Test
    void allowsOnlyTitlesThatFitTheDepartment() {
        assertThat(MemberTitle.PRESIDENT.allowedIn(MemberDepartment.LEADERSHIP)).isTrue();
        assertThat(MemberTitle.VICE_PRESIDENT.allowedIn(MemberDepartment.LEADERSHIP)).isTrue();
        assertThat(MemberTitle.LEAD.allowedIn(MemberDepartment.LEADERSHIP)).isFalse();

        assertThat(MemberTitle.LEAD.allowedIn(MemberDepartment.ACADEMIC)).isTrue();
        assertThat(MemberTitle.STAFF.allowedIn(MemberDepartment.FINANCE)).isTrue();
        assertThat(MemberTitle.PRESIDENT.allowedIn(MemberDepartment.ACADEMIC)).isFalse();

        assertThat(MemberTitle.SERVER_ADMIN.allowedIn(MemberDepartment.INFRA)).isTrue();
        assertThat(MemberTitle.SERVER_ADMIN.allowedIn(MemberDepartment.PR)).isFalse();
        assertThat(MemberTitle.LEAD.allowedIn(MemberDepartment.INFRA)).isFalse();
    }

    @Test
    void noTitleFitsAMissingDepartment() {
        for (MemberTitle t : MemberTitle.values()) {
            assertThat(t.allowedIn(null)).as(t.name()).isFalse();
        }
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.member.MemberTitleTest'`
Expected: 컴파일 실패. `MemberTitle.LEAD`, `label(MemberDepartment)`, `allowedIn(...)` 이 없다는 오류.

- [ ] **Step 3: `MemberTitle`을 교체한다**

Replace the whole of `src/main/java/com/jaram/be/member/MemberTitle.java`:

```java
package com.jaram.be.member;

// 직책. Persisted by enum name (@Enumerated STRING). 부서 무관한 직위만 담고,
// 표시 라벨은 MemberDepartment 와 조합해 파생한다 (ACADEMIC + LEAD → "학술부장").
// Member.authority 도 이 값에서 파생된다 — title 이 있으면 OFFICER.
public enum MemberTitle {
    PRESIDENT,       // LEADERSHIP 전용
    VICE_PRESIDENT,  // LEADERSHIP 전용
    LEAD,            // ACADEMIC / PR / FINANCE
    STAFF,           // ACADEMIC / PR / FINANCE
    SERVER_ADMIN;    // INFRA 전용

    // 표시 라벨. LEAD/STAFF 는 부서 라벨("학술부")에 접미사만 붙인다.
    // department 가 없으면 부서 없는 일반 표기로 폴백한다.
    public String label(MemberDepartment d) {
        return switch (this) {
            case PRESIDENT -> "회장";
            case VICE_PRESIDENT -> "부회장";
            case SERVER_ADMIN -> "서버 관리자";
            case LEAD -> (d == null ? "부" : d.label()) + "장";
            case STAFF -> (d == null ? "부" : d.label()) + "원";
        };
    }

    // 부서×직책 조합 규칙. 부서 없이 직책만 두는 것은 허용하지 않는다.
    public boolean allowedIn(MemberDepartment d) {
        if (d == null) return false;
        return switch (d) {
            case LEADERSHIP -> this == PRESIDENT || this == VICE_PRESIDENT;
            case ACADEMIC, PR, FINANCE -> this == LEAD || this == STAFF;
            case INFRA -> this == SERVER_ADMIN;
        };
    }
}
```

- [ ] **Step 4: `PeopleService`의 라벨 호출부를 고친다**

`src/main/java/com/jaram/be/people/PeopleService.java:85`의

```java
        if (m.getTitle() != null) return m.getTitle().label();
```

을 부서를 넘기도록 바꾼다.

```java
        if (m.getTitle() != null) return m.getTitle().label(m.getDepartment());
```

- [ ] **Step 5: 기존 테스트의 구 enum 값을 새 값으로 바꾼다**

`src/test/java/com/jaram/be/people/PeopleTest.java:47` —

```java
        active("박학술", "2023000002", "b@hanyang.ac.kr", MemberCategory.exec, MemberDepartment.ACADEMIC, MemberTitle.ACADEMIC_LEAD, 41);
```

을

```java
        active("박학술", "2023000002", "b@hanyang.ac.kr", MemberCategory.exec, MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
```

로 바꾼다. 같은 테스트의 `"exec.groups.heading"` 기대값("회장단", "학술부")은 그대로다.

`PeopleContractTest.java:42` 부근에서 `m.setTitle(MemberTitle.PRESIDENT);` 는 이름이 유지되므로 그대로 두되, 바로 앞뒤에 `m.setDepartment(MemberDepartment.LEADERSHIP);` 가 없으면 추가한다 (없으면 role 이 "회장"으로 나오긴 하나, 새 조합 규칙과 어긋난 데이터를 테스트가 만들지 않도록).

- [ ] **Step 6: 라벨 파생 회귀 테스트를 추가한다**

`src/test/java/com/jaram/be/people/PeopleTest.java`의 `returnsActiveMembersGroupedByTab` 안, 마지막 `.body(...)` 체인 끝에 한 줄을 더해 "학술부장"이 그대로 나오는지 못박는다.

```java
                .body("exec.groups.flatten().members.flatten().role", hasItems("회장", "학술부장"))
```

- [ ] **Step 7: 테스트를 돌려 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.member.MemberTitleTest' --tests 'com.jaram.be.people.*'`
Expected: PASS. `role`이 리팩터링 전과 같은 "학술부장"으로 나온다.

- [ ] **Step 8: 커밋**

BE 레포에서 Skill 도구로 `cavemancommit`을 invoke한다.

---

### Task 3: BE — `Authority` 저장 제거, `title`에서 파생

**Files:**
- Modify: `src/main/java/com/jaram/be/member/Member.java:26-27, 78, 93`
- Test: `src/test/java/com/jaram/be/member/MemberAuthorityTest.java` (신규)

**Interfaces:**
- Consumes: Task 2의 `MemberTitle` 상수
- Produces: `Member.getAuthority()` 가 파생값을 반환한다. 시그니처는 그대로 `public Authority getAuthority()` 이므로 `AuthService`, `MeService`, `JwtProvider` 호출부는 수정 불필요.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

Create `src/test/java/com/jaram/be/member/MemberAuthorityTest.java`:

```java
package com.jaram.be.member;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberAuthorityTest {

    private Member newMember() {
        return Member.newPending("홍길동", "2023000001", "hong@hanyang.ac.kr", "hash");
    }

    @Test
    void newMemberHasNoTitleAndIsAPlainMember() {
        Member m = newMember();
        assertThat(m.getTitle()).isNull();
        assertThat(m.getAuthority()).isEqualTo(Authority.MEMBER);
    }

    @Test
    void assigningAnyTitleGrantsOfficer() {
        Member m = newMember();
        m.setDepartment(MemberDepartment.ACADEMIC);
        m.setTitle(MemberTitle.STAFF);
        assertThat(m.getAuthority()).isEqualTo(Authority.OFFICER);

        m.setDepartment(MemberDepartment.LEADERSHIP);
        m.setTitle(MemberTitle.PRESIDENT);
        assertThat(m.getAuthority()).isEqualTo(Authority.OFFICER);
    }

    @Test
    void clearingTitleRevokesOfficer() {
        Member m = newMember();
        m.setDepartment(MemberDepartment.PR);
        m.setTitle(MemberTitle.LEAD);
        assertThat(m.getAuthority()).isEqualTo(Authority.OFFICER);

        m.setTitle(null);
        assertThat(m.getAuthority()).isEqualTo(Authority.MEMBER);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.member.MemberAuthorityTest'`
Expected: `assigningAnyTitleGrantsOfficer` 와 `clearingTitleRevokesOfficer` 가 FAIL — `getAuthority()`가 저장된 `MEMBER`를 그대로 돌려준다.

- [ ] **Step 3: `Member`에서 저장 필드를 지우고 게터를 파생으로 바꾼다**

`src/main/java/com/jaram/be/member/Member.java:26-27`의

```java
    @Enumerated(EnumType.STRING)
    private Authority authority = Authority.MEMBER;
```

두 줄을 삭제한다.

`Member.newPending` 안(`:78`)의

```java
        m.authority = Authority.MEMBER;
```

한 줄을 삭제한다.

`:93`의

```java
    public Authority getAuthority() { return authority; }
```

를 파생으로 바꾼다.

```java
    // 권한은 저장하지 않는다 — 직책이 있으면 임원. 부원(STAFF)도 임원 권한을 갖는다.
    public Authority getAuthority() { return title != null ? Authority.OFFICER : Authority.MEMBER; }
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.member.MemberAuthorityTest'`
Expected: PASS

- [ ] **Step 5: 전체 테스트로 회귀를 확인한다**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. 기존 테스트는 officer JWT를 `jwt.generate(..., Authority.OFFICER)` 로 직접 발급하므로 저장 컬럼 제거의 영향을 받지 않는다.

- [ ] **Step 6: 커밋**

BE 레포에서 Skill 도구로 `cavemancommit`을 invoke한다.

---

### Task 4: BE — 부서×직책 조합 검증

**Files:**
- Modify: `src/main/java/com/jaram/be/admin/AdminBatchExecutor.java:134-151` (`updateMember`)
- Test: `src/test/java/com/jaram/be/admin/AdminMemberAssignmentTest.java` (신규)

**Interfaces:**
- Consumes: Task 2의 `MemberTitle.allowedIn(MemberDepartment)`
- Produces: 없음 (종단 검증)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

기존 `AdminResourceTest`의 배치 호출 방식을 따른다. 먼저 `src/test/java/com/jaram/be/admin/AdminResourceTest.java`를 읽어 `officerToken` 발급과 `PATCH /api/admin/members:batch` 요청 바디 구성 방식을 그대로 가져온다.

Create `src/test/java/com/jaram/be/admin/AdminMemberAssignmentTest.java` — 배치 업데이트로 (a) 허용 조합이 저장되고 (b) 위반 조합이 `errors[0].fieldErrors.title` 로 거부되는지 검증한다.

```java
package com.jaram.be.admin;

import com.jaram.be.member.*;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminMemberAssignmentTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired com.jaram.be.security.JwtProvider jwt;

    String officerToken;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
    }

    private Member saved() {
        Member m = Member.newPending("김자람", "2023000001", "a@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        return members.save(m);
    }

    private io.restassured.response.Response patch(String id, Map<String, Object> fields) {
        return given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + officerToken)
                .body(Map.of("updates", List.of(Map.of("id", id, "fields", fields))))
                .when().patch("/api/admin/members:batch");
    }

    @Test
    void acceptsATitleThatFitsTheDepartment() {
        Member m = saved();

        patch(m.getId(), Map.of("department", "ACADEMIC", "title", "LEAD"))
                .then().statusCode(200)
                .body("updated[0].id", equalTo(m.getId()))
                .body("errors", anyOf(nullValue(), empty()));

        Member reloaded = members.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getDepartment()).isEqualTo(MemberDepartment.ACADEMIC);
        assertThat(reloaded.getTitle()).isEqualTo(MemberTitle.LEAD);
        assertThat(reloaded.getAuthority()).isEqualTo(Authority.OFFICER);
    }

    @Test
    void rejectsATitleThatDoesNotFitTheDepartment() {
        Member m = saved();

        patch(m.getId(), Map.of("department", "FINANCE", "title", "PRESIDENT"))
                .then().statusCode(200)
                .body("updated", anyOf(nullValue(), empty()))
                .body("errors[0].fieldErrors.title", notNullValue());

        Member reloaded = members.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isNull();
        assertThat(reloaded.getDepartment()).isNull();
    }

    @Test
    void rejectsATitleWithoutADepartment() {
        Member m = saved();

        patch(m.getId(), Map.of("title", "LEAD"))
                .then().statusCode(200)
                .body("errors[0].fieldErrors.title", notNullValue());

        assertThat(members.findById(m.getId()).orElseThrow().getTitle()).isNull();
    }

    @Test
    void clearingTheTitleAndDepartmentIsAllowed() {
        Member m = saved();
        m.setDepartment(MemberDepartment.PR);
        m.setTitle(MemberTitle.STAFF);
        members.save(m);

        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("department", null);
        fields.put("title", null);
        patch(m.getId(), fields).then().statusCode(200)
                .body("errors", anyOf(nullValue(), empty()));

        Member reloaded = members.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isNull();
        assertThat(reloaded.getAuthority()).isEqualTo(Authority.MEMBER);
    }
}
```

`@SpringBootTest`가 Testcontainers 설정을 어떻게 받는지는 기존 `AdminResourceTest`와 동일해야 한다. 그 파일의 클래스 어노테이션(예: 공통 베이스 클래스나 `@Testcontainers` 조합)을 그대로 복사해 맞춘다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.AdminMemberAssignmentTest'`
Expected: `rejectsATitleThatDoesNotFitTheDepartment` 와 `rejectsATitleWithoutADepartment` 가 FAIL — 현재는 조합 검증이 없어 그대로 저장된다.

- [ ] **Step 3: `updateMember`에 조합 검증을 넣는다**

`src/main/java/com/jaram/be/admin/AdminBatchExecutor.java`의 `updateMember`는 지금 필드마다 `actions`에 setter 호출을 쌓고 `errors`가 비면 일괄 적용한다. 조합은 `department`와 `title` 두 필드에 걸쳐 있으므로, 적용 전에 "요청에 온 값 + 없으면 현재 값"으로 최종 상태를 계산해 검사한다.

`updateMember` 메서드 전체를 아래로 교체하고, 그 아래에 헬퍼 둘을 새로 추가한다. 기존 `switch` 분기는 그대로 두고 조합 검증 블록만 얹는 형태다.

```java
    private Map<String, String> updateMember(Member m, Map<String, Object> f) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<Runnable> actions = new ArrayList<>();
        f.forEach((k, v) -> {
            switch (k) {
                case "name" -> actions.add(() -> m.setName(str(v)));
                case "gen" -> intField(v, errors, k, m::setGen, actions);
                case "grade" -> enumField(MemberGrade.class, v, errors, k, m::setGrade, actions, false);
                case "status" -> enumField(MemberStatus.class, v, errors, k, m::setStatus, actions, false);
                case "approval" -> enumField(MemberApproval.class, v, errors, k, m::setApproval, actions, false);
                case "department" -> enumField(MemberDepartment.class, v, errors, k, m::setDepartment, actions, true);
                case "title" -> enumField(MemberTitle.class, v, errors, k, m::setTitle, actions, true);
                default -> errors.put(k, "수정할 수 없는 필드입니다.");
            }
        });
        // 부서×직책은 두 필드에 걸친 규칙이라, 개별 파싱이 모두 통과한 뒤에 한 번 더 본다.
        // 요청에 없는 쪽은 엔티티의 현재 값을 그대로 쓴다.
        if (errors.isEmpty()) {
            MemberDepartment d = f.containsKey("department")
                    ? parsed(f.get("department"), MemberDepartment.class) : m.getDepartment();
            MemberTitle t = f.containsKey("title")
                    ? parsed(f.get("title"), MemberTitle.class) : m.getTitle();
            String combo = comboError(d, t);
            if (combo != null) errors.put("title", combo);
        }
        if (errors.isEmpty()) actions.forEach(Runnable::run);
        return errors;
    }

    // enumField 를 이미 통과한 값만 들어오므로 여기서 valueOf 는 실패하지 않는다.
    private <E extends Enum<E>> E parsed(Object v, Class<E> type) {
        return v == null ? null : Enum.valueOf(type, v.toString());
    }

    // 부서×직책 조합 규칙 위반 시 사용자 메시지, 문제 없으면 null.
    // 직책 없이 부서만 있는 것은 허용한다 (직책 미부여 상태).
    private String comboError(MemberDepartment d, MemberTitle t) {
        if (t == null) return null;
        if (d == null) return "직책을 지정하려면 부서를 함께 지정해 주세요.";
        if (t.allowedIn(d)) return null;
        return switch (d) {
            case LEADERSHIP -> "회장단에는 회장 또는 부회장만 지정할 수 있습니다.";
            case ACADEMIC, PR, FINANCE -> d.label() + "에는 부장 또는 부원만 지정할 수 있습니다.";
            case INFRA -> "인프라에는 서버 관리자만 지정할 수 있습니다.";
        };
    }
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인한다**

Run: `./gradlew test --tests 'com.jaram.be.admin.*'`
Expected: PASS

- [ ] **Step 5: 커밋**

BE 레포에서 Skill 도구로 `cavemancommit`을 invoke한다.

---

### Task 5: BE — `enrolled` 컬럼 제거

**Files:**
- Modify: `src/main/java/com/jaram/be/member/Member.java:54, 135-136`
- Modify: `src/main/java/com/jaram/be/auth/AuthService.java:60`
- Modify: `src/main/java/com/jaram/be/admin/AdminDashboardService.java:103`

**Interfaces:**
- Consumes: 없음
- Produces: 없음. `SignupRequest.enrolled` 입력 필드와 `DashboardStats.pendingBreakdown.enrolled` 응답 필드는 그대로 유지된다.

- [ ] **Step 1: `AdminDashboardService`의 집계를 `status` 기준으로 바꾼다**

`src/main/java/com/jaram/be/admin/AdminDashboardService.java:103`의

```java
                .filter(m -> Boolean.TRUE.equals(m.getEnrolled())).count();
```

를

```java
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE).count();
```

로 바꾼다. `com.jaram.be.member.MemberStatus` import가 없으면 추가한다.

- [ ] **Step 2: `AuthService`에서 중복 저장을 없앤다**

`src/main/java/com/jaram/be/auth/AuthService.java:60`의

```java
        m.setEnrolled(req.enrolled());
```

한 줄을 삭제한다. 바로 아래 두 줄은 그대로 둔다.

```java
        // 활동축 파생: 재학 → ACTIVE, 휴학 → ON_LEAVE. 승인축은 PENDING (팩토리 기본).
        m.setStatus(req.enrolled() ? MemberStatus.ACTIVE : MemberStatus.ON_LEAVE);
```

- [ ] **Step 3: `Member`에서 필드와 접근자를 지운다**

`src/main/java/com/jaram/be/member/Member.java:54`의

```java
    private Boolean enrolled;     // 재학여부 (true=재학, false=휴학)
```

한 줄을 삭제하고, `:135-136`의

```java
    public Boolean getEnrolled() { return enrolled; }
    public void setEnrolled(Boolean e) { this.enrolled = e; }
```

두 줄도 삭제한다.

- [ ] **Step 4: 남은 참조가 없는지 확인한다**

Run: `grep -rn "nrolled" src/main/java`
Expected: `AuthService.java`의 `req.enrolled()` 두 곳(요청 DTO 필드)과 `SignupRequest`, `AdminDashboardService`의 `PendingBreakdown` 관련 지역변수만 남는다. `Member`의 `getEnrolled`/`setEnrolled` 호출은 0건이어야 한다.

- [ ] **Step 5: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. `SignupTest`는 요청 필드만 쓰므로 영향이 없고, `AdminDashboardTest`의 `pendingBreakdown.enrolled` 기대값은 승인 대기 회원이 기본 `ACTIVE`라 그대로 유지된다. 만약 해당 단언이 깨지면 테스트가 세운 회원의 `status`를 확인하고, 의도한 값(휴학 회원은 `ON_LEAVE`)으로 픽스처를 명시한다.

- [ ] **Step 6: 커밋**

BE 레포에서 Skill 도구로 `cavemancommit`을 invoke한다.

---

### Task 6: FE — 직책 라벨 조합

**Files:**
- Modify (FE 레포): `src/shared/member/enums.js:17-45`
- Modify (FE 레포): `src/features/profile/views/ProfileView.jsx:23`

**Interfaces:**
- Consumes: Task 1의 와이어 enum 값 목록
- Produces: `titleLabel(title, department)` — 시그니처가 1인자에서 2인자로 바뀐다.

- [ ] **Step 1: `TITLE_LABELS`와 `titleLabel`을 조합 방식으로 바꾼다**

`/home/ksb/Dev/home-jaram/home-jaram-fe/src/shared/member/enums.js`에서 `TITLE_LABELS` 정의부터 파일 끝까지를 아래로 교체한다. (`DEPARTMENT_LABELS`, `DEPARTMENTS`, `departmentLabel`은 그대로 둔다.)

```js
// 직책 키 → 고정 라벨. LEAD/STAFF는 부서와 조합해야 라벨이 정해지므로 여기 없다.
const ABSOLUTE_TITLE_LABELS = {
  PRESIDENT: '회장',
  VICE_PRESIDENT: '부회장',
  SERVER_ADMIN: '서버 관리자',
};

// 부서에 붙는 직위 접미사. 부서 라벨이 이미 "학술부"이므로 접미사만 더한다.
const TITLE_SUFFIX = { LEAD: '장', STAFF: '원' };

// 직책 키 배열 — 셀렉터 옵션 등에 사용 (표시 순서 유지)
export const TITLES = ['PRESIDENT', 'VICE_PRESIDENT', 'LEAD', 'STAFF', 'SERVER_ADMIN'];

/**
 * 직책 라벨. LEAD/STAFF는 부서 라벨과 조합한다(ACADEMIC + LEAD → "학술부장").
 * 부서가 없으면 부서 없는 일반 표기로 폴백한다. 모르는/빈 키는 null.
 */
export function titleLabel(title, department) {
  if (!title) return null;
  if (ABSOLUTE_TITLE_LABELS[title]) return ABSOLUTE_TITLE_LABELS[title];
  const suffix = TITLE_SUFFIX[title];
  if (!suffix) return null;
  return (DEPARTMENT_LABELS[department] ?? '부') + suffix;
}
```

기존 `TITLE_LABELS` export와 `OB`/`REGULAR`/`ASSOCIATE`/`NEWCOMER` 항목은 함께 사라진다 — 이 넷은 등급(`MemberGrade`) 값이 직책 맵에 잘못 남아 있던 것이다.

- [ ] **Step 2: 상단 주석을 새 구조에 맞춘다**

같은 파일 첫 블록 주석에서 "와이어에는 enum name(키)이 오고, 화면 표시는 여기 한글 라벨로 매핑한다" 문장 뒤에 한 줄을 더한다.

```js
 * 직책은 부서와 조합해야 라벨이 정해진다(ACADEMIC + LEAD → 학술부장).
```

- [ ] **Step 3: `ProfileView`의 호출부를 고친다**

`src/features/profile/views/ProfileView.jsx:23`의

```jsx
    if (key === 'title') return titleLabel(me[key]) ?? empty;
```

를

```jsx
    if (key === 'title') return titleLabel(me.title, me.department) ?? empty;
```

로 바꾼다.

- [ ] **Step 4: 남은 참조를 확인한다**

Run: `cd /home/ksb/Dev/home-jaram/home-jaram-fe && grep -rn "TITLE_LABELS\|titleLabel" src/`
Expected: `enums.js`의 정의부와 `ProfileView.jsx:23` 한 곳만 나온다. `TITLE_LABELS`를 import하는 곳은 없어야 한다.

- [ ] **Step 5: lint와 빌드를 돌린다**

Run: `cd /home/ksb/Dev/home-jaram/home-jaram-fe && npm run lint && npm run build`
Expected: 둘 다 오류 없이 완료.

- [ ] **Step 6: 수동 확인**

Run: `cd /home/ksb/Dev/home-jaram/home-jaram-fe && npm run dev`
프로필 화면에서 직책이 있는 계정은 "학술부장"처럼, 직책이 없는 계정은 빈 값 표시로 나오는지 본다. 확인 후 dev 서버를 종료한다.

- [ ] **Step 7: 커밋**

FE 레포 `/home/ksb/Dev/home-jaram/home-jaram-fe`에서 Skill 도구로 `cavemancommit`을 invoke한다.

---

### Task 7: 마무리 — 백로그 갱신과 전체 검증

**Files:**
- Modify: `docs/superpowers/plans/2026-07-02-be-refactor-and-feature-backlog.md` (§6 잔여 작업 항목 갱신)

**Interfaces:**
- Consumes: Task 2~6
- Produces: 없음

- [ ] **Step 1: 전체 BE 테스트를 돌린다**

Run: `cd /home/ksb/Dev/home-jaram/home-jaram-be && ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 계약 복사본이 최신인지 재확인한다**

Run: `cd /home/ksb/Dev/home-jaram/home-jaram-be && ./scripts/sync-openapi.sh`
Expected: `Already in sync: .../src/main/resources/openapi/openapi.yaml`

- [ ] **Step 3: 백로그에 완료 기록을 남긴다**

`docs/superpowers/plans/2026-07-02-be-refactor-and-feature-backlog.md`의 `## 4-1. 구현 완료 기록` 목록 끝에 한 줄을 추가한다.

```markdown
- [x] **P8 — 회원 축 중복 제거** (MemberTitle 5값 축소·라벨 파생 · Authority 파생 · enrolled 제거 · 부서×직책 검증) — spec `docs/superpowers/specs/2026-07-20-member-axis-dedup-design.md`
```

같은 문서 `### 6-3. 구조적 보강`에 `Authority`가 부여되지 않는다는 항목이 있으면 해소됐다고 표시한다. 없으면 추가하지 않는다.

- [ ] **Step 4: 데이터 마이그레이션 SQL을 배포 노트로 남긴다**

`docs/superpowers/specs/2026-07-20-member-axis-dedup-design.md` §5에 이미 SQL이 있다. 배포 담당에게 전달해야 함을 백로그 §6 상단에 한 줄로 적는다.

```markdown
> 배포 전 필수: 회원 축 리팩터링(P8)의 enum 값 마이그레이션 SQL — spec `2026-07-20-member-axis-dedup-design.md` §5 참조.
```

- [ ] **Step 5: 커밋**

BE 레포에서 Skill 도구로 `cavemancommit`을 invoke한다.

---

## 미해결 위험

- **Task 4의 `enumField` 재파싱.** `enumField`가 파싱 결과를 `actions` 클로저 안에 가두므로, 조합 검증에서 `f.get(...)`을 한 번 더 `Enum.valueOf` 한다. 같은 값을 두 번 파싱하는 셈이라 깔끔하지 않다. 구현 중 `enumField`가 파싱값을 돌려주도록 바꾸는 편이 나아 보이면 그렇게 해도 된다 — 단, 다른 리소스(`updateSeminar`·`updateStudy`)의 호출부까지 함께 고쳐야 한다.
- **`AdminDashboardTest`의 `pendingBreakdown.enrolled`.** Task 5 Step 5에서 이 단언이 깨질 가능성이 있다. 깨지면 픽스처의 `status`를 명시적으로 세워 의도를 드러낸다.
- **기존 DB 데이터.** 로컬/운영 DB에 구 `title` 값이 있으면 Hibernate가 역직렬화에 실패한다. spec §5의 SQL을 먼저 돌려야 한다. 테스트는 `create-drop`이라 영향이 없다.
