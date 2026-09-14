# 권한 재설계 (Policy / Role) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `MEMBER`/`OFFICER` 2단계 권한을 Permission 20개 × Role 10개 매트릭스로 바꾸고, 권한 판정을 `SecurityConfig` 의 URL 문자열에서 핸들러의 `@PreAuthorize` 로 옮긴다.

**Architecture:** 요청마다 3층으로 평가한다. 1층 `Eligibility` 가 승인·활동 상태로 상한을 정하고(교집합), 2층 `RoleResolver` 가 현직 임기에서 Role 을 파생해 `Policy` 매트릭스로 Permission 집합을 전개하며(가산 OR), 3층은 도메인 `*AccessPolicy` 빈이 리소스 조건을 본다. JWT 는 신원만 싣고 권한은 매 요청 DB 에서 읽는다 — `Eligibility` 가 어차피 회원을 로드하고 `Member.terms` 가 `FetchType.EAGER` 라 추가 비용이 없다.

**Tech Stack:** Spring Boot 3.x, Spring Security (`@EnableMethodSecurity`, `@PreAuthorize`), JPA/Hibernate (`ddl-auto: update`), jjwt, JUnit 5 + RestAssured + Testcontainers(Postgres 16)

**Spec:** `docs/superpowers/specs/2026-09-14-authz-policy-role-design.md`

**Branch:** `feat/authz-policy-role` (base: `origin/develop` @ `306231f`)

## Global Constraints

- **새 테이블을 만들지 않는다.** Role 은 `member_term(department, title)` 에서 파생한다 (스펙 §4). `member_role_grant` 는 범위 밖이다.
- **API 계약을 깨지 않는다.** `MeProfile.authority` 와 `UserSummary.authority` 는 `MEMBER`/`OFFICER` 값과 현재 파생 규칙(현직 임기가 있으면 OFFICER)을 그대로 유지한다. `roles[]` 와 `permissions[]` 는 **추가**만 한다 (스펙 §9). `Authority` 는 이 계획이 끝난 뒤 **인가 판정에 한 곳도 쓰이지 않는다** — 순수하게 계약 필드로만 남으며, FE 가 `isAdmin` 을 `permissions` 기준으로 바꾼 뒤 별도 배포에서 제거한다 (필수 후속 작업, Task 13 참조).
- **`GrantedAuthority` 의 문자열은 `Permission` enum 의 `name()` 그대로다.** 접두사(`ROLE_`, `PERM_`)를 붙이지 않는다. `@PreAuthorize` 는 항상 `hasAuthority('...')` 를 쓰고 `hasRole` 은 쓰지 않는다.
- **실패 방향은 막히는 쪽이다.** `@PreAuthorize` 를 빠뜨리면 `anyRequest().authenticated()` 에 걸려 거부돼야 한다. 조용히 열리는 경로를 새로 만들지 않는다.
- **매 태스크 끝에 전체 테스트가 초록이어야 한다.** 중간 상태에서 깨진 채 다음 태스크로 넘어가지 않는다.
- **주석과 커밋 메시지는 한국어**, 기존 코드 문체를 따른다 (왜 그렇게 했는지를 적는다).
- **테스트 실행 명령** (이 호스트 전용 우회 두 가지가 필요하다 — JDK 21 경로와 Testcontainers 의 Docker API 버전):

  ```bash
  export JAVA_HOME=/home/ksb/.local/jdk-21
  export PATH="$JAVA_HOME/bin:$PATH"
  ./gradlew --no-daemon -I /home/ksb/.claude/jobs/d6573f1c/tmp/docker-api.init.gradle test
  ```

  단일 클래스는 `--tests 'com.jaram.be.security.authz.PolicyMatrixTest'` 를 덧붙인다.
  init 스크립트가 없으면 아래 내용으로 만든다:

  ```groovy
  allprojects {
      tasks.withType(Test).configureEach {
          systemProperty 'api.version', '1.44'
      }
  }
  ```

- **Gradle 이 "UP-TO-DATE" 로 건너뛴 실행을 통과로 착각하지 않는다.** 판정이 필요한 실행에는 `--rerun` 을 붙이거나 소요 시간이 1분 이상인지 확인한다 (전체 스위트는 약 1분 20초).

---

## 스펙에 없어 이 계획에서 정한 것

스펙 §5 의 Permission 표는 컨트롤러 표면에서 도출했지만 세 곳이 비어 있었다. 구현이 막히므로 여기서 정한다. 새 Permission 은 만들지 않는다.

| 빈 곳 | 결정 | 근거 |
|---|---|---|
| `GET /api/admin/{resource}` (관리 목록) | `members` → `MEMBER_READ`, `seminars` → `SEMINAR_EDIT`, `studies` → `STUDY_EDIT` | 목록과 일괄 편집이 같은 화면이다. `members` 만 읽기 권한을 따로 두는 이유는 `FINANCE_STAFF` 가 `MEMBER_READ` 만 갖기 때문 — 여기서 `MEMBER_EDIT` 을 요구하면 그 Role 이 쓸 화면이 없어진다. 세미나·스터디에는 매트릭스에 읽기 전용 Role 이 없어 새 Permission 이 낭비다 |
| `PATCH /api/admin/{resource}:batch` | `members` → `MEMBER_EDIT`, `seminars` → `SEMINAR_EDIT`, `studies` → `STUDY_EDIT` | 스펙 §5 표 그대로. 핸들러가 하나라 경로 변수로 갈라야 하므로 `@adminResourceAccess` 빈을 쓴다 |
| `PATCH /api/admin/settings` 의 필드별 권한 | `currentGen` → `SETTINGS_ROLLOVER`, `semesterTerm`·`autoPromote` → `SETTINGS_EDIT`, `links` → `SETTINGS_EDIT` **또는** `SITE_LINKS_EDIT` | 스펙이 `SITE_LINKS_EDIT` 을 분리한 목적(홍보부가 푸터 링크만 고친다)은 엔드포인트가 하나뿐이라 필드 조건으로만 달성된다. `SETTINGS_ROLLOVER` 에 대응하는 별도 엔드포인트는 존재하지 않는다 — 기수 전환은 `MemberLifecycleService` 의 스윕이고, 사람이 손으로 기수를 바꾸는 유일한 통로가 이 필드다 |

추가로 스펙 §8 이 적지 않은 것:

- `SeminarService.getOne(id, callerId, officer)` 의 `officer` 불리언은 "승인 전 세미나를 볼 수 있는가"다. `SEMINAR_APPROVE` 로 옮긴다. `ACADEMIC_STAFF` 는 이 권한이 없어 남의 미승인 세미나를 못 보게 되지만, 본인 것은 소유자 조건으로 계속 보인다 — 최소 권한에 맞는 방향이다.
- P6 위계는 임기를 바꾸는 유일한 코드 경로인 `AdminBatchExecutor.updateMember()` 에 건다 (`department`/`title` 필드와 `grade=OB` 로 인한 임기 종료 둘 다).

---

## 파일 구조

**새로 만드는 것** — `com.jaram.be.security.authz` 패키지 하나에 모은다. 권한 표가 흩어지면 "누가 무엇을 할 수 있나"를 읽으려고 여러 파일을 열어야 한다.

| 파일 | 책임 |
|---|---|
| `security/authz/Permission.java` | 행위 20개 enum. 데이터만 |
| `security/authz/Role.java` | 직책 10개 enum + `rank` + `(department,title) → Role` 매핑 |
| `security/authz/Policy.java` | Role → `Set<Permission>` 매트릭스 한 곳 + P6 `canAssign` |
| `security/authz/RoleResolver.java` | `Member → Set<Role>`. 파생 규칙의 유일한 이음매 |
| `security/authz/Eligibility.java` | 1층 자격 게이트. `MemberActivityGuard` 를 흡수 |
| `security/authz/Permissions.java` | `Authentication` 에서 Permission 보유를 확인하는 정적 헬퍼 |
| `admin/AdminResourceAccess.java` | `@PreAuthorize` 용 빈. 경로 변수 `{resource}` 로 갈라지는 권한 |
| `admin/SettingsAccess.java` | `@PreAuthorize` 용 빈. 설정 PATCH 의 필드별 권한 |
| `seminar/SeminarAccessPolicy.java` | 3층 조건. 본인 세미나 여부 |
| `src/test/java/.../support/Actors.java` | 테스트용 — 실제 회원 + 임기를 저장하고 토큰을 만든다 |

**고치는 것**

| 파일 | 변경 |
|---|---|
| `security/JwtProvider.java` | `authority` 클레임 제거. `generate(memberId, name, email)` |
| `security/JwtAuthFilter.java` | Eligibility → RoleResolver → Policy 전개 후 Permission 단위 권한 부여 |
| `security/CurrentMember.java` | `Set<Role> roles`, `Set<Permission> permissions` 추가 |
| `security/SecurityConfig.java` | `@EnableMethodSecurity`. 매처를 public 경로 + `anyRequest().authenticated()` 로 축소 |
| 컨트롤러 9개 | `@PreAuthorize` 부착 |
| `admin/AdminBatchExecutor.java` 외 2 | 행위자(actor)를 P6 검사까지 전달 |
| `me/dto/MeProfile.java`, `auth/dto/UserSummary.java` | `roles[]`, `permissions[]` 추가 |
| `member/MemberActivityGuard.java` | 삭제 — `Eligibility` 로 흡수 |

**삭제하지 않는 것:** `member/Authority.java` 와 `Member.getAuthority()` 는 계약이 쓰므로 남는다.

---

## 태스크 순서와 각 단계의 초록 상태

1–3 은 배선 없는 순수 코드라 기존 테스트에 영향이 없다. 4 는 **기존 `OFFICER` 권한을 유지한 채** Permission 을 **덧붙이기만** 하므로 URL 매처가 그대로 동작한다. 5 는 테스트만 바꾼다. 6–10 에서 도메인별로 `@PreAuthorize` 로 옮기고 해당 매처를 지운다. 11 이 매처를 비우고 누락 검사를 건다. 12 가 마지막으로 `authority` 클레임을 뺀다.

---

### Task 1: Permission, Role, Policy — 권한 매트릭스

**Files:**
- Create: `src/main/java/com/jaram/be/security/authz/Permission.java`
- Create: `src/main/java/com/jaram/be/security/authz/Role.java`
- Create: `src/main/java/com/jaram/be/security/authz/Policy.java`
- Test: `src/test/java/com/jaram/be/security/authz/PolicyMatrixTest.java`

**Interfaces:**
- Consumes: `com.jaram.be.member.MemberDepartment`, `com.jaram.be.member.MemberTitle` (기존 enum, 변경 없음)
- Produces:
  - `enum Permission` — 20개 상수
  - `enum Role` — 10개 상수, `int rank()`, `static Optional<Role> of(MemberDepartment d, MemberTitle t)`
  - `final class Policy` — `static Set<Permission> permissionsOf(Role r)`, `static Set<Permission> permissionsOf(Set<Role> roles)`, `static boolean canAssign(Set<Role> actorRoles, Role target)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/security/authz/PolicyMatrixTest.java`:

```java
package com.jaram.be.security.authz;

import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTitle;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.jaram.be.security.authz.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 권한 매트릭스를 표로 고정한다. 매트릭스가 바뀌면 이 테스트가 먼저 깨지고,
 * PR diff 에 "누가 무엇을 얻고 잃는지"가 드러난다.
 */
class PolicyMatrixTest {

    @Test
    void presidentIsTheOnlyWildcard() {
        assertThat(Policy.permissionsOf(Role.PRESIDENT))
                .containsExactlyInAnyOrder(Permission.values());
    }

    @Test
    void vicePresidentLacksRolloverAndExport() {
        assertThat(Policy.permissionsOf(Role.VICE_PRESIDENT))
                .doesNotContain(SETTINGS_ROLLOVER, EXPORT_RUN)
                .hasSize(Permission.values().length - 2);
    }

    @Test
    void serverAdminManagesSettingsAndExportOnly() {
        assertThat(Policy.permissionsOf(Role.SERVER_ADMIN)).containsExactlyInAnyOrder(
                SETTINGS_READ, SETTINGS_EDIT, EXPORT_RUN, DASHBOARD_READ, MEMBER_READ);
    }

    @Test
    void academicLeadOwnsSeminarsAndStudies() {
        assertThat(Policy.permissionsOf(Role.ACADEMIC_LEAD)).containsExactlyInAnyOrder(
                SEMINAR_CREATE, SEMINAR_APPROVE, SEMINAR_ATTENDANCE_MANAGE,
                SEMINAR_ROSTER_READ, SEMINAR_ROSTER_EDIT, SEMINAR_EDIT,
                STUDY_APPROVE, STUDY_APPLICANT_MANAGE, STUDY_EDIT,
                SCHEDULE_MANAGE, DASHBOARD_READ);
    }

    @Test
    void academicStaffRunsSeminarsButCannotApprove() {
        assertThat(Policy.permissionsOf(Role.ACADEMIC_STAFF)).containsExactlyInAnyOrder(
                SEMINAR_CREATE, SEMINAR_ATTENDANCE_MANAGE,
                SEMINAR_ROSTER_READ, SEMINAR_ROSTER_EDIT,
                STUDY_APPLICANT_MANAGE, DASHBOARD_READ);
    }

    @Test
    void prLeadAndStaffShareTheSameSet() {
        Set<Permission> expected = Set.of(
                SETTINGS_READ, SITE_LINKS_EDIT, SEMINAR_CREATE, DASHBOARD_READ);
        assertThat(Policy.permissionsOf(Role.PR_LEAD)).isEqualTo(expected);
        assertThat(Policy.permissionsOf(Role.PR_STAFF)).isEqualTo(expected);
    }

    @Test
    void financeHasMemberPermissionsOnly() {
        assertThat(Policy.permissionsOf(Role.FINANCE_LEAD))
                .containsExactlyInAnyOrder(MEMBER_READ, MEMBER_APPROVE, DASHBOARD_READ);
        assertThat(Policy.permissionsOf(Role.FINANCE_STAFF))
                .containsExactlyInAnyOrder(MEMBER_READ, DASHBOARD_READ);
    }

    /** 임기 없는 회원은 권한이 없다. 조회·출석·신청은 인증과 자격으로 통과한다. */
    @Test
    void plainMemberHasNoPermissions() {
        assertThat(Policy.permissionsOf(Role.MEMBER)).isEmpty();
    }

    @Test
    void rolesAreAdditive() {
        assertThat(Policy.permissionsOf(Set.of(Role.FINANCE_STAFF, Role.PR_STAFF)))
                .containsExactlyInAnyOrder(
                        MEMBER_READ, DASHBOARD_READ, SETTINGS_READ, SITE_LINKS_EDIT, SEMINAR_CREATE);
    }

    @Test
    void everyValidDepartmentTitlePairMapsToARole() {
        assertThat(Role.of(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT)).contains(Role.PRESIDENT);
        assertThat(Role.of(MemberDepartment.LEADERSHIP, MemberTitle.VICE_PRESIDENT)).contains(Role.VICE_PRESIDENT);
        assertThat(Role.of(MemberDepartment.INFRA, MemberTitle.SERVER_ADMIN)).contains(Role.SERVER_ADMIN);
        assertThat(Role.of(MemberDepartment.ACADEMIC, MemberTitle.LEAD)).contains(Role.ACADEMIC_LEAD);
        assertThat(Role.of(MemberDepartment.ACADEMIC, MemberTitle.STAFF)).contains(Role.ACADEMIC_STAFF);
        assertThat(Role.of(MemberDepartment.PR, MemberTitle.LEAD)).contains(Role.PR_LEAD);
        assertThat(Role.of(MemberDepartment.PR, MemberTitle.STAFF)).contains(Role.PR_STAFF);
        assertThat(Role.of(MemberDepartment.FINANCE, MemberTitle.LEAD)).contains(Role.FINANCE_LEAD);
        assertThat(Role.of(MemberDepartment.FINANCE, MemberTitle.STAFF)).contains(Role.FINANCE_STAFF);
    }

    /** 허용되지 않는 조합은 Role 이 없다 — 막히는 쪽으로 실패한다. */
    @Test
    void invalidPairHasNoRole() {
        assertThat(Role.of(MemberDepartment.ACADEMIC, MemberTitle.PRESIDENT)).isEmpty();
        assertThat(Role.of(null, MemberTitle.LEAD)).isEmpty();
        assertThat(Role.of(MemberDepartment.ACADEMIC, null)).isEmpty();
    }

    /** P6 — 자기보다 낮은 rank 만 임명할 수 있다. */
    @Test
    void assignmentRequiresStrictlyHigherRank() {
        Set<Role> vp = Set.of(Role.VICE_PRESIDENT);
        assertThat(Policy.canAssign(vp, Role.ACADEMIC_LEAD)).isTrue();
        assertThat(Policy.canAssign(vp, Role.SERVER_ADMIN)).isTrue();
        assertThat(Policy.canAssign(vp, Role.PRESIDENT)).isFalse();
        // 같은 rank 도 안 된다 — 자기 임기를 스스로 고칠 수 없다는 규칙이 여기서 나온다
        assertThat(Policy.canAssign(vp, Role.VICE_PRESIDENT)).isFalse();
        assertThat(Policy.canAssign(Set.of(Role.PRESIDENT), Role.PRESIDENT)).isFalse();
        assertThat(Policy.canAssign(Set.of(Role.PRESIDENT), Role.VICE_PRESIDENT)).isTrue();
    }

    /** TERM_ASSIGN 이 없으면 rank 가 높아도 임명할 수 없다. */
    @Test
    void assignmentRequiresTermAssignPermission() {
        assertThat(Policy.permissionsOf(Role.SERVER_ADMIN)).doesNotContain(TERM_ASSIGN);
        assertThat(Policy.canAssign(Set.of(Role.SERVER_ADMIN), Role.ACADEMIC_STAFF)).isFalse();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.security.authz.PolicyMatrixTest'`
