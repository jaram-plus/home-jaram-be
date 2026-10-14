# 임원 임기 모델 설계

**작성일:** 2026-07-20
**선행 문서:** `2026-07-20-member-axis-dedup-design.md`

## 1. 목표

회원의 임원 이력을 기수 단위 임기(term)로 저장하고, 지금 `Member`에 흩어져 있는
중복 축을 그 임기에서 파생시킨다.

직전 리팩터링에서 `authority`를 `title`에서 파생시켜 중복을 없앴다. 그런데 같은
모양의 중복이 두 개 더 남아 있다.

- `MemberCategory.exec`와 `title != null`이 둘 다 "임원인가"를 뜻한다.
- `MemberCategory.grad`와 `MemberGrade.OB`가 둘 다 "졸업자인가"를 뜻한다.

둘 다 서로를 참조하지 않아 어긋날 수 있다. `title=LEAD`인데 exec 미수여면 권한은
OFFICER인데 임원진 탭에 나오지 않고, 반대면 탭에는 뜨지만 권한은 MEMBER이고 카드
역할이 등급 라벨로 폴백된다.

동시에 "언제 임원이었는지"와 "언제 졸업했는지"를 화면에 보여주고 싶다는 요구가
있다. 이력을 담으려면 어차피 시간 축이 필요하고, 그 축을 들이는 김에 위 두 중복을
파생으로 정리한다.

## 2. 범위

이 문서는 **A(임기 모델 기반)** 만 다룬다. 전체는 셋으로 나뉘며 A가 선행이다.

| | 범위 | 계약 |
|---|---|---|
| **A (이 문서)** | `member_term` 신설, `title`/`department` 파생, `exec`/`grad` 파생, admin 자동 전환, 마이그레이션, 사람들 페이지 `role`에 과거 직책 반영 | 변경 없음 |
| B | `MeProfile.terms` + ProfileView 임기 목록 | `MemberTerm` 스키마 추가 |
| C | 기수별 역대 임원 아카이브 엔드포인트 + FE 페이지 | 새 경로·스키마 |

A만 배포해도 그 자체로 완결된 개선이다(중복 축 제거 + 졸업자 카드의 과거 직책 표시).
B와 C는 A 이후 서로 독립이며 각각 별도 스펙으로 브레인스토밍한다.

## 3. 도메인 모델

### 3.1 MemberTerm

한 행이 한 임기다. 1년 임기는 `start_gen == end_gen`, 연임은 범위, 현직은
`end_gen IS NULL`로 표현된다.

```
member_term
  id           varchar   PK (UUID 문자열, Member.id와 같은 방식)
  member_id    varchar   FK → member.id
  department   varchar   NOT NULL  (MemberDepartment enum name)
  title        varchar   NOT NULL  (MemberTitle enum name)
  start_gen    integer   NOT NULL
  end_gen      integer   NULL = 현직
```

`department`와 `title`이 NOT NULL인 이유: 임기는 "직책을 맡은 기간"이고, 직책 없는
임기란 존재하지 않는다. 직책을 내려놓는 것은 임기의 종료(`end_gen` 설정)이지 null
직책을 가진 임기가 아니다.

`Member`와의 관계는 `@OneToMany(mappedBy="member", cascade=ALL, orphanRemoval=true,
fetch=EAGER)`에 `@OrderBy("startGen ASC")`. EAGER인 이유는 `getTitle()`이 거의 모든
읽기 경로에서 호출되고, 현재도 `Member`는 항상 통째로 로드되기 때문이다(기존
`categories`도 EAGER였다).

### 3.2 Member 변경

| 제거 | 대체 |
|---|---|
| `title`, `department` 컬럼 | 현직 임기에서 파생하는 게터 |
| `categories` 필드 + `member_category` 테이블 + `MemberCategory` enum | `exec`→현직 임기 유무, `grad`→`grade == OB`, `contrib`→새 `contributor` boolean 컬럼 |
| `award()`, `revoke()`, `hasCategory()`, `getCategories()` | `setContributor()`, `isContributor()` |
| `setTitle()`, `setDepartment()` | `assignTerm()`, `endCurrentTerm()` |
| — | `graduated_gen` (Integer, nullable) 컬럼 추가 |

