# Seminar 승인 흐름 + Schedule 도메인 — 설계

**작성일:** 2026-07-19
**범위:** backend (`home-jaram-be`, `com.jaram.be`)
**계약:** `docs/api/openapi.yaml`(FE 소유 심링크)에 이미 반영됨 — 이 설계는 **계약을 코드로 구현**하는 작업. 계약 편집 없음(sync만).

## 목표

FE 세미나 탭 재설계의 후반부 — 회원이 임원이 연 **일정(Schedule)**의 **슬롯(slot)**을 선착순으로 잡고, 잠금 후 세미나를 제출하면 **승인 흐름(PENDING→APPROVED/REJECTED→재제출)**을 타는 구조 — 의 백엔드를 구현한다. 두 덩어리:

1. **Seminar 승인 흐름**: 단건 조회, 본인 재제출, 임원 승인/반려. Seminar에 `scheduleId`/`approvalStatus`/`rejectReason` 3필드 추가.
2. **Schedule 도메인 신규**: 일정 목록, 슬롯 자기등록/자진취소/세미나 제출, 임원 일정 생성/잠금/강제해제.

## 아키텍처

- **package-by-feature.** 기존 `seminar` 패키지 확장 + `schedule` 패키지 신규(`com.jaram.be.schedule`).
- 레이어: Controller(계약 operation 1:1) → Service(`@Transactional`, 상태전이·권한·파생, `ApiException`) → Repository(Spring Data JPA) → Entity(정적 팩토리, Lombok 없음).
- day/month/weekday/time·시각 표시(HH:mm)는 `SeminarService`와 동일하게 **Asia/Seoul 파생**(저장 안 함).
- 슬롯 응답의 `member`(id·name)·`seminarApprovalStatus`·`seminarRejectReason`은 저장하지 않고 Member/Seminar 조회로 **파생해 얹는다**(`findAllById` 배치 조회로 N+1 회피).

## 도메인 모델

### Seminar 확장 (엔티티 3필드 추가)

`Seminar.create(...)` 팩토리(호출처 18곳)는 **건드리지 않는다.** 기존 `description`이 setter로 추가된 선례를 따라, 새 필드도 setter/전이 메서드로 관리.

- `scheduleId: String?` — 슬롯 경로 생성 시 채움. 임원 직접생성은 `null`.
- `approvalStatus: ApprovalStatus` (`@Enumerated(STRING)`, 기본 `PENDING`) — **seminar 패키지에 자체 `ApprovalStatus` enum 신규**(값 `PENDING`/`APPROVED`/`REJECTED`). `study.ApprovalStatus`와 wire 값은 같지만 패키지 경계상 재사용하지 않는다.
- `rejectReason: String?` — `REJECTED`일 때만 채워짐.
- 전이 메서드(Study 미러):
  - `approve()` → `approvalStatus = APPROVED`, `rejectReason = null`
  - `reject(reason)` → `approvalStatus = REJECTED`, `rejectReason = reason`
  - `resubmit()` → `approvalStatus = PENDING`, `rejectReason = null` (필드 갱신은 서비스에서 setter로)
- **임원 직접 `POST /api/seminars` create → 즉시 `APPROVED`** (승인 흐름 밖). 슬롯 제출 경로만 `PENDING`으로 시작. `create(...)` 팩토리는 기본 `PENDING`을 두고, 임원 create 서비스에서 `approve()` 호출로 승격 — 슬롯 제출 서비스는 그대로 `PENDING` 유지.

### Schedule 도메인 신규 (`com.jaram.be.schedule`)

- `ScheduleStatus` enum: `OPEN`, `LOCKED` (임원 수동 토글로만 LOCKED, 역방향 없음).
- `Schedule` 엔티티:
  - `id`, `startsAt: Instant`, `place: String?`, `mode: String?`, `capacity: Integer`(기본 3), `status: ScheduleStatus`(기본 `OPEN`), `@Version`.
  - `slots: List<ScheduleSlot>` — `@OneToMany(cascade = ALL, orphanRemoval = true)`. **생성 시 `capacity`개의 빈 슬롯(index 0..capacity-1) 고정 생성.**
  - `lock()` → `status = LOCKED`.
