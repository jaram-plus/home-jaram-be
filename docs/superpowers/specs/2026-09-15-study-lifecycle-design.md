# 스터디 설계 ① — 상태 기계와 모집

작성일 2026-09-15. 대상 브랜치 `feat/study-lifecycle`.
전체 3단계 중 **① 단계**. ②·③ 단계는 §14 에 범위만 적어 둔다.

## 1. 문제

스터디 도메인은 이미 있다. 개설 신청 → 임원 승인 → 모집 → 지원 → 지원 승인의
2단 흐름과 엔드포인트 9개가 돌아간다. 문제는 **그 흐름에 시간축이 없다는 것**이다.

- `StudyStatus` 가 `cur >= cap ? CLOSED : RECRUITING` 으로만 파생된다.
  `ONGOING` 은 열거형에 있을 뿐 **도달할 방법이 없다**(`StudyStatus.java` 주석이
  스스로 인정한다: "라이프사이클 전환 엔드포인트가 계약에 없어 현재 미도달").
  스터디가 시작했는지, 끝났는지 시스템이 모른다.
- 그래서 `cur`(승인 인원)이 정원에 닿는 순간 스터디가 "닫힌" 것으로 취급된다.
  인원이 곧 진행 여부가 되어 있다. 정원을 채우지 못한 스터디는 학기가 끝나도
  영원히 `모집 중` 이고, 정원을 채운 스터디는 첫 모임 전에 이미 `마감` 이다.
- 학기 단위의 모집 기간이라는 개념이 없다. 스터디 개설을 언제 받고 언제 닫는지가
  운영자 손에 없다.
- 커리큘럼·장소·문의처를 담을 자리가 없어, 화면이 스터디를 소개할 재료가 부족하다.

① 단계는 **상태를 저장값으로 바꾸고, 그 상태를 움직이는 손잡이를 운영자에게
주는 것**까지다. 출석과 관리자 도구는 ②·③ 단계다.

## 2. 결정 요약

설계 문답에서 확정된 것. 근거는 각 절에 있다.

| # | 항목 | 결정 |
|---|---|---|
| D1 | 상태 | `RECRUITING` / `ONGOING` / `FINISHED` 를 **저장**한다 |
| D2 | 신청 가능 시점 | `RECRUITING` 에서만 |
| D3 | 모집 토글 | 관리자 설정의 boolean. **OFF 로 바뀌는 순간** 일괄 전이 |
| D4 | 개설 승인 | 임원 유지 (`STUDY_APPROVE`) |
| D5 | 커리큘럼 | 주차별. **개설 시 최소 1주차 필수** |
| D6 | 문의처 | 개설 시 **별도 필수 입력**. 회원 `phone` 을 쓰지 않는다 |
| D7 | 분야 태그 | 유지. 제목 옆 칩으로 노출 |
| D8 | `period`(기간) | **삭제**. 커리큘럼 주차 수가 대신한다 |
| D9 | 정원 | **한도가 아니라 희망 인원**. 마감 판정을 없앤다 |
| D10 | 지원 인원 공개 | 상세 모달에 명단. 학번 마스킹, 반려 제외, **로그인 필수** |
| D11 | 반려 뒤 재신청 | **허용한다.** '삭제하기'가 신청 행을 하드 삭제해 제약이 풀린다 |
| D12 | 상태 되돌리기 | 관리자 일괄 편집에서 `status` 를 고칠 수 있게 **① 로 당긴다** |

## 3. 상태 기계

```
                  임원 승인               토글 OFF           스터디장 '종료'(②)
개설 신청 ──────────────────▶ 모집 중 ─────────────────▶ 진행 중 ─────────────────▶ 종료
(PENDING)     │              RECRUITING                 ONGOING                  FINISHED
              │                   ▲                        ▲                        │
              │ 임원 반려          └────────────────────────┴────────────────────────┘
              ▼                        임원이 관리자 상세에서 임의 지정 (③)
           REJECTED
```

**축이 둘이다.** 기존 `approvalStatus`(개설 승인축, `PENDING`/`APPROVED`/`REJECTED`)는
그대로 두고, 새 `status`(생애축)를 옆에 세운다. 합치지 않는 이유는 둘이 다른 질문에
답하기 때문이다 — "이 스터디를 열어도 되는가" 와 "지금 어느 단계인가" 는
같이 움직이지 않는다. 반려된 스터디에는 생애가 없고, 진행 중인 스터디의 개설 승인은
이미 끝난 과거다.