읽기 쪽 호출부는 시그니처가 그대로다. `getTitle()`·`getDepartment()`가 파생
게터가 되고, `getAuthority()`는 이미 `title` 기반이라 코드를 바꿀 필요가 없다.

```java
// 현직 임기 = end_gen 이 null 인 임기. 불변식상 최대 하나.
public Optional<MemberTerm> currentTerm();

// 종료된 임기 중 start_gen 이 가장 큰 것. 없으면 empty.
public Optional<MemberTerm> lastEndedTerm();

public MemberTitle getTitle();            // currentTerm 의 title, 없으면 null
public MemberDepartment getDepartment();  // currentTerm 의 department, 없으면 null
```

### 3.3 임기 전환

```java
/**
 * 직책 배정. 현직 임기가 같은 (department, title)이면 아무것도 하지 않는다.
 * 다르면 현직 임기를 currentGen 에서 종료하고 새 임기를 currentGen 에서 시작한다.
 */
public void assignTerm(MemberDepartment d, MemberTitle t, int currentGen);

/** 직책 해제. 현직 임기를 currentGen 에서 종료한다. 현직이 없으면 무시한다. */
public void endCurrentTerm(int currentGen);
```

같은 값일 때 no-op인 것이 중요하다. admin 일괄 편집은 바뀌지 않은 필드도 함께
보낼 수 있어서, no-op이 아니면 저장 버튼 한 번에 길이 0짜리 임기가 쌓인다.

같은 기수 안에서 직책이 두 번 바뀌면 `start_gen == end_gen`인 임기가 생긴다. 이는
"그 기수에 잠깐 맡았다"는 사실이므로 그대로 남긴다.

### 3.4 기수 파생

학번 앞 4자리가 대학 입학 연도다. 입학 기수는 컬럼 없이 여기서 계산한다.

```
입학 기수  = Integer.parseInt(studentId.substring(0, 4)) - FOUNDING_YEAR
표시 기수  = grade == OB ? 입학 기수 : gen
```

`gen`(가입 기수)은 저장된 채로 불변이다. 재학 중에는 가입 기수로 부르고 졸업하면
입학 기수로 부르는 것이 동아리 관례인데, 표시 규칙일 뿐이므로 저장값을 덮어쓰지
않고 파생한다. 그래야 가입 기수와 졸업 기수가 함께 남는다.

졸업 여부의 판정자는 `grade == OB` 하나다. `graduatedGen`은 "언제"만 담당하며
"졸업했는가"를 판정하는 데 쓰지 않는다. 두 개의 술어를 두면 이 문서가 없애려는
바로 그 종류의 중복이 생기고, 마이그레이션으로 `graduatedGen`이 비어 있는 기존
졸업자에게서 즉시 어긋난다.

`FOUNDING_YEAR = 1984`는 현재 `AdminMemberService`에 private 상수로 묶여 있다.
이제 `member` 패키지에서도 필요하므로 `com.jaram.be.member.Gen`으로 옮기고
`AdminMemberService`는 그것을 참조한다.

```java
public final class Gen {
    public static final int FOUNDING_YEAR = 1984;
    public static int current();                            // Year.now() - FOUNDING_YEAR
    public static Integer ofStudentId(String studentId);     // 파싱 실패 시 null
}
```

`ofStudentId`가 null을 돌려주는 경우(학번이 4자리 연도로 시작하지 않는 이상
데이터): 표시 기수는 `gen`으로 폴백한다. 졸업자라도 기수 없이 표시되는 것보다
가입 기수라도 보이는 편이 낫다.

## 4. 표시 규칙 (PeopleService)

| 탭 | 필터 |
|---|---|
| exec | 현직 임기 보유 (`currentTerm().isPresent()`) |
| contrib | `isContributor()` |
| grad | `getGrade() == MemberGrade.OB` |

exec 탭의 그룹 heading은 현직 임기의 `department` 라벨이다. 현직 임기는 항상
department가 NOT NULL이므로 heading이 null인 그룹이 더 이상 생기지 않는다.

