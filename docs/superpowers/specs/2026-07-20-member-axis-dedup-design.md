# 회원 데이터 축 중복 제거 설계 (2026-07-20)

BE `home-jaram-be` · FE `home-jaram-fe` 동시 변경. 계약(`docs/api/openapi.yaml`) 변경 포함.

## 배경

`Member` 엔티티에 의미가 겹치는 축이 두 쌍 남아 있다.

1. **직책 축** — `MemberTitle`이 부서명과 직위를 한 값에 담아(`ACADEMIC_LEAD`, `PR_MEMBER` …) 이미 저장돼 있는 `MemberDepartment`와 정보가 겹친다. 9개 값은 실제로는 부서 5개 × 직위 2개의 조합일 뿐이다. 동시에 `Authority`(`MEMBER`/`OFFICER`)와 직책이 별개 컬럼으로 저장돼 두 값이 어긋날 수 있다.
2. **활동 축** — `enrolled`(Boolean)와 `status`(`MemberStatus`)가 같은 사실을 두 번 기록한다. `enrolled=false` ⟺ `status=ON_LEAVE`.

또한 현재 `Authority`는 **어디서도 `OFFICER`로 설정되지 않는다**. `Member`에 setter가 없고 `AdminBatchExecutor`의 수정 가능 필드에도 없어, 모든 회원이 영구히 `MEMBER`다. `SeminarController.java:42`의 임원 분기는 실제 로그인 사용자에게 절대 참이 되지 않는다(테스트는 JWT를 직접 발급해 통과한다). 이 설계는 그 결함도 함께 해소한다.

## 결정 요약

| 항목 | 결정 |
| --- | --- |
| `MemberTitle` | 9개 → 5개 (`PRESIDENT`, `VICE_PRESIDENT`, `LEAD`, `STAFF`, `SERVER_ADMIN`) |
| 표시 라벨 | `department + title` 조합으로 파생 (`ACADEMIC`+`LEAD` → "학술부장") |
| `Authority` | 저장 컬럼 제거. `title != null ? OFFICER : MEMBER` 파생 |
| 부원(`STAFF`) 권한 | `OFFICER` |
| title×department 검증 | 서비스 계층(`AdminBatchExecutor`)에서 400 `fieldErrors` |
| `enrolled` | 컬럼 제거. `MemberStatus`가 단일 진실원 |

`MemberDepartment`, `MemberGrade`, `MemberStatus`, `MemberApproval`, `MemberCategory`는 변경하지 않는다.

## 1. 직책 축

### enum

```java
public enum MemberTitle {
    PRESIDENT, VICE_PRESIDENT,   // LEADERSHIP 전용
    LEAD, STAFF,                 // ACADEMIC / PR / FINANCE
    SERVER_ADMIN                 // INFRA
}
```

### 라벨 파생

라벨 계산은 `MemberTitle.label(MemberDepartment)` 한 곳에만 둔다. `PersonMember.role`과 프로필 화면이 이 결과를 쓴다.

| department | title | 표시 |
| --- | --- | --- |
| LEADERSHIP | PRESIDENT | 회장 |
| LEADERSHIP | VICE_PRESIDENT | 부회장 |
| ACADEMIC | LEAD / STAFF | 학술부장 / 학술부원 |
| PR | LEAD / STAFF | 홍보부장 / 홍보부원 |
| FINANCE | LEAD / STAFF | 회계부장 / 회계부원 |
| INFRA | SERVER_ADMIN | 서버 관리자 |
| null | null | (일반 회원 — `grade.label()` 폴백) |

`LEAD`/`STAFF`의 라벨은 `department.label() + "장" | "원"` 이다. 기존 부서 라벨이 이미 "학술부"·"홍보부"·"회계부"이므로 접미사만 붙이면 정확히 맞는다. 문자열을 자르거나 파싱하지 않으며 `MemberDepartment`는 손대지 않는다. `LEADERSHIP`("회장단")과 `INFRA`("인프라")는 조합 규칙상 `LEAD`/`STAFF`가 올 수 없으므로 이 접미사 규칙이 적용되지 않는다.

