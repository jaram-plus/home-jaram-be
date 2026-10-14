# 임기(MemberTerm) 모델 도입 — 구현 계획

- 스펙: `docs/superpowers/specs/2026-07-20-member-term-model-design.md`
- 범위: 하위 프로젝트 **A** 만. B(`MeProfile.terms`)·C(역대 임원 아카이브)는 별도 사이클.

## Goal

`Member` 에서 중복된 두 축을 제거한다.

1. `MemberCategory.exec` vs `title != null` → **임기(term)가 진행 중인가**로 단일화
2. `MemberCategory.grad` vs `MemberGrade.OB` → **`grade == OB`** 로 단일화

이를 위해 직책 이력을 기수 범위로 갖는 `member_term` 테이블을 만들고, `Member.title`/`department` 를 현재 임기에서 파생시킨다. `MemberCategory` 는 완전히 삭제하고, 남는 의미인 "기여자"는 `contributor` boolean 으로 옮긴다. 와이어 계약은 바뀌지 않는다.

## Architecture

```
Member 1 ──< MemberTerm
             department, title, startGen, endGen(null=현직)

Member.getTitle()      = currentTerm().title      (없으면 null)
Member.getDepartment() = currentTerm().department (없으면 null)
Member.getAuthority()  = currentTerm().isPresent() ? OFFICER : MEMBER

people 탭 판정
  exec    = currentTerm().isPresent()
  contrib = isContributor()
  grad    = getGrade() == OB

admin 배치 수정
  {department, title} → assignTerm(d, t, currentGen)
  {title: null}       → endCurrentTerm(currentGen)
  grade → OB          → setGrade(OB) + endCurrentTerm(currentGen)
  grade → 그 외        → setGrade(g)
```

### 졸업(OB) 규칙 — 2026-07-21 확정

- **수동만.** 기수·연도 기준 자동 승격은 넣지 않는다.
- **NEWCOMER → OB 금지.** ASSOCIATE·REGULAR 에서는 허용한다.
- **현직 임원과 OB 는 동시에 성립하지 않는다.**
  - OB 로 전환하면 진행 중인 임기를 `currentGen` 에서 종료한다. 이력은 남으므로 졸업자 카드에는 "전 학술부장"으로 나온다. 저장 전 확인은 FE 가 받는다(Task 6).
  - 이미 OB 인 회원에게 새 임기를 부여하는 요청은 거부한다. 대칭 불변식을 위한 것으로, 운영자가 명시적으로 고른 규칙은 아니다.
- **탈퇴하면 졸업자 탭에서도 사라진다.** `PeopleService` 의 기존 `status != WITHDRAWN` 필터를 그대로 둔다.
- **졸업 기수는 저장하지 않는다.** 자람의 기수는 입학 시점으로 고정이고 학번 앞 4자리에서 파생하므로 별도 컬럼이 필요 없다. `graduated_gen` 은 도입하지 않는다.

기수(gen)가 시간 축이다. `currentGen` 은 `AdminSettings.currentCohort` 를 우선하고, 0/미설정이면 `Gen.current()`(= 올해 − 1984, 2026년 기준 42) 로 폴백한다.

## Tech Stack

- Spring Boot 3.4.1 / Java 21 / Gradle
- Spring Data JPA + Hibernate (`ddl-auto: update` 운영, `create-drop` 테스트)
- PostgreSQL (Testcontainers), REST-assured, swagger-request-validator
- Lombok 없음. 롬복 대신 손으로 쓴 getter/setter.

## Global Constraints

- **Lombok 금지.** 생성자·getter·setter 를 직접 작성한다.
- 열거형은 `@Enumerated(EnumType.STRING)`, 와이어 값 = enum 이름.
- 사용자 대상 메시지는 한국어 존댓말, 이모지 없음.
- 계약(`home-jaram-fe/docs/api/openapi.yaml`)이 단일 진실원. BE 쪽 두 openapi.yaml 은 심볼릭 링크다.
- 기존 스타일에 맞춘다. 요청 범위 밖 코드는 건드리지 않는다.
- **테스트 실행 명령** (docker-java 가 로컬 데몬 버전을 거부하므로 init script 필요):
  ```bash
  cd /home/ksb/Dev/home-jaram/home-jaram-be && ./gradlew \
    --init-script /tmp/claude-1000/-home-ksb-Dev-home-jaram/32bd1627-6e3c-47e9-bc04-d581d8553359/scratchpad/init.gradle \
    test --tests '<패턴>'
  ```
  init.gradle 내용:
  ```groovy
  allprojects {
      tasks.withType(Test).configureEach {
          systemProperty 'api.version', '1.44'
      }
  }
  ```
- 기존 실패 4건은 이 작업 범위 밖이다. 손대지 않는다:
  `AdminContractTest.patchSettingsMatchesContract`, `AdminContractTest.dashboardStatsMatchesContract`,
  `AdminContractTest.getSettingsMatchesContract`, `SeminarContractTest.createMatchesContract`.

---

## Task 1 — `Gen` 유틸리티

기수 계산이 `AdminMemberService` 와 `AdminDashboardService` 두 곳에 각각 `FOUNDING_YEAR = 1984` 로 복제돼 있다. 한 곳으로 모은다. (스펙 §3.4 는 `AdminMemberService` 만 언급하지만, 실제로는 두 곳이다.)

### Files

- 생성: `src/main/java/com/jaram/be/member/Gen.java`
- 생성: `src/test/java/com/jaram/be/member/GenTest.java`
- 수정: `src/main/java/com/jaram/be/admin/AdminMemberService.java`
- 수정: `src/main/java/com/jaram/be/admin/AdminDashboardService.java`

### Interfaces

```java
public final class Gen {
    public static final int FOUNDING_YEAR = 1984;
    public static int current();
    public static Integer ofStudentId(String studentId);  // 파싱 실패 시 null
}
```

### Steps

