# BE 리팩터링·기능 개발 백로그 (contract 기준 2026-07-02)

`docs/api/openapi.yaml`(FE 소유, symlink) 를 sync 한 뒤 현재 코드와 대조해 도출한
리팩터링·신규 기능 목록. 계약이 이번 sync 로 +303줄 커졌고, **study 전 도메인**과
**admin 관리 서피스**가 통째로 추가됨. 아래는 계약을 법으로 두고 코드를 맞추는 작업 목록.

방법: `./scripts/sync-openapi.sh` → `git diff` 대조 → 컨트롤러/엔티티/enum 실물 확인.

---

## 1. 구현 현황 매트릭스

| 계약 경로 | 메서드 | UC | 상태 |
|---|---|---|---|
| `/api/auth/login` `/signup` `/password/reset-request` `/password/reset` | POST | — | ✅ 구현 |
| `/api/people` | GET | UC-P1 | ✅ |
| `/api/seminars` | GET/POST | UC-S1/S3 | ✅ |
| `/api/seminars/{id}/attend` | POST | UC-S2 | ✅ |
| `/api/seminars/{id}/roster` | GET | UC-S4 | ✅ |
| `/api/me` | GET/PATCH | — | ✅ |
| `/api/admin/members/pending` `/{id}/approve` `/{id}/reject` | GET/POST | — | ✅ |
| `/api/studies` | GET | UC-T1 | ❌ 미구현 |
| `/api/studies` | POST | UC-T3 | ❌ |
| `/api/studies/{id}/apply` | POST | UC-T2 | ❌ |
| `/api/studies/my` | GET | UC-T4 | ❌ |
| `/api/studies/pending` | GET | UC-T5 | ❌ |
| `/api/studies/{id}/approve` `/reject` | POST | UC-T6 | ❌ |
| `/api/studies/applicants` | GET | UC-T7 | ❌ |
| `/api/studies/applicants/{id}/approve` `/reject` | POST | UC-T8 | ❌ |
| `/api/admin/{resource}` | GET | — | ❌ 신규 |
| `/api/admin/{resource}:batch` | PATCH | — | ❌ 신규 |
| `/api/admin/dashboard/stats` | GET | — | ❌ 신규 |
| `/api/admin/settings` | GET/PATCH | — | ❌ 신규 |
| `/api/admin/export/google-drive` | POST | — | ❌ 신규 |

**결과: 계약 26 경로 중 12 구현, 14 미구현.** 미구현 = study 9 + admin 서피스 5.

> 주의: `SecurityConfig` 는 이미 `/api/studies` 계열 라우트 인가를 선언해 뒀지만
> (`SecurityConfig.java:52,54,55,58`) **컨트롤러가 없어 지금은 전부 404**. 인가 설정과 구현이 불일치.

---

## 2. 리팩터링 (기능 추가 전 선행)

계약의 회원 도메인 enum 축이 재설계됨. 신규 기능(특히 admin `{resource}=members`)이
이 축을 그대로 노출하므로 **먼저 정리해야 함**. 현재 코드와 계약이 어긋난 지점:

### R1. `MemberStatus` 축 분리 (승인축 ≠ 활동축) — 파급 큼
- 현재 코드: `MemberStatus = [PENDING, ACTIVE, REJECTED]` (`member/MemberStatus.java`).
  가입 승인 상태와 활동 상태를 한 필드에 섞음. `Member.status` 기본값 = `PENDING`.
- 계약: `MemberStatus = [ACTIVE, ON_LEAVE, WITHDRAWN]` — **활동 상태 전용**. PENDING/REJECTED 없음.
  승인축은 별도(`/admin/members/pending` 목록으로만 노출; 스터디의 `ApprovalStatus=[PENDING,APPROVED,REJECTED]` 와 동형).
- 작업:
  - `Member` 에 **승인축 필드 신규**(예 `approval: PENDING|APPROVED|REJECTED`) 도입, 가입 기본 `PENDING`.
  - `MemberStatus` enum 값을 `[ACTIVE, ON_LEAVE, WITHDRAWN]` 로 교체. 가입 시
    `SignupRequest.enrolled` 파생(true→ACTIVE, false→ON_LEAVE) — 계약 `MemberStatus.description` 반영.
  - `AdminMemberService.approve/reject` 를 승인축 갱신으로 재작성(현재 `status=ACTIVE/REJECTED` 로 추정).
  - 마이그레이션/컬럼 추가 + 기존 데이터 백필 고려.
- 영향 파일: `member/Member.java`, `member/MemberStatus.java`, 신규 `member/MemberApproval.java`,
  `admin/AdminMemberService.java`, `auth/AuthService`(가입 파생·로그인 PENDING 차단 로직).
- 테스트: `AdminMemberTest`, `SignupTest`, `LoginTest`(pending/rejected 분기) 갱신.

