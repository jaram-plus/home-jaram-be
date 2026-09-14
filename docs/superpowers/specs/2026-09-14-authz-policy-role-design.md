# 권한 설계 — Policy와 Role

작성일 2026-09-14. 대상 브랜치 `worktree-design-authz-policy-role`.

## 1. 문제

권한이 `MEMBER`/`OFFICER` 2단계뿐이고, 규칙이 `SecurityConfig`의 URL 매처에만 있다.

- `Member.getAuthority()` 는 `currentTerm().isPresent()` 하나로 권한을 정한다. 임기가 있으면
  부원(STAFF)도 회장과 똑같이 `/api/admin/**` 전체를 쓴다.
- 권한 규칙이 경로 문자열이라, 엔드포인트를 추가할 때 매처를 빠뜨리면
  `anyRequest().authenticated()` 로 흘러 **조용히 열린다**. 실패 방향이 위험한 쪽이다.
- 리소스 조건("본인 세미나만 수정")을 표현할 자리가 없어 서비스 안에 손으로 박혀 있다
  (`SeminarService:71`, `SeminarService:140`).

추가로 설계 중 발견한 현재 결함 두 가지:

- **탈퇴·미승인 회원이 admin 을 계속 쓴다.** `getAuthority()` 는 `approval` 과 `status` 를
  보지 않는다. `MemberActivityGuard.requireRegistered()` 는 세미나 출석·스터디 개설/신청·
  일정 슬롯 2곳, 총 5곳에서만 호출되고 **admin 엔드포인트에는 한 곳도 없다**. 탈퇴 처리된
  현직 임원은 손에 든 토큰으로 ttl(12시간) 동안 회원 승인·설정 변경·드라이브 export 를
  계속 할 수 있다.
- **권한 변경이 즉시 반영되지 않는다.** 권한이 JWT 클레임에 실려 있어 임기를 거둬도
  토큰이 만료될 때까지 유효하다.
- **비밀번호를 바꿔도 기존 토큰이 살아 있다.** `AuthService.resetConfirm()` 은
  `setPasswordHash()` 만 하고 발급된 토큰을 무효화하지 않는다(`AuthService.java:104`).
  토큰을 탈취당해 비밀번호를 재설정해도 공격자는 ttl(12시간) 동안 계속 접근한다.
  비밀번호 재설정의 절반이 작동하지 않는 셈이다.

## 2. 원칙

Discord 권한 시스템과 AWS IAM 을 조사해 자람의 제약(단일 조직, 고정 직제, 소수 리소스)에
맞춰 좁힌 것이다.

| # | 원칙 | 출처 |
|---|---|---|
| P1 | 권한은 지위가 아니라 **행위**로 이름 붙인다 | 둘 다 (`MANAGE_ROLES`, `s3:GetObject`) |
| P2 | 주체와 권한 사이에 반드시 **Role** 을 둔다. `Member` 에 권한을 저장하지 않는다 | 둘 다 |
| P3 | 역할은 **가산(OR)** 만 한다. 역할 레벨 거부는 없다 | Discord |
| P4 | **자격은 권한의 상한이다(교집합).** 탈퇴·미승인은 어떤 Role 도 넘지 못한다 | AWS permissions boundary |
| P5 | 리소스 조건은 Permission 이 아니라 **조건**으로 쓴다 | AWS Condition |
| P6 | 부여 권한은 **위계**로 제한한다. 자기보다 낮은 직책만 임명할 수 있다 | Discord role position |
| P7 | **탈출 해치는 정확히 하나다.** `PRESIDENT` 만 와일드카드 | ADMINISTRATOR / root |

의도적으로 채택하지 않은 것:

- AWS 의 explicit-deny 우선 + SCP + session policy 다층 구조 — 단일 조직에 과잉.
- Discord 의 6단계 채널 overwrite 순서 — 순서 기반 해결은 사람이 못 읽는다.
  ("왜 안 보이지"가 디스코드 최대 지원 문의다.)
- 정책의 DB 저장 — P7 과 코드 리뷰 가능성을 잃는다. 자람은 비엔지니어가 런타임에
  권한 매트릭스를 바꿀 상황이 없다.

## 3. 평가 모델 — 3층