`status` 는 `approvalStatus == APPROVED` 일 때만 의미가 있다. 그 전에는 `null` 이다.

### 전이 규칙

| 전이 | 계기 | 주체 |
|---|---|---|
| `null` → `RECRUITING` | 개설 승인 **且** 모집 토글 ON | 임원 |
| `null` → `ONGOING` | 개설 승인 **且** 모집 토글 OFF | 임원 |
| `RECRUITING` → `ONGOING` | 모집 토글이 ON→OFF 로 바뀜 | 운영자 (일괄) |
| `ONGOING` → `FINISHED` | 종료 버튼 (**② 단계**) | 스터디장 |
| 임의 → 임의 | 관리자 상세 편집 (**③ 단계**) | 임원 |

토글이 꺼져 있는 동안 승인된 스터디가 곧바로 `ONGOING` 으로 들어가는 것은,
모집이 끝난 뒤 승인된 스터디를 `모집 중` 으로 두면 **신청을 받을 수 없는데
신청 버튼이 켜져 있는 상태**가 되기 때문이다.

### 정원이 차는 것은 상태가 아니다

D9 에 따라 `capacity` 는 희망 인원이다. 승인 인원이 그 숫자를 넘어도 상태는
`RECRUITING` 그대로고, 신청도 계속 받는다. 스터디장이 판단한다(②).

## 4. 모집 토글 — 경계에서만 전이한다

`AdminSettings` 에 `studyRecruiting boolean` 하나를 더한다. 이 값이 하는 일:

1. `POST /api/studies`(개설 신청)를 열고 닫는다. **OFF 면 `409 RECRUIT_CLOSED`.**
2. `PATCH /api/admin/settings` 로 **`true` → `false` 가 되는 순간**,
   `approvalStatus == APPROVED && status == RECRUITING` 인 스터디를 전부
   `ONGOING` 으로 옮긴다.

**경계 트리거(edge-triggered)다.** 이미 `false` 인 값에 `false` 를 다시 써도 아무 일도
일어나지 않는다. 그래야 설정 화면이 다른 필드를 저장할 때마다 전이가 다시 돌지 않는다.

**되돌리지 않는다.** `false` → `true` 는 개설 버튼만 다시 켜고 `ONGOING` 을
`RECRUITING` 으로 되돌리지 않는다. 일괄 전이를 양방향으로 만들면 토글 한 번 잘못
눌러 종료 직전의 스터디들이 한꺼번에 모집 상태로 돌아간다. 되살릴 스터디는
임원이 관리자 상세에서 하나씩 고른다(③).

**화면이 버튼을 숨기는 것은 통제가 아니다.** 토글이 꺼져 있으면 서버가 개설 신청을
거절해야 한다. FE 의 버튼 숨김은 편의일 뿐이다.

### 되돌릴 손이 ① 안에 있어야 한다 (D12)

위의 "되돌리지 않는다" 는 되살릴 스터디를 임원이 하나씩 고른다는 전제 위에 서 있는데,
그 화면이 ③ 단계에 있다. 즉 **①·② 만 배포된 동안 토글을 잘못 끄면 되돌릴 방법이
아무 데도 없다.** 관리자 일괄 편집(`AdminBatchExecutor.updateStudy`)이 지금 허용하는
필드는 `title` 과 `capacity` 뿐이다.

한 번의 클릭으로 그 학기 모든 스터디의 상태가 바뀌는데 되돌릴 수 없는 것은
설계 결함이지 단계 분할의 문제가 아니다. `updateStudy` 의 `switch` 에 `status` 분기
하나를 **① 로 당긴다**:

```java
case "status" -> enumField(StudyStatus.class, v, errors, k, s::setStatus, actions, false);
```

기존 `enumField` 헬퍼를 그대로 쓴다 — `members` 의 `grade`·`status`·`approval` 이
이미 같은 모양이다.

③ 단계는 이 위에 화면만 얹는다. 게이트는 기존 `AdminResourceAccess.canEdit`
(`studies → STUDY_EDIT`) 그대로라 새 권한도 없다.

### 토글 상태를 화면이 어떻게 읽는가

`GET /api/admin/settings` 는 임원 전용이라 일반 회원이 못 읽는다. 그렇다고
공개 설정 엔드포인트를 새로 만들 일은 아니다.

→ **`GET /api/studies` 의 응답을 감싼다.**

```json
{ "recruiting": true, "items": [ ... ] }
```