### R2. `MemberGrade` 추출 (title 과 grade 분리)
- 현재 코드: `MemberTitle` enum 에 등급값이 섞여 있음 —
  `[... SERVER_ADMIN, OB, REGULAR, ASSOCIATE, NEWCOMER]` (`member/MemberTitle.java`).
- 계약: 직책 `MemberTitle = [PRESIDENT, VICE_PRESIDENT, ACADEMIC_LEAD, ACADEMIC_MEMBER,
  PR_LEAD, PR_MEMBER, FINANCE_LEAD, FINANCE_MEMBER, SERVER_ADMIN, null]`,
  등급 `MemberGrade = [NEWCOMER, ASSOCIATE, REGULAR, OB]` (**별도 enum**).
- 작업: `MemberGrade` enum 신규 → `Member.grade` 필드 추가 →
  `MemberTitle` 에서 `OB/REGULAR/ASSOCIATE/NEWCOMER` 제거.
  승인 시 gen 파생(`gen == 현재년도-1984 → NEWCOMER, else ASSOCIATE`) — 계약 `MemberGrade.description`.
- 영향 파일: 신규 `member/MemberGrade.java`, `member/MemberTitle.java`, `member/Member.java`,
  `admin/AdminMemberService`(승인 시 grade 파생).

### R3. 축 검증된 enum (변경 없음, 확인용)
- `MemberDepartment = [LEADERSHIP, ACADEMIC, PR, FINANCE, INFRA, null]` — 코드 일치 ✅.
- `Authority = [MEMBER, OFFICER]` — 일치 ✅.
- study enum 신규: `StudyStatus=[RECRUITING,ONGOING,CLOSED]`, `ApplyState=[OPEN,APPLIED,CLOSED,JOINED,null]`,
  `ApprovalStatus`/`ApplicationStatus=[PENDING,APPROVED,REJECTED]` — 아래 신규 기능에서 생성.

---

## 3. 신규 기능

### F1. Study 도메인 (신규 `com.jaram.be.study` 패키지) — 9 엔드포인트

FE 계약상 스터디는 **개설 신청→개설 승인(임원)→모집→지원(회원)→지원 승인(임원)** 2단 승인 흐름.

엔티티(추정):
- `Study` — id, title, `fields: List<String>`, leaderId, capacity, schedule/period/mode/intro(nullable),
  `status: StudyStatus`, `approvalStatus: ApprovalStatus`(개설 승인축), creatorId, createdAt, reason(반려).
  `cur`(현재 인원)·`apply`(사용자별 ApplyState) 는 **파생** — 저장 안 함.
- `StudyApplication` — id, studyId, applicantId, motive, `status: ApplicationStatus`, reason(nullable), createdAt.

| 태스크 | 경로 | 권한 | 응답 스키마 | 핵심 로직 |
|---|---|---|---|---|
| T3 개설 신청 | `POST /api/studies` | 인증 | `Study` | `StudyCreateRequest`(title/fields/capacity 필수). approval=PENDING 로 생성 |
| T1 목록 | `GET /api/studies` | public | `Study[]` | `cur` 집계·`apply` 는 인증 시 사용자 파생, 미인증 null |
| T2 지원 | `POST /api/studies/{id}/apply` | 인증 | 204 | `ApplyRequest`(motive 필수). 중복지원/정원마감 검증→422/400 |
| T4 내 활동 | `GET /api/studies/my` | 인증 | `MyActivity{apps[],studies[]}` | 내 지원 + 내가 개설한 스터디 |
| T5 개설 대기 | `GET /api/studies/pending` | OFFICER | `PendingStudy[]` | approval=PENDING 목록 |
| T6 개설 승인/거절 | `POST /api/studies/{id}/approve` `/reject` | OFFICER | 204 | reject 는 `RejectRequest`(reason 필수) |
| T7 신청자 목록 | `GET /api/studies/applicants` | OFFICER | `Applicant[]` | 승인 대기 지원자 전체 |
| T8 신청자 승인/거절 | `POST /api/studies/applicants/{id}/approve` `/reject` | OFFICER | 204 | reject 는 reason 필수 |

- SecurityConfig: 라우트는 이미 선언됨(§1 주의 참고). `POST /api/studies` `/apply` `/my` 는
  `authenticated()` 로 커버되는지 재확인(현재 명시 매처 없음 → `anyRequest().authenticated()` 로 커버 ✅).
- 파일: `study/StudyController.java`, `StudyService`, `StudyRepository`, `StudyApplicationRepository`,
  `Study`, `StudyApplication`, `StudyStatus`, `ApprovalStatus`, `ApplicationStatus`, `study/dto/*`.
- 테스트: usecase 테스트 8종(성공+에러분기) + `study/StudyRepositoryTest` + `contract/StudyContractTest`.

### F2. Admin 관리 서피스 (기존 `admin` 패키지 확장) — 5 엔드포인트

관리자 화면용 범용 CRUD·집계. `{resource} ∈ {members, seminars, studies}` 다형 엔드포인트.