```
요청
 │
 ├─[1층] 자격 게이트 (교집합 / 상한, P4)      ← DB, Role 무시
 │       approval != APPROVED   → 거부
 │       status == WITHDRAWN    → 거부
 │       status == REREGISTER   → 신청류만 거부
 │
 ├─[2층] 권한 (가산 OR, P2/P3)                ← 현직 임기 → Role → Policy
 │       MEMBER(기본) | 현직 임기에서 파생한 Role
 │       PRESIDENT 이면 전체 허용 (P7)
 │
 └─[3층] 조건 (P5)                            ← 도메인 policy 빈
         본인 소유 / 기간 중 / 위계 rank 비교 (P6)
```

1층은 Discord 에 없고 AWS boundary 에서 가져왔다. 2층이 Discord, 3층이 AWS Condition 이다.

## 4. Role 출처 — 임기에서 파생. 새 테이블 없음

`member_term` 의 `(department, title)` 을 Role 로 매핑한다. Role 도 Permission 도 저장하지
않는다.

```
member (기존, 변경 없음)
  id PK / approval ENUM / status ENUM / ...
     │        └──────────────┐ 1층 입력
     │ 1                     │
     │ N                     │
member_term (기존, 변경 없음)
  id PK / member_id FK / department ENUM / title ENUM
  start_gen INT / end_gen INT NULL      ← 현직 = end_gen IS NULL
     ╎
     ╎ derive (코드, 저장하지 않음)
     ▼
  Role (+rank) ──[Policy: 코드 선언 매트릭스]──▶ Set<Permission>
```

### 이 선택의 근거와 대가

디스코드도 AWS 도 역할 부여를 **명시적으로 저장**한다. 둘은 남의 조직 모델을 모르는
멀티테넌트 플랫폼이라 파생할 근거 자체가 없다. 자람은 자기 직제를 알고 있고,
`MemberTitle.allowedIn()` 이 유효 조합을 이미 9개로 닫아놨다.

파생의 대가는 셋이며, 전부 알고 받아들인다.

1. **역사 수정이 곧 권한 변경이다.** `endTerm(currentGen)` 은 이력 쓰기와 권한 회수가
   같은 write 다.
2. **시간 해상도가 기수 단위다.** 날짜 단위로 주거나 거둘 수 없다.
3. **세밀한 회수가 불가능하다.** 권한만 일부 뺏으려면 임기를 끝내야 하고 그건 1번이다.

대가 1·2 는 §3 의 1층 자격 게이트와 §7 의 JWT 변경이 완화한다 — 긴급 차단은 `status` 로
즉시 되고, 임기 변경도 즉시 반영된다.
대가 3 은 자람 규모에서 감수한다.

### 파생이 무너지는 신호 — 하나라도 생기면 즉시 구체화한다

- 임기 **없는** 사람에게 권한이 필요해질 때 (졸업생 멘토, 외부 서버 관리자, 휴학 중 인수인계자)
- **같은 직책인데 사람마다** 권한이 달라야 할 때
- 권한을 **날짜 단위**로 주거나 거둬야 할 때

그때 추가할 테이블 (지금은 만들지 않는다):

```
member_role_grant
  id PK / member_id FK / role / granted_by FK / granted_at
  expires_at NULL / reason
```

전환 비용이 싸다는 것이 파생을 택한 핵심 근거다. 실측:

| 항목 | 비용 |
|---|---|
| 스키마 | 0 — `ddl-auto: update` 라 Hibernate 가 기동 시 생성 |
| 백필 | `INSERT ... SELECT FROM member_term WHERE end_gen IS NULL` 한 페이지. 파생이 곧 현재 진실이라 무손실 |
| 코드 | `RoleResolver.rolesOf()` 메서드 하나 |
| **API 계약** | **변경 없음.** `roles[]`/`permissions[]` 는 출처를 노출하지 않는다 |

선례: `docs/migrations/2026-08-02-contributor-backfill.sql` (1.3KB, 멱등, 배포 후 아무 때나).
피해야 할 쪽은 `docs/migrations/2026-07-20-member-refactor.sql` (7.2KB, 실행 순서 강제,
트래픽 열기 전 실행) — 잘못된 모양을 되돌리는 마이그레이션이었다.

이음매는 `RoleResolver.rolesOf(Member)` **메서드 하나**다. 전략 인터페이스는 만들지 않는다.
구현체 하나짜리 추상화이고, 메서드 하나가 이미 충분한 이음매다.

## 5. Permission — 20개

현재 컨트롤러 표면에서 도출했다.