`PersonMember.role`은 required이며 4단 폴백이다.

1. 현직 임기가 있으면 그 라벨 — `"학술부장"`
2. 없고 과거 임기가 있으면 `"전 "` + 가장 최근 임기 라벨 — `"전 학술부장"`
3. 없고 `grade`가 있으면 등급 라벨 — `"OB"`
4. 모두 없으면 `""`

2번이 이번에 추가되는 경로다. 계약의 `PersonMember.role` 설명은 이미
`직책 표시 텍스트 (예 회장·전 학술부장)`이라 원래 의도와 맞는다.

`PersonMember.gen`은 표시 기수(3.4)로 채운다.

### 4.1 파급 — STAFF의 임원진 노출

exec 탭이 `title != null` 기반이 되면서 `STAFF`(부원)도 임원진 페이지에 나온다.
직전 리팩터링에서 STAFF에게 OFFICER 권한을 준 결정과 일관되며, FE 시드 데이터
(`people.data.js`)에도 학술부원이 임원 탭에 들어 있어 원래 의도와 어긋나지 않는다.

## 5. admin 자동 전환

`AdminBatchExecutor.updateMember`가 받는 필드 이름은 그대로다. admin 화면과
계약의 편집 부분은 손대지 않는다. 적용 방식만 바뀐다.

| 필드 | 동작 |
|---|---|
| `department` + `title` | `assignTerm(d, t, currentGen)` |
| `title: null` | `endCurrentTerm(currentGen)` |
| `grade` → `OB` | `setGrade(OB)` + `graduatedGen = currentGen` |
| `grade` → `OB` 이외 | `setGrade(...)` + `graduatedGen = null` |

직책×부서 조합 검증(`comboError`)은 그대로 유지한다. 현재 값을 읽을 때 파생
게터를 쓰므로 코드 변경이 없다. 검증은 validate-all-then-apply 규약대로
`assignTerm` 호출 **전에** 끝난다.

`title`만 오고 `department`가 오지 않은 경우 현직 임기의 department가 기준이 되고,
현직 임기가 없으면 department가 null이라 기존 오류 메시지
`"직책을 지정하려면 부서를 함께 지정해 주세요."`가 그대로 나간다.

### 5.1 currentGen 조달

`AdminSettings.currentCohort`에서 가져온다. `AdminBatchExecutor`에
`AdminSettingsRepository`를 주입한다.

`currentCohort`의 기본값이 `0`이므로 미설정 상태에서 그대로 쓰면 0기 임기가 박힌다.
`currentCohort > 0`이면 그 값, 아니면 `Gen.current()`로 폴백한다.

## 6. 마이그레이션

`ddl-auto: update`는 테이블·컬럼 추가만 하고 드롭은 하지 않는다. 아래 SQL을
**배포 시 애플리케이션 기동 후** 수동 실행한다. 기동 후여야 Hibernate가
`member_term` 테이블과 `contributor`·`graduated_gen` 컬럼을 이미 만들어 둔 상태다.

`<currentCohort>`는 `admin_settings.current_cohort`의 실제 값으로 치환한다.

```sql
-- 1) 현재 직책을 현직 임기로 이관 (컬럼 드롭보다 먼저)
INSERT INTO member_term (id, member_id, department, title, start_gen, end_gen)
SELECT gen_random_uuid()::text, id, department, title, <currentCohort>, NULL
  FROM member
 WHERE title IS NOT NULL AND department IS NOT NULL;

-- 2) contrib → boolean 컬럼
UPDATE member SET contributor = true
 WHERE id IN (SELECT member_id FROM member_category WHERE category = 'contrib');

-- 3) grad → 등급 OB
UPDATE member SET grade = 'OB'
 WHERE id IN (SELECT member_id FROM member_category WHERE category = 'grad');

-- 4) 정리
ALTER TABLE member DROP COLUMN title, DROP COLUMN department;
DROP TABLE member_category;
```