Expected: 컴파일 실패 — `package com.jaram.be.security.authz does not exist`

- [ ] **Step 3: Permission 을 만든다**

`src/main/java/com/jaram/be/security/authz/Permission.java`:

```java
package com.jaram.be.security.authz;

/**
 * 행위 단위 권한. 지위가 아니라 할 수 있는 일로 이름 붙인다 — "부장이니까"가 아니라
 * "세미나를 승인할 수 있으니까" 로 읽혀야 매트릭스가 사람 눈에 검증된다.
 *
 * enum name 이 그대로 GrantedAuthority 문자열이며 @PreAuthorize 가 참조한다.
 * 이름을 바꾸면 애너테이션 문자열도 같이 바꿔야 하고, 컴파일러가 잡아 주지 않는다 —
 * AdminAuthorizationCoverageTest 가 잡는다.
 */
public enum Permission {
    MEMBER_READ,
    MEMBER_APPROVE,
    MEMBER_EDIT,
    TERM_ASSIGN,

    SEMINAR_CREATE,
    SEMINAR_APPROVE,
    SEMINAR_ATTENDANCE_MANAGE,
    SEMINAR_ROSTER_READ,
    SEMINAR_ROSTER_EDIT,
    SEMINAR_EDIT,

    STUDY_APPROVE,
    STUDY_APPLICANT_MANAGE,
    STUDY_EDIT,

    SCHEDULE_MANAGE,

    SETTINGS_READ,
    SETTINGS_EDIT,
    SETTINGS_ROLLOVER,
    SITE_LINKS_EDIT,

    DASHBOARD_READ,
    EXPORT_RUN
}
```

- [ ] **Step 4: Role 을 만든다**

`src/main/java/com/jaram/be/security/authz/Role.java`:

```java
package com.jaram.be.security.authz;

import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTitle;

import java.util.Optional;

/**
 * 주체와 권한 사이의 중간 계층. 권한 집합은 들고 있지 않다 — Policy 가 갖는다.
 * 나눈 이유는 바뀌는 빈도다. Role 목록은 직제가 바뀔 때만 바뀌고(거의 없다),
 * 매트릭스는 기능이 늘 때마다 바뀐다.
 *
 * rank 는 P6 위계에 쓴다. 임기를 부여·종료하려면 대상 Role 보다 rank 가 커야 한다.
 * 간격을 40·60·80·90·100 으로 벌려 둔 것은 나중에 중간 직책이 생겨도 재번호가
 * 필요 없게 하기 위해서다.
 */
public enum Role {
    PRESIDENT(100),
    VICE_PRESIDENT(90),
    SERVER_ADMIN(80),
    ACADEMIC_LEAD(60),
    PR_LEAD(60),
    FINANCE_LEAD(60),
    ACADEMIC_STAFF(40),
    PR_STAFF(40),
    FINANCE_STAFF(40),
    /** 임기가 없는 회원. Discord 의 @everyone 자리이며 권한은 비어 있다. */
    MEMBER(0);

    private final int rank;

    Role(int rank) { this.rank = rank; }

    public int rank() { return rank; }

    /**
     * 임기 한 줄을 Role 로 옮긴다. MemberTitle.allowedIn 이 유효 조합을 이미 9개로
     * 닫아 놨으므로 그 9개만 매핑하고 나머지는 비운다 — 막히는 쪽으로 실패한다.
     */
    public static Optional<Role> of(MemberDepartment d, MemberTitle t) {
        if (d == null || t == null || !t.allowedIn(d)) return Optional.empty();
        return Optional.of(switch (d) {
            case LEADERSHIP -> t == MemberTitle.PRESIDENT ? PRESIDENT : VICE_PRESIDENT;
            case INFRA -> SERVER_ADMIN;
            case ACADEMIC -> t == MemberTitle.LEAD ? ACADEMIC_LEAD : ACADEMIC_STAFF;
            case PR -> t == MemberTitle.LEAD ? PR_LEAD : PR_STAFF;
            case FINANCE -> t == MemberTitle.LEAD ? FINANCE_LEAD : FINANCE_STAFF;
        });
    }
}
```

- [ ] **Step 5: Policy 를 만든다**

`src/main/java/com/jaram/be/security/authz/Policy.java`:

```java
package com.jaram.be.security.authz;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.jaram.be.security.authz.Permission.*;

/**
 * 권한 매트릭스. 이 파일 하나만 열면 전체 표가 한 화면에 보이고, PR 리뷰에서
 * 누가 무엇을 얻고 잃는지가 diff 로 드러난다.
 *
 * DB 에 두지 않은 이유는 두 가지다. 비엔지니어가 런타임에 이 표를 바꿀 상황이 없고,
 * 코드에 두어야 매트릭스 변경이 코드 리뷰를 거친다.
 *
 * 역할은 가산(OR)만 한다. 역할 레벨의 거부는 없다 — 순서로 해결되는 규칙은 사람이
 * 읽지 못한다.
 */
public final class Policy {

    private Policy() { }

    private static final Map<Role, Set<Permission>> MATRIX = new EnumMap<>(Role.class);

    static {
        // P7 — 탈출 해치는 정확히 하나다.
        MATRIX.put(Role.PRESIDENT, EnumSet.allOf(Permission.class));

        // 되돌리기 어려운 기수 전환과 개인정보 반출만 회장에게 남긴다.
        MATRIX.put(Role.VICE_PRESIDENT,
                EnumSet.complementOf(EnumSet.of(SETTINGS_ROLLOVER, EXPORT_RUN)));

        MATRIX.put(Role.SERVER_ADMIN, EnumSet.of(
                SETTINGS_READ, SETTINGS_EDIT, EXPORT_RUN, DASHBOARD_READ, MEMBER_READ));

        MATRIX.put(Role.ACADEMIC_LEAD, EnumSet.of(
                SEMINAR_CREATE, SEMINAR_APPROVE, SEMINAR_ATTENDANCE_MANAGE,
                SEMINAR_ROSTER_READ, SEMINAR_ROSTER_EDIT, SEMINAR_EDIT,
                STUDY_APPROVE, STUDY_APPLICANT_MANAGE, STUDY_EDIT,
                SCHEDULE_MANAGE, DASHBOARD_READ));

        // 부원은 운영은 하되 승인은 못 한다. 지금까지 부원도 임원 권한 전부를 갖고
        // 있었으므로 이 줄이 이번 변경에서 실제로 권한을 잃는 지점이다.
        MATRIX.put(Role.ACADEMIC_STAFF, EnumSet.of(
                SEMINAR_CREATE, SEMINAR_ATTENDANCE_MANAGE,
                SEMINAR_ROSTER_READ, SEMINAR_ROSTER_EDIT,
                STUDY_APPLICANT_MANAGE, DASHBOARD_READ));

        // 홍보부는 부장과 부원의 권한이 같다. 나눌 일이 아직 없다.
        Set<Permission> pr = EnumSet.of(
                SETTINGS_READ, SITE_LINKS_EDIT, SEMINAR_CREATE, DASHBOARD_READ);
        MATRIX.put(Role.PR_LEAD, pr);
        MATRIX.put(Role.PR_STAFF, pr);

        // 앱에 회계 기능이 없다. 회계 기능이 생기면 FINANCE_* Permission 을 추가한다.
        MATRIX.put(Role.FINANCE_LEAD, EnumSet.of(MEMBER_READ, MEMBER_APPROVE, DASHBOARD_READ));
        MATRIX.put(Role.FINANCE_STAFF, EnumSet.of(MEMBER_READ, DASHBOARD_READ));

        // 비어 있는 것이 의도다. 세미나 조회·출석, 스터디 신청, 일정 슬롯 예약은
        // 권한이 아니라 인증 + 1층 자격으로 통과한다.
        MATRIX.put(Role.MEMBER, EnumSet.noneOf(Permission.class));
    }

    public static Set<Permission> permissionsOf(Role role) {
        return MATRIX.getOrDefault(role, Set.of());
    }

    public static Set<Permission> permissionsOf(Set<Role> roles) {
        EnumSet<Permission> union = EnumSet.noneOf(Permission.class);
        roles.forEach(r -> union.addAll(permissionsOf(r)));
        return union;
    }

    /**
     * P6 — 임기를 부여하거나 종료할 수 있는가. TERM_ASSIGN 을 가진 사람도 자기 최고
     * rank 미만의 Role 만 건드릴 수 있다.
     *
     * "누구도 자기 자신의 임기를 수정할 수 없다"는 별도 규칙이 아니라 이 규칙의
     * 결과다 — 자기 Role 은 rank 가 같아서 통과하지 못한다.
     */
    public static boolean canAssign(Set<Role> actorRoles, Role target) {
        if (!permissionsOf(actorRoles).contains(TERM_ASSIGN)) return false;
        int highest = actorRoles.stream().mapToInt(Role::rank).max().orElse(0);
        return highest > target.rank();
    }

    /** 매트릭스에 빠진 Role 이 없는지 — enum 에 추가하고 표를 잊는 것을 막는다. */
    static boolean isComplete() {
        return Arrays.stream(Role.values()).allMatch(MATRIX::containsKey);
    }
}
```

- [ ] **Step 6: Role 을 매트릭스에 넣는 것을 잊지 못하게 하는 테스트를 더한다**

`PolicyMatrixTest.java` 끝에 덧붙인다:

```java
    /** Role 을 추가하고 매트릭스를 잊으면 여기서 걸린다. */
    @Test
    void matrixCoversEveryRole() {
        assertThat(Policy.isComplete()).isTrue();
    }
```

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.security.authz.PolicyMatrixTest'`
Expected: PASS (13 tests)

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jaram/be/security/authz src/test/java/com/jaram/be/security/authz
git commit -m "$(cat <<'EOF'
feat(authz): 권한 매트릭스를 Permission·Role·Policy 로 선언한다

배선은 아직 하지 않는다. 매트릭스를 표 테스트로 고정해 두면 이후
태스크에서 권한이 조용히 넓어지거나 좁아지는 것을 먼저 잡는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 2: RoleResolver — 임기에서 Role 파생

**Files:**
- Create: `src/main/java/com/jaram/be/security/authz/RoleResolver.java`
- Test: `src/test/java/com/jaram/be/security/authz/RoleResolverTest.java`

**Interfaces:**
- Consumes: `Role.of(MemberDepartment, MemberTitle)` (Task 1), `com.jaram.be.member.Member` 의 `currentTerm()`
- Produces: `@Component class RoleResolver` — `Set<Role> rolesOf(Member m)`

**왜 클래스 하나인가:** 스펙 §4 가 파생을 택한 대가로 "나중에 `member_role_grant` 테이블이 필요해지면 여기만 바꾼다"는 이음매를 남겼다. 전략 인터페이스는 만들지 않는다 — 구현체 하나짜리 추상화이고 메서드 하나가 이미 충분한 이음매다. `@Component` 인 이유는 나중에 저장소를 주입받아야 하기 때문이다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/security/authz/RoleResolverTest.java`:

```java
package com.jaram.be.security.authz;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTitle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 없이 도는 순수 테스트 — Member 는 엔티티지만 new 로 만들 수 있다. */
class RoleResolverTest {

    private final RoleResolver resolver = new RoleResolver();

    private Member member() {
        return Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
    }

    @Test
    void noTermMeansPlainMember() {
        assertThat(resolver.rolesOf(member())).containsExactly(Role.MEMBER);
    }