스터디 페이지가 어차피 첫 화면에서 부르는 엔드포인트고, 모집 여부는 비밀이 아니다.
새 경로도, `SecurityConfig` 변경도 필요 없다. ① 단계가 이 응답을 어차피 바꾸므로
(항목에 `status`·`leaderGen` 추가) 감싸는 비용이 지금 가장 싸다.

## 5. 데이터 모델

### `Study` 변경

```
+ status       StudyStatus?   생애축. approvalStatus == APPROVED 전에는 null
+ contact      String         문의처. 개설 시 필수 (자유 텍스트)
+ place        String         장소. 개설 시 필수
- period       String         삭제 (D8)
```

`fields`(분야)·`schedule`(일시)·`mode`(방식)·`intro`(설명)·`capacity`(희망 인원)는
그대로 쓴다.

### `StudyWeek` — 새 엔티티

```
id       String   UUID
studyId  String
weekNo   int      1부터. 연속이어야 한다
title    String   필수
content  String?  2000자. 선택

unique (studyId, weekNo)
```

**커리큘럼 주차가 곧 출석 주차다**(D5). ② 단계의 출석이 이 행들을 그대로 대상으로
삼는다. 둘을 따로 두면 화면의 "3주차"가 커리큘럼 3주차와 같은 것인지 보장할 수 없다.

`weekNo` 는 1부터 빈칸 없이 이어진다. 검증은 저장 시점에 한다 —
`[1,2,4]` 같은 입력은 `400`.

### `StudyStatus` 열거형 교체

```java
public enum StudyStatus { RECRUITING, ONGOING, FINISHED }
```

기존 `CLOSED` 는 버린다. 그 값은 "정원이 찼다"는 뜻이었고, D9 으로 그 개념 자체가
사라졌다. `CLOSED` 를 `FINISHED` 의 뜻으로 재사용하지 않는 이유는, 뜻이 달라진 값을
같은 이름으로 남기면 계약을 읽는 사람이 옛 뜻으로 읽기 때문이다.

### `ApplyState` — 이름은 남고 뜻이 바뀐다

| 값 | 새 의미 |
|---|---|
| `null` | 미인증 |
| `OPEN` | 스터디가 `RECRUITING` 이고 내 신청 기록이 없다 → 신청 가능 |
| `APPLIED` | 내 신청이 `PENDING` |
| `JOINED` | 내 신청이 `APPROVED`, 또는 내가 스터디장 |
| `CLOSED` | 신청 불가 — `RECRUITING` 이 아니거나, 내가 반려당했다 |

## 6. 신청 규칙 — 정원 마감을 없앤다

D9 에 따라 걷어내는 것:

| 위치 | 지금 | 바뀜 |
|---|---|---|
| `StudyService.apply()` | `approvedCount >= cap` → `409 RECRUIT_CLOSED` | **삭제** |
| `StudyService.approveApplicant()` | `approvedCount >= cap` → `409 CAPACITY_FULL` | **삭제** |
| `StudyService.deriveStatus()` | `cur >= cap ? CLOSED : RECRUITING` | **삭제** (저장값을 읽는다) |

`apply()` 에 남는 검사:

1. `eligibility.requireActive(applicantId)` — 조회보다 먼저. (기존 주석의 이유 그대로:
   없는 id 에 404 가 앞서면 안 된다)
2. `approvalStatus == APPROVED` 그리고 `status == RECRUITING` → 아니면
   `409 RECRUIT_CLOSED`
3. 본인이 스터디장이면 `409 LEADER_SELF`
4. 이미 신청 기록이 있으면 `409 ALREADY_APPLIED`

### 반려 뒤 재신청 — 허용한다 (D11)

`study_application` 에 `unique (studyId, applicantId)` 가 걸려 있어 반려된 사람은
재신청할 수 없다. ② 단계의 '삭제하기' 버튼이 그 행을 **하드 삭제**하면 제약이 풀려
재신청이 가능해진다.

**재신청을 허용한다.** 선착순이 아니라 스터디장 재량으로 뽑는 구조라면,
한 번 반려됐다고 학기 내내 막을 근거가 약하다. 소프트 삭제 플래그를 지금 만드는 것은
쓰지 않을 상태를 하나 더 만드는 일이다. 스팸이 실제로 생기면 그때 재신청 횟수 제한을
얹는 편이 싸다.

(이 결정은 ② 단계에서 구현된다. ① 단계에는 삭제 버튼이 없다.)

## 7. 지원 인원 공개와 학번 마스킹

상세 모달 하단에 지원 인원 명단을 붙인다.