결과 문자열은 리팩터링 전과 동일하다. `PeopleService.java:85`의 동작은 바뀌지 않는다.

### Authority 파생

`Member.authority` 필드와 컬럼을 삭제하고 게터를 파생으로 바꾼다.

```java
public Authority getAuthority() {
    return title != null ? Authority.OFFICER : Authority.MEMBER;
}
```

`Authority` enum, JWT `authority` claim, `UserSummary.authority`, `MeProfile.authority`는 모두 그대로 유지된다. **응답 형태와 계약의 `Authority` 스키마는 변하지 않는다.** `Member.newPending`에서 `m.authority = ...` 한 줄이 사라진다.

부원(`STAFF`)도 `OFFICER`다. 임원진에 속한 사람은 직위와 무관하게 관리 화면 접근 권한을 갖는다.

### 정합성 검증

`AdminBatchExecutor.updateMember`가 `title`/`department`를 파싱한 뒤, 적용 직전에 조합을 한 번 검사한다. 기존 validate-all-then-apply 구조를 그대로 따른다. 한쪽만 요청에 담겨 온 경우 나머지는 엔티티의 현재 값을 기준으로 판정한다.

규칙:

- `LEADERSHIP` → `PRESIDENT` | `VICE_PRESIDENT`
- `ACADEMIC` | `PR` | `FINANCE` → `LEAD` | `STAFF`
- `INFRA` → `SERVER_ADMIN`
- `department == null` → `title == null`

위반 시 `fieldErrors.title`에 한국어 존댓말 메시지를 담아 반환한다. 예: `"회장단에는 회장 또는 부회장만 지정할 수 있습니다."`

`title`을 바꾸면 권한도 함께 바뀌므로, 이 검증이 곧 권한 부여 경로의 유일한 관문이 된다.

## 2. 활동 축

`enrolled` 컬럼을 삭제하고 `MemberStatus`를 단일 진실원으로 둔다. `ACTIVE`(재학) / `ON_LEAVE`(휴학) / `WITHDRAWN`(탈퇴) 세 상태로 충분하며, `enrolled`는 그중 둘을 중복 표현할 뿐이다. 반대 방향(`status` 삭제)은 `WITHDRAWN`을 담을 곳이 없어 불가능하다.

변경 지점:

- `Member.java` — `enrolled` 필드·게터·세터 제거
- `AuthService.java:60` — `m.setEnrolled(req.enrolled())` 삭제. 바로 아래 `setStatus(req.enrolled() ? ACTIVE : ON_LEAVE)`는 유지
- `AdminDashboardService.java:103` — `Boolean.TRUE.equals(m.getEnrolled())` → `m.getStatus() == MemberStatus.ACTIVE`

`SignupRequest.enrolled`(가입 입력)와 `DashboardStats.pendingBreakdown.enrolled`(집계 응답)는 계약에 그대로 남는다. 저장 컬럼만 사라지고 와이어는 변하지 않는다.

## 3. 계약 변경

`home-jaram-fe/docs/api/openapi.yaml`이 단일 진실원이다. BE의 `docs/api/openapi.yaml`은 그 파일을 가리키는 심볼릭 링크이므로 FE 쪽 파일만 고치면 된다. 다만 BE 계약 테스트는 클래스패스상의 **복사본** `src/main/resources/openapi/openapi.yaml`을 읽으므로, 계약을 고친 뒤 `./scripts/sync-openapi.sh`를 반드시 실행해야 한다.

변경은 `MemberTitle` 스키마 한 곳뿐이다.

```yaml
MemberTitle:
  type: [string, 'null']
  description: >
    직책. department와 조합해 표시 라벨을 만든다(ACADEMIC+LEAD → 학술부장).
    LEADERSHIP은 PRESIDENT|VICE_PRESIDENT, ACADEMIC|PR|FINANCE는 LEAD|STAFF,
    INFRA는 SERVER_ADMIN만 허용. null이면 일반 회원(authority=MEMBER).
  enum: [PRESIDENT, VICE_PRESIDENT, LEAD, STAFF, SERVER_ADMIN, null]
```