| 도메인 | Permission | 대응 엔드포인트 |
|---|---|---|
| 회원 | `MEMBER_READ` | `GET /api/admin/members/pending`, `/{id}` |
| | `MEMBER_APPROVE` | `POST .../approve`, `/reject`, `/reregister` |
| | `MEMBER_EDIT` | `PUT .../graduation`, `PATCH /api/admin/members:batch` |
| | `TERM_ASSIGN` | 임기 부여·종료. **P6 위계 규칙 적용 대상** |
| 세미나 | `SEMINAR_CREATE` | `POST /api/seminars` |
| | `SEMINAR_APPROVE` | `GET /api/admin/seminars/pending`, `POST .../approve`, `/reject` |
| | `SEMINAR_ATTENDANCE_MANAGE` | `POST .../attendance-code`, `/close-attendance` |
| | `SEMINAR_ROSTER_READ` | `GET /{id}/roster`, `GET /api/admin/seminars/{id}/attendees` |
| | `SEMINAR_ROSTER_EDIT` | `POST`/`DELETE .../attendees` |
| | `SEMINAR_EDIT` | `PATCH /api/admin/seminars:batch` |
| 스터디 | `STUDY_APPROVE` | `GET /api/studies/pending`, `POST /{id}/approve`, `/reject` |
| | `STUDY_APPLICANT_MANAGE` | `GET /applicants`, `POST /applicants/{id}/approve`, `/reject` |
| | `STUDY_EDIT` | `PATCH /api/admin/studies:batch` |
| 일정 | `SCHEDULE_MANAGE` | `/api/admin/schedules` 전부 |
| 설정 | `SETTINGS_READ` | `GET /api/admin/settings` |
| | `SETTINGS_EDIT` | `PATCH /api/admin/settings` |
| | `SETTINGS_ROLLOVER` | 기수 전환. 되돌리기 어려워 분리 |
| | `SITE_LINKS_EDIT` | 푸터 외부 링크 수정. 홍보부용으로 `SETTINGS_EDIT` 에서 분리 |
| 기타 | `DASHBOARD_READ` | `GET /api/admin/dashboard/stats` |
| | `EXPORT_RUN` | `POST /api/admin/export/google-drive`. 개인정보 반출, 최소 권한 |

## 6. Role 매트릭스

`MemberTitle.allowedIn()` 이 유효 조합을 9개로 닫아놨다. Role 은 그 9개 + `@everyone` 상당의
`MEMBER` 뿐이다.

| Role | rank | 파생 조건 | 권한 |
|---|---|---|---|
| `PRESIDENT` | 100 | LEADERSHIP + PRESIDENT | **전부** (P7 탈출 해치) |
| `VICE_PRESIDENT` | 90 | LEADERSHIP + VICE_PRESIDENT | `SETTINGS_ROLLOVER`·`EXPORT_RUN` 외 전부 |
| `SERVER_ADMIN` | 80 | INFRA + SERVER_ADMIN | `SETTINGS_READ`, `SETTINGS_EDIT`, `EXPORT_RUN`, `DASHBOARD_READ`, `MEMBER_READ` |
| `ACADEMIC_LEAD` | 60 | ACADEMIC + LEAD | 세미나 6종 전부, 스터디 3종 전부, `SCHEDULE_MANAGE`, `DASHBOARD_READ` |
| `ACADEMIC_STAFF` | 40 | ACADEMIC + STAFF | `SEMINAR_CREATE`, `SEMINAR_ATTENDANCE_MANAGE`, `SEMINAR_ROSTER_READ`, `SEMINAR_ROSTER_EDIT`, `STUDY_APPLICANT_MANAGE`, `DASHBOARD_READ` |
| `PR_LEAD` | 60 | PR + LEAD | `SETTINGS_READ`, `SITE_LINKS_EDIT`, `SEMINAR_CREATE`, `DASHBOARD_READ` |
| `PR_STAFF` | 40 | PR + STAFF | `SETTINGS_READ`, `SITE_LINKS_EDIT`, `SEMINAR_CREATE`, `DASHBOARD_READ` |
| `FINANCE_LEAD` | 60 | FINANCE + LEAD | `MEMBER_READ`, `MEMBER_APPROVE`, `DASHBOARD_READ` |
| `FINANCE_STAFF` | 40 | FINANCE + STAFF | `MEMBER_READ`, `DASHBOARD_READ` |
| `MEMBER` | 0 | 임기 없음 | 없음 |