| 규칙 | 내용 |
|---|---|
| 포함 | `PENDING` + `APPROVED` |
| 제외 | `REJECTED`, 그리고 스터디장(지원자가 아니고 이미 제목 아래에 있다) |
| 필드 | 마스킹된 학번 · 기수 · 이름 |
| 정렬 | **기수 → 이름** |
| 접근 | **로그인 필수** |

**승인 여부를 내려보내지 않는다.** 응답 객체에 상태 필드 자체를 넣지 않는다.
쓰지 않는 필드를 실어 보내면 언젠가 화면에 샌다.

**정렬이 상태를 누설하지 않게 한다.** 신청 순으로 세우면 "먼저 신청했는데 아직 뒤에
있다" 가 순서에서 읽힌다. 상태와 무관한 축(기수·이름)으로 세워야 숨긴 의미가 있다.

**마스킹은 서버가 한다.** 전체 학번을 내려보내고 화면에서 가리면 개발자 도구로
그대로 보인다.

```java
// 앞 4자리 + 가운데 전부 '*' + 뒤 1자리. 길이를 보존한다.
static String maskStudentId(String id) {
    if (id == null || id.length() < 6) return id;   // 방어. 학번은 ^\d{8,10}$
    return id.substring(0, 4)
           + "*".repeat(id.length() - 5)
           + id.substring(id.length() - 1);
}
```

`2022123459`(10자리) → `2022*****9`, `20231234`(8자리) → `2023***4`.

### 인원 카운터와의 불일치 — 의도한 것이다

카운터는 `승인 3 / 희망 8`, 명단은 대기 포함 5명일 수 있다. 보는 사람이 "2명은
대기중" 까지 셈할 수 있으나 **누가** 대기인지는 모른다. 카운터를 명단 길이에 맞추면
승인 인원이라는 운영 숫자가 사라진다. 카운터는 승인 인원 그대로 둔다.

## 8. 화면과 응답

### 목록 카드 (`GET /api/studies`)

```
+------------------------------------------+
| [모집 중]                                |
| 알고리즘 스터디   (알고리즘) (Python)    |   <- 분야 칩 (D7)
| 기초부터 백준 골드까지 함께 풉니다.      |
| 40기 이준호                              |
+------------------------------------------+
```

항목 필드는 기존 `StudyResponse` 를 이어받는다:
`id`, `title`, `fields[]`, `leader`(이름), `schedule`, `mode`, `cur`(승인 인원),
`cap`(희망 인원), `status`, `apply`. 여기에 **더한다**: `intro`(설명 — 카드에 필요),
`leaderGen`(기수). **뺀다**: `period`(D8).

`cur`·`cap`·`leader` 는 이름을 바꾸지 않는다. `cap` 이 이제 한도가 아니라 희망 인원을
뜻하지만(D9), 뜻이 바뀌었다고 계약 필드명을 세 개 갈면 FE 가 쓰는 자리를 전부
건드리면서 기능은 하나도 안 는다. 뜻의 변화는 `ApplyState` 와 같이 문서로 남긴다.

상태 칩 필터는 화면에서 거르지 않고 서버가 거른다 — `?status=RECRUITING` 등.
파라미터가 없으면 **`RECRUITING` + `ONGOING`** 만 준다(`전체` 칩). `FINISHED` 는
`?status=FINISHED` 로만 나온다. 개설 대기·반려 스터디는 어느 경우에도 목록에 없다.

### 상세 모달 (`GET /api/studies/{id}`)

제목 · 분야 칩 · 스터디장(기수·이름) · 설명 · 모집 인원 · 진행 방식(일시·장소·방식) ·
커리큘럼(주차별) · 문의 · **지원 인원 명단** · 신청하기 버튼.

**이 엔드포인트는 인증이 필요하다.** `GET /api/studies`(목록)는 지금처럼 공개로 두고,
상세부터 로그인을 요구한다. 이유가 둘이다:

- D10 이 명단에 로그인을 요구한다. 로그인 경계를 "모달" 하나로 그으면 규칙이 하나다.
  명단만 비우고 나머지를 여는 것보다 읽기 쉽다.
- `SecurityConfig` 에 `GET /api/studies/*` 를 `permitAll` 로 넣으면 그 와일드카드가
  `/api/studies/my`·`/pending`·`/applicants` 까지 한 세그먼트로 잡는다. 권한 리팩터링이
  URL 매처를 걷어낸 이유가 정확히 이것이다 — 매처는 빠뜨려도 조용히 열리고,
  `AdminAuthorizationCoverageTest` 는 애너테이션만 지킨다. 매처를 다시 늘리지 않는다.