- [x] `src/main/java/com/jaram/be/member/Gen.java` 를 만든다.
  ```java
  package com.jaram.be.member;

  import java.time.Year;

  /** 기수 계산. 창립 연도 기준 오프셋이며 학번 앞 4자리(입학 연도)에서도 파생한다. */
  public final class Gen {

      public static final int FOUNDING_YEAR = 1984;

      private Gen() { }

      public static int current() {
          return Year.now().getValue() - FOUNDING_YEAR;
      }

      /** 학번 앞 4자리 = 대학 입학 연도. 4자리 숫자로 시작하지 않으면 null. */
      public static Integer ofStudentId(String studentId) {
          if (studentId == null || studentId.length() < 4) return null;
          try {
              return Integer.parseInt(studentId.substring(0, 4)) - FOUNDING_YEAR;
          } catch (NumberFormatException e) {
              return null;
          }
      }
  }
  ```

- [x] `src/test/java/com/jaram/be/member/GenTest.java` 를 만든다.
  ```java
  package com.jaram.be.member;

  import org.junit.jupiter.api.Test;

  import java.time.Year;

  import static org.assertj.core.api.Assertions.assertThat;

  class GenTest {

      @Test
      void currentIsYearMinusFoundingYear() {
          assertThat(Gen.current()).isEqualTo(Year.now().getValue() - 1984);
      }

      @Test
      void derivesGenFromStudentIdPrefix() {
          assertThat(Gen.ofStudentId("2026123456")).isEqualTo(42);
          assertThat(Gen.ofStudentId("2023000003")).isEqualTo(39);
      }

      @Test
      void returnsNullWhenStudentIdIsNotParsable() {
          assertThat(Gen.ofStudentId(null)).isNull();
          assertThat(Gen.ofStudentId("202")).isNull();
          assertThat(Gen.ofStudentId("abcd1234")).isNull();
      }
  }
  ```

- [x] `AdminMemberService` 에서 `private static final int FOUNDING_YEAR = 1984;` 줄을 지우고, `deriveGrade` 를 다음처럼 바꾼다.
  ```java
  private MemberGrade deriveGrade(Integer gen) {
      int currentGen = Gen.current();
      return (gen != null && gen == currentGen) ? MemberGrade.NEWCOMER : MemberGrade.ASSOCIATE;
  }
  ```
  `import java.time.Year;` 가 이 파일에서 더 이상 쓰이지 않으면 지우고, `import com.jaram.be.member.Gen;` 을 더한다.

- [x] `AdminDashboardService` 에서 `private static final int FOUNDING_YEAR = 1984;` 줄을 지우고, `int currentGen = Year.now().getValue() - FOUNDING_YEAR;` 를 `int currentGen = Gen.current();` 로 바꾼다. 남은 `Year` 사용처가 없으면 import 를 지우고 `Gen` import 를 더한다.

- [x] 검증:
  ```bash
  cd /home/ksb/Dev/home-jaram/home-jaram-be && ./gradlew \
    --init-script /tmp/claude-1000/-home-ksb-Dev-home-jaram/32bd1627-6e3c-47e9-bc04-d581d8553359/scratchpad/init.gradle \
    test --tests 'com.jaram.be.member.GenTest' \
         --tests 'com.jaram.be.admin.AdminDashboardTest'
  ```
  기대: `BUILD SUCCESSFUL`.

- [x] `grep -rn 'FOUNDING_YEAR' src/main` 이 `Gen.java` 한 줄만 내놓는지 확인한다.

---

## Task 2 — `MemberTerm` 도입, title/department 파생화, admin 자동 전환

이 태스크는 **원자적**이다. `Member.setTitle`/`setDepartment` 를 지우는 순간 모든 호출부가 동시에 깨지므로 한 번에 끝낸다. 이 단계에서 `categories` 는 아직 남겨둔다(Task 3 담당). `Set<MemberCategory>` (EAGER) 와 `List<MemberTerm>` (EAGER + `@OrderBy`) 가 공존해도 `MultipleBagFetchException` 은 나지 않는다 — bag 은 하나뿐이다.

### Files

- 생성: `src/main/java/com/jaram/be/member/MemberTerm.java`
- 생성: `src/test/java/com/jaram/be/member/MemberTermTest.java`
- 수정: `src/main/java/com/jaram/be/member/Member.java`
- 수정: `src/main/java/com/jaram/be/admin/AdminBatchExecutor.java`
- 수정: `src/test/java/com/jaram/be/member/MemberAuthorityTest.java`
- 수정: `src/test/java/com/jaram/be/admin/AdminMemberAssignmentTest.java`
- 수정: `src/test/java/com/jaram/be/people/PeopleTest.java` (헬퍼만; 탭 판정은 Task 3)
- 수정: `src/test/java/com/jaram/be/contract/PeopleContractTest.java` (시드만)

### Interfaces

```java
// MemberTerm
static MemberTerm start(Member m, MemberDepartment d, MemberTitle t, int startGen);  // 패키지 전용
public MemberDepartment getDepartment();
public MemberTitle getTitle();
public int getStartGen();
public Integer getEndGen();
public boolean isCurrent();     // endGen == null
public String label();          // title.label(department)
void end(int gen);              // 패키지 전용

// Member
public List<MemberTerm> getTerms();          // 불변 뷰, startGen ASC
public Optional<MemberTerm> currentTerm();   // endGen 이 null 인 임기. 불변식상 최대 하나.
public Optional<MemberTerm> lastEndedTerm(); // 종료된 임기 중 startGen 이 가장 큰 것.
public MemberTitle getTitle();               // currentTerm 의 title, 없으면 null
public MemberDepartment getDepartment();     // currentTerm 의 department, 없으면 null
public void assignTerm(MemberDepartment d, MemberTitle t, int currentGen);
public void endCurrentTerm(int currentGen);
// 삭제: setTitle(MemberTitle), setDepartment(MemberDepartment)
```

### Steps