    @Test
    void currentTermBecomesItsRole() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        assertThat(resolver.rolesOf(m)).containsExactly(Role.ACADEMIC_LEAD);
    }

    /** 임기가 끝나면 권한도 끝난다 — 이력은 남아도 Role 은 아니다. */
    @Test
    void endedTermGrantsNothing() {
        Member m = member();
        m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 41);
        m.endCurrentTerm(42);
        assertThat(resolver.rolesOf(m)).containsExactly(Role.MEMBER);
    }

    /** 새 임기를 받으면 이전 임기가 끝나고 새 Role 만 남는다. */
    @Test
    void reassignmentReplacesTheRole() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.STAFF, 41);
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        assertThat(resolver.rolesOf(m)).containsExactly(Role.ACADEMIC_LEAD);
    }

    @Test
    void everyValidCombinationResolves() {
        assertRole(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, Role.PRESIDENT);
        assertRole(MemberDepartment.LEADERSHIP, MemberTitle.VICE_PRESIDENT, Role.VICE_PRESIDENT);
        assertRole(MemberDepartment.INFRA, MemberTitle.SERVER_ADMIN, Role.SERVER_ADMIN);
        assertRole(MemberDepartment.ACADEMIC, MemberTitle.LEAD, Role.ACADEMIC_LEAD);
        assertRole(MemberDepartment.ACADEMIC, MemberTitle.STAFF, Role.ACADEMIC_STAFF);
        assertRole(MemberDepartment.PR, MemberTitle.LEAD, Role.PR_LEAD);
        assertRole(MemberDepartment.PR, MemberTitle.STAFF, Role.PR_STAFF);
        assertRole(MemberDepartment.FINANCE, MemberTitle.LEAD, Role.FINANCE_LEAD);
        assertRole(MemberDepartment.FINANCE, MemberTitle.STAFF, Role.FINANCE_STAFF);
    }

    private void assertRole(MemberDepartment d, MemberTitle t, Role expected) {
        Member m = member();
        m.assignTerm(d, t, 42);
        assertThat(resolver.rolesOf(m)).as("%s+%s", d, t).containsExactly(expected);
    }

    @Test
    void nullMemberHasNoRoleAtAll() {
        assertThat(resolver.rolesOf(null)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.security.authz.RoleResolverTest'`
Expected: 컴파일 실패 — `cannot find symbol: class RoleResolver`

- [ ] **Step 3: RoleResolver 를 만든다**

`src/main/java/com/jaram/be/security/authz/RoleResolver.java`:

```java
package com.jaram.be.security.authz;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberTerm;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 회원이 지금 어떤 Role 인가. 현직 임기(endGen == null)에서 파생하며 아무것도
 * 저장하지 않는다.
 *
 * 파생을 택한 대가는 셋이고 전부 알고 받아들인 것이다 — 이력 수정이 곧 권한 변경이고,
 * 시간 해상도가 기수 단위이며, 권한만 일부 회수할 수 없다. 임기 없는 사람에게 권한이
 * 필요해지거나, 같은 직책인데 사람마다 권한이 달라야 하거나, 날짜 단위 부여가
 * 필요해지면 member_role_grant 테이블을 만들고 **이 메서드만** 고친다.
 * 계약은 roles[]/permissions[] 로 출처를 노출하지 않으므로 그때도 API 는 그대로다.
 */
@Component
public class RoleResolver {

    public Set<Role> rolesOf(Member m) {
        if (m == null) return Set.of();
        return m.currentTerm()
                .flatMap(t -> Role.of(t.getDepartment(), t.getTitle()))
                .map(Set::of)
                .orElse(Set.of(Role.MEMBER));
    }
}
```

`MemberTerm` import 가 쓰이지 않으면 지운다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.security.authz.RoleResolverTest'`
Expected: PASS (6 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jaram/be/security/authz/RoleResolver.java src/test/java/com/jaram/be/security/authz/RoleResolverTest.java
git commit -m "$(cat <<'EOF'
feat(authz): 현직 임기에서 Role 을 파생한다

저장하지 않는다. 나중에 부여 테이블이 필요해지면 이 메서드 하나만 바뀌고
API 계약은 그대로다 — roles[] 가 출처를 노출하지 않기 때문이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 3: Eligibility — 1층 자격 게이트를 한 곳으로

**Files:**
- Create: `src/main/java/com/jaram/be/security/authz/Eligibility.java`
- Delete: `src/main/java/com/jaram/be/member/MemberActivityGuard.java`
- Modify: `src/main/java/com/jaram/be/security/JwtAuthFilter.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java` (import, 필드, 생성자, 198행)
- Modify: `src/main/java/com/jaram/be/study/StudyService.java` (import, 필드, 생성자, 40·58행)
- Modify: `src/main/java/com/jaram/be/schedule/ScheduleService.java` (import, 필드, 생성자, 64·101행)
- Test: 기존 `src/test/java/com/jaram/be/security/TokenEligibilityTest.java` 가 회귀 테스트다. 새 테스트 없음.

**Interfaces:**
- Consumes: `com.jaram.be.member.Member`, `MemberRepository`
- Produces: `@Component class Eligibility` —
  - `boolean isUsable(Member m, Instant issuedAt)` — 인증 시점. `JwtAuthFilter` 가 부른다
  - `void requireActive(String memberId)` — 신청류. `ApiException` 을 던진다

**행위 변경은 없다.** 두 판정이 서로 다른 두 파일에 흩어져 있던 것을 한 클래스로 옮기기만 한다. 스펙 §8 이 `MemberActivityGuard` 를 흡수하라고 한 이유는 1층이 한 곳에 있어야 "무엇이 자격을 막는가"를 한 번에 읽을 수 있기 때문이다.

- [ ] **Step 1: Eligibility 를 만든다**

`src/main/java/com/jaram/be/security/authz/Eligibility.java`:

```java
package com.jaram.be.security.authz;

import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 1층 자격 게이트. Role 이 무엇이든 넘지 못하는 상한이다 — AWS 의 permissions
 * boundary 자리이며, 권한 부여(가산 OR)와 교집합으로 만난다.
 *
 * 질문이 두 개라 메서드도 둘이다.
 *
 * isUsable — 이 토큰을 지금도 인증에 쓸 수 있는가. 미승인·탈퇴·자격증명 무효화가
 * 걸린다. JwtAuthFilter 가 매 요청 부르며, 막히면 인증 자체가 서지 않아 401 이 된다.
 * 재등록 대상(REREGISTER)은 여기서 막지 않는다 — 팝업을 띄우고 재등록을 신청하려면
 * 로그인 상태여야 한다.
 *
 * requireActive — 지금 신청류를 할 자격이 있는가. 재등록 대상을 여기서 막는다.
 * 403 과 코드를 던지는 이유는 화면이 재등록 팝업을 띄워야 하기 때문이다.
 */
@Component
public class Eligibility {

    private final MemberRepository members;

    public Eligibility(MemberRepository members) { this.members = members; }

    /**
     * 발급된 토큰이 지금도 유효한가. 경로 목록을 SecurityConfig 에 문자열로 다시
     * 적지 않고 필터 한 곳에서 보는 이유는, 경로가 바뀔 때 조용히 어긋나기 때문이다.
     * 실제로 가드가 신청류 다섯 곳에만 걸려 있어 관리자 경로가 통째로 비어 있었다.
     */
    public boolean isUsable(Member m, Instant issuedAt) {
        if (m == null) return false;
        if (m.getApproval() != MemberApproval.APPROVED) return false;
        if (m.getStatus() == MemberStatus.WITHDRAWN) return false;

        Instant invalidatedAt = m.getCredentialsInvalidatedAt();
        if (invalidatedAt == null || issuedAt == null) return true;
        // iat 는 초 단위로만 저장된다. 무효화 시각을 자르지 않으면, 재설정과 같은 초에
        // 다시 로그인해 받은 새 토큰이 iat < invalidatedAt 이 되어 거부된다.
        return !issuedAt.isBefore(invalidatedAt.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * 활동 자격이 없는 회원의 신청류를 막는다. 조회와 프로필 수정은 막지 않는다 —
     * 팝업을 닫아도 재등록할 이유가 남게 하는 것이 목적이지 사이트를 잠그는 게 아니다.
     */
    @Transactional(readOnly = true)
    public void requireActive(String memberId) {
        members.findById(memberId).ifPresent(m -> {
            if (m.getStatus() == MemberStatus.REREGISTER) {
                throw new ApiException(HttpStatus.FORBIDDEN, "REREGISTRATION_REQUIRED",
                        "재등록이 승인되어야 이용할 수 있습니다.");
            }
            if (m.getStatus() == MemberStatus.WITHDRAWN) {
                throw new ApiException(HttpStatus.FORBIDDEN, "WITHDRAWN", "탈퇴한 계정입니다.");
            }
        });
    }
}
```

- [ ] **Step 2: 세 서비스의 가드를 갈아 끼운다**

각 파일에서 아래를 바꾼다. 세 파일 모두 같은 모양이다.

```java
// import
-import com.jaram.be.member.MemberActivityGuard;
+import com.jaram.be.security.authz.Eligibility;

// 필드
-    private final MemberActivityGuard guard;
+    private final Eligibility eligibility;

// 생성자 파라미터
-                          MemberActivityGuard guard,
+                          Eligibility eligibility,

// 생성자 본문
-        this.guard = guard;
+        this.eligibility = eligibility;

// 호출부
-        guard.requireRegistered(memberId);
+        eligibility.requireActive(memberId);
```

호출부는 `SeminarService:198`, `StudyService:40`, `StudyService:58`, `ScheduleService:64`, `ScheduleService:101` 다섯 곳이다. 뒤에 붙은 한국어 주석(`// 조회보다 먼저다 — ...`)은 그대로 둔다.

- [ ] **Step 3: JwtAuthFilter 가 Eligibility 를 쓰게 한다**

`JwtAuthFilter.java`:

```java
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwt;
    private final MemberRepository members;
    private final Eligibility eligibility;

    public JwtAuthFilter(JwtProvider jwt, MemberRepository members, Eligibility eligibility) {
        this.jwt = jwt;
        this.members = members;
        this.eligibility = eligibility;
    }
```

`doFilterInternal` 안의 판정을 바꾼다:

```java
-                if (m != null && !usable(m, claims.issuedAt())) {
+                if (m != null && !eligibility.isUsable(m, claims.issuedAt())) {
```

`private boolean usable(...)` 메서드와 이제 쓰이지 않는 import (`MemberApproval`, `MemberStatus`, `ChronoUnit`) 를 지운다. `Eligibility` import 를 더한다. 클래스 주석에서 `MemberActivityGuard` 를 가리키는 `{@link}` 는 `Eligibility` 로 바꾼다.

- [ ] **Step 4: SecurityConfig 가 Eligibility 를 필터에 넘기게 한다**

```java
     public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtProvider jwtProvider,
                                            MemberRepository members,
+                                           Eligibility eligibility,
                                            RestAuthEntryPoint entryPoint,
                                            RestAccessDeniedHandler deniedHandler) throws Exception {
...
-            .addFilterBefore(new JwtAuthFilter(jwtProvider, members), UsernamePasswordAuthenticationFilter.class);
+            .addFilterBefore(new JwtAuthFilter(jwtProvider, members, eligibility),
+                    UsernamePasswordAuthenticationFilter.class);
```

`import com.jaram.be.security.authz.Eligibility;` 를 더한다.

- [ ] **Step 5: 가드를 지운다**

```bash
git rm src/main/java/com/jaram/be/member/MemberActivityGuard.java
```

`TokenEligibilityTest` 의 클래스 주석에 남은 `MemberActivityGuard` 언급을 `Eligibility` 로 고친다.

- [ ] **Step 6: 전체 테스트를 돌린다**

Run: `./gradlew --no-daemon -I <init> test --rerun`
Expected: PASS — 행위를 바꾸지 않았으므로 기존 테스트가 전부 그대로 통과해야 한다. 특히 `TokenEligibilityTest`(6), `MeReregistrationTest`, `SeminarAttendanceTest`, `StudyApplyTest`, `ScheduleContractTest` 를 확인한다.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(authz): 1층 자격 게이트를 Eligibility 한 곳으로 모은다

행위는 그대로다. 인증 시점 판정(JwtAuthFilter)과 신청류 판정
(MemberActivityGuard)이 서로 다른 파일에 흩어져 있어, 무엇이 자격을
막는지 한 번에 읽을 수 없었다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 4: 필터에서 Permission 을 부여한다 (기존 권한과 병행)

**Files:**
- Modify: `src/main/java/com/jaram/be/security/CurrentMember.java`
- Modify: `src/main/java/com/jaram/be/security/JwtAuthFilter.java`
- Modify: `src/main/java/com/jaram/be/security/SecurityConfig.java`
- Create: `src/main/java/com/jaram/be/security/authz/Permissions.java`
- Test: `src/test/java/com/jaram/be/security/authz/GrantedPermissionsTest.java`

**Interfaces:**
- Consumes: `RoleResolver.rolesOf(Member)` (Task 2), `Policy.permissionsOf(Set<Role>)` (Task 1), `Eligibility` (Task 3)
- Produces:
  - `record CurrentMember(String id, String name, String email, Authority authority, Set<Role> roles, Set<Permission> permissions)` — `boolean can(Permission p)`
  - `final class Permissions` — `static boolean has(Authentication auth, Permission p)`

**핵심: 기존 `MEMBER`/`OFFICER` 권한을 그대로 두고 Permission 을 덧붙이기만 한다.** `SecurityConfig` 의 `hasAuthority("OFFICER")` 매처 10개가 아직 살아 있으므로, 지우지 않으면 모든 기존 테스트가 그대로 통과한다. 이 태스크의 산출물은 "새 권한이 실제로 실려 나간다"는 사실뿐이다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/security/authz/GrantedPermissionsTest.java`:

```java
package com.jaram.be.security.authz;

import com.jaram.be.member.Authority;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * 권한이 토큰이 아니라 DB 에서 나온다는 것을 /api/me 응답으로 확인한다.
 * 임기를 거둔 사람이 손에 든 토큰으로 계속 임원 권한을 쓰던 것이 §1 의 결함이었다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GrantedPermissionsTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @AfterEach void cleanup() { members.deleteAll(); }

    private String tokenFor(MemberDepartment d, MemberTitle t) {
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setStatus(MemberStatus.ACTIVE);
        if (d != null) m.assignTerm(d, t, 42);
        m = members.save(m);
        return jwt.generate(m.getId(), m.getName(), m.getEmail(), m.getAuthority());
    }

    @Test
    void academicStaffGetsTheStaffSlice() {
        given().header("Authorization", "Bearer " + tokenFor(MemberDepartment.ACADEMIC, MemberTitle.STAFF))
                .when().get("/api/me")
                .then().statusCode(200)
                .body("roles", containsInAnyOrder("ACADEMIC_STAFF"))
                .body("permissions", hasItem("SEMINAR_CREATE"))
                // 부원은 승인권이 없다 — 이번 변경에서 실제로 잃는 권한이다
                .body("permissions", not(hasItem("SEMINAR_APPROVE")))
                .body("permissions", not(hasItem("MEMBER_APPROVE")));
    }

    @Test
    void presidentGetsEverything() {
        given().header("Authorization", "Bearer " + tokenFor(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT))
                .when().get("/api/me")
                .then().statusCode(200)
                .body("roles", containsInAnyOrder("PRESIDENT"))
                .body("permissions", hasItem("SETTINGS_ROLLOVER"))
                .body("permissions", hasItem("EXPORT_RUN"));
    }

    @Test
    void memberWithoutTermGetsNothing() {
        given().header("Authorization", "Bearer " + tokenFor(null, null))
                .when().get("/api/me")
                .then().statusCode(200)
                .body("roles", containsInAnyOrder("MEMBER"))
                .body("permissions", emptyIterable());
    }

    /** authority 는 계약이라 그대로 유지된다 — FE 이행 전까지 값과 규칙이 같아야 한다. */
    @Test
    void legacyAuthorityStillReflectsTheCurrentTerm() {
        given().header("Authorization", "Bearer " + tokenFor(MemberDepartment.PR, MemberTitle.STAFF))
                .when().get("/api/me")
                .then().statusCode(200).body("authority", org.hamcrest.Matchers.equalTo("OFFICER"));
        members.deleteAll();
        given().header("Authorization", "Bearer " + tokenFor(null, null))
                .when().get("/api/me")
                .then().statusCode(200).body("authority", org.hamcrest.Matchers.equalTo("MEMBER"));
    }
}
```

이 테스트는 `/api/me` 가 `roles`/`permissions` 를 실어야 통과한다. 그 계약 추가는 이 태스크의 Step 5 에서 한다 (스펙 §9 는 추가만 허용하고 `authority` 제거는 FE 이행 뒤 별도 배포다).

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.security.authz.GrantedPermissionsTest'`
Expected: FAIL — `roles` 필드가 응답에 없어 `containsInAnyOrder` 가 null 에서 깨진다

- [ ] **Step 3: Permissions 헬퍼를 만든다**

`src/main/java/com/jaram/be/security/authz/Permissions.java`:

```java
package com.jaram.be.security.authz;

import org.springframework.security.core.Authentication;

/**
 * @PreAuthorize 의 조건 빈들이 쓰는 헬퍼. GrantedAuthority 문자열이 Permission 의
 * enum name 과 같다는 규약이 여기 한 줄에만 적혀 있게 한다.
 */
public final class Permissions {

    private Permissions() { }

    public static boolean has(Authentication auth, Permission p) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> p.name().equals(a.getAuthority()));
    }
}
```

- [ ] **Step 4: CurrentMember 에 roles·permissions 를 더한다**

`src/main/java/com/jaram/be/security/CurrentMember.java`:

```java
package com.jaram.be.security;

import com.jaram.be.member.Authority;
import com.jaram.be.security.authz.Permission;
import com.jaram.be.security.authz.Role;

import java.util.Set;

/**
 * 인증된 요청의 주체. authority 는 계약(MEMBER/OFFICER)이라 남아 있고, 실제 판정은
 * permissions 로 한다.
 */
public record CurrentMember(String id, String name, String email, Authority authority,
                            Set<Role> roles, Set<Permission> permissions) {

    public boolean can(Permission p) { return permissions.contains(p); }
}
```

- [ ] **Step 5: 필터가 Permission 을 함께 부여하게 한다**

`JwtAuthFilter.doFilterInternal` 의 인증 구성 부분을 바꾼다:

```java
                Authority authority = m != null ? m.getAuthority() : claims.authority();
                Set<Role> roles = resolver.rolesOf(m);
                Set<Permission> permissions = Policy.permissionsOf(roles);

                // 기존 MEMBER/OFFICER 권한을 함께 싣는다. SecurityConfig 의 URL 매처가
                // 아직 이 값을 보고 있어서, 핸들러가 @PreAuthorize 로 다 옮겨 갈 때까지
                // 둘을 나란히 둔다. 마지막 태스크에서 이 줄이 사라진다.
                List<GrantedAuthority> granted = new ArrayList<>();
                granted.add(new SimpleGrantedAuthority(authority.name()));
                permissions.forEach(p -> granted.add(new SimpleGrantedAuthority(p.name())));

                var principal = new CurrentMember(
                        claims.memberId(), claims.name(), claims.email(), authority,
                        roles, permissions);
                var auth = new UsernamePasswordAuthenticationToken(principal, null, granted);
                SecurityContextHolder.getContext().setAuthentication(auth);
```

생성자에 `RoleResolver resolver` 를 더하고, `SecurityConfig` 의 `new JwtAuthFilter(...)` 에도 넘긴다 (`filterChain` 파라미터에 `RoleResolver resolver` 추가).

import 를 더한다: `java.util.ArrayList`, `java.util.Set`, `org.springframework.security.core.GrantedAuthority`, `com.jaram.be.security.authz.Permission`, `Policy`, `Role`, `RoleResolver`.

- [ ] **Step 6: 계약에 roles·permissions 를 더한다**

`src/main/java/com/jaram/be/me/dto/MeProfile.java` 의 마지막 필드 뒤에 두 줄을 더한다:

```java
        String githubUrl,
        String blogUrl,
        List<String> roles,          // Role enum name. 임기에서 파생하며 출처는 노출하지 않는다
        List<String> permissions) {  // Permission enum name. FE 는 버튼 노출을 이 값으로 판단한다
```

`src/main/java/com/jaram/be/me/MeService.java` 의 `toProfile` 이 값을 채우게 한다. `MeService` 에 `RoleResolver` 를 주입한다:

```java
    private final RoleResolver roles;
```

생성자 파라미터에 더하고 `toProfile` 끝에:

```java
                m.getBlogUrl(),
                roleNames(m),
                permissionNames(m));
    }

    private List<String> roleNames(Member m) {
        return roles.rolesOf(m).stream().map(Enum::name).sorted().toList();
    }

    private List<String> permissionNames(Member m) {
        return Policy.permissionsOf(roles.rolesOf(m)).stream().map(Enum::name).sorted().toList();
    }
```

import: `com.jaram.be.security.authz.Policy`, `com.jaram.be.security.authz.RoleResolver`, `java.util.List`.

`src/main/java/com/jaram/be/auth/dto/UserSummary.java` 도 같이 넓힌다 — FE 의 세션 저장소는 로그인 응답으로 채워지므로 여기에 없으면 첫 화면에서 권한을 모른다:

```java
package com.jaram.be.auth.dto;

import com.jaram.be.member.Authority;

import java.util.List;

public record UserSummary(String id, String name, String email, Authority authority,
                          List<String> roles, List<String> permissions) { }
```

`AuthService:85` 의 생성자 호출을 맞춘다 (`RoleResolver` 주입 + 같은 두 헬퍼).

- [ ] **Step 7: 통과를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --rerun`
Expected: PASS 전체. `GrantedPermissionsTest` 4개가 새로 통과하고, 기존 테스트는 URL 매처가 그대로라 영향을 받지 않는다.

계약 필드를 읽는 기존 테스트가 있으면(`MeTest` 등) 필드 추가만으로는 깨지지 않는다 — RestAssured 의 `body("...")` 는 부분 검증이다. 레코드 생성자를 직접 부르는 테스트가 있다면 인자를 맞춘다.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(authz): 요청마다 DB 에서 Permission 을 전개해 부여한다

기존 MEMBER/OFFICER 권한은 그대로 둔다 — URL 매처가 아직 그 값을 보고
있어서, 핸들러가 @PreAuthorize 로 옮겨 갈 때까지 둘을 나란히 싣는다.

/api/me 와 로그인 응답에 roles[]·permissions[] 를 추가한다. authority 는
계약이라 값도 파생 규칙도 그대로다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 5: 테스트 토큰이 실제 회원을 가리키게 한다

**Files:**
- Create: `src/test/java/com/jaram/be/support/Actors.java`
- Modify: 35개 테스트 파일의 토큰 발급부 (52개 호출 지점)
- Test: 기존 스위트 전체가 그대로 초록이어야 한다

**Interfaces:**
- Consumes: `MemberRepository`, `JwtProvider`, `Role` (Task 1)
- Produces: `@Component class Actors` —
  - `String tokenFor(Member m)`
  - `Member save(Role role)` — 승인된 ACTIVE 회원 + 해당 Role 의 현직 임기
  - `String token(Role role)` — 위를 저장하고 토큰까지
  - `String officer()` — `token(Role.PRESIDENT)` 의 별칭. 기존 `Authority.OFFICER` 토큰의 대체
  - `String member()` — `token(Role.MEMBER)` 의 별칭

**왜 지금인가:** 지금까지 테스트는 `jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER)` 로 **DB 에 없는 회원**의 토큰을 만들어 왔다. Task 12 에서 `authority` 클레임이 사라지면 그런 토큰은 권한이 하나도 없어 모든 관리자 테스트가 403 이 된다. 이 태스크는 **운영 코드를 전혀 바꾸지 않고** 그 의존을 먼저 끊는다 — 앞뒤로 초록이며, 리뷰어는 "테스트 전용 변경"으로 읽으면 된다.

**주의해야 할 회귀 한 가지:** `Actors` 는 회원 행을 **실제로 저장한다**. 회원 수를 세거나 목록 전체를 단언하는 테스트(`AdminMemberTest`, `AdminResourceTest`, `PeopleTest`)는 행이 하나 늘어난다. 승인 대기 목록은 영향이 없다(행위자는 APPROVED). 깨지면 개수 단언을 **식별자 기반 단언**으로 바꾼다 — `hasSize(3)` 대신 `extracting("name").contains("홍길동")`. 개수를 늘려 맞추는 것은 다음 사람이 또 걸리므로 하지 않는다.

- [ ] **Step 1: Actors 를 만든다**

`src/test/java/com/jaram/be/support/Actors.java`:

```java
package com.jaram.be.support;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.security.authz.Role;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트에서 "이 Role 인 사람으로 요청한다"를 한 줄로 만든다.
 *
 * 예전에는 DB 에 없는 id 로 토큰만 찍어 썼다. 권한이 토큰 클레임에 실려 있어서
 * 그래도 통했지만, 이제 권한은 요청 시점에 회원의 현직 임기에서 나온다 — 행이 없으면
 * 권한도 없다. 그래서 행위자는 실제로 저장된다.
 *
 * 학번과 이메일은 호출할 때마다 달라진다. member 테이블의 UNIQUE 제약 때문이며,
 * 한 테스트가 행위자를 둘 이상 만들 때 충돌하지 않게 한다.
 *
 * component scan 에 잡히는 이유: @SpringBootApplication 이 com.jaram.be 를 스캔하고
 * 테스트 클래스도 같은 패키지의 클래스패스에 있다. 이 동작이 부담스러워지면
 * 생성자를 public 으로 둔 채 각 테스트가 new Actors(members, jwt) 로 만들면 된다.
 */
@Component
public class Actors {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final MemberRepository members;
    private final JwtProvider jwt;

    public Actors(MemberRepository members, JwtProvider jwt) {
        this.members = members;
        this.jwt = jwt;
    }

    /** 이 Role 인 승인된 활동 회원을 저장한다. MEMBER 는 임기 없이 저장된다. */
    public Member save(Role role) {
        int n = SEQ.incrementAndGet();
        Member m = Member.newPending(
                "행위자" + n, "2023%06d".formatted(n), "actor%d@hanyang.ac.kr".formatted(n), "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setStatus(MemberStatus.ACTIVE);
        m.setGrade(MemberGrade.REGULAR);
        m.setGen(41);
        assignTerm(m, role);
        return members.save(m);
    }

    public String token(Role role) { return tokenFor(save(role)); }

    public String tokenFor(Member m) {
        return jwt.generate(m.getId(), m.getName(), m.getEmail(), m.getAuthority());
    }

    /**
     * 지금까지 Authority.OFFICER 토큰이 뜻하던 것 — 관리자 화면 전부를 쓸 수 있는 사람.
     * 권한이 좁은 Role 로 막히는지 보려는 테스트는 token(Role.X) 를 직접 쓴다.
     */
    public String officer() { return token(Role.PRESIDENT); }

    /** 임기 없는 일반 회원. */
    public String member() { return token(Role.MEMBER); }

    private void assignTerm(Member m, Role role) {
        switch (role) {
            case PRESIDENT -> m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 41);
            case VICE_PRESIDENT -> m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.VICE_PRESIDENT, 41);
            case SERVER_ADMIN -> m.assignTerm(MemberDepartment.INFRA, MemberTitle.SERVER_ADMIN, 41);
            case ACADEMIC_LEAD -> m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
            case ACADEMIC_STAFF -> m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.STAFF, 41);
            case PR_LEAD -> m.assignTerm(MemberDepartment.PR, MemberTitle.LEAD, 41);
            case PR_STAFF -> m.assignTerm(MemberDepartment.PR, MemberTitle.STAFF, 41);
            case FINANCE_LEAD -> m.assignTerm(MemberDepartment.FINANCE, MemberTitle.LEAD, 41);
            case FINANCE_STAFF -> m.assignTerm(MemberDepartment.FINANCE, MemberTitle.STAFF, 41);
            case MEMBER -> { }   // 임기 없음이 곧 MEMBER 다
        }
    }
}
```

- [ ] **Step 2: 바꿔야 할 호출 지점을 뽑는다**

```bash
grep -rn 'jwt\.generate(' src/test/java --include='*.java'
```

52개가 나온다. 형태는 셋뿐이다:

| 지금 | 바꿀 것 |
|---|---|
| `jwt.generate(<아무 id>, ..., Authority.OFFICER)` | `actors.officer()` |
| `jwt.generate(<아무 id>, ..., Authority.MEMBER)` | `actors.member()` |
| `jwt.generate(m.getId(), m.getName(), m.getEmail(), ...)` — 이미 저장된 회원 | `actors.tokenFor(m)` |

세 번째는 **이미 올바르므로 손대지 않아도 된다.** 첫 두 개만 바꾼다.

- [ ] **Step 3: 파일마다 고친다**

각 테스트 클래스에서:

```java
+    @Autowired Actors actors;
...
-        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
+        officerToken = actors.officer();
```

`members.deleteAll()` 을 하는 `@BeforeEach` 가 있다면 **행위자 발급을 그 뒤로** 옮긴다. 안 그러면 방금 만든 행위자가 지워진다. 예:

```java
    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();          // 먼저 치우고
        officerToken = actors.officer();   // 그 다음 행위자를 만든다
    }