- `ScheduleSlot` 엔티티:
  - `id`, `@ManyToOne Schedule`, `index: int`, `memberId: String?`, `seminarId: String?`.
  - `claim(memberId)`, `release()`(member·seminar 모두 null), `attachSeminar(seminarId)`.
- 리포지토리: `ScheduleRepository`(목록 `findAllByOrderByStartsAtAsc`), 슬롯은 Schedule 통해 접근.

## 엔드포인트

### Seminar

| operation | 권한 | 규칙 / 에러 |
|---|---|---|
| `GET /api/seminars` (기존) | public | **APPROVED만** 반환(변경점). 슬롯 제출로 생긴 PENDING/REJECTED 세미나는 공개 목록에 노출하지 않는다 — 본인 제출 현황은 Schedule 슬롯(`seminarApprovalStatus`)에서 확인. 단건 가시성 게이트와 일관. `attendedAt`/파생 필드는 기존 그대로. |
| `GET /api/seminars/{id}` | public | APPROVED는 누구나. PENDING/REJECTED는 **본인(`createdById == callerId`) 또는 officer**만, 그 외 **404**(존재 은닉). principal nullable. |
| `PATCH /api/seminars/{id}` | 회원(authenticated) | 본인 소유 && `REJECTED`만 허용. 미존재→404 / 비소유→**403 FORBIDDEN** / REJECTED아님→**409 CONFLICT**. 성공: 필드 갱신 후 `resubmit()`. **`attendanceCode`는 무시**(§6-4). **`scheduleId != null`이면 `startsAt/place/mode`는 요청 무시하고 유지**; scheduleId=null(임원 직접생성)이면 전 필드 요청값 반영. body는 `SeminarCreateRequest`. |
| `POST /api/admin/seminars/{id}/approve` | officer | `approve()`. **200 + Seminar**. 미존재→404. 멱등(이미 APPROVED도 200). |
| `POST /api/admin/seminars/{id}/reject` | officer | body `RejectRequest`(reason 필수). `reject(reason)`. **200 + Seminar**. reason 누락→422 / 미존재→404. |

### Schedule

| operation | 권한 | 규칙 / 에러 |
|---|---|---|
| `GET /api/schedules` | public | 슬롯 포함 목록, `startsAt` 오름차순. |
| `POST /api/schedules/{id}/slots/{index}/claim` | 회원 | `OPEN`만. 성공→slot.member=caller, 200 + Schedule. 미존재/범위밖→404 / **409 CONFLICT**(LOCKED · 슬롯 이미 점유 · caller가 같은 일정 다른 슬롯 이미 점유). |
| `DELETE /api/schedules/{id}/slots/{index}` | 회원 | `OPEN` && 본인 슬롯만. 성공→release, 200 + Schedule. 미존재→404 / **403 FORBIDDEN**(LOCKED 이후 · 비소유 · 빈 슬롯). |
| `POST /api/schedules/{id}/slots/{index}/seminar` | 회원 | `LOCKED` && 본인 슬롯 && `seminarId` 없음. Seminar 생성(**PENDING**, `scheduleId` 채움, **`startsAt/place/mode`는 Schedule 값** — 요청 바디 값 무시, `attendanceCode`도 무시). slot.seminarId 연결. **201 + Seminar**. 미존재→404 / 비소유→**403** / 미잠김·이미제출→**409 CONFLICT** / title 누락→422. |
| `POST /api/admin/schedules` | officer | body `ScheduleCreateRequest`(startsAt 필수). capacity개 빈 슬롯 생성. **201 + Schedule**. |
| `PATCH /api/admin/schedules/{id}/lock` | officer | `OPEN→LOCKED`. 200 + Schedule. 미존재→404. 멱등(이미 LOCKED도 200). |
| `DELETE /api/admin/schedules/{id}/slots/{index}` | officer | **강제 해제. 409 조건: `seminarId` 존재 AND 그 세미나 `approvalStatus != REJECTED`**(좁힌 게이트 — REJECTED/빈 슬롯은 통과). 통과 시 release. 200 + Schedule. 미존재→404 / **409 CONFLICT**. |