`MEMBER` 가 비어 있는 것은 의도다. 세미나 조회·출석, 스터디 신청, 일정 슬롯 예약은 권한이
아니라 **인증 + 1층 자격**으로 통과한다.

결정 사항:

- **부원(STAFF) 권한 축소에 동의함.** 지금은 부원도 회원 승인·설정·export 를 전부 쓸 수
  있으나, 이 표에서는 잃는다. 조용한 파괴적 변경이므로 배포 공지가 필요하다.
- **모든 STAFF 에 `DASHBOARD_READ` 를 준다.**
- **FINANCE Role 은 회계 전용 권한 없이 둔다.** 앱에 회계 기능이 없다. 회원 관련 권한만
  현재 수준으로 유지한다. 회계 기능이 생기면 그때 `FINANCE_*` Permission 을 추가한다.
- **`SITE_LINKS_EDIT` 를 분리한다.** 홍보부가 `SETTINGS_EDIT` 전체를 갖지 않고 푸터 링크만
  고칠 수 있다.

### P6 위계 규칙

`TERM_ASSIGN` 을 가진 사람도 **자기 최고 rank 미만의 Role 만** 부여·종료할 수 있다.

- 부회장(90)은 서버 관리자(80)·부장(60)·부원(40)을 임명할 수 있다.
- 부회장은 **회장(100)을 임명하거나 회장의 임기를 끝낼 수 없다** — rank 가 자기 이상이다.
- 회장(100)은 전부 가능하다.
- 누구도 **자기 자신의 임기는 수정할 수 없다** — rank 가 같기 때문이며, 별도 규칙이 아니라
  같은 규칙의 결과다.

회장이 한 명뿐인데 임기가 끝나면 아무도 새 회장을 임명할 수 없다. 이 경우는 기수 전환
(`SETTINGS_ROLLOVER`)이 처리하며, 그 권한 역시 회장만 갖는다. 회장 계정을 잃으면 DB 를 직접
고쳐야 한다 — P7 의 탈출 해치가 하나뿐인 데서 오는 알려진 대가다.

## 7. JWT — 신원만 싣는다

현재 JWT 는 `authority` 클레임을 싣는다. 이를 없애고 **`memberId` 만** 싣는다. Role 과
Permission 과 자격은 전부 요청 시점에 DB 에서 읽는다.

근거: 1층 자격 게이트가 **어차피 매 요청 `members.findById()` 를 한다.** 그리고
`Member.terms` 는 이미 `FetchType.EAGER` 라 임기가 같이 딸려 온다. Role 파생 비용이 0이다.

| | role 을 JWT 에 싣기 | **JWT 는 신원만** |
|---|---|---|
| 정책 변경 반영 | 즉시 | 즉시 |
| 임기 변경 반영 | 최대 12시간 | **즉시** |
| 탈퇴·승인취소 반영 | 최대 12시간 | **즉시** |
| DB 조회 | 요청당 1회 | 요청당 1회 (동일) |

§1 의 권한 반영 지연이 사라진다. 무상태성을 포기하지만, 자람 규모에서 요청당 조회 1회는
의미 있는 비용이 아니다. 부하가 문제가 되면 그때 짧은 TTL 의 인메모리 캐시를 둔다.

### 이것이 막지 못하는 것 — 토큰 탈취

**JWT 에서 role 을 빼는 것은 토큰 탈취에 대한 방어가 전혀 아니다.** 탈취된 토큰은
`memberId` 를 담은 bearer 자격증명이고, 서버는 그 id 로 회원을 로드해 임기 → Role →
Permission 을 전개한다. 공격자는 **피해자의 권한을 그대로 얻는다.** role 이 토큰 안에
있든 없든 결과가 같다.

오히려 한 가지는 미세하게 나빠진다. 탈취 후 피해자가 승진하면 신원-only 토큰은 **새 권한을
즉시 따라간다.** role 을 박아 둔 토큰이라면 만료까지 옛 권한에 얼어 있었을 것이다.

두 위협을 구분해야 한다.

| 위협 | 성격 | JWT 신원-only 가 해결하나 |
|---|---|---|
| 권한을 잃은 **정당한 사용자**가 계속 쓴다 (탈퇴·임기 종료·승인 취소) | 인가 신선도 | **예** |
| **도둑**이 훔친 토큰으로 쓴다 | 자격증명 탈취 | **아니오** |

### 세션 무효화 — `credentialsInvalidatedAt`