```

쓰이지 않게 된 `import com.jaram.be.member.Authority;` 와 `@Autowired JwtProvider jwt;` 는 지운다 (같은 클래스에서 다른 용도로 쓰면 남긴다).

- [ ] **Step 4: 전체 테스트를 돌린다**

Run: `./gradlew --no-daemon -I <init> test --rerun`
Expected: PASS 전체.

깨지면 원인은 거의 회원 수 단언이다. 실패 메시지가 `expected: 3 but was: 4` 꼴이면 Step 5 로 간다. 그 외의 실패는 행위자 생성 순서(`deleteAll` 뒤인지)를 먼저 의심한다.

- [ ] **Step 5: 개수 단언을 식별자 단언으로 바꾼다**

깨진 곳마다:

```java
-                .body("items", hasSize(3))
+                .body("items.name", hasItems("홍길동", "김철수", "이영희"))
```

행위자를 세지 않겠다고 필터를 거는 것이 아니라, **무엇이 있어야 하는가**를 단언한다. 원래 그 테스트가 확인하려던 것도 그것이다.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
test: 테스트 토큰이 실제로 저장된 회원을 가리키게 한다

운영 코드는 건드리지 않는다. 지금까지 테스트는 DB 에 없는 id 로 토큰만
찍어 썼고, 권한이 클레임에 실려 있어서 통했다. 권한이 현직 임기에서
나오게 되면 그런 토큰은 권한이 없다 — 클레임을 제거하기 전에 의존을
먼저 끊는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 6: 메서드 보안을 켜고 관리자 회원 API 를 옮긴다

**Files:**
- Modify: `src/main/java/com/jaram/be/security/SecurityConfig.java`
- Modify: `src/main/java/com/jaram/be/admin/AdminMemberController.java`
- Test: `src/test/java/com/jaram/be/admin/AdminMemberPermissionTest.java` (신규)

**Interfaces:**
- Consumes: 필터가 부여하는 Permission 권한 (Task 4), `Actors` (Task 5)
- Produces: 없음 (엔드포인트 애너테이션)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/AdminMemberPermissionTest.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;

/**
 * 회원 관리 권한이 Role 별로 갈리는지 본다. 지금까지는 임기만 있으면 부원도 회장과
 * 똑같이 회원을 승인할 수 있었다 — 이 테스트가 그 경계를 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminMemberPermissionTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @AfterEach void cleanup() { members.deleteAll(); }

    /** 회계부는 MEMBER_READ 를 가지므로 대기 목록을 본다. */
    @Test
    void financeStaffCanReadPendingMembers() {
        given().header("Authorization", "Bearer " + actors.token(Role.FINANCE_STAFF))
                .when().get("/api/admin/members/pending")
                .then().statusCode(200);
    }

    /** 학술부원은 회원 권한이 전혀 없다. */
    @Test
    void academicStaffCannotReadPendingMembers() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .when().get("/api/admin/members/pending")
                .then().statusCode(403);
    }

    /** 회계부원은 읽기만 — 승인은 부장부터다. */
    @Test
    void financeStaffCannotApprove() {
        given().header("Authorization", "Bearer " + actors.token(Role.FINANCE_STAFF))
                .when().post("/api/admin/members/any-id/approve")
                .then().statusCode(403);
    }

    @Test
    void financeLeadCanApprove() {
        // 존재하지 않는 회원이라 404 다. 403 이 아니라는 것이 요점 — 권한은 통과했다.
        given().header("Authorization", "Bearer " + actors.token(Role.FINANCE_LEAD))
                .when().post("/api/admin/members/any-id/approve")
                .then().statusCode(404);
    }

    /** 졸업 정보 수정은 MEMBER_EDIT — 회계부장도 못 한다. */
    @Test
    void financeLeadCannotEditGraduation() {
        given().header("Authorization", "Bearer " + actors.token(Role.FINANCE_LEAD))
                .contentType("application/json").body("{}")
                .when().put("/api/admin/members/any-id/graduation")
                .then().statusCode(403);
    }

    @Test
    void plainMemberIsRejectedEverywhere() {
        String token = actors.member();
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/members/pending").then().statusCode(403);
        given().header("Authorization", "Bearer " + token)
                .when().post("/api/admin/members/any-id/approve").then().statusCode(403);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.admin.AdminMemberPermissionTest'`