비로그인 방문자는 목록 카드까지 본다. 학회 홍보라는 목적에는 그것으로 충분하다.

### 개설 폼 (`POST /api/studies`)

| 필드 | 필수 | 비고 |
|---|---|---|
| `title` | O | |
| `fields[]` | O | 최소 1개 |
| `intro` | O | 설명 |
| `capacity` | O | 희망 인원. 1 이상 |
| `schedule` | O | 일시 |
| `place` | O | 장소 — 새 필드 |
| `mode` | O | 방식 |
| `contact` | O | 문의처 — 새 필드 (D6) |
| `weeks[]` | O | **최소 1개**. 각 항목 `{weekNo, title, content?}` (D5) |

`period` 는 받지 않는다(D8).

## 9. 권한 — 새 Permission 은 없다

Permission 20개, Role 10개, 매트릭스 모두 그대로다. 노션 기능 지도의 표를 고칠 일이 없다.

| 동작 | 게이트 | 단계 |
|---|---|---|
| 목록 조회 | 공개 | ① |
| 상세 조회 | 인증 | ① |
| 개설 신청 | 인증 + `Eligibility.requireActive` + 토글 ON | ① |
| 신청 | 인증 + `Eligibility.requireActive` | ① |
| 개설 승인·반려 | `hasAuthority('STUDY_APPROVE')` | ① (기존) |
| 신청 승인·반려 | `hasAuthority('STUDY_APPLICANT_MANAGE')` | ① (기존, ②에서 스터디장으로 확장) |
| 모집 토글 | `SettingsAccess.canApply` 에 `studyRecruiting → STUDY_EDIT` 분기 | ① |

`SettingsAccess.canApply` 는 이미 PATCH 한 건을 필드별로 가른다
(`currentGen → SETTINGS_ROLLOVER`, `links → SETTINGS_EDIT | SITE_LINKS_EDIT`).
`studyRecruiting` 은 스터디 관리 탭의 손잡이라 `STUDY_EDIT` 로 가른다. 설정 화면에
얹혀 있다고 설정 권한으로 가르면, 홍보부장이 스터디 모집을 닫을 수 있게 된다.

이 변경으로 `STUDY_EDIT` 이 `AdminResourceAccess` 밖에서 처음으로 쓰인다.

## 10. 엔드포인트

### 새로

| 메서드 | 경로 | 권한 |
|---|---|---|
| `GET` | `/api/studies/{id}` | 인증 |

### 바뀌는 것

| 경로 | 변경 |
|---|---|
| `GET /api/studies` | 응답을 `{recruiting, items[]}` 로 감쌈. `?status=` 추가. 항목에 `intro`·`leaderGen` 추가, `period` 제거 |
| `POST /api/studies` | `place`·`contact`·`weeks[]` 필수, `period` 제거. 토글 OFF 면 `409` |
| `POST /api/studies/{id}/apply` | `status == RECRUITING` 검사 추가, 정원 검사 제거 |
| `POST /api/studies/{id}/approve` | 승인 시 `status` 를 토글 상태에 맞춰 세팅 |
| `GET /api/studies/pending` | 응답에서 `period` 제거 |
| `PATCH /api/admin/settings` | `studyRecruiting` 필드. `true→false` 경계에서 일괄 전이 |
| `GET /api/admin/settings` | `studyRecruiting` 필드 |
| `GET /api/admin/studies` | 행에 `status` 추가 (`studyRow` 에 `period` 는 원래 없다) |
| `PATCH /api/admin/studies:batch` | `updateStudy` 가 `status` 를 받는다 (D12) |

`/api/studies/my`·`/pending`·`/applicants` 와 신청자 승인/거절은 ① 에서 손대지 않는다.
`my` 의 재정의는 ② 단계다.

## 11. 스키마 이행

`ddl-auto: update` 라서 **추가는 자동, 삭제는 수동**이다.

자동으로 되는 것: `study.status`·`study.contact`·`study.place` 컬럼 추가,
`study_week` 테이블 생성, `admin_settings.study_recruiting` 추가.

`contact`·`place` 는 **DB 에서 nullable** 이다. 기존 행이 있는 테이블에 NOT NULL 컬럼을
붙이면 Hibernate 가 실패한다. 필수 여부는 요청 DTO 의 `@NotBlank` 가 지킨다 —
기존 스터디는 값이 비어 있어도 화면에 "미등록" 으로 뜬다.