- [x] `src/main/java/com/jaram/be/member/MemberTerm.java` 를 만든다.
  ```java
  package com.jaram.be.member;

  import jakarta.persistence.Column;
  import jakarta.persistence.Entity;
  import jakarta.persistence.EnumType;
  import jakarta.persistence.Enumerated;
  import jakarta.persistence.FetchType;
  import jakarta.persistence.Id;
  import jakarta.persistence.JoinColumn;
  import jakarta.persistence.ManyToOne;
  import jakarta.persistence.Table;

  import java.util.UUID;

  /**
   * 한 회원이 한 직책을 맡은 기간. 1년 임기는 startGen == endGen, 연임은 범위,
   * 현직은 endGen == null 로 표현한다. Member 를 통해서만 생성·종료된다.
   */
  @Entity
  @Table(name = "member_term")
  public class MemberTerm {

      @Id
      private String id;

      @ManyToOne(fetch = FetchType.LAZY, optional = false)
      @JoinColumn(name = "member_id", nullable = false)
      private Member member;

      @Enumerated(EnumType.STRING)
      @Column(nullable = false)
      private MemberDepartment department;

      @Enumerated(EnumType.STRING)
      @Column(nullable = false)
      private MemberTitle title;

      @Column(nullable = false)
      private int startGen;

      private Integer endGen;   // null = 현직

      protected MemberTerm() { }

      static MemberTerm start(Member m, MemberDepartment d, MemberTitle t, int startGen) {
          MemberTerm term = new MemberTerm();
          term.id = UUID.randomUUID().toString();
          term.member = m;
          term.department = d;
          term.title = t;
          term.startGen = startGen;
          return term;
      }

      public MemberDepartment getDepartment() { return department; }

      public MemberTitle getTitle() { return title; }

      public int getStartGen() { return startGen; }

      public Integer getEndGen() { return endGen; }

      public boolean isCurrent() { return endGen == null; }

      public String label() { return title.label(department); }

      void end(int gen) { this.endGen = gen; }
  }
  ```

- [x] `Member.java` 에서 다음 필드와 메서드를 지운다.
  - 필드 `private MemberTitle title;` 과 그 `@Enumerated` 애너테이션
  - 필드 `private MemberDepartment department;` 와 그 `@Enumerated` 애너테이션
  - 메서드 `setTitle(MemberTitle)`, `setDepartment(MemberDepartment)`

- [x] `Member.java` 에 `terms` 필드를 더한다 (`gen` 필드 근처, `categories` 아래).
  ```java
  @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true,
             fetch = FetchType.EAGER)
  @OrderBy("startGen ASC")
  private List<MemberTerm> terms = new ArrayList<>();
  ```
  필요한 import: `jakarta.persistence.CascadeType`, `jakarta.persistence.OneToMany`, `jakarta.persistence.OrderBy`, `java.util.ArrayList`, `java.util.Comparator`, `java.util.List`, `java.util.Optional`.

- [x] `Member.java` 의 기존 `getTitle`/`getDepartment` 자리에 파생 구현과 임기 API 를 넣는다.
  ```java
  public List<MemberTerm> getTerms() { return Collections.unmodifiableList(terms); }

  /** 진행 중인 임기. 불변식상 최대 하나다. */
  public Optional<MemberTerm> currentTerm() {
      return terms.stream().filter(MemberTerm::isCurrent).findFirst();
  }

  /** 종료된 임기 중 startGen 이 가장 큰 것. 없으면 empty. */
  public Optional<MemberTerm> lastEndedTerm() {
      return terms.stream().filter(t -> !t.isCurrent())
              .max(Comparator.comparingInt(MemberTerm::getStartGen));
  }

  public MemberTitle getTitle() {
      return currentTerm().map(MemberTerm::getTitle).orElse(null);
  }

  public MemberDepartment getDepartment() {
      return currentTerm().map(MemberTerm::getDepartment).orElse(null);
  }

  /** 같은 (부서, 직책)이면 아무것도 하지 않는다 — 저장할 때마다 길이 0 임기가 쌓이지 않도록. */
  public void assignTerm(MemberDepartment d, MemberTitle t, int currentGen) {
      Optional<MemberTerm> cur = currentTerm();
      if (cur.isPresent() && cur.get().getDepartment() == d && cur.get().getTitle() == t) return;
      cur.ifPresent(term -> term.end(currentGen));
      terms.add(MemberTerm.start(this, d, t, currentGen));
  }

  public void endCurrentTerm(int currentGen) {
      currentTerm().ifPresent(t -> t.end(currentGen));
  }
  ```
  `getAuthority()` 는 그대로 둔다 — `getTitle()` 이 이제 파생값이므로 자동으로 옳다. 주석만 다음으로 갱신한다:
  ```java
  // 권한은 저장하지 않는다 — 진행 중인 임기가 있으면 임원. 부원(STAFF)도 임원 권한을 갖는다.
  ```

- [x] `src/test/java/com/jaram/be/member/MemberTermTest.java` 를 만든다. (순수 단위 테스트, 스프링 컨텍스트 불필요)
  ```java
  package com.jaram.be.member;

  import org.junit.jupiter.api.Test;

  import static org.assertj.core.api.Assertions.assertThat;

  class MemberTermTest {

      private Member member() {
          return Member.newPending("김자람", "2026123456", "a@jaram.net", "hash");
      }

      @Test
      void assigningFirstTermMakesItCurrent() {
          Member m = member();
          m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);

          assertThat(m.getTitle()).isEqualTo(MemberTitle.LEAD);
          assertThat(m.getDepartment()).isEqualTo(MemberDepartment.ACADEMIC);
          assertThat(m.currentTerm()).isPresent();
          assertThat(m.lastEndedTerm()).isEmpty();
      }

      @Test
      void reassigningSameRoleIsNoOp() {
          Member m = member();
          m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
          m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 43);

          assertThat(m.getTerms()).hasSize(1);
          assertThat(m.currentTerm().orElseThrow().getStartGen()).isEqualTo(42);
      }

      @Test
      void assigningNewRoleEndsThePreviousTerm() {
          Member m = member();
          m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.STAFF, 41);
          m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);

          assertThat(m.getTerms()).hasSize(2);
          assertThat(m.getTitle()).isEqualTo(MemberTitle.LEAD);
          MemberTerm past = m.lastEndedTerm().orElseThrow();
          assertThat(past.getTitle()).isEqualTo(MemberTitle.STAFF);
          assertThat(past.getStartGen()).isEqualTo(41);
          assertThat(past.getEndGen()).isEqualTo(42);
      }

      @Test
      void endingCurrentTermClearsDerivedTitleButKeepsHistory() {
          Member m = member();
          m.assignTerm(MemberDepartment.PR, MemberTitle.LEAD, 42);
          m.endCurrentTerm(42);

          assertThat(m.getTitle()).isNull();
          assertThat(m.getDepartment()).isNull();
          assertThat(m.currentTerm()).isEmpty();
          assertThat(m.lastEndedTerm().orElseThrow().label()).isEqualTo("홍보부장");
      }

      @Test
      void endingWhenThereIsNoCurrentTermDoesNothing() {
          Member m = member();
          m.endCurrentTerm(42);

          assertThat(m.getTerms()).isEmpty();
      }
  }
  ```
  주: `label()` 기대값은 `MemberTitle.label(MemberDepartment)` 구현(`d.label() + "장"`)에서 온다. 실행 후 실제 라벨과 다르면 실제 값에 맞춘다.