Expected: FAIL — `financeStaffCanReadPendingMembers` 가 403 을 받는다. `hasAuthority("OFFICER")` 매처가 아직 살아 있고 회계부원도 OFFICER 이므로 200 일 수도 있는데, 그 경우 `academicStaffCannotReadPendingMembers` 가 200 을 받아 실패한다. 어느 쪽이든 빨간색이다.

- [ ] **Step 3: 메서드 보안을 켠다**

`SecurityConfig.java` 클래스 애너테이션에 더한다:

```java
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
```

이 시점에는 `@PreAuthorize` 가 아직 하나도 없어 동작이 바뀌지 않는다.

- [ ] **Step 4: 관리자 회원 컨트롤러에 권한을 붙인다**

`AdminMemberController.java`:

```java
import org.springframework.security.access.prepost.PreAuthorize;
...
    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    public List<PendingMember> pending() { return service.listPending(); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    public MemberDetail detail(@PathVariable String id) { return service.detail(id); }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('MEMBER_APPROVE')")
    public void approve(@PathVariable String id) { service.approve(id); }

    @PostMapping("/{id}/reregister")
    @PreAuthorize("hasAuthority('MEMBER_APPROVE')")
    public void reregister(@PathVariable String id) { service.approveReregistration(id); }

    @PutMapping("/{id}/graduation")
    @PreAuthorize("hasAuthority('MEMBER_EDIT')")
    public MemberDetail graduation(@PathVariable String id, @RequestBody GraduationUpdate req) { ... }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('MEMBER_APPROVE')")
    public void reject(@PathVariable String id, @Valid @RequestBody RejectRequest req) { ... }
```

기존 메서드 시그니처와 본문은 건드리지 않는다. 애너테이션만 더한다.

- [ ] **Step 5: URL 매처는 아직 지우지 않는다**

`/api/admin/**` 매처는 Task 11 까지 남는다. 그동안은 `hasAuthority("OFFICER")` **와** `@PreAuthorize` 를 **둘 다** 통과해야 하므로, 임기가 있는 사람 중에서만 Permission 으로 한 번 더 걸러진다. 이 태스크의 테스트는 전부 임기가 있는 행위자를 쓰므로 그대로 성립한다.

- [ ] **Step 6: 전체 테스트를 돌린다**

Run: `./gradlew --no-daemon -I <init> test --rerun`
Expected: PASS 전체. 기존 `AdminMemberTest` 등은 `actors.officer()`(= PRESIDENT, 전권)를 쓰므로 영향이 없다.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(authz): 관리자 회원 API 를 Permission 단위로 나눈다

@EnableMethodSecurity 를 켜고 회원 승인·조회·수정을 각각 다른 권한으로
가른다. 회계부는 읽고 승인하고, 학술부는 회원을 건드리지 못한다.

URL 매처는 아직 남겨 둔다 — 모든 핸들러가 옮겨 간 뒤에 한 번에 지운다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 7: 관리 목록·일괄 편집과 P6 위계

**Files:**
- Create: `src/main/java/com/jaram/be/admin/AdminResourceAccess.java`
- Modify: `src/main/java/com/jaram/be/admin/AdminResourceController.java`
- Modify: `src/main/java/com/jaram/be/admin/AdminResourceService.java`
- Modify: `src/main/java/com/jaram/be/admin/AdminBatchExecutor.java`
- Test: `src/test/java/com/jaram/be/admin/TermAssignmentHierarchyTest.java` (신규)

**Interfaces:**
- Consumes: `Permissions.has(Authentication, Permission)` (Task 4), `Policy.canAssign(Set<Role>, Role)` (Task 1), `RoleResolver` (Task 2), `CurrentMember.roles()` (Task 4)
- Produces:
  - `@Component("adminResourceAccess") class AdminResourceAccess` — `boolean canList(AdminResource r, Authentication auth)`, `boolean canEdit(AdminResource r, Authentication auth)`
  - `AdminResourceService.batch(AdminResource, AdminBatchRequest, CurrentMember actor)`
  - `AdminBatchExecutor.updateRow(AdminResource, AdminBatchRequest.Update, CurrentMember actor)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/TermAssignmentHierarchyTest.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6 — 임기를 부여·종료하려면 대상보다 rank 가 높아야 한다. 부회장이 회장을 갈아
 * 치우거나 자기 임기를 스스로 연장하는 것을 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TermAssignmentHierarchyTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @AfterEach void cleanup() { members.deleteAll(); }

    private Map<String, Object> assign(Member target, String dept, String title) {
        return Map.of("updates", List.of(Map.of(
                "id", target.getId(),
                "version", target.getVersion(),
                "fields", Map.of("department", dept, "title", title))));
    }

    @Test
    void vicePresidentCanAppointALead() {
        String vp = actors.token(Role.VICE_PRESIDENT);
        Member target = actors.save(Role.MEMBER);

        given().header("Authorization", "Bearer " + vp)
                .contentType("application/json").body(assign(target, "ACADEMIC", "LEAD"))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200);

        assertThat(members.findById(target.getId()).orElseThrow().getTitle()).isNotNull();
    }

    /** 회장은 rank 가 자기 이상이라 건드리지 못한다. */
    @Test
    void vicePresidentCannotAppointAPresident() {
        String vp = actors.token(Role.VICE_PRESIDENT);
        Member target = actors.save(Role.MEMBER);

        given().header("Authorization", "Bearer " + vp)
                .contentType("application/json").body(assign(target, "LEADERSHIP", "PRESIDENT"))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("errors[0].fieldErrors.title", org.hamcrest.Matchers.notNullValue());

        assertThat(members.findById(target.getId()).orElseThrow().getTitle()).isNull();
    }

    /** 현직 회장의 임기를 부회장이 끝낼 수 없다 — 종료도 부여와 같은 규칙이다. */
    @Test
    void vicePresidentCannotEndAPresidentsTerm() {
        String vp = actors.token(Role.VICE_PRESIDENT);
        Member president = actors.save(Role.PRESIDENT);
        Map<String, Object> body = Map.of("updates", List.of(Map.of(
                "id", president.getId(),
                "version", president.getVersion(),
                "fields", new java.util.HashMap<String, Object>() {{ put("title", null); }})));

        given().header("Authorization", "Bearer " + vp)
                .contentType("application/json").body(body)
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("errors[0].fieldErrors.title", org.hamcrest.Matchers.notNullValue());

        assertThat(members.findById(president.getId()).orElseThrow().getTitle()).isNotNull();
    }

    /** 학술부장은 TERM_ASSIGN 자체가 없다. */
    @Test
    void academicLeadCannotAssignTermsAtAll() {
        Member target = actors.save(Role.MEMBER);
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_LEAD))
                .contentType("application/json").body(assign(target, "ACADEMIC", "STAFF"))
                .when().patch("/api/admin/members:batch")
                .then().statusCode(200)
                .body("errors[0].fieldErrors.title", org.hamcrest.Matchers.notNullValue());
    }

    /** 학술부장은 세미나 일괄 편집은 할 수 있다 — 권한이 리소스별로 갈린다. */
    @Test
    void academicLeadCanStillBatchEditSeminars() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_LEAD))
                .contentType("application/json").body(Map.of("updates", List.of()))
                .when().patch("/api/admin/seminars:batch")
                .then().statusCode(200);
    }

    /** 회계부원은 회원 목록은 보지만 고치지는 못한다. */
    @Test
    void financeStaffReadsMembersButCannotBatchEdit() {
        String token = actors.token(Role.FINANCE_STAFF);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/members").then().statusCode(200);
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("updates", List.of()))
                .when().patch("/api/admin/members:batch").then().statusCode(403);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.admin.TermAssignmentHierarchyTest'`
Expected: FAIL — 위계 검사가 없어 부회장이 회장을 임명하는 데 성공한다

- [ ] **Step 3: AdminResourceAccess 를 만든다**

`src/main/java/com/jaram/be/admin/AdminResourceAccess.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.security.authz.Permission;
import com.jaram.be.security.authz.Permissions;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 관리 목록과 일괄 편집은 핸들러가 하나이고 대상이 경로 변수로 온다. 정적 문자열
 * 하나로는 권한을 적을 수 없어 조건 빈으로 뺀다.
 *
 * 목록에서 members 만 읽기 권한이 따로인 이유: FINANCE_STAFF 는 MEMBER_READ 만
 * 가진다. 여기서 MEMBER_EDIT 을 요구하면 그 Role 이 쓸 화면이 하나도 없어진다.
 * 세미나·스터디는 매트릭스에 읽기 전용 Role 이 없어 편집 권한으로 함께 본다.
 */
@Component("adminResourceAccess")
public class AdminResourceAccess {

    public boolean canList(AdminResource resource, Authentication auth) {
        return Permissions.has(auth, switch (resource) {
            case members -> Permission.MEMBER_READ;
            case seminars -> Permission.SEMINAR_EDIT;
            case studies -> Permission.STUDY_EDIT;
        });
    }

    public boolean canEdit(AdminResource resource, Authentication auth) {
        return Permissions.has(auth, switch (resource) {
            case members -> Permission.MEMBER_EDIT;
            case seminars -> Permission.SEMINAR_EDIT;
            case studies -> Permission.STUDY_EDIT;
        });
    }
}
```

- [ ] **Step 4: 컨트롤러에 권한과 행위자를 붙인다**

`AdminResourceController.java`:

```java
import com.jaram.be.security.CurrentMember;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
...
    @GetMapping("/{resource}")
    @PreAuthorize("@adminResourceAccess.canList(#resource, authentication)")
    public AdminListResponse list(@PathVariable AdminResource resource, ...) { ... }

    @PatchMapping("/{resource}:batch")
    @PreAuthorize("@adminResourceAccess.canEdit(#resource, authentication)")
    public AdminBatchResponse batch(@PathVariable AdminResource resource,
                                    @RequestBody AdminBatchRequest req,
                                    @AuthenticationPrincipal CurrentMember me) {
        return service.batch(resource, req, me);
    }
```

- [ ] **Step 5: 행위자를 실행기까지 넘긴다**

`AdminResourceService.java`:

```java
-    public AdminBatchResponse batch(AdminResource resource, AdminBatchRequest req) {
+    public AdminBatchResponse batch(AdminResource resource, AdminBatchRequest req, CurrentMember actor) {
...
-            switch (executor.updateRow(resource, u)) {
+            switch (executor.updateRow(resource, u, actor)) {
```

`import com.jaram.be.security.CurrentMember;` 를 더한다.

`AdminBatchExecutor.java`:

```java
-    public UpdateOutcome updateRow(AdminResource resource, AdminBatchRequest.Update u) {
+    public UpdateOutcome updateRow(AdminResource resource, AdminBatchRequest.Update u, CurrentMember actor) {
```

`updateRow` 안에서 회원 갈래가 `updateMember(m, f)` 를 부르는 부분을 `updateMember(m, f, actor)` 로 바꾸고, 선언도 바꾼다:

```java
-    private Map<String, String> updateMember(Member m, Map<String, Object> f) {
+    private Map<String, String> updateMember(Member m, Map<String, Object> f, CurrentMember actor) {
```

`createRow`/`deleteRow` 는 임기를 건드리지 않으므로 그대로 둔다.

- [ ] **Step 6: 위계 검사를 임기 변경 지점에 건다**

`AdminBatchExecutor.updateMember` 의 직책×부서 블록(193–195행 근처)을 바꾼다:

```java
        if (errors.isEmpty() && (f.containsKey("department") || f.containsKey("title"))) {
            MemberDepartment d = f.containsKey("department")
                    ? parsed(f.get("department"), MemberDepartment.class) : m.getDepartment();
            MemberTitle t = f.containsKey("title")
                    ? parsed(f.get("title"), MemberTitle.class) : m.getTitle();
            String comboError = comboError(d, t);
            if (comboError != null) {
                errors.put("title", comboError);
            } else {
                String rankError = rankError(actor, m, d, t);
                if (rankError != null) {
                    errors.put("title", rankError);
                } else {
                    actions.add(t == null ? () -> m.endCurrentTerm(currentGen())
                                          : () -> m.assignTerm(d, t, currentGen()));
                }
            }
        }
```

`applyGrade` 의 OB 전환도 임기를 끝내므로 같은 검사가 필요하다. `grade` 갈래를 바꾼다:

```java
-            case "grade" -> enumField(MemberGrade.class, v, errors, k, g -> applyGrade(m, g), actions, false);
+            case "grade" -> enumField(MemberGrade.class, v, errors, k, g -> {
+                        if (g == MemberGrade.OB && m.currentTerm().isPresent()) {
+                            String e = rankError(actor, m, null, null);
+                            if (e != null) { errors.put("grade", e); return; }
+                        }
+                        applyGrade(m, g);
+                    }, actions, false);
```

새 헬퍼를 클래스에 더한다:

```java
    /**
     * P6 — 임기를 바꾸려면 대상보다 rank 가 높아야 한다. 부여할 Role(d,t)과 지금 Role
     * 둘 다를 본다: 학술부원을 회장으로 올리는 것도, 회장을 학술부원으로 내리는 것도
     * 회장을 건드리는 일이다.
     *
     * null 을 돌려주면 통과다. 메시지를 돌려주면 그 줄이 invalid 로 떨어진다 —
     * 일괄 편집은 부분 성공이라 한 줄이 막혀도 나머지는 저장된다.
     */
    private String rankError(CurrentMember actor, Member target,
                             MemberDepartment d, MemberTitle t) {
        Set<Role> actorRoles = actor == null ? Set.of() : actor.roles();

        Role current = target.currentTerm()
                .flatMap(term -> Role.of(term.getDepartment(), term.getTitle()))
                .orElse(Role.MEMBER);
        if (!Policy.canAssign(actorRoles, current)) {
            return "이 회원의 임기를 변경할 권한이 없습니다.";
        }
        Role next = Role.of(d, t).orElse(Role.MEMBER);
        if (!Policy.canAssign(actorRoles, next)) {
            return "이 직책을 부여할 권한이 없습니다.";
        }
        return null;
    }
```

import: `com.jaram.be.security.CurrentMember`, `com.jaram.be.security.authz.Policy`, `Role`, `java.util.Set`.

- [ ] **Step 7: 테스트를 돌린다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.admin.TermAssignmentHierarchyTest'`
Expected: PASS (6 tests)

- [ ] **Step 8: 전체 테스트를 돌린다**

Run: `./gradlew --no-daemon -I <init> test --rerun`
Expected: PASS 전체.