1번에서 `department IS NULL`인 행은 건너뛴다. 임기는 department가 NOT NULL이라
담을 수 없다. 직전 리팩터링의 마이그레이션이 `SERVER_ADMIN`의 department를
`INFRA`로 채우므로 정상 데이터에는 해당 행이 없어야 하지만, 방어적으로 조건을 둔다.
누락된 행이 있으면 그 회원은 직책 없는 일반 회원이 되므로, 실행 전에
`SELECT count(*) FROM member WHERE title IS NOT NULL AND department IS NULL`로
확인한다.

기존 임원의 `start_gen`을 현재 기수로 채우는 것은 근사다. 과거 임기 정보는 DB에
존재한 적이 없어 복원할 수 없고, "지금 재직 중"이 유일하게 아는 사실이다.

`graduated_gen`은 이관하지 않는다. 기존 졸업자가 언제 졸업했는지 역시 기록이 없다.
null로 남고, 표시 기수는 가입 기수로 폴백된다.

## 7. 계약

와이어 계약은 변경되지 않는다. `MemberCategory` 스키마는 어떤 엔드포인트도
참조하지 않는 고아이므로 제거한다. `PersonMember.gen` 설명에 표시 기수 규칙을
한 줄 적는다.

`GET /api/admin/list?resource=members&tab=...`의 `tab` 값(`member`/`exec`/
`contrib`/`graduate`)도 문자열 그대로 유지되며, `AdminResourceService`의
`matchesMemberTab`이 새 파생 술어를 쓰도록만 바뀐다.

## 8. 오류 처리

새로 생기는 실패 경로는 없다. 임기 전환은 검증을 통과한 뒤에만 실행되고,
`assignTerm`은 예외를 던지지 않는다. 낙관적 잠금은 `Member.version`이 그대로
담당한다 — `member_term` 행 변경은 `Member`를 통해서만 일어나므로 별도 `@Version`이
필요 없다.

## 9. 테스트

**단위**

- `GenTest` — 학번에서 입학 기수 파싱, 비정상 학번의 null 반환, 표시 기수 전환
- `MemberTermTest` — 신규 배정, 같은 값 재배정 no-op, 부서 이동, 승진, 해임,
  같은 기수 내 재배정으로 `start_gen == end_gen` 임기 생성
- `MemberAuthorityTest` (기존 확장) — 현직 임기 유무에 따른 OFFICER/MEMBER

**서비스**

- `PeopleTest` (재작성) — 세 탭의 파생 필터, `role` 4단 폴백 특히 `"전 학술부장"`,
  표시 기수 전환
- `AdminMemberAssignmentTest` (확장) — 자동 임기 전환, `grade`→`OB` 시
  `graduatedGen` 기록, `OB` 해제 시 null 복원, 조합 검증이 파생 게터 기준으로 동작
- `AdminDashboardTest`·`AdminResourceTest` (수정) — `award()` 제거에 따른 셋업 변경

**계약**

기존 계약 테스트가 무변경으로 통과하는 것이 성공 기준이다. 단, 이번 작업 이전부터
실패하던 4건(`AdminContractTest`의 `currentCohort` 2건·`cohortBreakdown` 1건,
`SeminarContractTest`의 `capacity` 1건)은 범위 밖이며 그대로 남는다.

## 10. 결정 기록

- **시간 축은 기수** — 동아리가 기수 단위로 운영되고 `AdminSettings.currentCohort`가
  이미 존재한다. 날짜는 화면 표시("38기 학술부장")를 위해 다시 기수로 변환해야 한다.
- **admin은 기존 일괄 편집 그대로** — 임기 전용 CRUD 화면을 만들지 않는다. 직책을
  바꾸는 행위가 곧 임기 전환이므로 admin이 두 개념을 구분할 필요가 없다. 과거 임기를
  소급 입력할 수 없다는 한계는 감수한다.
- **`contrib`은 boolean 컬럼** — `exec`/`grad`가 파생으로 빠지면 `MemberCategory`에는
  값 하나만 남는다. 단일 boolean을 위해 `@ElementCollection` 테이블과 조인을 유지할
  이유가 없다.
- **입학 기수는 파생** — 학번 앞 4자리가 입학 연도라는 사실이 확정적이므로 컬럼을
  늘리지 않는다.