- [x] `AdminBatchExecutor` 에 `AdminSettingsRepository` 를 주입한다. 생성자 파라미터 목록 끝에 `AdminSettingsRepository settings` 를 더하고 필드에 대입한다. `AdminSettings.SINGLETON_ID` 는 같은 패키지(`com.jaram.be.admin`)라 접근 가능하다. 그리고 헬퍼를 더한다:
  ```java
  /** 임기 전환 기준 기수. 운영이 설정한 현재 기수를 우선하고, 미설정(0)이면 올해 기준으로 계산한다. */
  private int currentGen() {
      Integer c = settings.findById(AdminSettings.SINGLETON_ID)
              .map(AdminSettings::getCurrentCohort).orElse(null);
      return (c != null && c > 0) ? c : Gen.current();
  }
  ```

- [x] `AdminBatchExecutor.updateMember` 를 고친다. `department`/`title` 은 이제 개별 setter 가 없으므로 **검증만** 하고, 적용은 조합 검사 뒤에 임기 전환으로 한 번에 한다.

  switch 의 두 줄을 바꾼다:
  ```java
  case "department" -> enumCheck(MemberDepartment.class, v, errors, k);
  case "title" -> enumCheck(MemberTitle.class, v, errors, k);
  ```

  새 헬퍼를 더한다:
  ```java
  /** department/title 은 임기로 함께 적용되므로 개별 setter 가 없다. 값 검증만 여기서 한다. */
  private <E extends Enum<E>> void enumCheck(Class<E> type, Object v,
                                             Map<String, String> errors, String key) {
      if (v == null) return;   // null = 해제, 허용
      try {
          Enum.valueOf(type, v.toString());
      } catch (IllegalArgumentException e) {
          errors.put(key, "허용되지 않은 값입니다.");
      }
  }
  ```

  기존 조합 검사 블록을, 검사에 쓰던 `d`/`t` 를 그대로 재사용해 임기 전환까지 하도록 넓힌다:
  ```java
  // 직책×부서 조합 검사. 한쪽만 요청에 담겨 오면 나머지는 엔티티의 현재 값을 기준으로 판정한다.
  if (errors.isEmpty() && (f.containsKey("department") || f.containsKey("title"))) {
      MemberDepartment d = f.containsKey("department")
              ? parsed(f.get("department"), MemberDepartment.class) : m.getDepartment();
      MemberTitle t = f.containsKey("title")
              ? parsed(f.get("title"), MemberTitle.class) : m.getTitle();
      String comboError = comboError(d, t);
      if (comboError != null) {
          errors.put("title", comboError);
      } else {
          actions.add(t == null ? () -> m.endCurrentTerm(currentGen())
                                : () -> m.assignTerm(d, t, currentGen()));
      }
  }
  ```
  `comboError` 와 `parsed` 는 그대로 둔다.

- [x] `MemberAuthorityTest` 의 `setTitle` 호출을 바꾼다.
  - `setTitle(MemberTitle.X)` → `assignTerm(<해당 부서>, MemberTitle.X, 42)`
  - `clearingTitleRevokesOfficer` 의 `setTitle(LEAD); setTitle(null);` → `assignTerm(ACADEMIC, LEAD, 42); endCurrentTerm(42);`
  - 부서는 `MemberTitle.allowedIn` 을 만족하게 고른다: PRESIDENT/VICE_PRESIDENT→`LEADERSHIP`, LEAD/STAFF→`ACADEMIC`, SERVER_ADMIN→`INFRA`.

- [x] `AdminMemberAssignmentTest` 를 고친다.
  - `allowsClearingTitleAndDepartment`: 시드를 `m.assignTerm(MemberDepartment.PR, MemberTitle.STAFF, 42);` 로 바꾼다. 패치 본문(둘 다 null)과 기대(200, 이후 `getTitle()`/`getDepartment()` 가 null)는 그대로.
  - `judgesAgainstTheStoredValueWhenOnlyOneSideIsSent`: 새 모델에서는 "부서만 있고 직책은 없는" 상태가 구조적으로 불가능하므로 다시 쓴다. 시드를 `m.assignTerm(MemberDepartment.INFRA, MemberTitle.SERVER_ADMIN, 42);` 로 하고,
    - `{"title": "LEAD"}` 만 보내면 → 400, `errors.title` 에 "인프라에는 서버 관리자만 지정할 수 있습니다."
    - `{"title": "SERVER_ADMIN"}` 만 보내면 → 200 (저장된 부서 INFRA 와 조합이 맞음)
  - 다른 시드에서 `setDepartment`/`setTitle` 을 쓰는 곳이 있으면 모두 `assignTerm(...)` 으로 바꾼다.

- [x] `PeopleTest` 헬퍼 `active(...)` 의 `m.setDepartment(department); m.setTitle(title);` 를 바꾼다:
  ```java
  if (title != null) m.assignTerm(department, title, 42);
  ```
  (탭 판정 자체는 Task 3 에서 다시 쓴다. 여기서는 컴파일만 통과시킨다.)

- [x] `PeopleContractTest` 의 시드 `setDepartment(...)+setTitle(...)` 쌍을 `assignTerm(<부서>, <직책>, 42)` 로 바꾼다. 기대 `role` 값("회장", "학술부장")은 그대로다.