`AdminResourceTest`·`AdminMemberAssignmentTest` 가 `actors.officer()`(PRESIDENT, rank 100)를 쓰므로 대부분 통과한다. 다만 **행위자가 자기 자신에게 임기를 주는 테스트**가 있으면 이제 막힌다 — 같은 rank 이기 때문이다. 그런 테스트는 `actors.save(Role.MEMBER)` 로 만든 **다른 회원**을 대상으로 바꾼다. 그것이 원래 의도이기도 하다.

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(authz): 관리 목록·일괄 편집을 리소스별 권한으로 가르고 P6 위계를 건다

핸들러가 하나이고 대상이 경로 변수라 조건 빈으로 뺐다. 임기 변경은
대상보다 rank 가 높아야 통과한다 — 부회장은 회장을 임명하지도, 회장의
임기를 끝내지도, 자기 임기를 고치지도 못한다. 마지막 것은 별도 규칙이
아니라 같은 규칙의 결과다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 8: 세미나 — 권한과 소유자 조건

**Files:**
- Create: `src/main/java/com/jaram/be/seminar/SeminarAccessPolicy.java`
- Modify: `src/main/java/com/jaram/be/seminar/AdminSeminarController.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarController.java`
- Modify: `src/main/java/com/jaram/be/seminar/SeminarService.java` (`getOne` 의 `officer` 인자 의미 변경 없음, 호출부만)
- Test: `src/test/java/com/jaram/be/seminar/SeminarPermissionTest.java` (신규)

**Interfaces:**
- Consumes: `SeminarRepository`, `CurrentMember` (Task 4)
- Produces: `@Component("seminarAccess") class SeminarAccessPolicy` — `boolean isOwner(String seminarId, Authentication auth)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/seminar/SeminarPermissionTest.java`:

```java
package com.jaram.be.seminar;

import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarPermissionTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired SeminarRepository seminars;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        seminars.deleteAll();
        members.deleteAll();
    }

    @AfterEach void cleanup() {
        seminars.deleteAll();
        members.deleteAll();
    }

    /** 홍보부는 세미나를 만들 수 있다 — 매트릭스에 SEMINAR_CREATE 가 있다. */
    @Test
    void prStaffCanCreateASeminar() {
        given().header("Authorization", "Bearer " + actors.token(Role.PR_STAFF))
                .contentType("application/json")
                .body(Map.of("title", "테스트", "speaker", "홍길동", "topic", "주제",
                        "startsAt", "2026-12-01T19:00:00Z", "place", "강의실", "mode", "OFFLINE"))
                .when().post("/api/seminars")
                .then().statusCode(201);
    }

    /** 승인은 학술부장부터다. 부원은 운영은 하되 승인은 못 한다. */
    @Test
    void academicStaffCannotApprove() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .when().post("/api/admin/seminars/any-id/approve")
                .then().statusCode(403);
    }

    @Test
    void academicLeadCanApprove() {
        // 없는 세미나라 404. 403 이 아니라는 것이 요점이다.
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_LEAD))
                .when().post("/api/admin/seminars/any-id/approve")
                .then().statusCode(404);
    }

    /** 출석 관리와 명단은 부원도 한다 — 실제 운영을 맡는 사람들이다. */
    @Test
    void academicStaffManagesAttendance() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .when().post("/api/admin/seminars/any-id/attendance-code")
                .then().statusCode(404);
    }

    /** 홍보부는 세미나를 만들 뿐 명단은 못 본다 — 출석은 개인정보다. */
    @Test
    void prStaffCannotReadRoster() {
        given().header("Authorization", "Bearer " + actors.token(Role.PR_STAFF))
                .when().get("/api/admin/seminars/any-id/attendees")
                .then().statusCode(403);
    }

    @Test
    void plainMemberCannotCreateASeminar() {
        given().header("Authorization", "Bearer " + actors.member())
                .contentType("application/json")
                .body(Map.of("title", "테스트", "speaker", "홍길동", "topic", "주제",
                        "startsAt", "2026-12-01T19:00:00Z", "place", "강의실", "mode", "OFFLINE"))
                .when().post("/api/seminars")
                .then().statusCode(403);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.seminar.SeminarPermissionTest'`
Expected: FAIL — `academicStaffCannotApprove` 가 404 를 받는다 (아직 OFFICER 면 다 통과한다)

- [ ] **Step 3: SeminarAccessPolicy 를 만든다**

`src/main/java/com/jaram/be/seminar/SeminarAccessPolicy.java`:

```java
package com.jaram.be.seminar;

import com.jaram.be.security.CurrentMember;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 3층 조건 — 권한이 아니라 리소스와의 관계를 본다. "본인 세미나만 수정"은 Permission
 * 으로 표현할 수 없다. 모두가 갖는 능력이고 대상만 제한되기 때문이다.
 *
 * 지금까지 이 판정은 SeminarService 안에 손으로 박혀 있었다. 애너테이션으로 올리면
 * 권한과 조건이 핸들러 한 줄에 같이 보인다.
 */
@Component("seminarAccess")
public class SeminarAccessPolicy {

    private final SeminarRepository seminars;

    public SeminarAccessPolicy(SeminarRepository seminars) { this.seminars = seminars; }

    public boolean isOwner(String seminarId, Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof CurrentMember me)) return false;
        return seminars.findById(seminarId)
                .map(s -> me.id().equals(s.getCreatedById()))
                .orElse(false);
    }
}
```

- [ ] **Step 4: 관리자 세미나 컨트롤러에 권한을 붙인다**

`AdminSeminarController.java` — 각 핸들러 위에:

```java
    @GetMapping("/pending")            @PreAuthorize("hasAuthority('SEMINAR_APPROVE')")
    @PostMapping("/{id}/approve")      @PreAuthorize("hasAuthority('SEMINAR_APPROVE')")
    @PostMapping("/{id}/reject")       @PreAuthorize("hasAuthority('SEMINAR_APPROVE')")
    @PostMapping("/{id}/attendance-code")   @PreAuthorize("hasAuthority('SEMINAR_ATTENDANCE_MANAGE')")
    @PostMapping("/{id}/close-attendance")  @PreAuthorize("hasAuthority('SEMINAR_ATTENDANCE_MANAGE')")
    @GetMapping("/{id}/attendees")     @PreAuthorize("hasAuthority('SEMINAR_ROSTER_READ')")
    @PostMapping("/{id}/attendees")    @PreAuthorize("hasAuthority('SEMINAR_ROSTER_EDIT')")
    @DeleteMapping("/{id}/attendees/{memberId}") @PreAuthorize("hasAuthority('SEMINAR_ROSTER_EDIT')")
```

(실제 코드에서는 매핑 애너테이션 아래 줄에 `@PreAuthorize` 를 둔다. 위 표는 대응만 보인 것이다.)

- [ ] **Step 5: 공개 세미나 컨트롤러에 권한과 조건을 붙인다**

`SeminarController.java`:

```java
    @PostMapping
    @PreAuthorize("hasAuthority('SEMINAR_CREATE')")
    public SeminarResponse create(...) { ... }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('SEMINAR_EDIT') or @seminarAccess.isOwner(#id, authentication)")
    public SeminarResponse resubmit(@PathVariable String id, ...) { ... }

    @GetMapping("/{id}/roster")
    @PreAuthorize("hasAuthority('SEMINAR_ROSTER_READ')")
    public RosterResponse roster(@PathVariable String id) { ... }
```

`list`, `getOne`, `attend`, `attendees` 는 그대로 둔다 — 인증과 1층 자격으로 통과하는 것이 설계다.

42행의 `officer` 인자를 Permission 기준으로 바꾼다:

```java
-                me != null && me.authority() == Authority.OFFICER);
+                me != null && me.can(Permission.SEMINAR_APPROVE));
```

import 를 `com.jaram.be.security.authz.Permission` 으로 바꾸고 `Authority` import 가 남는지 확인한다.

`SeminarService.resubmit` 의 소유자 검사(139–141행)는 **그대로 둔다.** 애너테이션이 먼저 걸러 주지만, 서비스가 단독으로 불릴 때의 불변식이고 `SEMINAR_EDIT` 보유자가 남의 것을 고칠 때는 통과해야 하므로 조건을 넓힌다:

```java
-        if (!callerId.equals(s.getCreatedById())) {
-            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "본인 세미나만 수정할 수 있습니다.");
-        }
```

이 줄은 **지운다.** 소유자 판정이 이제 `@PreAuthorize` 로 올라가 두 곳에 같은 규칙이 생기고, 둘이 어긋나면 `SEMINAR_EDIT` 보유자가 애너테이션은 통과하고 서비스에서 막히는 모순이 된다.

- [ ] **Step 6: 테스트를 돌린다**

Run: `./gradlew --no-daemon -I <init> test --rerun`
Expected: PASS 전체.

"남의 세미나 수정은 403" 을 확인하던 테스트가 여전히 403 이어야 한다 — 이제 `@PreAuthorize` 가 던지고 `RestAccessDeniedHandler` 가 응답을 만든다. **`code` 는 양쪽 다 `FORBIDDEN` 이라 그대로다.** 바뀌는 것은 `message` 뿐이다 (`"본인 세미나만 수정할 수 있습니다."` → `"접근 권한이 없습니다."`). 메시지 문자열을 단언하는 테스트가 있으면 새 값으로 맞춘다.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(authz): 세미나 권한을 행위별로 가르고 소유자 조건을 애너테이션으로 올린다

승인·출석 관리·명단 읽기·명단 수정이 각각 다른 권한이다. 부원은 운영을
맡되 승인은 못 한다. "본인 세미나만 수정"은 서비스 안에 박혀 있던 것을
@PreAuthorize 로 올려, 권한과 조건이 핸들러 한 줄에 같이 보이게 했다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 9: 스터디

**Files:**
- Modify: `src/main/java/com/jaram/be/study/StudyController.java`
- Test: `src/test/java/com/jaram/be/study/StudyPermissionTest.java` (신규)

**Interfaces:**
- Consumes: 없음 (애너테이션만)
- Produces: 없음

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/study/StudyPermissionTest.java`:

```java
package com.jaram.be.study;