위 표의 두 번째 줄에 대한 최소 대응. 이 설계 덕에 거의 공짜다.

- `Member.credentialsInvalidatedAt` (Instant, nullable) 컬럼 하나. `ddl-auto: update` 가 만든다.
- JWT 는 **이미 `iat` 를 싣는다** (`JwtProvider.generate()` 의 `.issuedAt(...)`). 토큰 포맷을
  바꾸지 않는다.
- 필터에서 `iat < credentialsInvalidatedAt` 이면 거부한다. 어차피 회원을 로드하므로
  **추가 조회가 없다.** denylist 테이블도 필요 없다.
- 세우는 시점: 비밀번호 변경(§1 의 세 번째 결함), 탈퇴, 관리자의 강제 로그아웃.

이것으로 "즉시 회수"가 탈취 토큰을 포함해 **진짜로** 참이 된다. 단 **탈취를 알았을 때만**
동작한다 — 대응 수단이지 예방이 아니다.

### refresh token — 이번 범위 밖

refresh token 이 best practice 인 것은 맞으나 전제가 붙는다. **access token TTL 을 실제로
짧게(5~15분) 줄일 때만** 의미가 있다. 현재 12시간을 그대로 두고 refresh token 만 더하면
훔칠 자격증명이 하나 늘 뿐 얻는 것이 없다.

탈취 예방의 레버는 셋이고, 우선순위가 분명하다.

1. **FE 가 토큰을 어디에 두는가.** `localStorage` 면 XSS 한 번에 털리고, httpOnly 쿠키면
   스크립트가 읽지 못한다(대신 CSRF 를 SameSite 로 막는다). **가장 큰 레버이며 BE 설계가
   아니라 FE 결정이다.** 이것부터 정해야 한다.
2. **access TTL 단축.** 12시간 → 15분이면 노출 창이 48분의 1이 된다. **여기서 refresh token
   이 필요해지며, 이것이 refresh token 의 진짜이자 유일한 논거다.**
3. 탈취 탐지(IP/UA 변화) — 이 규모에 과하다.

따라서 순서는 1 → 2 이고, refresh token 은 2 에 딸려 온다. 1 을 정하지 않은 채 2 를 하면
가장 큰 구멍을 열어 둔 채 작은 구멍을 메우는 셈이다.

도입한다면 올바른 모양은 아래와 같다. 이 설계와 독립적인 별도 작업으로 진행한다 — 둘을
한 번에 바꾸면 회귀 원인을 추적할 수 없다.

- access 15분 + refresh 14일, refresh 는 httpOnly / Secure / SameSite 쿠키
- 회전(rotation) — 갱신할 때마다 새 refresh 발급, 이전 것 무효화
- 재사용 탐지(reuse detection) — 무효화된 refresh 가 다시 오면 해당 계열 전체 폐기
- **access TTL 을 반드시 함께 줄인다.** 줄이지 않으면 순손실이다

## 8. 클래스 구조

```
com.jaram.be.security.authz/          (새 패키지)
├── Permission.java       enum 20개. 행위 단위 (P1)
├── Role.java             enum 10개. rank + label. 권한은 들고 있지 않음
├── Policy.java           Role → Set<Permission> 매트릭스 한 곳 (P2)
│                         + canAssign(actorRoles, targetRole) 위계 (P6)
├── RoleResolver.java     Member → Set<Role>. 현직 임기에서 파생.
│                         나중에 grant 테이블이 생겨도 여기만 바뀐다
└── Eligibility.java      1층 자격 게이트 (P4).
                          기존 MemberActivityGuard 를 흡수·확장

com.jaram.be.security/                (기존, 수정)
├── JwtProvider.java      authority 클레임 제거, memberId 만. iat 는 이미 싣고 있다
├── JwtAuthFilter.java    memberId → Member 로드 → iat vs credentialsInvalidatedAt →
│                         Eligibility → RoleResolver → Policy 로 전개 →
│                         Permission 단위 GrantedAuthority 부여
├── CurrentMember.java    authority → Set<Role> roles, Set<Permission> permissions
└── SecurityConfig.java   @EnableMethodSecurity 활성화.
                          URL 매처는 public 경로 + anyRequest().authenticated() 로 축소

com.jaram.be.member/                  (기존, 수정)
└── Member.java           credentialsInvalidatedAt (Instant, nullable) 추가.
                          비밀번호 변경·탈퇴·강제 로그아웃이 세운다

com.jaram.be.auth/                    (기존, 수정)
└── AuthService.java      resetConfirm() 이 credentialsInvalidatedAt 을 세운다
                          (§1 세 번째 결함)

각 도메인 (3층 조건, P5)
├── seminar/SeminarAccessPolicy.java    isOwner(seminarId, memberId)
├── study/StudyAccessPolicy.java
└── schedule/ScheduleAccessPolicy.java
```