손으로 돌릴 SQL (`docs/migrations/2026-09-15-study-lifecycle.sql`):

```sql
-- 1. 승인된 기존 스터디에 생애 상태를 채운다. 모집 기간이 이미 끝났다고 보고 진행 중으로.
UPDATE study SET status = 'ONGOING' WHERE approval_status = 'APPROVED' AND status IS NULL;

-- 2. period 를 버린다 (D8). 배포가 끝나고 코드가 이 컬럼을 읽지 않는 것을 확인한 뒤 실행.
ALTER TABLE study DROP COLUMN period;
```

2번은 **배포 후**에 실행한다. 먼저 지우면 구버전 인스턴스가 뜨는 동안 매핑이 깨진다.

## 12. 계약과 배포 순서

`openapi.yaml` 은 home-jaram-fe 에만 있고 BE 의 두 경로는 그 파일로의 심볼릭 링크다.
BE CI 는 **BE 브랜치와 같은 이름의 FE 브랜치**에서 스펙을 집고, 없으면 조용히
FE `develop` 으로 떨어진다. 검증기는 스키마에 없는 응답 필드를 거부한다.

① 단계는 필드를 **더하기도 하고 빼기도 한다**(`period` 제거). 그래서:

1. home-jaram-fe 에 `feat/study-lifecycle` 브랜치를 같은 이름으로 만든다.
2. 계약 PR 을 먼저 머지한다.
3. BE PR 을 머지한다.
4. 배포 후 `ALTER TABLE study DROP COLUMN period` 를 실행한다.

## 13. 테스트

| 대상 | 검증 |
|---|---|
| 상태 전이 | 토글 ON 중 승인 → `RECRUITING`; OFF 중 승인 → `ONGOING` |
| 일괄 전이 | `true→false` 로 `RECRUITING` 이 전부 `ONGOING`; `FINISHED`·`PENDING` 은 안 건드림 |
| 경계 트리거 | `false→false` 재저장이 아무것도 옮기지 않음 |
| 되돌리지 않음 | `false→true` 가 `ONGOING` 을 되돌리지 않음 |
| 개설 차단 | 토글 OFF 에서 `POST /api/studies` → `409` |
| 신청 시점 | `ONGOING`·`FINISHED` 스터디에 신청 → `409 RECRUIT_CLOSED` |
| 정원 제거 | 승인 인원이 `capacity` 를 넘어도 신청·승인이 통과 |
| `ApplyState` | 5가지 경우(미인증/OPEN/APPLIED/JOINED/CLOSED) 각각 |
| 마스킹 | 8자리·10자리 각각, 그리고 `roster` 에 전체 학번이 없음 |
| 명단 규칙 | 반려 제외, 스터디장 제외, 기수·이름 정렬, 상태 필드 부재 |
| 상세 인증 | 비로그인 `GET /api/studies/{id}` → `401`; 목록은 `200` |
| 토글 권한 | `SITE_LINKS_EDIT` 만 가진 홍보부가 `studyRecruiting` 저장 → `403` |
| 커리큘럼 | 빈 `weeks[]` → `400`; `weekNo` 가 `[1,2,4]` → `400` |
| 되돌리기 (D12) | 일괄 전이 후 `PATCH :batch` 로 `status: RECRUITING` 을 써서 복구; 없는 값은 `400` |

`AdminAuthorizationCoverageTest` 가 새 핸들러의 애너테이션 누락을 자동으로 잡는다.

## 14. 범위 밖 — ②·③ 단계

**② 운영** — 스터디장이 신청을 승인·반려(`StudyAccess.isLeader` 소유자 조건),
`StudyWeek.takenAt` 추가와 `StudyAttendance` 신설(존재=출석), 첫 저장 +24h 편집 창,
종료 버튼(`ONGOING → FINISHED`), '내 스터디' 탭 재정의(`FINISHED` 제외),
반려 신청 '삭제하기'(§6 의 재신청 가정을 구현).

**③ 임원 도구** — 관리자 '스터디 관리' 탭, 모집 토글 UI, 표(스터디명·스터디장·인원·
출석률·상태·상세·삭제), 상세 편집 **화면**(상태 편집 API 자체는 D12 로 ① 에 있다),
멤버 직접 추가
(진행 중 합류 시 지난 주차는 결석 — 존재=출석 모델에서 저절로 성립한다).

**아예 안 하는 것** — 스터디 채팅·파일 공유·과제 제출. 이번 재설계에 없다.