import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyPermissionTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @AfterEach void cleanup() { members.deleteAll(); }

    /** 스터디 개설 승인은 학술부장부터다. */
    @Test
    void academicStaffCannotApproveStudies() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .when().get("/api/studies/pending")
                .then().statusCode(403);
    }

    @Test
    void academicLeadSeesPendingStudies() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_LEAD))
                .when().get("/api/studies/pending")
                .then().statusCode(200);
    }

    /** 신청자 관리는 부원도 한다. */
    @Test
    void academicStaffManagesApplicants() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .when().get("/api/studies/applicants")
                .then().statusCode(200);
    }

    @Test
    void prStaffTouchesNeither() {
        String token = actors.token(Role.PR_STAFF);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/studies/pending").then().statusCode(403);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/studies/applicants").then().statusCode(403);
    }

    /** 목록과 개설은 권한이 아니라 인증으로 통과한다. */
    @Test
    void plainMemberStillListsStudies() {
        given().header("Authorization", "Bearer " + actors.member())
                .when().get("/api/studies")
                .then().statusCode(200);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.study.StudyPermissionTest'`
Expected: FAIL — `academicStaffCannotApproveStudies` 가 200 을 받는다

- [ ] **Step 3: 컨트롤러에 권한을 붙인다**

`StudyController.java`:

```java
    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('STUDY_APPROVE')")

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('STUDY_APPROVE')")

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('STUDY_APPROVE')")

    @GetMapping("/applicants")
    @PreAuthorize("hasAuthority('STUDY_APPLICANT_MANAGE')")

    @PostMapping("/applicants/{id}/approve")
    @PreAuthorize("hasAuthority('STUDY_APPLICANT_MANAGE')")

    @PostMapping("/applicants/{id}/reject")
    @PreAuthorize("hasAuthority('STUDY_APPLICANT_MANAGE')")
```

`list`, `create`, `my`, `apply` 는 그대로 둔다.

`import org.springframework.security.access.prepost.PreAuthorize;` 를 더한다.

- [ ] **Step 4: 전체 테스트를 돌린다**

Run: `./gradlew --no-daemon -I <init> test --rerun`
Expected: PASS 전체

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(authz): 스터디 승인과 신청자 관리를 다른 권한으로 가른다

개설 승인은 부장부터, 신청자 관리는 부원도 한다. 목록과 개설은 권한이
아니라 인증으로 통과한다 — 회원이 하는 일이지 운영이 아니다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 10: 일정·설정·대시보드·반출

**Files:**
- Create: `src/main/java/com/jaram/be/admin/SettingsAccess.java`
- Modify: `src/main/java/com/jaram/be/schedule/AdminScheduleController.java`
- Modify: `src/main/java/com/jaram/be/admin/AdminSettingsController.java`
- Modify: `src/main/java/com/jaram/be/admin/AdminDashboardController.java`
- Modify: `src/main/java/com/jaram/be/admin/AdminExportController.java`
- Test: `src/test/java/com/jaram/be/admin/SettingsPermissionTest.java` (신규)

**Interfaces:**
- Consumes: `Permissions.has` (Task 4), `AdminSettingsUpdate` (기존)
- Produces: `@Component("settingsAccess") class SettingsAccess` — `boolean canApply(AdminSettingsUpdate req, Authentication auth)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/admin/SettingsPermissionTest.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Role;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * SITE_LINKS_EDIT 를 SETTINGS_EDIT 에서 분리한 목적은 홍보부가 푸터 링크만 고치게
 * 하는 것이다. 설정 PATCH 가 엔드포인트 하나라 필드 조건으로만 달성된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettingsPermissionTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @AfterEach void cleanup() { members.deleteAll(); }

    @Test
    void prLeadEditsFooterLinksOnly() {
        String token = actors.token(Role.PR_LEAD);

        given().header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(Map.of("links", Map.of("github", "https://github.com/jaram")))
                .when().patch("/api/admin/settings")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(Map.of("autoPromote", true))
                .when().patch("/api/admin/settings")
                .then().statusCode(403);
    }

    /** 기수 변경은 되돌리기 어려워 회장만 한다. */
    @Test
    void onlyPresidentChangesTheGeneration() {
        given().header("Authorization", "Bearer " + actors.token(Role.VICE_PRESIDENT))
                .contentType("application/json").body(Map.of("currentGen", 43))
                .when().patch("/api/admin/settings")
                .then().statusCode(403);

        given().header("Authorization", "Bearer " + actors.token(Role.PRESIDENT))
                .contentType("application/json").body(Map.of("currentGen", 43))
                .when().patch("/api/admin/settings")
                .then().statusCode(200);
    }

    /** 개인정보 반출도 회장과 서버 관리자만. */
    @Test
    void exportIsNarrow() {
        given().header("Authorization", "Bearer " + actors.token(Role.VICE_PRESIDENT))
                .contentType("application/json").body(Map.of("resource", "members"))
                .when().post("/api/admin/export/google-drive")
                .then().statusCode(403);
    }

    /** 임기가 있으면 누구나 대시보드는 본다. */
    @Test
    void everyStaffSeesTheDashboard() {
        for (Role r : new Role[]{Role.FINANCE_STAFF, Role.PR_STAFF, Role.ACADEMIC_STAFF,
                                 Role.SERVER_ADMIN, Role.VICE_PRESIDENT, Role.PRESIDENT}) {
            given().header("Authorization", "Bearer " + actors.token(r))
                    .when().get("/api/admin/dashboard/stats")
                    .then().statusCode(200);
        }
    }

    @Test
    void plainMemberSeesNoDashboard() {
        given().header("Authorization", "Bearer " + actors.member())
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(403);
    }

    /** 일정 관리는 학술부장만 — 세미나 슬롯을 여는 사람이다. */
    @Test
    void scheduleManagementIsAcademicLeadUpwards() {
        given().header("Authorization", "Bearer " + actors.token(Role.ACADEMIC_STAFF))
                .contentType("application/json").body(Map.of())
                .when().post("/api/admin/schedules")
                .then().statusCode(403);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.admin.SettingsPermissionTest'`
Expected: FAIL — 여러 개. 지금은 임기만 있으면 전부 통과한다.

- [ ] **Step 3: SettingsAccess 를 만든다**

`src/main/java/com/jaram/be/admin/SettingsAccess.java`:

```java
package com.jaram.be.admin;

import com.jaram.be.admin.dto.AdminSettingsUpdate;
import com.jaram.be.security.authz.Permission;
import com.jaram.be.security.authz.Permissions;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 설정 PATCH 는 엔드포인트가 하나인데 그 안의 필드마다 무게가 다르다. SITE_LINKS_EDIT
 * 를 따로 둔 목적(홍보부가 푸터 링크만 고친다)은 필드 조건으로만 달성된다.
 *
 * currentGen 이 SETTINGS_ROLLOVER 인 이유: 기수를 손으로 바꾸는 유일한 통로다.
 * 전환 자체는 MemberLifecycleService 의 스윕이고 사람이 부르는 엔드포인트가 없다.
 */
@Component("settingsAccess")
public class SettingsAccess {

    public boolean canApply(AdminSettingsUpdate req, Authentication auth) {
        if (req == null) return false;

        if (req.currentGen() != null && !Permissions.has(auth, Permission.SETTINGS_ROLLOVER)) {
            return false;
        }
        boolean touchesSettings = req.semesterTerm() != null || req.autoPromote() != null;
        if (touchesSettings && !Permissions.has(auth, Permission.SETTINGS_EDIT)) {
            return false;
        }
        if (req.links() != null
                && !Permissions.has(auth, Permission.SETTINGS_EDIT)
                && !Permissions.has(auth, Permission.SITE_LINKS_EDIT)) {
            return false;
        }
        // 아무 필드도 없는 요청은 SETTINGS_READ 만 있으면 통과시킨다 — 바뀌는 것이 없다.
        return Permissions.has(auth, Permission.SETTINGS_READ)
                || Permissions.has(auth, Permission.SETTINGS_EDIT)
                || Permissions.has(auth, Permission.SITE_LINKS_EDIT);
    }
}
```

- [ ] **Step 4: 네 컨트롤러에 권한을 붙인다**

`AdminSettingsController.java`:

```java
    @GetMapping
    @PreAuthorize("hasAuthority('SETTINGS_READ')")
    public AdminSettingsResponse get() { return service.get(); }

    @PatchMapping
    @PreAuthorize("@settingsAccess.canApply(#req, authentication)")
    public AdminSettingsResponse update(@Valid @RequestBody AdminSettingsUpdate req) { ... }
```

`AdminDashboardController.java`:

```java
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('DASHBOARD_READ')")
```

`AdminExportController.java`:

```java
    @PostMapping("/google-drive")
    @PreAuthorize("hasAuthority('EXPORT_RUN')")
```

`AdminScheduleController.java` — 다섯 핸들러 전부:

```java
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
```

각 파일에 `import org.springframework.security.access.prepost.PreAuthorize;` 를 더한다.

- [ ] **Step 5: 푸터 링크 읽기는 열어 둔다**

`SiteLinksController` 의 `GET /api/site/links` 는 비로그인 방문자도 보는 화면이다. 애너테이션을 붙이지 않고 `SecurityConfig` 의 `permitAll` 도 그대로 둔다.

- [ ] **Step 6: 전체 테스트를 돌린다**

Run: `./gradlew --no-daemon -I <init> test --rerun`
Expected: PASS 전체. `AdminSettingsTest` 는 `actors.officer()`(PRESIDENT) 를 쓰므로 통과한다.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(authz): 설정·일정·대시보드·반출에 권한을 건다

설정 PATCH 는 필드마다 무게가 달라 조건 빈으로 뺐다 — 홍보부는 푸터
링크만 고치고, 기수 변경은 회장만 하며, 개인정보 반출은 회장과 서버
관리자만 한다. 대시보드는 임기가 있으면 누구나 본다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 11: URL 매처를 걷어내고 누락을 테스트로 잡는다

**Files:**
- Modify: `src/main/java/com/jaram/be/security/SecurityConfig.java`
- Test: `src/test/java/com/jaram/be/security/AdminAuthorizationCoverageTest.java` (신규)

**Interfaces:**
- Consumes: Spring 의 `RequestMappingHandlerMapping`
- Produces: 없음

**이 태스크가 실패 방향을 뒤집는다.** 지금까지는 `SecurityConfig` 매처를 빠뜨리면 `anyRequest().authenticated()` 로 흘러 **조용히 열렸다**. 이후에는 `@PreAuthorize` 를 빠뜨리면 인증만 요구하게 되는데, 그 누락을 컴파일러가 아니라 이 테스트가 잡는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jaram/be/security/AdminAuthorizationCoverageTest.java`:

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
 * /api/admin/** 의 모든 핸들러에 @PreAuthorize 가 붙어 있는지 본다.
 *
 * 권한 규칙이 URL 문자열에 있을 때는 매처를 빠뜨리면 조용히 열렸다. 이제는
 * 애너테이션을 빠뜨리면 "로그인한 아무나"가 된다 — 컴파일러가 잡지 못하는
 * 누락이라 테스트가 잡는다.
 */
@SpringBootTest
class AdminAuthorizationCoverageTest extends PostgresTest {

    @Autowired RequestMappingHandlerMapping mapping;

    @Test
    void everyAdminHandlerDeclaresPreAuthorize() {
        List<String> missing = new ArrayList<>();

        mapping.getHandlerMethods().forEach((info, method) -> {
            Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of()
                    : info.getPathPatternsCondition().getPatternValues();
            if (patterns.stream().noneMatch(p -> p.startsWith("/api/admin"))) return;

            boolean declared = method.getMethodAnnotation(PreAuthorize.class) != null
                    || method.getBeanType().getAnnotation(PreAuthorize.class) != null;
            if (!declared) {
                missing.add(method.getBeanType().getSimpleName() + "#" + method.getMethod().getName()
                        + " " + patterns);
            }
        });

        assertThat(missing)
                .as("@PreAuthorize 가 없는 관리자 핸들러 — 로그인한 아무나 쓸 수 있게 된다")
                .isEmpty();
    }
}
```

- [ ] **Step 2: 통과를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.security.AdminAuthorizationCoverageTest'`
Expected: PASS — Task 6·7·10 에서 관리자 핸들러를 전부 덮었다. 실패하면 목록에 나온 핸들러에 애너테이션을 더한다.

- [ ] **Step 3: 매처를 걷어낸다**

`SecurityConfig.filterChain` 의 `authorizeHttpRequests` 를 줄인다:

```java
            .authorizeHttpRequests(reg -> reg
                .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/people", "/api/seminars", "/api/studies").permitAll()
                // 푸터의 외부 링크 — 비로그인 방문자도 보는 화면이라 읽기는 열어 둔다 (수정은 /api/admin).
                .requestMatchers(HttpMethod.GET, "/api/site/links").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/schedules").permitAll()
                // 나머지는 전부 인증을 요구하고, 무엇을 할 수 있는지는 핸들러의
                // @PreAuthorize 가 정한다. 애너테이션을 빠뜨리면 "로그인한 아무나"가
                // 되므로 AdminAuthorizationCoverageTest 가 누락을 잡는다.
                .anyRequest().authenticated())
```

`hasAuthority("OFFICER")` 매처 여섯 줄이 사라진다.

- [ ] **Step 4: 전체 테스트를 돌린다**

Run: `./gradlew --no-daemon -I <init> test --rerun`
Expected: PASS 전체.

여기서 깨지는 것이 있다면 그 경로가 **매처에만 기대고 있었다**는 뜻이다 — 즉 `@PreAuthorize` 를 빠뜨린 곳이다. 실패한 경로의 핸들러에 애너테이션을 더한다. `/api/admin` 밖의 경로(`/api/studies/pending`, `/api/studies/applicants`, `/api/seminars/{id}/roster`, `POST /api/seminars`)는 커버리지 테스트가 보지 않으므로 특히 주의해서 확인한다 — Task 8·9 에서 이미 붙였어야 한다.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(authz): URL 매처를 걷어내고 누락을 테스트로 잡는다

권한 규칙이 경로 문자열에 있을 때는 매처를 빠뜨리면 조용히 열렸다.
이제 애너테이션을 빠뜨리면 로그인한 아무나가 되고, 커버리지 테스트가
그 누락을 잡는다. 실패 방향이 막히는 쪽으로 뒤집힌다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 12: JWT 에서 authority 클레임을 뺀다

**Files:**
- Modify: `src/main/java/com/jaram/be/security/JwtProvider.java`
- Modify: `src/main/java/com/jaram/be/security/JwtAuthFilter.java`
- Modify: `src/main/java/com/jaram/be/auth/AuthService.java` (84행)
- Modify: `src/test/java/com/jaram/be/support/Actors.java`
- Modify: 남은 `jwt.generate(...)` 호출 지점
- Test: `src/test/java/com/jaram/be/security/TokenEligibilityTest.java` 수정

**Interfaces:**
- Produces:
  - `JwtProvider.generate(String memberId, String name, String email)` — 인자 3개
  - `record JwtClaims(String memberId, String name, String email, Instant issuedAt)`

**동시에 정하는 것: 회원을 찾지 못한 토큰은 인증하지 않는다.** 지금은 클레임으로 되돌아갔지만, 클레임이 없으면 되돌아갈 곳이 없다. 권한 없는 인증을 만드는 것보다 401 이 맞다 — 회원 행은 파기(`purge`)로도 남으므로 운영에서 "없는 id" 는 나오지 않는다.

- [ ] **Step 1: 기존 테스트를 새 계약에 맞춘다**

`TokenEligibilityTest.authorityComesFromTheDatabaseNotTheClaim` 은 `Authority.OFFICER` 클레임을 심는 테스트다. 클레임이 사라지므로 다시 쓴다:

```java
    /**
     * 임기를 거두면 권한이 즉시 사라진다. 토큰은 신원만 싣고 권한은 요청 시점에
     * 임기에서 나오므로, ttl 이 남아 있어도 관리자 API 가 막힌다.
     */
    @Test
    void authorityComesFromTheDatabaseNotTheToken() {
        Member m = saved(MemberApproval.APPROVED, MemberStatus.ACTIVE);
        m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 41);
        m = members.save(m);
        String token = jwt.generate(m.getId(), m.getName(), m.getEmail());

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(200);

        m.endCurrentTerm(42);
        members.save(m);

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/dashboard/stats")
                .then().statusCode(403);
    }
```

같은 파일의 `tokenFor(Member m, Authority authority)` 를 `tokenFor(Member m)` 으로 바꾸고 호출부를 정리한다. `MemberDepartment`·`MemberTitle` import 를 더한다.

새 테스트를 하나 더한다:

```java
    /** 회원을 찾지 못하면 인증하지 않는다 — 권한 없는 인증을 만들지 않는다. */
    @Test
    void tokenForAnUnknownMemberIsRejected() {
        given().header("Authorization", "Bearer " + jwt.generate("ghost", "유령", "ghost@hanyang.ac.kr"))
                .when().get("/api/me")
                .then().statusCode(401);
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew --no-daemon -I <init> test --tests 'com.jaram.be.security.TokenEligibilityTest'`
Expected: 컴파일 실패 — `generate` 가 인자 3개를 받지 않는다

- [ ] **Step 3: JwtProvider 에서 클레임을 뺀다**

```java
public class JwtProvider {

    /** 토큰은 신원만 싣는다. 권한은 요청 시점에 DB 에서 읽는다 — 임기를 거두면 즉시 반영된다. */
    public record JwtClaims(String memberId, String name, String email, Instant issuedAt) { }

    ...

    public String generate(String memberId, String name, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(memberId)
                .claim("name", name)
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    public JwtClaims parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new JwtClaims(
                c.getSubject(),
                c.get("name", String.class),
                c.get("email", String.class),
                c.getIssuedAt().toInstant());
    }
}
```

`import com.jaram.be.member.Authority;` 를 지운다.

- [ ] **Step 4: 필터에서 클레임 폴백과 기존 권한을 뺀다**

`JwtAuthFilter.doFilterInternal`:

```java
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var claims = jwt.parse(header.substring(7));
                Member m = members.findById(claims.memberId()).orElse(null);
                // 회원을 찾지 못하거나 자격이 없으면 인증하지 않는다 — 인증 없이 통과시키면
                // entrypoint 가 401 을 만든다. 권한 없는 인증을 세우는 것보다 401 이 맞다.
                if (!eligibility.isUsable(m, claims.issuedAt())) {
                    chain.doFilter(req, res);
                    return;
                }
                Set<Role> roles = resolver.rolesOf(m);
                Set<Permission> permissions = Policy.permissionsOf(roles);

                var principal = new CurrentMember(
                        m.getId(), m.getName(), m.getEmail(), m.getAuthority(),
                        roles, permissions);
                var auth = new UsernamePasswordAuthenticationToken(principal, null,
                        permissions.stream()
                                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name()))
                                .toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // invalid token → leave unauthenticated → entrypoint returns 401
            }
        }
        chain.doFilter(req, res);
```

`Eligibility.isUsable` 은 이미 `m == null` 에서 `false` 를 돌려주므로 (Task 3 Step 1) 별도 분기가 필요 없다.

이름과 이메일을 클레임이 아니라 엔티티에서 읽는 것에 주의한다 — 회원이 이름을 바꾸면 토큰 안의 옛 이름이 아니라 지금 이름이 나가야 한다.

`ArrayList` import 를 지운다.

- [ ] **Step 5: 발급부를 고친다**

`AuthService:84`:

```java
-        String token = jwt.generate(m.getId(), m.getName(), m.getEmail(), m.getAuthority());
+        String token = jwt.generate(m.getId(), m.getName(), m.getEmail());
```

`Actors.tokenFor`:

```java
-        return jwt.generate(m.getId(), m.getName(), m.getEmail(), m.getAuthority());
+        return jwt.generate(m.getId(), m.getName(), m.getEmail());
```

- [ ] **Step 6: 남은 호출 지점을 찾아 고친다**

```bash
grep -rn 'jwt\.generate(' src/main/java src/test/java --include='*.java'
```

남은 것은 전부 `m.getAuthority()` 를 네 번째 인자로 넘기는 형태다. 그 인자만 지운다. `Authority` import 가 그 파일에서 더 쓰이지 않으면 지운다.

- [ ] **Step 7: 전체 테스트를 돌린다**

Run: `./gradlew --no-daemon -I <init> test --rerun`
Expected: PASS 전체.

이 시점에 깨지는 테스트는 **DB 에 없는 회원의 토큰을 쓰는 곳**이다. Task 5 에서 전부 `Actors` 로 옮겼어야 하므로 남아 있으면 안 된다. 남았다면 `actors.officer()` / `actors.member()` / `actors.token(Role.X)` 로 바꾼다.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(authz): JWT 에서 authority 클레임을 빼고 신원만 싣는다

권한은 요청 시점에 임기에서 나온다. 임기를 거두거나 승인을 취소하면
ttl(12시간)을 기다리지 않고 즉시 반영된다.

이것이 토큰 탈취를 막는 것은 아니다. 탈취된 토큰은 memberId 를 담은
자격증명이고 서버가 그 id 로 권한을 전개하므로 공격자는 피해자의 권한을
그대로 얻는다. 탈취에 대한 수단은 credentialsInvalidatedAt 이다.

회원을 찾지 못한 토큰은 이제 인증하지 않는다 — 클레임으로 되돌아갈 곳이
없어졌고, 권한 없는 인증을 세우는 것보다 401 이 맞다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

### Task 13: 계약 문서와 배포 공지

**Files:**
- Create: `docs/migrations/2026-09-14-authz-staff-permission-reduction.md`
- Modify: `src/test/resources/openapi/openapi.yaml` 은 **건드리지 않는다** (FE 저장소로의 심볼릭 링크다)
- Test: 없음 — 문서 태스크다

**왜 마이그레이션 SQL 이 없는가:** Role 을 저장하지 않으므로 스키마 변경이 없다. `credentialsInvalidatedAt` 컬럼은 hotfix #21 에서 이미 들어갔고 `ddl-auto: update` 가 만든다. 이 문서는 **사람에게 알리는 것**이 목적이다.

- [ ] **Step 1: 공지 문서를 쓴다**

`docs/migrations/2026-09-14-authz-staff-permission-reduction.md`:

```markdown
# 권한 재설계 — 부원 권한 축소 (배포 공지)

배포 전에 임원진에게 알려야 한다. **스키마 변경도 데이터 이전도 없다.**
Role 은 `member_term(department, title)` 에서 파생하며 저장하지 않는다.

## 무엇이 바뀌나

지금까지는 **임기가 있으면 누구나 `/api/admin/**` 전체**를 썼다. 부원도 회장과
똑같이 회원을 승인하고 설정을 바꾸고 개인정보를 반출할 수 있었다.

이제 직책마다 권한이 다르다.

| 직책 | 잃는 것 |
|---|---|
| 학술부원 | 회원 승인·조회, 설정 변경, 개인정보 반출, 세미나/스터디 **승인**, 일정 관리 |
| 홍보부장·부원 | 회원 관련 전부, 설정 변경(푸터 링크만 남음), 세미나 승인·출석·명단, 스터디 전부, 일정 관리, 반출 |
| 회계부장 | 세미나·스터디·일정·설정·반출 전부 (회원 조회와 승인은 유지) |
| 회계부원 | 위와 같고 회원 **승인**도 잃는다 (조회만 유지) |
| 서버 관리자 | 회원 승인·수정, 세미나·스터디·일정 전부 (설정과 반출은 유지) |
| 부회장 | 기수 변경, 개인정보 반출 |
| 회장 | 없음 |

모든 직책이 대시보드(`GET /api/admin/dashboard/stats`)는 계속 본다.

## 임기 변경에 위계가 생긴다

임기를 부여하거나 끝내려면 **대상보다 직책이 높아야** 한다.

- 부회장은 서버 관리자·부장·부원을 임명한다.
- 부회장은 **회장을 임명하거나 회장의 임기를 끝낼 수 없다.**
- **누구도 자기 임기는 고칠 수 없다** — 별도 규칙이 아니라 같은 규칙의 결과다.

회장 계정을 잃으면 DB 를 직접 고쳐야 한다. 탈출 해치를 하나만 둔 대가다.

## 로그인 세션

`authority` 클레임이 토큰에서 빠진다. **배포 시점에 발급돼 있던 토큰도 계속
동작한다** — 서버가 클레임을 읽지 않고 회원 id 로 권한을 다시 전개하기 때문이다.
재로그인을 요구할 필요가 없다.

## 확인할 것

배포 뒤 회장 계정으로 아래를 한 번씩 눌러 본다.

- `GET /api/admin/members/pending` — 200
- `PATCH /api/admin/settings` 에 `currentGen` — 200 (부회장 계정이면 403 이 맞다)
- `POST /api/admin/export/google-drive` — 200
```

- [ ] **Step 2: 계약 변경을 FE 에 넘길 준비를 한다**

`openapi.yaml` 은 두 경로 모두 FE 저장소(`home-jaram-fe/docs/api/openapi.yaml`)로의 심볼릭 링크라 BE 에서 고치면 FE 저장소를 바꾸는 것이 된다. 이 계획에서는 **건드리지 않는다.** 대신 공지 문서 끝에 FE 가 받아야 할 계약 변경을 적는다:

```markdown
## FE 가 받아야 할 계약 변경

`MeProfile` 과 로그인 응답의 `user` 에 필드 두 개가 **추가**된다. 기존 필드는
전부 그대로이며 `authority` 도 값과 파생 규칙이 같다(현직 임기가 있으면 `OFFICER`).
따라서 **BE 만 배포해도 FE 는 깨지지 않는다.**

- `roles: string[]` — 예 `["ACADEMIC_LEAD"]`
- `permissions: string[]` — 예 `["SEMINAR_CREATE", "SEMINAR_APPROVE", ...]`

### FE 이행은 선택이 아니라 필수다

`authority` 가 그대로라서 화면이 **동작은 한다.** 하지만 판정이 틀린 채로 동작한다.

`src/shared/auth/roles.js` 의 `isAdmin(user)` 은 `authority === 'OFFICER'` 를 본다.
즉 **임기가 있으면 참**이다. 새 권한 체계에서 홍보부원도 `OFFICER` 지만 회원 관리
화면에서 403 을 받는다. 그 파일의 주석이 스스로 막겠다고 적어 둔 상황이 바로
이것이다 — "버튼은 보이는데 들어가면 403 이 뜨는 어긋남".

`authority` 를 남기는 것이 그 어긋남을 **유지한다.** 이번 배포로 어긋남이 처음
생기는 것이므로, FE 이행 전까지는 권한이 좁은 직책이 막힌 화면을 보게 된다.

고쳐야 할 곳은 넷이다.

| 파일 | 지금 | 바꿀 것 |
|---|---|---|
| `src/shared/auth/roles.js:8,13` | `ADMIN_ROLES.includes(user.authority)` | `user.permissions?.length > 0` (콘솔 진입 여부) |
| `src/features/admin/RequireAdmin.jsx:26` | `isAdmin(user)` | 위와 같음 |
| `src/features/profile/ProfilePage.jsx:30` | `isAdmin(s.user)` | 위와 같음 |
| 각 관리자 화면의 버튼·탭 | 노출 조건 없음 | 해당 `permissions` 보유 여부로 가린다 |

`roles.js` 의 `ADMIN_ROLES` 에 있는 `'ADMIN'` 은 BE 가 한 번도 보낸 적 없는 값이다.
죽은 분기이므로 같이 지운다.

### 그 다음에야 authority 를 뺀다

위 이행이 끝나고 배포된 것을 확인한 뒤, **별도 배포**에서 BE 가 `MeProfile.authority`
와 `UserSummary.authority` 를 제거한다. 프로필 화면의 "권한: 임원" 표시
(`profile.data.js:11,23`, `ProfileView.jsx:21`, `EditView.jsx:26`)는 그때 `roles` 의
한글 라벨로 바꾸거나 없앤다.

순서를 지켜야 하는 이유: `authority` 를 먼저 빼면 `isAdmin` 이 모든 사용자에게
거짓을 돌려주고 `RequireAdmin` 이 전원을 튕겨내 **관리자 화면 전체가 죽는다.**

### 함께 볼 것 — 403 에서 세션이 남는다

`src/shared/api/client.js` 의 응답 인터셉터는 **401 에서만** 세션을 지운다. 권한이
좁아진 사용자는 403 을 받고 세션이 남은 채 막힌 화면을 본다. 위 이행으로 버튼
자체가 가려지면 403 이 거의 나지 않으므로, 그 이행이 이 문제의 해결이기도 하다.

`WITHDRAWN` 은 별개다. 탈퇴 회원은 이제 인증 필터가 막아 **401** 이 나가므로 화면이
자동으로 로그아웃한다. 재등록 대상(`REREGISTRATION_REQUIRED`)은 의도적으로 403 이며
세션이 남아야 팝업을 띄울 수 있다.
```

- [ ] **Step 3: 커밋하고 푸시한다**

```bash
git add docs/migrations/2026-09-14-authz-staff-permission-reduction.md
git commit -m "$(cat <<'EOF'
docs(authz): 부원 권한 축소 배포 공지를 남긴다

스키마 변경도 데이터 이전도 없다. 사람에게 알리는 것이 목적이다 —
조용한 파괴적 변경이라 배포 전에 임원진이 알아야 한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
git push -u origin feat/authz-policy-role
```

- [ ] **Step 4: 마지막 전체 검증**

```bash
./gradlew --no-daemon -I <init> clean test
```

Expected: PASS 전체, 소요 1분 이상 (UP-TO-DATE 건너뛰기가 아님을 확인)

- [ ] **Step 5: PR 을 연다**

```bash
gh pr create --base develop --head feat/authz-policy-role \
  --title "feat(authz): 권한을 Permission × Role 매트릭스로 재설계한다" \
  --body "$(cat <<'EOF'
`docs/superpowers/specs/2026-09-14-authz-policy-role-design.md` 구현.

## 무엇이 바뀌나

- Permission 20개 × Role 10개 매트릭스. Role 은 현직 임기에서 파생하며 저장하지 않는다
- 권한 판정이 `SecurityConfig` 의 URL 문자열에서 핸들러의 `@PreAuthorize` 로 옮겨 간다
- JWT 는 신원만 싣는다 — 임기를 거두면 ttl 을 기다리지 않고 즉시 반영된다
- 임기 변경에 위계가 생긴다 (부회장은 회장을 건드리지 못한다)

## 파괴적 변경

부원(STAFF)이 권한을 잃는다. `docs/migrations/2026-09-14-authz-staff-permission-reduction.md`
에 직책별로 무엇을 잃는지 적어 두었다. **배포 전에 임원진 공지가 필요하다.**

스키마 변경과 데이터 이전은 없다. 배포 시점에 발급돼 있던 토큰도 계속 동작한다.

## 계약

`MeProfile` 과 로그인 응답에 `roles[]`·`permissions[]` 를 **추가**한다. `authority` 는
값도 규칙도 그대로라 **BE 만 배포해도 FE 는 깨지지 않는다.**

다만 FE 이행은 선택이 아니다. `shared/auth/roles.js` 의 `isAdmin` 이 `authority` 를
보는 한, 홍보부원처럼 권한이 좁아진 직책은 버튼은 보이는데 403 을 받는다. 이행
대상 파일 넷과 순서를 공지 문서에 적어 두었다. `authority` 제거는 그 이행이
배포된 것을 확인한 뒤 별도 배포에서 한다 — 먼저 빼면 `RequireAdmin` 이 전원을
튕겨내 관리자 화면 전체가 죽는다.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01DjcT8p1MYNVFgszzEAMPJq
EOF
)"
```

---

## 자체 점검

**스펙 커버리지**

| 스펙 | 태스크 |
|---|---|
| §1 결함 1 — 부원이 임원 전권 | Task 6·7·8·9·10 (Role 별 분리) |
| §1 결함 2 — 매처 누락이 조용히 열림 | Task 11 (매처 제거 + 커버리지 테스트) |
| §1 결함 3 — 리소스 조건이 서비스에 박힘 | Task 8 (`SeminarAccessPolicy`) |
| §1 결함 4 — 탈퇴·미승인이 admin 사용 | hotfix #21 에서 완료. Task 3 이 `Eligibility` 로 정리 |
| §1 결함 5 — 권한 변경 지연 | Task 4·12 |
| §1 결함 6 — 비밀번호 변경 후 토큰 생존 | hotfix #21 에서 완료 (`credentialsInvalidatedAt`) |
| §2 P1 행위로 이름 | Task 1 (`Permission`) |
| §2 P2 Role 중간계층 | Task 1 (`Role` + `Policy` 분리) |
| §2 P3 가산 OR | Task 1 (`permissionsOf(Set<Role>)`, 거부 없음) |
| §2 P4 자격 = 상한 | Task 3 (`Eligibility`) |
| §2 P5 조건 | Task 8 (`SeminarAccessPolicy`), Task 10 (`SettingsAccess`), Task 7 (`AdminResourceAccess`) |
| §2 P6 위계 | Task 1 (`canAssign`) + Task 7 (`rankError`) |
| §2 P7 탈출 해치 하나 | Task 1 (`PRESIDENT` 만 전체) |
| §3 3층 모델 | Task 3 (1층) + Task 4 (2층) + Task 8·10 (3층) |
| §4 임기에서 파생, 새 테이블 없음 | Task 2 (`RoleResolver`) |
| §5 Permission 20개 | Task 1 |
| §6 Role 매트릭스 | Task 1 (`Policy` + 표 테스트) |
| §7 JWT 신원만 | Task 12 |
| §7 `credentialsInvalidatedAt` | hotfix #21 에서 완료. Task 3 이 `Eligibility.isUsable` 로 옮김 |
| §7 refresh token | 범위 밖 (스펙 §11) |
| §8 클래스 구조 | 파일 구조 절 참조 — 스펙의 배치를 그대로 따른다 |
| §9 계약 유지 + `roles[]`/`permissions[]` 추가 | Task 4 Step 6 + Task 13 |
| §10 테스트 7종 | Policy 매트릭스(T1), RoleResolver(T2), Eligibility(T3, 기존 회귀), 위계(T7), 세션 무효화(기존 `TokenEligibilityTest`), 도메인 조건(T8), 엔드포인트 커버리지(T11) |
| §11 범위 밖 | 손대지 않는다 |

**스펙이 비운 곳을 채운 것** — "스펙에 없어 이 계획에서 정한 것" 절에 세 가지를 근거와 함께 적었다. 새 Permission 을 만들지 않았다.

**타입 일관성** — `Role.of(...)` 는 `Optional<Role>`, `RoleResolver.rolesOf(...)` 는 `Set<Role>`, `Policy.permissionsOf(...)` 는 `Set<Permission>`, `Policy.canAssign(Set<Role>, Role)` 는 `boolean`. Task 7 의 `rankError` 와 Task 4 의 필터가 같은 시그니처를 쓴다. `Permissions.has(Authentication, Permission)` 는 Task 7·10 의 조건 빈이 공유한다. `CurrentMember` 의 6인자 생성자는 Task 4 에서 정의되고 Task 12 에서 인자 출처만 바뀐다 (클레임 → 엔티티).

**알려진 위험 두 가지**

1. **Task 5 의 회원 수 단언.** `Actors` 가 실제 행을 저장하므로 개수를 세는 테스트가 깨질 수 있다. 대응을 Task 5 Step 5 에 적었다 — 개수를 늘려 맞추지 않고 식별자 단언으로 바꾼다.
2. **Task 8 의 오류 메시지.** 소유자 검사를 서비스에서 애너테이션으로 올리면 403 의 `code` 는 `FORBIDDEN` 으로 그대로지만 `message` 가 `"접근 권한이 없습니다."` 로 바뀐다. 메시지를 단언하는 테스트가 있으면 맞춘다.

**검증한 사실** — 계획을 쓰면서 코드로 확인한 것들: `AdminBatchResponse` 의 실패 목록 필드명은 `errors`(`RowError(id, fieldErrors)`)다. `RestAccessDeniedHandler` 는 `code = "FORBIDDEN"` 을 낸다. 없는 세미나에 대한 승인·출석코드 요청은 `NOT_FOUND` 404 다. `SeminarCreateRequest` 의 필수 필드는 `title` 과 `startsAt` 뿐이다. `MemberActivityGuard.requireRegistered` 호출 지점은 세 서비스의 다섯 곳이다. `jwt.generate` 호출 지점은 테스트 35개 파일에 52개다.