| 태스크 | 경로 | 요청/응답 | 핵심 |
|---|---|---|---|
| A1 관리 목록 | `GET /api/admin/{resource}` | q/tab/sort/page(기본1)/size(기본8) → `AdminListResponse` | `items` 는 `additionalProperties:true` 행(리소스별 FE 스키마). enum 은 wire 키(UPPER_SNAKE). 페이지네이션 |
| A2 일괄 저장 | `PATCH /api/admin/{resource}:batch` | `AdminBatchRequest{updates,creates,deletes}` → `AdminBatchResponse` | **부분 성공**. `version` 낙관적 잠금→`conflicts`, 검증실패→`errors[].fieldErrors`, create 는 `tempId→id` 매핑 |
| A3 대시보드 | `GET /api/admin/dashboard/stats` | → `DashboardStats` | totalMembers/alumniCount/출석률·deltas·gradeBreakdown·cohortBreakdown·attendanceTrend·pendingBreakdown 집계 |
| A4 설정 조회/수정 | `GET/PATCH /api/admin/settings` | `AdminSettings` / `AdminSettingsUpdate` | semester/currentCohort/autoPromote/driveConnected/driveFolder. 설정 저장소(신규 테이블/단일행) 필요 |
| A5 Drive 내보내기 | `POST /api/admin/export/google-drive` | `DriveExportRequest` → `DriveExportResult{fileUrl,fileId}` | Google Drive 연동. **외부 통합 — 별도 스파이크** |

- 인가: `/api/admin/**` 는 이미 `hasAuthority("OFFICER")` (`SecurityConfig.java:53`) 로 전부 커버 ✅.
- 콜론 경로 `{resource}:batch` 는 Spring 매핑 시 URL 인코딩/매처 주의(`@PostMapping("/{resource}:batch")` 형태 확인 필요).
- 난도: A1/A2 는 다형 `Map<String,Object>` 직렬화라 타입 안전성·검증 설계가 핵심. A5 는 외부 API 로 가장 무거움.
- 파일: `admin/AdminResourceController`, `AdminSettingsController`, `AdminDashboardController`,
  `AdminExportController`(또는 통합), 각 Service, `AdminResource` enum, `admin/dto/*`, 설정 엔티티.
- 테스트: 각 엔드포인트 usecase + `contract/AdminContractTest`. A2 부분성공 분기 다수.

---

## 4. 권장 순서 (페이즈)

이전 계획이 P1(foundation-auth)·P3(seminar) 로 매겨져 있으므로 이어서:

1. **P4 — 회원 도메인 리팩터링 (R1·R2·R3확인).** 신규 기능의 전제. 마이그레이션 포함. 파급 크므로 단독 페이즈.
2. **P5 — Study 도메인 (F1).** 엔티티→목록/개설(T1·T3)→지원(T2·T4)→승인 흐름(T5~T8) 순. 계약 테스트 필수.
3. **P6 — Admin 관리 서피스 코어 (F2 A1~A4).** R1/R2 로 정리된 회원 축 위에서 목록·batch·대시보드·설정.
4. **P7 — Drive 연동 (F2 A5).** 외부 통합 스파이크 후 구현. 나머지와 독립이라 후순위.

각 페이즈: `superpowers:writing-plans` 로 태스크별 Files/Interfaces/TDD 단계 확정 후
`superpowers:executing-plans` 로 test-first 진행. 엔드포인트마다 contract 테스트 + `./gradlew test` green = 완료.

## 4-1. 구현 완료 기록

트랙별 개발 완료 체크. 각 트랙: 구현 → 테스트 green → sub-agent 검증 → 체크.

- [x] **P4 — 회원 도메인 리팩터링** (R1 축분리 · R2 grade 추출 · R3 확인) — 68 tests green, sub-agent 검증 CLEAN
- [ ] **P5 — Study 도메인** (F1, UC-T1~T8)
- [ ] **P6 — Admin 관리 서피스 코어** (F2 A1~A4)
- [ ] **P7 — Drive 연동** (F2 A5)

## 5. 열린 질문 (FE 확인 필요)

- **회원 승인축 필드명/노출**: 계약 스키마에 회원 `approval` 필드가 직접 노출되지 않음.
  `/admin/{resource}=members` 행에 어떤 키로 승인 상태가 실리는지(FE `admin.data SCHEMAS`) 확인 필요.
- **`{resource}:batch` 의 리소스별 `fields` 스키마**: `additionalProperties:true` 라 서버 검증 규칙이
  계약에 없음 — 리소스별 허용 필드/검증을 FE 화면 스키마와 맞춰 별도 합의 필요.
- **AdminSettings 저장 위치**: 단일 학회 설정 로우인지, autoPromote 승격 잡 트리거 여부.
- **Drive 연동 인증 방식**: 서비스 계정 vs OAuth, driveFolder 지정 방식.