- [x] 검증:
  ```bash
  cd /home/ksb/Dev/home-jaram/home-jaram-be && ./gradlew \
    --init-script /tmp/claude-1000/-home-ksb-Dev-home-jaram/32bd1627-6e3c-47e9-bc04-d581d8553359/scratchpad/init.gradle \
    test --tests 'com.jaram.be.member.*' \
         --tests 'com.jaram.be.admin.AdminMemberAssignmentTest' \
         --tests 'com.jaram.be.people.PeopleTest' \
         --tests 'com.jaram.be.contract.PeopleContractTest'
  ```
  기대: `BUILD SUCCESSFUL`.

- [x] `grep -rn 'setTitle\|setDepartment' src/` 가 빈 결과인지 확인한다.

---

## Task 3 — `contributor` 도입, `MemberCategory` 삭제, 졸업 규칙

### Files

- 수정: `src/main/java/com/jaram/be/member/Member.java`
- 삭제: `src/main/java/com/jaram/be/member/MemberCategory.java`
- 수정: `src/main/java/com/jaram/be/people/PeopleService.java`
- 수정: `src/main/java/com/jaram/be/admin/AdminResourceService.java`
- 수정: `src/main/java/com/jaram/be/admin/AdminDashboardService.java`
- 수정: `src/main/java/com/jaram/be/admin/AdminBatchExecutor.java`
- 수정: `src/test/java/com/jaram/be/people/PeopleTest.java`
- 수정: `src/test/java/com/jaram/be/contract/PeopleContractTest.java`
- 수정: `src/test/java/com/jaram/be/admin/AdminDashboardTest.java`
- 수정: `src/test/java/com/jaram/be/admin/AdminResourceTest.java`

### Interfaces

```java
// Member
public boolean isContributor();
public void setContributor(boolean contributor);
// 삭제: getCategories(), hasCategory(MemberCategory), award(...), revoke(...), categories 필드
```

### Steps

- [x] `Member.java` 에서 `categories` 필드(+`@ElementCollection`/`@CollectionTable`/`@Column`/`@Enumerated`)와 `getCategories()`, `hasCategory()`, `award()`, `revoke()` 를 지운다. `newPending(...)` 안의 `m.categories = new LinkedHashSet<>(Set.of(MemberCategory.regular));` 줄도 지운다. 남는 `LinkedHashSet`/`Set`/`Collections` import 중 다른 곳에서 쓰이지 않는 것을 정리한다 (`Collections` 는 `getTerms()` 에서 계속 쓴다).

- [x] `Member.java` 에 새 필드 하나와 접근자를 더한다. 졸업 기수는 저장하지 않는다 — 기수는 입학 시점으로 고정이고 `Gen.ofStudentId` 로 파생한다.
  ```java
  @Column(nullable = false)
  @ColumnDefault("false")
  private boolean contributor = false;
  ```
  import: `org.hibernate.annotations.ColumnDefault`.

  **`@ColumnDefault` 는 필수다.** 이게 없으면 Hibernate 가 `ALTER TABLE member ADD COLUMN contributor boolean not null` 을 DEFAULT 없이 발행하고, PostgreSQL 은 기존 행이 하나라도 있으면 `column "contributor" contains null values` 로 거부한다 — 즉 회원이 있는 DB 에서는 앱이 뜨지 않는다. 붙이면 `... not null default false` 가 되어 기존 행이 모두 false 로 채워진다.
  ```java
  public boolean isContributor() { return contributor; }

  public void setContributor(boolean contributor) { this.contributor = contributor; }
  ```

- [x] `PeopleService.getPeople()` 의 탭 구성을 바꾸고, `byCategory` 헬퍼를 지운다.
  ```java
  return new PeopleResponse(
          execTab(active.stream().filter(m -> m.currentTerm().isPresent()).toList()),
          flatTab("자람에 힘을 더해주신 분들입니다.", "등록된 기여자가 없습니다.",
                  active.stream().filter(Member::isContributor).toList()),
          flatTab("자람을 거쳐 나아간 선배들입니다.", "등록된 졸업자가 없습니다.",
                  active.stream().filter(m -> m.getGrade() == MemberGrade.OB).toList()));
  ```

- [x] `PeopleService.execTab` 에서 부서 null 분기를 없앤다 — exec 탭에 든 회원은 모두 진행 중인 임기가 있으므로 부서가 항상 있다.
  ```java
  byDept.forEach((dept, cards) -> groups.add(new PeopleGroup(dept.label(), cards)));
  ```

- [x] `AdminResourceService.memberRow` 에서 `r.put("categories", ...)` 줄을 지운다. (계약에도 FE 소스에도 `categories` 키가 없음을 grep 으로 확인했다.)

- [x] `AdminResourceService.matchesMemberTab` 의 분기를 바꾼다.
  ```java
  return switch (tab) {
      case "exec" -> m.currentTerm().isPresent();
      case "contrib" -> m.isContributor();
      case "graduate" -> m.getGrade() == MemberGrade.OB;
      default -> true;
  };
  ```

- [x] `AdminDashboardService` 의 `alumniCount` 를 바꾼다.
  ```java
  int alumniCount = (int) approved.stream().filter(m -> m.getGrade() == MemberGrade.OB).count();
  ```

- [x] `AdminBatchExecutor` 에서 `grade` 적용이 임기 종료까지 함께 다루도록 바꾼다.
  ```java
  case "grade" -> enumField(MemberGrade.class, v, errors, k, g -> applyGrade(m, g), actions, false);
  ```
  ```java
  /** OB 로 전환하면 현직 임원 자격이 끝난다. 임기 이력은 남는다("전 학술부장"). */
  private void applyGrade(Member m, MemberGrade g) {
      m.setGrade(g);
      if (g == MemberGrade.OB) m.endCurrentTerm(currentGen());
  }
  ```