### Role 과 Policy 를 나눈 이유

Discord 처럼 Role 이 자기 권한 집합을 직접 들게 해도 된다. 나눈 이유는 **바뀌는 빈도가
다르기 때문**이다. Role 목록은 직제가 바뀔 때만 바뀌고(거의 없음), 매트릭스는 기능이 늘
때마다 바뀐다. `Policy.java` 한 파일만 열면 전체 권한 표가 한 화면에 보이고, PR 리뷰에서
"누가 무엇을 얻고 잃는지"가 diff 로 드러난다.

### 사용하는 모습

```java
// 2층만
@PostMapping("/{id}/approve")
@PreAuthorize("hasAuthority('SEMINAR_APPROVE')")
public SeminarResponse approve(@PathVariable String id) { ... }

// 2층 + 3층 조건 (P5)
@PatchMapping("/{id}")
@PreAuthorize("hasAuthority('SEMINAR_EDIT') or @seminarAccessPolicy.isOwner(#id, authentication)")
public SeminarResponse resubmit(...) { ... }

// P6 위계 — 서비스 안에서
policy.requireCanAssign(actor, MemberDepartment.ACADEMIC, MemberTitle.LEAD);
```

### 실패 방향이 뒤집힌다

지금은 `SecurityConfig` 매처를 빠뜨리면 **조용히 열린다**. 이후에는 `@PreAuthorize` 를
빠뜨리면 `anyRequest().authenticated()` 에 걸려 **막히는 쪽으로** 실패한다.

## 9. 계약과 이행

계약을 깨지 않는다.

- `MeProfile.authority`(MEMBER/OFFICER)와 `UserSummary` 는 **그대로 둔다.** 파생 규칙도
  현재와 같다 — 현직 임기가 있으면 OFFICER.
- `permissions: string[]` 과 `roles: string[]` 을 **추가**한다. FE 가 버튼 노출 판단을
  permission 기준으로 옮긴다.
- FE 이행이 끝난 뒤 별도 배포에서 `authority` 를 제거한다.

`openapi.yaml` 은 두 경로 모두 FE 저장소로 심볼릭 링크돼 있어 자동 동기화가 되지 않는다.
계약 변경은 FE 와 함께 움직여야 한다.

## 10. 테스트

- `Policy` 매트릭스 — Role 10개 × Permission 20개 전 칸을 표로 고정하는 테스트 하나.
  매트릭스가 바뀌면 이 테스트가 먼저 깨진다.
- `RoleResolver` — 9개 유효 조합 + 임기 없음 + 종료된 임기만 있음.
- `Eligibility` — approval × status 조합. 특히 **탈퇴한 현직 임원이 admin 에서 막히는지**
  (§1 의 결함에 대한 회귀 테스트).
- P6 위계 — 부회장이 회장을 임명하지 못하는지, 자기 임기를 못 고치는지.
- 세션 무효화 — 비밀번호를 바꾼 뒤 **이전 토큰이 거부되는지** (§1 의 세 번째 결함에 대한
  회귀 테스트), `credentialsInvalidatedAt` 이후 발급된 토큰은 통과하는지.
- 도메인 조건 — 본인 세미나 수정 허용 / 남의 것 거부.
- 엔드포인트 커버리지 — 모든 `/api/admin/**` 핸들러에 `@PreAuthorize` 가 붙어 있는지
  리플렉션으로 확인하는 테스트. 빠뜨림을 컴파일이 아니라 테스트가 잡는다.

## 11. 범위 밖

- refresh token — §7 의 레버 1(FE 토큰 저장 위치)을 정한 뒤 access TTL 단축과 함께 진행한다
- FE 의 토큰 저장 위치 변경 (localStorage → httpOnly 쿠키) — FE 결정이며 BE 는 CORS 와
  쿠키 설정으로 따라간다
- 권한 감사 로그 — 필요해지면 별도
- `member_role_grant` 테이블 — §4 의 신호가 나타나면
- 회계 기능과 `FINANCE_*` Permission