### 슬롯 응답 파생 필드

`ScheduleSlot` 응답(`index`, `member`, `seminarId`, `seminarApprovalStatus`, `seminarRejectReason`):
- `member`: `memberId`로 Member 조회 → `{id, name}`(`SlotMember`), 빈 슬롯이면 null.
- `seminarApprovalStatus`/`seminarRejectReason`: `seminarId`로 Seminar 조회 파생, 없으면 null. 슬롯 카드가 세미나 상세를 따로 조회하지 않도록 얹는다.

## 에러 모델

기존 `GlobalExceptionHandler` 봉투(`{code, message, fieldErrors}`) 그대로. 신규:
- **409 → code `"CONFLICT"`** (계약 `Conflict` 응답 예시가 `code: CONFLICT`). `new ApiException(HttpStatus.CONFLICT, "CONFLICT", "...")`.
- 403→`FORBIDDEN`, 404→`NOT_FOUND`, 422→`VALIDATION`(모두 기존).

## 보안 라우팅 (`SecurityConfig.filterChain` 추가)

- `GET /api/schedules` → `permitAll`
- `GET /api/seminars/*` → `permitAll` (단건 조회. 가시성은 서비스에서 판단. `/roster`·`/attendees`는 세그먼트가 더 길어 `*` 단일 세그먼트 매처와 겹치지 않음. `PATCH /api/seminars/{id}`는 GET 매처라 영향 없이 `authenticated()` 유지.)
- `POST/DELETE /api/schedules/**`(claim/cancel/submit), `PATCH /api/seminars/*`는 기본 `.anyRequest().authenticated()`로 커버.
- `/api/admin/**`는 기존 `hasAuthority("OFFICER")` 매처가 admin schedules/seminars 전부 커버 — 추가 불필요.

## 동시성

슬롯 선착순 claim은 `Schedule.@Version` 낙관적 잠금으로 최소 방어(경쟁 시 한쪽 `OptimisticLockException`→재시도/실패). 비관적 잠금·큐 등은 규모(일정당 슬롯 3개) 대비 과함 — YAGNI.

## 테스트 전략

- **Repository 테스트**(`@DataJpaTest` + `PostgresTest`): Schedule–slot cascade 저장/조회, 빈 슬롯 생성.
- **엔드포인트 테스트**(`@SpringBootTest` RANDOM_PORT + RestAssured): operation별 성공 + 각 에러 분기.
  - Seminar: 단건 가시성(APPROVED public / PENDING 비소유 404 / officer 조회), 재제출(성공·비소유 403·REJECTED아님 409·slot세미나 시간 유지), 승인/반려.
  - Schedule: 목록, claim(성공·LOCKED 409·중복 409·범위밖 404), cancel(성공·LOCKED 403·비소유 403), 세미나 제출(성공 PENDING·미잠김 409·이미제출 409·비소유 403), 임원 생성/잠금/강제해제(REJECTED 통과·PENDING 409).
- **Contract 테스트**: `SeminarContractTest`에 get/patch/approve/reject 추가; 신규 `ScheduleContractTest`(전 operation, `OpenApiValidationFilter` 부착).

## 완료 기준 (Definition of Done)

- 위 11개 operation 구현, 요청/응답 DTO가 계약 스키마와 정확히 일치.
- 각 usecase의 성공·에러 분기 테스트 통과.
- Contract 테스트 추가·통과. 전체 `./gradlew test` green(기존 알려진 `SeminarContractTest.createMatchesContract`의 capacity/target drift 예외는 별개 — 이 설계 범위 밖, 회귀만 아니면 OK).
- 인증 필요한 신규 라우트는 `SecurityConfig`에 반영.

## 범위 밖

- FE(`home-jaram-fe`): 일정 UI, 슬롯 카드, 제출/재제출 폼 — 별도 repo·plan.
- `Seminar.capacity → target: TargetGrade[]` 기존 drift(§6-2): 손대지 않음.
- `attendanceCode` admin batch 쓰기 경로(§6-4): 이 설계는 슬롯/재제출에서 `attendanceCode`를 무시만 하고, admin batch 쓰기 허용 여부는 별도 후속.