`Authority` 스키마는 그대로 두되, 파생값임을 description에 남긴다. 다른 스키마(`MeProfile`, `UserSummary`, `SignupRequest`, `MemberStatus`, `DashboardStats`)는 변경하지 않는다.

## 4. FE 변경

소비 지점은 두 파일이다.

**`src/shared/member/enums.js`**

- `TITLE_LABELS`를 5개 키로 축소. `LEAD`/`STAFF`는 부서 없이는 라벨이 정해지지 않으므로 단순 맵으로 표현할 수 없다. `DEPARTMENT_LABELS`에 접미사를 붙이는 함수로 바꾼다(BE와 동일한 규칙).
- `titleLabel(key)` → `titleLabel(title, department)`. 시그니처가 바뀐다.
- 현재 `TITLE_LABELS`에 남아 있는 `OB`/`REGULAR`/`ASSOCIATE`/`NEWCOMER`는 등급(`MemberGrade`) 값으로, 이전 리팩터링(R2)에서 분리됐는데 이 맵에 잔존한 것이다. 함께 제거한다.
- `TITLES` 배열 export는 현재 어디서도 쓰이지 않는다. 유지하되 5개 키를 반영한다.

**`src/features/profile/views/ProfileView.jsx:23`**

- `titleLabel(me[key])` → `titleLabel(me.title, me.department)`

`RequireAdmin.jsx`는 `user.authority`만 보므로 변경 없다. `admin.data.js`는 한글 라벨 기반 목업이라 `MemberTitle` 키를 직접 참조하지 않는다.

## 5. 데이터 마이그레이션

`ddl-auto: update`라 마이그레이션 도구가 없다. 컬럼 삭제(`authority`, `enrolled`)는 Hibernate가 무시하므로 미사용 컬럼으로 잔존하며 무해하다. 원한다면 배포 후 수동으로 `DROP COLUMN` 한다.

**enum 값 이름 변경은 기존 행을 깨뜨린다.** 배포 전 일회성 SQL이 필요하다.

```sql
UPDATE member SET title = 'LEAD'  WHERE title IN ('ACADEMIC_LEAD','PR_LEAD','FINANCE_LEAD');
UPDATE member SET title = 'STAFF' WHERE title IN ('ACADEMIC_MEMBER','PR_MEMBER','FINANCE_MEMBER');
UPDATE member SET department = 'INFRA' WHERE title = 'SERVER_ADMIN' AND department IS NULL;
```

세 번째 문장은 `SERVER_ADMIN`에 부서가 비어 있던 기존 행을 새 규칙(`INFRA`)에 맞춘다.

## 6. 검증

BE (계약 수정 후 `./scripts/sync-openapi.sh` 선행):

- `PeopleTest.java:47` — `MemberTitle.ACADEMIC_LEAD` → `MemberTitle.LEAD`. 기대 라벨 "학술부장"은 그대로여야 한다(라벨 파생이 옳다는 회귀 테스트가 된다).
- `PeopleContractTest.java:42` — `PRESIDENT`는 이름이 유지되므로 변경 없음. `department`를 `LEADERSHIP`으로 설정해야 라벨이 나온다.
- 신규: title×department 조합 검증 테스트(`AdminResourceTest` 또는 인접). 허용 조합 1건, 위반 1건(400 + `fieldErrors.title`).
- 신규: `title` 부여 시 `getAuthority()`가 `OFFICER`가 되고 제거 시 `MEMBER`로 돌아오는 단위 테스트.
- `./gradlew test` 전체 green.

FE (테스트 러너 없음):

- `npm run lint` · `npm run build` 통과
- 프로필 화면에서 직책이 있는 계정/없는 계정 각각 수동 확인

## 범위 밖

- `MemberGrade`의 `REGULAR`/`OB` 승격 경로와 `autoPromote` 배치 (백로그 §6-2에 남아 있음)
- `MemberCategory`(`exec`)와 `department != null`의 의미 중복 — 별개 축으로 볼 여지가 있어 이번에 건드리지 않는다
- `memberId` 등 FK 제약 부재 (백로그 §6-3)