- [x] `AdminBatchExecutor` 에 졸업 규칙 검증을 더한다. **직책×부서 조합 검사 블록보다 앞에** 놓는다 — 여기서 거른 요청이 임기 부여 액션까지 가면 안 된다.
  ```java
  // 졸업 규칙. 신입부원은 바로 OB 가 될 수 없고, 현직 임원과 OB 는 공존하지 않는다.
  MemberGrade newGrade = f.containsKey("grade") ? parsed(f.get("grade"), MemberGrade.class) : null;
  if (errors.isEmpty() && newGrade == MemberGrade.OB) {
      if (m.getGrade() == MemberGrade.NEWCOMER) {
          errors.put("grade", "신입부원은 바로 OB로 변경할 수 없습니다. 준회원 또는 정회원을 거쳐 주세요.");
      } else if (f.get("title") != null) {
          errors.put("grade", "OB로 변경하면서 직책을 함께 지정할 수 없습니다.");
      }
  }
  // 이미 OB 인 회원에게는 새 임기를 부여하지 않는다. 등급을 먼저 되돌려야 한다.
  if (errors.isEmpty() && f.get("title") != null
          && m.getGrade() == MemberGrade.OB && newGrade == null) {
      errors.put("title", "OB 회원에게는 직책을 지정할 수 없습니다. 등급을 먼저 변경해 주세요.");
  }
  ```
  주: `parsed` 는 이미 있는 헬퍼다. `f.get("title") != null` 은 "직책을 부여하려는 요청"을 뜻한다 — 키가 없거나 값이 null(해제)이면 둘 다 통과해야 한다.

- [x] `src/test/java/com/jaram/be/admin/AdminMemberGraduationTest.java` 를 만든다. `AdminMemberAssignmentTest` 의 구조(REST-assured + 시드)를 그대로 따른다. 케이스:
  - 준회원이 `{"grade": "OB"}` → 200, 이후 `getGrade() == OB`
  - 신입부원이 `{"grade": "OB"}` → 400, `errors.grade` 에 "신입부원은 바로 OB로 변경할 수 없습니다."로 시작하는 문구
  - 진행 중인 임기가 있는 정회원이 `{"grade": "OB"}` → 200, 이후 `currentTerm()` 이 empty, `getTerms()` 는 1건이고 그 `endGen` 이 `currentGen`
  - `{"grade": "OB", "title": "LEAD"}` → 400, `errors.grade`
  - grade 가 OB 인 회원에게 `{"title": "LEAD"}` → 400, `errors.title`

- [x] `rm src/main/java/com/jaram/be/member/MemberCategory.java` 후 `grep -rn 'MemberCategory' src/` 로 남은 참조가 없는지 확인한다.

- [x] `PeopleTest` 를 새 판정 기준으로 다시 쓴다. 헬퍼에서 `MemberCategory` 파라미터를 없애고, 대신 탭별로 상태를 직접 만든다.
  - exec: `m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42)`
  - contrib: `m.setContributor(true)`
  - grad: `m.setGrade(MemberGrade.OB)`
  - `memberWithMultipleAwardsAppearsInEachAwardedTab` → 임기 + `setContributor(true)` 를 동시에 준 회원이 exec 와 contrib 양쪽에 나오는지로 바꾼다.
  - `regularMemberAppearsInNoTab` → 임기 없음 + contributor false + grade REGULAR 인 회원이 어느 탭에도 없는지로 바꾼다.
  - `emptyDatabaseReturnsEmptyGroups` 는 그대로.

- [x] `PeopleContractTest` 에 `award(exec)` 호출이 남아 있으면 지운다 (임기가 exec 판정을 대신한다).

- [x] `AdminDashboardTest` 헬퍼에서 `MemberCategory cat` 파라미터와 `if (cat != null) m.award(cat);` 을 없앤다. `approved("c", "2023000003", MemberGrade.OB, 38)` 이 `alumniCount == 1` 을 그대로 만족하는지 확인한다 — `grade == OB` 시드가 이미 있으므로 만족한다.

- [x] `AdminResourceTest` 에서 헬퍼의 `MemberCategory cat` 파라미터를 없애고, exec 시드를 `m.assignTerm(...)` 으로 바꾼다. `listFiltersMembersByTabAndQuery` 의 `.body("items[0].categories", hasItem("exec"))` 단언을 지운다 (해당 키가 응답에서 사라졌다). 탭 필터 자체(`tab=exec` 로 그 회원만 나오는지)는 남긴다. 쓰이지 않게 된 `hasItem` import 를 정리한다.

- [x] 검증:
  ```bash
  cd /home/ksb/Dev/home-jaram/home-jaram-be && ./gradlew \
    --init-script /tmp/claude-1000/-home-ksb-Dev-home-jaram/32bd1627-6e3c-47e9-bc04-d581d8553359/scratchpad/init.gradle \
    test --tests 'com.jaram.be.people.*' \
         --tests 'com.jaram.be.admin.*' \
         --tests 'com.jaram.be.member.*' \
         --tests 'com.jaram.be.contract.PeopleContractTest'
  ```
  기대: `BUILD SUCCESSFUL`.

---

## Task 4 — `role` 4단 폴백 + 표시 기수

### Files

- 수정: `src/main/java/com/jaram/be/people/PeopleService.java`
- 수정: `src/test/java/com/jaram/be/people/PeopleTest.java`

### Steps

- [x] `PeopleService.roleLabel` 을 4단 폴백으로 바꾼다.
  ```java
  private String roleLabel(Member m) {
      Optional<MemberTerm> cur = m.currentTerm();
      if (cur.isPresent()) return cur.get().label();
      Optional<MemberTerm> past = m.lastEndedTerm();
      if (past.isPresent()) return "전 " + past.get().label();
      if (m.getGrade() != null) return m.getGrade().label();
      return "";
  }
  ```

- [x] `PeopleService` 에 표시 기수 헬퍼를 더한다.
  ```java
  // 재학 중에는 가입 기수, 졸업(OB) 후에는 입학 기수로 부르는 관례. 저장값은 그대로 두고 표시만 바꾼다.
  private Integer displayGen(Member m) {
      if (m.getGrade() != MemberGrade.OB) return m.getGen();
      Integer enrolled = Gen.ofStudentId(m.getStudentId());
      return enrolled != null ? enrolled : m.getGen();
  }
  ```

- [x] `PeopleService.toCard` 가 이 헬퍼를 쓰게 한다.
  ```java
  private PersonMember toCard(Member m) {
      Integer gen = displayGen(m);
      return new PersonMember(m.getName(), roleLabel(m),
              gen == null ? null : gen + "기",
              m.getBio(), m.getGithubUrl(), m.getBlogUrl());
  }
  ```

- [x] `PeopleTest` 에 두 테스트를 더한다.
  ```java
  @Test
  void pastOfficerKeepsRoleWithFormerPrefix() {
      Member m = Member.newPending("박선배", "2021000001", "senior@jaram.net", "hash");
      m.setApproval(MemberApproval.APPROVED);
      m.setStatus(MemberStatus.ACTIVE);
      m.setGrade(MemberGrade.OB);
      m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
      m.endCurrentTerm(41);
      members.save(m);

      PeopleResponse res = service.getPeople();

      PersonMember card = res.grad().groups().get(0).members().get(0);
      assertThat(card.role()).isEqualTo("전 학술부장");
  }

  @Test
  void graduateGenComesFromStudentIdPrefix() {
      Member m = Member.newPending("최선배", "2021000002", "senior2@jaram.net", "hash");
      m.setApproval(MemberApproval.APPROVED);
      m.setStatus(MemberStatus.ACTIVE);
      m.setGrade(MemberGrade.OB);
      m.setGen(40);
      members.save(m);

      PeopleResponse res = service.getPeople();

      PersonMember card = res.grad().groups().get(0).members().get(0);
      assertThat(card.gen()).isEqualTo("37기");   // 2021 - 1984
  }
  ```
  레코드 정의 확인됨: `PeopleResponse(PeopleTab exec, PeopleTab contrib, PeopleTab grad)`,
  `PeopleTab(String desc, String empty, List<PeopleGroup> groups)`,
  `PeopleGroup(String heading, List<PersonMember> members)`,
  `PersonMember(name, role, gen, bio, githubUrl, blogUrl)` — `gen` 은 `"41기"` 형태의 문자열이다.

- [x] 검증:
  ```bash
  cd /home/ksb/Dev/home-jaram/home-jaram-be && ./gradlew \
    --init-script /tmp/claude-1000/-home-ksb-Dev-home-jaram/32bd1627-6e3c-47e9-bc04-d581d8553359/scratchpad/init.gradle \
    test --tests 'com.jaram.be.people.*' --tests 'com.jaram.be.contract.PeopleContractTest'
  ```
  기대: `BUILD SUCCESSFUL`.

---

## Task 5 — 계약 정리 + 마이그레이션 런북

와이어는 바뀌지 않는다. 계약 파일에서 할 일은 고아 스키마 제거와 문서 보강뿐이다.

### Files

- 수정: `/home/ksb/Dev/home-jaram/home-jaram-fe/docs/api/openapi.yaml`
- 수정: `docs/superpowers/plans/2026-07-20-member-term-model.md` (이 문서의 런북 절)

### Steps

- [x] `home-jaram-fe/docs/api/openapi.yaml` 에서 `MemberCategory` 스키마(`enum: [exec, contrib, grad]`, 약 1051행)를 지운다. 어디에서도 `$ref` 되지 않는 고아다. `grep -n 'MemberCategory' /home/ksb/Dev/home-jaram/home-jaram-fe/docs/api/openapi.yaml` 이 빈 결과인지 확인한다.

- [x] 같은 파일의 `PersonMember.gen` 에 표시 규칙을 `description` 으로 적는다.
  ```yaml
  gen:
    type: string
    nullable: true
    description: 표시 기수. 재학 중에는 가입 기수, OB 는 학번에서 파생한 입학 기수를 쓴다. 예 "42기".
  ```
  (기존 필드 정의의 다른 키는 건드리지 않는다.)

- [x] BE 쪽 두 openapi.yaml 이 여전히 심볼릭 링크인지 확인한다.
  ```bash
  ls -l /home/ksb/Dev/home-jaram/home-jaram-be/docs/api/openapi.yaml \
        /home/ksb/Dev/home-jaram/home-jaram-be/src/test/resources/openapi/openapi.yaml
  ```
  기대: 둘 다 `-> ...home-jaram-fe/docs/api/openapi.yaml`.

- [x] 전체 테스트를 돌린다.
  ```bash
  cd /home/ksb/Dev/home-jaram/home-jaram-be && ./gradlew \
    --init-script /tmp/claude-1000/-home-ksb-Dev-home-jaram/32bd1627-6e3c-47e9-bc04-d581d8553359/scratchpad/init.gradle \
    test
  ```
  기대: 알려진 기존 실패 4건(`AdminContractTest` 3건, `SeminarContractTest` 1건) 외에는 모두 통과. **새 실패가 하나라도 있으면 태스크는 끝난 게 아니다.**

- [x] FE 검증:
  ```bash
  cd /home/ksb/Dev/home-jaram/home-jaram-fe && npm run lint && npm run build
  ```
  기대: 둘 다 성공.

### 배포 런북 (사람이 손으로 실행)

`ddl-auto: update` 라 Hibernate 는 컬럼을 지우지 않는다. 앱을 먼저 띄워 `member_term` 테이블과 `member.contributor` 컬럼이 생긴 **뒤에** 아래를 실행한다.

앱을 띄운 직후, 스키마가 실제로 생겼는지 먼저 확인한다. `contributor` 가 없다면 `@ColumnDefault` 누락으로 DDL 이 실패한 것이므로(Hibernate 는 이 실패를 로그만 남기고 기동을 계속한다) 아래 SQL 을 손으로 돌리고 앱을 재기동한다.

```sql
-- 둘 다 나와야 한다
SELECT column_name FROM information_schema.columns
 WHERE table_name = 'member' AND column_name = 'contributor';
SELECT to_regclass('member_term');
```

```sql
-- 위가 비어 있을 때만
ALTER TABLE member ADD COLUMN contributor boolean NOT NULL DEFAULT false;
```

선행 조건: 이전 작업(P8, `2026-07-20-member-axis-dedup-design.md` §5)의 마이그레이션 SQL 이 아직 어느 DB 에도 실행되지 않았다. **그것을 먼저 실행한 뒤** 아래를 돌린다.

`<currentCohort>` 는 `admin_settings.current_cohort` 값으로 치환한다. 0 이거나 없으면 42(2026년)를 쓴다.

```sql
-- 사전 점검: 0 이어야 한다 (2026-07-21 확인 결과 0)
SELECT count(*) FROM member WHERE title IS NOT NULL AND department IS NULL;

INSERT INTO member_term (id, member_id, department, title, start_gen, end_gen)
SELECT gen_random_uuid()::text, id, department, title, <currentCohort>, NULL
  FROM member WHERE title IS NOT NULL AND department IS NOT NULL;

UPDATE member SET contributor = true
 WHERE id IN (SELECT member_id FROM member_category WHERE category = 'contrib');

UPDATE member SET grade = 'OB'
 WHERE id IN (SELECT member_id FROM member_category WHERE category = 'grad');

ALTER TABLE member DROP COLUMN title, DROP COLUMN department;
DROP TABLE member_category;
```

마지막 UPDATE 로 `grade = 'OB'` 가 된 회원 중 진행 중인 임기를 가진 사람이 있으면 불변식(현직 임원 + OB 공존 금지)을 깬다. 마이그레이션 뒤 다음이 0 인지 확인하고, 아니면 손으로 임기를 종료시킨다.

```sql
SELECT count(*) FROM member m
  JOIN member_term t ON t.member_id = m.id
 WHERE m.grade = 'OB' AND t.end_gen IS NULL;
```

---

## Task 6 — FE: 졸업생 선택지 + 저장 전 확인

BE 는 OB 전환 시 임기를 말없이 종료시킨다. 운영자가 그 사실을 모르고 저장하는 일이 없도록 확인을 FE 에서 받는다.

조사 결과 두 가지가 걸린다:

1. `admin.data.js` 의 `member` 스키마 등급 선택지가 `['수습회원','준회원','정회원']` 뿐이라 **UI 에서 OB 를 고를 방법이 아예 없다.** `GRADE_LABEL` 에는 `OB: '졸업생'` 이 있는데 컬럼 옵션에서 빠져 있다.
2. `admin.api.js` 의 `LIVE_RESOURCES` 는 `seminars`/`seminarApprovals`/`applications` 뿐이라 **인원 관리 탭은 아직 mock 이다.** 이 태스크의 변경은 mock 위에서 동작을 확인하는 데까지다.

### Files

- 수정: `src/features/admin/admin.data.js`
- 수정: `src/features/admin/views/table/TableView.jsx`

### Steps

- [x] `admin.data.js` 의 `member` 스키마에 졸업생을 더한다.
  - 필터(약 59행): `options: ['전체', '수습회원', '준회원', '정회원', '졸업생']`
  - 등급 컬럼(약 67행): `options: ['수습회원', '준회원', '정회원', '졸업생']`
  - `desc` 는 "수습·준·정회원 명단입니다."로 시작하는데, 졸업생이 목록에 들어오므로 "회원 명단입니다."로 고친다.

- [x] `TableView.jsx` 의 `onSave` 에서, 등급을 졸업생으로 바꾸는 편집이 있고 그 행에 `title` 이 있으면 확인을 받는다. 행 원본은 이미 `origById` 로 들고 있고, `memberRow` 응답에 `title` 이 들어 있다(FE 가 화면에 그리지는 않는다).
  ```javascript
  const onSave = () => {
    if (!slice || dcount === 0) return;
    const updates = Object.entries(slice.edits).map(([id, fields]) => ({ id, fields, version: origById[id]?.updatedAt }));

    // 졸업생 전환은 진행 중인 임원 임기를 종료시킨다. 되돌릴 수 없으므로 저장 전에 확인한다.
    const ending = updates.filter((u) => u.fields.grade === '졸업생' && origById[u.id]?.title);
    if (ending.length && !window.confirm(MESSAGES.confirmGraduate(ending.length))) return;

    const creates = slice.creates.map(({ id, _new, ...fields }) => ({ tempId: id, fields }));
    const deletes = Object.keys(slice.deletes);
    save.mutate({ updates, creates, deletes });
  };
  ```
  주: `slice.edits` 의 값은 화면 라벨(`'졸업생'`)이고 와이어 변환(`GRADE_LABEL` 역매핑)은 `admin.api.js` 에서 일어난다. 실제 저장 형태를 먼저 확인하고, 라벨이 아니라 `'OB'` 로 들고 있다면 비교값을 바꾼다.

- [x] 확인 문구를 `MESSAGES` 에 더한다. 이모지 없이 존댓말.
  ```javascript
  confirmGraduate: (n) => `졸업생으로 변경하는 회원 ${n}명의 진행 중인 임원 임기가 종료됩니다. 임기 이력은 남으며 임원진 명단에서는 빠집니다. 계속할까요?`,
  ```
  `MESSAGES` 가 정의된 파일은 `grep -rn "savePartialFail" src/` 로 찾는다.

- [x] 검증:
  ```bash
  cd /home/ksb/Dev/home-jaram/home-jaram-fe && npm run lint && npm run build
  ```
  기대: 둘 다 성공.

- [x] `window.confirm` 이 이 코드베이스의 관례와 맞는지 확인한다. → **맞지 않았다.** `views/forms/ConfirmDialog.jsx` 가 이미 있고 `AdminShell` 의 이탈 가드가 쓰고 있어, `window.confirm` 대신 그 컴포넌트를 썼다. 확인 대기 상태는 `graduating` state 로 들고 있다가 확인 시 `save.mutate` 한다. `grep -rn "window.confirm\|Modal\|Dialog" src/features/admin` 으로 이미 쓰는 확인 UI 가 있으면 그것을 쓴다. 디자인 시스템 규칙상 새 컴포넌트를 만든다면 `src/design-system` 의 토큰만 쓴다.

---

## 완료 기준

- [x] `grep -rn 'MemberCategory' src/` → 빈 결과
- [x] `grep -rn 'setTitle\|setDepartment' src/` → `Member` 관련 호출 0건 (남는 `setTitle` 은 `Seminar`/`Study` 것으로 무관)
- [x] `grep -rn 'FOUNDING_YEAR' src/main` → `Gen.java` 한 줄
- [x] `grep -n 'MemberCategory' ../home-jaram-fe/docs/api/openapi.yaml` → 빈 결과
- [x] `grep -rn 'graduatedGen\|graduated_gen' src/` → 빈 결과 (도입하지 않기로 함)
- [x] BE 전체 테스트: 알려진 4건 외 전부 통과
- [x] admin 에서 졸업생을 고를 수 있고, 임기가 있는 회원이면 저장 전 확인이 뜬다
- [x] FE `npm run lint && npm run build` 통과
- [x] 배포 런북이 이 문서에 기록됨
