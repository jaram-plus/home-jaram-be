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

① 단계는 **상태를 저장값으로 바꾸고, 그 상태를 움직이는 손잡이를 스터디장과 임원에게
쥐여 주는 것**까지다 — 기계와 전이 API 전부. 그 손잡이가 붙는 화면(②)과
임원 도구(③)는 다음 단계다.

## 2. 결정 요약

설계 문답에서 확정된 것. 근거는 각 절에 있다.

| # | 항목 | 결정 |
|---|---|---|
| D1 | 상태 | **축 하나에 5값**을 저장한다 — `PENDING`/`REJECTED`/`RECRUITING`/`ONGOING`/`FINISHED`. `approvalStatus` 는 여기로 접는다 |
| D2 | 신청 가능 시점 | `RECRUITING` 에서만 |
| D3 | 모집 토글 | 스터디 도메인의 단일 행 boolean(`StudyRecruitment`). **'스터디 개설' 버튼의 표시와 동작만** 가른다. 상태는 안 건드린다 |
| D4 | 개설 승인 | 임원 유지 (`STUDY_APPROVE`) |
| D5 | 커리큘럼 | 주차별. **개설 시 최소 1주차 필수** |
| D6 | 문의처 | 개설 시 **별도 필수 입력**. 회원 `phone` 을 쓰지 않는다 |
| D7 | 분야 태그 | 유지. 제목 옆 칩으로 노출 |
| D8 | `period`(기간) | **삭제**. 커리큘럼 주차 수가 대신한다 |
| D9 | 정원 | **한도가 아니라 희망 인원**. 마감 판정을 없앤다 |
| D10 | 지원 인원 공개 | 상세 모달에 명단. 학번 마스킹, 반려 제외, **로그인 필수** |
| D11 | 반려 뒤 재신청 | **허용한다.** '삭제하기'가 신청 행을 하드 삭제해 제약이 풀린다 |
| D12 | 임원의 상태 변경 | 관리자 일괄 편집에서 `status` 를 임의로 지정. **정규 경로다** |
| D13 | 모집 완료 | 스터디장이 '관리하기' 모달의 **'모집 완료'** 로 `RECRUITING → ONGOING` |
| D14 | 주차 유동성 | 주차는 도중에 늘고 준다. **맨 뒤에서만**, 출석이 기록된 주차는 못 자른다 |
| D15 | 인원 카운터 | **`정원 / 희망`** 으로 표기 (`cur` / `cap`) |

## 3. 상태 기계

```
            임원 승인          스터디장 '모집 완료'      스터디장 '종료'
 PENDING ─────────────▶ RECRUITING ───────────────▶ ONGOING ───────────────▶ FINISHED
    │      (개설 신청)    (모집 중)                   (진행 중)                 (종료)
    │                         ▲                         ▲                        │
    │ 임원 반려                └─────────────────────────┴────────────────────────┘
    ▼                            임원이 관리자 일괄 편집에서 임의 지정 (D12)
 REJECTED
```

**모집 토글은 이 그림에 없다.** 토글은 개설 버튼만 가른다(§4). 상태를 움직이는 것은
스터디장의 버튼 둘과 임원의 편집뿐이다.

**축은 하나다.** 기존 `approvalStatus`(`PENDING`/`APPROVED`/`REJECTED`)를 `status`
안으로 접어 5값 열거형 하나로 만든다. `APPROVED` 는 따로 남지 않는다 — 승인된
스터디는 곧바로 `RECRUITING` 이고, "승인되었다" 는 사실은 `RECRUITING` 이상의
어느 값이든 그 자체로 말해 준다.

### 왜 두 축으로 나누지 않는가

초안은 둘을 나란히 뒀었다. 근거는 "둘이 다른 질문에 답한다" 였는데, 값을 세어 보면
얻는 게 없다:

```
approvalStatus(3) × status(4, null 포함) = 12가지 조합, 합법은 5가지
```

**7가지가 표현 가능한 쓰레기 상태다.** `(REJECTED, ONGOING)` 이 저장되는 것을 막는
것은 타입이 아니라 규율이고, 읽는 사람은 조합을 볼 때마다 "이게 가능한가"를 따져야
한다. 하나로 접으면 불법 상태를 **표현할 수 없게** 된다.

`status` 가 `approvalStatus == APPROVED` 일 때만 의미를 갖는 조건부 nullable 컬럼이
된다는 것도 같은 신호다 — 한 가지를 두 컬럼에 나눠 담았을 때 나오는 모양이다.

계약에 이미 증거가 있다. `MyStudy` 가 축 두 개를 함께 싣고 있고, 화면은 그걸 조합해
칩 **하나**를 그린다:

```java
public record MyStudy(String id, String title,
        ApprovalStatus approvalStatus,   // <-
        StudyStatus status,              // <- 둘 다
        String reason) { }
```

접으면 이 레코드가 필드 하나 줄고, 조합 규칙이 화면에서 사라진다.

`reason`(반려 사유)은 그대로 둔다 — `status == REJECTED` 일 때만 채워지는 것은
지금과 같다.

### 전이 규칙

| 전이 | 계기 | 주체 |
|---|---|---|
| `PENDING` → `RECRUITING` | 개설 승인 | 임원 (`STUDY_APPROVE`) |
| `PENDING` → `REJECTED` | 개설 반려 (+ `reason`) | 임원 (`STUDY_APPROVE`) |
| `RECRUITING` → `ONGOING` | '모집 완료' 버튼 (D13) | 스터디장, 또는 `STUDY_EDIT` |
| `ONGOING` → `FINISHED` | '종료' 버튼 | 스터디장, 또는 `STUDY_EDIT` |
| 임의 → 임의 | 관리자 일괄 편집의 `status` (D12) | 임원 (`STUDY_EDIT`) |

**승인된 스터디는 언제나 `RECRUITING` 으로 들어간다.** 토글 상태를 보지 않는다 —
토글은 개설 신청을 받을지만 정하고, 이미 들어온 신청을 승인한다는 것은 그 스터디가
사람을 모아도 좋다는 뜻이다.

**모집을 끝내는 것은 스터디장이다.** 학회 전체가 한날에 모집을 닫는 구조였다면
토글이 그 일을 했겠지만, 스터디마다 사람 모으는 속도가 다르다. 자기 스터디가
찼는지는 스터디장이 가장 먼저 안다.

세 전이 API 는 전부 ① 단계에 있다. 버튼이 붙는 화면은 ② 지만, 기계를 반쪽만 만들면
① 배포 뒤 상태를 움직일 손이 임원의 일괄 편집밖에 안 남는다.

### 정원이 차는 것은 상태가 아니다

D9 에 따라 `capacity` 는 희망 인원이다. 승인 인원이 그 숫자를 넘어도 상태는
`RECRUITING` 그대로고, 신청도 계속 받는다. 스터디장이 판단한다(②).

## 4. 모집 토글 — 개설 버튼 하나만 가른다

스터디 도메인에 단일 행 엔티티 `StudyRecruitment` 를 둔다 (`id` 고정 `SINGLETON`,
`open boolean`). 이 값이 하는 일은 **딱 하나**다:

> `POST /api/studies`(개설 신청)를 열고 닫는다. OFF 면 `409 RECRUIT_CLOSED`.

상태는 건드리지 않는다. 승인 시 상태를 고를 때도 이 값을 보지 않는다(§3).

### 왜 `AdminSettings` 에 두지 않는가

초안은 `AdminSettings.studyRecruiting` 이었다. 매트릭스를 보면 그 자리가 틀렸다:

```java
MATRIX.put(Role.ACADEMIC_LEAD, EnumSet.of(
        …, STUDY_APPROVE, STUDY_APPLICANT_MANAGE, STUDY_EDIT,
        SCHEDULE_MANAGE, DASHBOARD_READ));   // SETTINGS_* 가 하나도 없다
```

`SettingsAccess.canApply` 는 필드별 분기를 다 통과한 뒤 마지막에
`SETTINGS_READ | SETTINGS_EDIT | SITE_LINKS_EDIT` 중 하나를 요구한다. 그래서
`studyRecruiting → STUDY_EDIT` 분기를 더해도 **학술부장은 403** 이고,
`GET /api/admin/settings`(`SETTINGS_READ`)로 현재 값을 읽지도 못한다. 토글을 쓸 수
있는 사람이 회장·부회장뿐인데, 바로 아래 스터디 표는 `AdminResourceAccess`
(`studies → STUDY_EDIT`)라 학술부장이 읽고 고친다. 한 화면에서 위아래 권한이 엇갈린다.

`canApply` 의 마지막 OR 에 `STUDY_EDIT` 을 끼워 넣어 고칠 수도 있다. 그러면 스터디와
무관한 설정 필드들이 `STUDY_EDIT` 하나로 함께 열리지 않도록 그 메서드 안에서 매번
관리해야 한다 — 설정 필드가 늘 때마다 다시 따져야 하는 종류의 부담이다. 값을 스터디
쪽으로 옮기면 게이트가 `hasAuthority('STUDY_EDIT')` 한 줄이고, `SettingsAccess` 는
손대지 않는다.

**왜 일괄 전이를 두지 않는가.** 초안은 토글 OFF 가 `RECRUITING` 을 전부 `ONGOING`
으로 옮기게 했었다. 그러면 학회 전체가 한날에 모집을 닫는 셈이라, 아직 사람을 더
받고 싶은 스터디까지 같이 닫힌다. 그리고 클릭 한 번이 그 학기 모든 스터디의 상태를
바꾸는데 되돌릴 화면이 없다는 문제가 따라붙는다. 모집을 끝내는 판단은 스터디장에게
있고(D13), 토글은 "이번 학기에 새 스터디를 더 받을 것인가" 만 답한다.

**화면이 버튼을 숨기는 것은 통제가 아니다.** 토글이 꺼져 있으면 서버가 개설 신청을
거절해야 한다. FE 의 버튼 숨김은 편의일 뿐이다 — 이 둘이 `StudyRecruitment.open`
하나를 같이 읽는다.

### 임원의 상태 변경은 정규 경로다 (D12)

스터디장이 '모집 완료'를 잘못 눌렀거나, 졸업·휴학으로 스터디장이 사라졌거나,
운영이 학기 말에 남은 스터디를 정리해야 할 때 — 상태를 고칠 손이 임원에게 필요하다.
관리자 일괄 편집(`AdminBatchExecutor.updateStudy`)이 지금 허용하는 필드는
`title` 과 `capacity` 뿐이다. `switch` 에 `status` 분기를 더한다:

```java
case "status" -> enumField(StudyStatus.class, v, errors, k, s::setStatus, actions, false);
```

기존 `enumField` 헬퍼를 그대로 쓴다 — `members` 의 `grade`·`status`·`approval` 이
이미 같은 모양이다.

③ 단계는 이 위에 화면만 얹는다. 게이트는 기존 `AdminResourceAccess.canEdit`
(`studies → STUDY_EDIT`) 그대로라 새 권한도 없다.

### 토글을 읽고 쓰는 길

**쓰기 — `PUT /api/studies/recruitment`**, 본문 `{ "open": true }`, 게이트
`hasAuthority('STUDY_EDIT')`. 값이 하나뿐이라 부분 수정할 것이 없으므로 `PATCH` 가
아니라 `PUT` 이다.

**읽기 — `GET /api/studies` 의 응답을 감싼다.**

```json
{ "recruiting": true, "items": [ ... ] }
```

토글 전용 GET 은 만들지 않는다. 스터디 페이지도 관리자 '스터디 관리' 탭도 어차피
목록을 부르고, 모집 여부는 비밀이 아니다. `SecurityConfig` 변경도 필요 없다 —
`GET /api/studies` 는 이미 `permitAll` 이고 경로가 그대로다. ① 단계가 이 응답을
어차피 바꾸므로(항목에 `status`·`leaderGen` 추가) 감싸는 비용이 지금 가장 싸다.

## 5. 데이터 모델

### `Study` 변경

```
+ status         StudyStatus  5값. 기본 PENDING. **DB 컬럼은 nullable** (§11)
+ contact        String       문의처. 개설 시 필수 (자유 텍스트)
+ place          String       장소. 개설 시 필수
- approvalStatus ApprovalStatus  삭제 — status 로 접힌다 (D1)
- period         String       삭제 (D8)
```

`Study.approve()` 는 `status = RECRUITING`, `reject(reason)` 은 `status = REJECTED`
로 바뀐다. `com.jaram.be.study.ApprovalStatus` 는 지운다 —
`com.jaram.be.seminar.ApprovalStatus` 는 이름만 같은 별개 열거형이라 손대지 않는다.

`fields`(분야)·`schedule`(일시)·`mode`(방식)·`intro`(설명)·`capacity`(희망 인원)는
그대로 쓴다.

`setStatus` 세터가 필요하다 — D12 의 `s::setStatus` 가 쓴다.
`AdminResourceService.studyRow` 의 `s.getApprovalStatus().name()` 도 `getStatus()` 로
바뀌며, 이행 중 `null` 을 견뎌야 한다(§11).

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
`[1,2,4]` 같은 입력은 `422 VALIDATION`. (이 코드베이스의 검증 실패는 400 이 아니라
422 다 — `GlobalExceptionHandler` 가 `MethodArgumentNotValidException` 을
`UNPROCESSABLE_ENTITY` 로 돌리고, 계약의 `POST /api/studies` 도 `422` 를 선언한다.)

### 주차는 도중에 늘고 준다 (D14)

8주로 열었다가 6주에 접기도 하고, 시험 기간에 한 주 쉬어 9주가 되기도 한다.
주차 목록은 개설 시에 굳지 않는다.

| 동작 | 규칙 |
|---|---|
| 제목·내용 수정 | **언제나 가능.** 출석이 기록된 주차도 마찬가지 |
| 주차 추가 | **맨 뒤에만.** `weekNo = max + 1` |
| 주차 삭제 | **맨 뒤에서만.** 출석이 기록된 주차(`takenAt != null`)는 `409` |

**맨 뒤에서만 늘리고 줄인다.** 중간 삽입·삭제를 허용하면 뒤 번호를 당길지 말지를
정해야 하는데, 당기면 이미 출석이 기록된 "3주차"가 가리키던 모임이 슬그머니 바뀌고,
안 당기면 `[1,2,4]` 같은 구멍이 생겨 "가장 빠른 빈 주차"(②)가 흔들린다.

이 규칙 하나로 실제 시나리오가 다 덮인다 — **한 주 쉼**은 뒤에 한 주 더하기,
**일찍 접음**은 뒤에서 자르기다. 중간의 어떤 주에 뭘 했는지가 바뀌는 것은 제목·내용
수정으로 끝난다. 실제로 중간 주차를 통째로 들어내야 하는 일이 생기면 그때 규칙을
늘리는 편이, 지금 번호 재배열 로직을 만들어 두는 것보다 싸다.

주차 편집 API 는 ② 단계다(출석 없이는 삭제 제한을 검사할 대상이 없다).
① 단계는 개설 시의 최초 입력만 다룬다.

### `StudyStatus` 열거형 교체

```java
public enum StudyStatus {
    PENDING,      // 개설 승인 대기
    REJECTED,     // 개설 반려 (reason 이 채워진다)
    RECRUITING,   // 모집 중
    ONGOING,      // 진행 중
    FINISHED      // 종료
}
```

선언 순서가 곧 생애 순서다. `PENDING`/`REJECTED` 는 `ApprovalStatus` 에서 접어 온
값이고, 뒤 셋이 새로 생긴 값이다.

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
2. `status == RECRUITING` → 아니면 `409 RECRUIT_CLOSED`
   (축을 합쳤으므로 검사가 하나다 — 전에는 `approvalStatus` 까지 두 번 봐야 했다)
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

### 인원 카운터 — `정원 / 희망` (D15)

카운터는 **`정원 3 / 희망 8`** 로 읽는다.

| 표기 | 값 | 필드 |
|---|---|---|
| 정원 | 승인되어 실제로 들어온 인원 | `cur` |
| 희망 | 개설할 때 목표한 인원 | `cap` |

여기서 **'정원'은 상한이 아니라 확정 인원**이다. D9 으로 상한이라는 개념 자체가
사라졌으므로 `정원 9 / 희망 8` 도 정상이다. 흔한 용법과 반대라 화면 문구를 바꿀 때
주의한다.

**명단과 숫자가 안 맞는 것은 의도한 것이다.** 카운터는 `정원 3`, 명단은 대기 포함
5명일 수 있다. 보는 사람이 "2명은 대기중" 까지 셈할 수 있으나 **누가** 대기인지는
모른다. 카운터를 명단 길이에 맞추면 확정 인원이라는 운영 숫자가 사라진다.

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
`?status=FINISHED` 로만 나온다.

`PENDING`·`REJECTED` 는 **`?status=` 로도 나오지 않는다.** 남의 개설 신청과 반려 사유가
목록에 뜰 일은 없다 — 이 둘은 본인의 '내 스터디'에서만 보인다. 축을 합치면서 이 필터가
`status in (…)` 한 줄이 되었고, 전에는 `approvalStatus == APPROVED` 를 모든 조회에
같이 걸어야 했다.

### 상세 모달 (`GET /api/studies/{id}`)

제목 · 분야 칩 · 스터디장(기수·이름) · 설명 · 모집 인원 · 진행 방식(일시·장소·방식) ·
커리큘럼(주차별) · 문의 · **지원 인원 명단** · 신청하기 버튼.

계약 PR 이 BE 보다 먼저 머지되어야 하므로(§12) 응답 이름을 여기서 굳힌다 —
스키마 이름은 `StudyDetail`:

```json
{
  "id": "…", "title": "알고리즘 스터디",
  "fields": ["알고리즘", "Python"],
  "intro": "기초부터 백준 골드까지 함께 풉니다.",
  "leader": "이준호", "leaderGen": 40,
  "schedule": "매주 화 19:00", "place": "공학관 401", "mode": "오프라인",
  "contact": "010-0000-0000",
  "cur": 3, "cap": 8,
  "status": "RECRUITING", "apply": "OPEN",
  "weeks":  [ { "weekNo": 1, "title": "완전탐색", "content": "…" } ],
  "roster": [ { "studentId": "2022*****9", "gen": 40, "name": "이준호" } ]
}
```

| 필드 | 타입 | 비고 |
|---|---|---|
| `leader`·`leaderGen` | `string` · `int?` | 목록 카드와 같은 이름·같은 모양. `Member.gen` 은 nullable 이라 `null` 이 올 수 있다 |
| `schedule`·`place`·`mode`·`contact` | `string?` | 이행 이전 스터디는 `place`·`contact` 가 `null` (§11) |
| `cur`·`cap` | `int` | `정원 / 희망` (D15) |
| `weeks[]` | `{weekNo:int, title:string, content:string?}` | `weekNo` 오름차순 |
| `roster[]` | `{studentId:string, gen:int?, name:string}` | `studentId` 는 **마스킹된 값**(§7). 승인 상태 필드는 없다. 기수 → 이름 정렬 |

**이 엔드포인트는 인증이 필요하다.** `GET /api/studies`(목록)는 지금처럼 공개로 두고,
상세부터 로그인을 요구한다. 이유가 둘이다:

- D10 이 명단에 로그인을 요구한다. 로그인 경계를 "모달" 하나로 그으면 규칙이 하나다.
  명단만 비우고 나머지를 여는 것보다 읽기 쉽다.
- `SecurityConfig` 에 `GET /api/studies/*` 를 `permitAll` 로 넣으면 그 와일드카드가
  `/api/studies/my`·`/pending`·`/applicants` 까지 한 세그먼트로 잡는다. 권한 리팩터링이
  URL 매처를 걷어낸 이유가 정확히 이것이다 — 매처는 빠뜨려도 조용히 열리고,
  `AdminAuthorizationCoverageTest` 는 애너테이션만 지킨다. 매처를 다시 늘리지 않는다.

비로그인 방문자는 목록 카드까지 본다. 학회 홍보라는 목적에는 그것으로 충분하다.
**대신 화면은 비로그인일 때 카드 클릭을 모달이 아니라 로그인 안내로 보낸다** — 열어
두고 401 을 받게 하면 이유 없는 실패로 보인다.

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
| **모집 완료** | `@studyAccess.isLeader(#id, …)` **or** `hasAuthority('STUDY_EDIT')` | ① |
| **종료** | `@studyAccess.isLeader(#id, …)` **or** `hasAuthority('STUDY_EDIT')` | ① |
| 상태 임의 지정 | `AdminResourceAccess.canEdit` (`studies → STUDY_EDIT`) | ① |
| 신청 승인·반려 | `@studyAccess.isLeaderOfApplication(#id, …)` **or** `hasAuthority('STUDY_APPLICANT_MANAGE')` | ② |
| 모집 토글 | `hasAuthority('STUDY_EDIT')` — `PUT /api/studies/recruitment` | ① |

**`StudyAccess` 가 ① 로 앞당겨진다.** 원래 ② 단계에 두려던 소유자 조건 빈인데,
상태 전이 둘이 스터디장 손에 있으므로 ① 에서 필요해졌다.

```java
@Component("studyAccess")
public class StudyAccess {
    /** 경로 변수가 스터디 id 일 때 (모집 완료·종료·정보 편집·출석) */
    public boolean isLeader(String studyId, Authentication auth) { … }

    /** 경로 변수가 신청 id 일 때 (신청 승인·반려) — 신청 → 스터디 → leaderId */
    public boolean isLeaderOfApplication(String applicationId, Authentication auth) { … }
}
```

`SeminarAccessPolicy` 와 같은 모양이다. 스터디장은 `Role` 이 **아니다** —
`Role` 은 `member_term`(부서·직책)에서 파생되고, 스터디장은 스터디 한 건에 매인
관계라 그 축에 올리면 "누구의 스터디장인가" 가 사라진다. 세미나 재제출
(`SEMINAR_EDIT or @seminarAccess.isOwner`)이 이미 같은 자리를 이렇게 풀었다.

### 메서드가 둘인 이유 — 경로 변수가 가리키는 것이 다르다

신청 승인·반려는 `/api/studies/applicants/{id}/approve` 이고, 여기서 `{id}` 는
**신청 id** 다. 스터디 id 가 아니다. `isLeader(#id, …)` 를 그대로 걸면 신청 id 로
스터디를 조회하니 **언제나 `false`** 가 되고, 스터디장은 자기 스터디의 신청을 하나도
처리할 수 없다. 조용히 틀리는 종류의 버그다 — 403 만 나고 이유가 안 보인다.

`isLeaderOfApplication` 은 신청을 먼저 읽어 `studyId` 를 얻은 뒤 그 스터디의
`leaderId` 와 견준다. 한 단계를 더 타는 것이지 다른 판정이 아니다.

### 개설 승인에는 소유자 조건이 없다 — 의도한 것이다

표에서 '개설 승인·반려' 만 `or @studyAccess…` 가 붙지 않는다. 자기가 낸 개설 신청을
자기가 승인할 수 있으면 승인 절차 자체가 없는 것과 같다. 스터디장이라는 지위는
**승인된 뒤에 생긴다**.

### 스터디장이 사라지면 임원이 대신한다

졸업·탈퇴한 스터디장은 1층 자격 게이트(`Eligibility`)에서 401 로 막히므로
`isLeader` 까지 가지도 않는다. 그 스터디는 관리자가 없는 상태가 되는데, 모든
스터디장 동작에 `or hasAuthority('STUDY_EDIT')` 가 함께 걸려 있어 임원이 이어받는다.
**스터디장 이양 기능은 만들지 않는다** — 학기 단위로 끝나는 스터디에서 그 일이
얼마나 자주 일어나는지 아직 모르고, 임원 우회로 막히지 않는다.

한 사람이 여는 스터디 수에도 제한을 두지 않는다.

모집 토글은 `SettingsAccess` 를 지나지 않는다 — `AdminSettings` 에서 뺐다(§4).
`PUT /api/studies/recruitment` 가 `hasAuthority('STUDY_EDIT')` 하나로 갈린다.
`SettingsAccess.canApply` 는 이 단계에서 손대지 않는다.

이 변경으로 `STUDY_EDIT` 이 `AdminResourceAccess` 밖에서 처음으로 쓰인다.

## 10. 엔드포인트

### 새로

| 메서드 | 경로 | 권한 |
|---|---|---|
| `GET` | `/api/studies/{id}` | 인증 |
| `POST` | `/api/studies/{id}/close-recruiting` | 스터디장 or `STUDY_EDIT` (D13) |
| `POST` | `/api/studies/{id}/finish` | 스터디장 or `STUDY_EDIT` |
| `PUT` | `/api/studies/recruitment` | `STUDY_EDIT` — 모집 토글 (§4) |

전이 API 둘은 **현재 상태를 검사한다** — `close-recruiting` 은 `RECRUITING` 에서만,
`finish` 는 `ONGOING` 에서만 통과하고 아니면 `409 INVALID_STATE`. 상태를 건너뛰거나
되돌리는 것은 임원의 일괄 편집(D12)으로만 한다.

### 바뀌는 것

| 경로 | 변경 |
|---|---|
| `GET /api/studies` | 응답을 `{recruiting, items[]}` 로 감쌈. `?status=` 추가. 항목에 `intro`·`leaderGen` 추가, `period` 제거 |
| `POST /api/studies` | `place`·`contact`·`weeks[]` 필수, `period` 제거. 토글 OFF 면 `409` |
| `POST /api/studies/{id}/apply` | `status == RECRUITING` 검사 추가, 정원 검사 제거 |
| `POST /api/studies/{id}/approve` | `status = RECRUITING` |
| `POST /api/studies/{id}/reject` | `status = REJECTED` |
| `GET /api/studies/pending` | `findByStatus(PENDING)`. 응답에서 `period` 제거 |
| `GET /api/studies/my` | `MyStudy` 에서 `approvalStatus` 제거 — `status` 하나로 (D1) |
| `GET /api/admin/studies` | 행의 `approvalStatus` → `status` (`studyRow` 에 `period` 는 원래 없다) |
| `PATCH /api/admin/studies:batch` | `updateStudy` 가 `status` 를 받는다 (D12) |

`/api/studies/my`·`/pending`·`/applicants` 와 신청자 승인/거절은 ① 에서 손대지 않는다.
`my` 의 재정의는 ② 단계다.

## 11. 스키마 이행

`ddl-auto: update` 라서 **추가는 자동, 삭제는 수동**이다.

자동으로 되는 것: `study.contact`·`study.place` 컬럼 추가, `study_week` 테이블 생성,
`study_recruitment` 테이블 생성. **`study.status` 는 손으로 먼저 만든다**(아래).

`status`·`contact`·`place` 는 **DB 에서 nullable** 이다. 엔티티에
`@Column(nullable = false)` 를 쓰지 않는다. 필수 여부는 도메인 불변식과 요청 DTO 의
`@NotBlank` 가 지킨다 — 이행 이전 스터디는 `contact`·`place` 가 비어 있어도 화면에
"미등록" 으로 뜬다.

**NOT NULL 로 매핑하면 컬럼이 아예 안 생긴다.** 셋이 겹친다. (a) Postgres 는 행이 있는
테이블에 `ADD COLUMN … not null` 을 거부한다 — `column "status" of relation "study"
contains null values`. (b) Hibernate 의 `SchemaUpdate` 는 그 예외를 **로그 한 줄로
삼키고 넘어간다** (`GenerationTarget encountered exception accepting command`).
기동은 정상 종료되고 헬스체크도 통과한다. (c) 그래서 컬럼이 없는 채로 트래픽을 받아
모든 스터디 질의가 `column s1_0.status does not exist` 로 500 이 된다. 빈 목록보다
나쁘고, 원인은 이미 지나간 기동 로그에 있다.

손으로 돌릴 SQL (`docs/migrations/2026-09-15-study-lifecycle.sql`):

**축을 접는 것이 이 단계에서 가장 조심할 대목인데, 배포 파이프라인이 그 틈을 주지
않는다.** 배포는 `develop` push 한 번에 `compose pull + up` 이다
(`.github/workflows/image.yml` 의 `deploy-dev`). 컬럼 생성과 트래픽 수용이 **같은
컨테이너 기동**이라 "`ddl-auto` 가 만든 직후, 새 코드가 받기 전" 이라는 창이 아예
존재하지 않는다.

→ **`ddl-auto` 보다 먼저 손으로 컬럼을 만들고 채운다.** `update` 는 이미 있는 컬럼을
건드리지 않으므로, 배포가 와도 이 컬럼에 대해서는 아무 DDL 도 내지 않는다.

**배포 전** (BE PR 을 `develop` 에 머지하기 **전**, 운영 DB 에 직접):

```sql
-- 1. 컬럼을 손으로 만든다. not null 도, check 제약도 붙이지 않는다.
--    Hibernate 6.2+ 는 enum 컬럼을 만들 때 값 목록 check 를 같이 만들지만, 이미
--    있는 컬럼에 뒤늦게 붙이지는 않는다. 손으로 붙여 두면 나중에 enum 값이 늘 때
--    update 가 그 제약을 고쳐 주지 않아 INSERT 가 막힌다.
ALTER TABLE study ADD COLUMN status varchar(255);

-- 2. 개설 승인축을 생애축으로 접는다. 승인된 것은 진행 중으로 본다.
UPDATE study SET status = 'PENDING'  WHERE approval_status = 'PENDING';
UPDATE study SET status = 'REJECTED' WHERE approval_status = 'REJECTED';
UPDATE study SET status = 'ONGOING'  WHERE approval_status = 'APPROVED';

-- 3. 0 이 아니면 배포하지 않는다.
SELECT count(*) FROM study WHERE status IS NULL;
```

**배포 후** (코드가 두 컬럼을 읽지 않는 것을 확인한 뒤):

```sql
ALTER TABLE study DROP COLUMN approval_status;
ALTER TABLE study DROP COLUMN period;
```

먼저 지우면 구버전 인스턴스가 뜨는 동안 매핑이 깨진다. 반대로 3번을 확인하지 않고
배포하면 그 스터디들은 `status = NULL` 이라 `status in (…)` 필터에 하나도 걸리지 않고
목록에서 조용히 사라진다 — **컬럼 생성과 backfill 이 배포보다 먼저다.**

승인된 스터디에 `ONGOING` 을 고르는 것은 보수적인 선택이다. 지난 학기 것들은 사실
`FINISHED` 에 가깝지만, 틀리면 목록에서 사라져 눈에 안 띈다. `ONGOING` 으로 두면
`전체` 칩에 남아 있으니 임원이 보고 D12 의 일괄 편집으로 내릴 수 있다. **안 보이는
쪽으로 틀리는 것보다 보이는 쪽으로 틀리는 편이 고치기 쉽다.**

## 12. 계약과 배포 순서

`openapi.yaml` 은 home-jaram-fe 에만 있고 BE 의 두 경로는 그 파일로의 심볼릭 링크다.
BE CI 는 **BE 브랜치와 같은 이름의 FE 브랜치**에서 스펙을 집고, 없으면 조용히
FE `develop` 으로 떨어진다. 검증기는 스키마에 없는 응답 필드를 거부한다.

① 단계는 필드를 **더하기도 하고 빼기도 한다**(`period` 제거). 그래서:

1. home-jaram-fe 에 `feat/study-lifecycle` 브랜치를 같은 이름으로 만든다.
2. 계약 PR 을 먼저 머지한다.
3. **운영 DB 에 `status` 컬럼을 손으로 만들고 backfill 한다** (§11 의 '배포 전' SQL).
4. BE PR 을 머지한다 — 여기서 배포가 자동으로 돈다.
5. 배포 후 `approval_status`·`period` 컬럼을 떨어뜨린다.

**계약에서 지우는 것은 `MyStudy.approvalStatus` 참조 한 줄뿐이다.** `ApprovalStatus`
스키마 자체는 남긴다 — 세미나가 같은 스키마를 두 곳에서 쓴다(`approvalStatus`,
`seminarApprovalStatus`). "축을 합쳤으니 `ApprovalStatus` 를 지운다" 로 읽으면 세미나
계약이 깨진다. 통째로 갈리는 것은 `StudyStatus` 쪽이다 — `CLOSED` 제거,
`PENDING`·`REJECTED`·`FINISHED` 추가.

## 13. 테스트

| 대상 | 검증 |
|---|---|
| 상태 전이 | 승인 → `RECRUITING`, 반려 → `REJECTED` + `reason` (토글 값과 무관) |
| 축 접기 | 목록·상세·신청 어디에도 `PENDING`·`REJECTED` 스터디가 새지 않음 |
| 토글 격리 | `StudyRecruitment.open` 을 `true↔false` 로 바꿔도 **어떤 스터디의 `status` 도 안 바뀜** |
| 모집 완료 | 스터디장이 `close-recruiting` → `ONGOING`; 남이 부르면 `403` |
| 잘못된 전이 | `ONGOING` 에 `close-recruiting`, `RECRUITING` 에 `finish` → `409 INVALID_STATE` |
| 임원 우회 | `STUDY_EDIT` 을 가진 임원은 남의 스터디에도 두 전이를 부를 수 있음 |
| 소유자 격리 | 다른 스터디의 스터디장이 내 스터디에 두 전이를 부르면 `403` |
| 자기 승인 차단 | 개설 신청자가 자기 신청에 `approve` → `403` (`STUDY_APPROVE` 없으면) |
| 개설 차단 | 토글 OFF 에서 `POST /api/studies` → `409` |
| 신청 시점 | `ONGOING`·`FINISHED` 스터디에 신청 → `409 RECRUIT_CLOSED` |
| 정원 제거 | 승인 인원이 `capacity` 를 넘어도 신청·승인이 통과 |
| `ApplyState` | 5가지 경우(미인증/OPEN/APPLIED/JOINED/CLOSED) 각각 |
| 마스킹 | 8자리·10자리 각각, 그리고 `roster` 에 전체 학번이 없음 |
| 명단 규칙 | 반려 제외, 스터디장 제외, 기수·이름 정렬, 상태 필드 부재 |
| 상세 인증 | 비로그인 `GET /api/studies/{id}` → `401`; 목록은 `200` |
| 토글 권한 | 학술부장(`ACADEMIC_LEAD`)이 `PUT /api/studies/recruitment` → `200`; 홍보부장(`PR_LEAD`)·일반 멤버 → `403` |
| 커리큘럼 | 빈 `weeks[]` → `422`; `weekNo` 가 `[1,2,4]` → `422` |
| 임원 편집 (D12) | `PATCH :batch` 로 `FINISHED → RECRUITING` 처럼 임의 전이가 통과; 없는 값은 행별 `errors` 로 돌아온다 |

### 애너테이션 누락은 무엇이 잡는가

지금은 아무것도 잡지 않는다. `AdminAuthorizationCoverageTest` 는 **`/api/admin` 으로
시작하는 핸들러만** 본다:

```java
if (patterns.stream().noneMatch(p -> p.startsWith("/api/admin"))) return;
```

`close-recruiting`·`finish`·`recruitment` 는 `/api/studies` 아래라 이 그물에 안 걸린다.
`SecurityConfig` 의 마지막 줄이 `anyRequest().authenticated()` 이므로 `@PreAuthorize`
를 빠뜨리면 **로그인한 아무나 남의 스터디를 종료**시킬 수 있다. 그물이 있다고 적어 둔
자리에 그물이 없었다.

**접두사를 넓히지 않고 기본값을 뒤집는다.** 지켜야 할 경로를 테스트에 열거하는 방식은
애너테이션을 잊는 것과 똑같이 목록을 잊을 수 있어서, 잊었을 때 조용한 성질이 그대로
남는다. 대신 **"인증만으로 통과하는 것이 의도인 핸들러"** 를 명시적 allowlist 로 적고,
그 밖의 모든 핸들러에 `@PreAuthorize` 를 요구한다:

```java
/** 로그인만으로 통과하는 것이 의도인 핸들러. 여기 없으면서 @PreAuthorize 도 없으면 실패. */
private static final Set<String> AUTHENTICATED_ONLY = Set.of(
        "StudyController#apply", "StudyController#my", "StudyController#detail", …);
```

애너테이션 없이 새 엔드포인트를 추가하면 테스트가 **기본적으로** 깨진다. 작성자는
게이트를 달거나 allowlist 에 한 줄을 더하면서 "이건 로그인한 아무나 해도 된다" 를
눈으로 확인하게 된다. 그 목록 자체가 "누가 권한 없이 통과하는가" 의 표라서 리뷰에서도
읽힌다. 이름은 `AuthorizationCoverageTest` 로 바꾼다 — 더 이상 관리자 전용이 아니다.

### 다시 쓰는 기존 테스트

새로 쓰는 것 말고 **철거·개작**이 583줄 있다. 계획에서 이 몫을 빠뜨리지 않는다.

| 파일 | 줄 | 무엇이 깨지나 |
|---|---|---|
| `study/StudyTest.java` | 318 | `CAPACITY_FULL`·정원 기반 `RECRUIT_CLOSED`·`apply == "CLOSED"` 단언을 D9 이 전제째 지운다. `studies[0].approvalStatus` 단언은 D1 |
| `contract/StudyContractTest.java` | 141 | 목록 응답이 배열에서 `{recruiting, items[]}` 로 바뀐다 |
| `study/StudyPermissionTest.java` | 70 | 새 엔드포인트 셋의 게이트가 는다 |
| `study/StudyRepositoryTest.java` | 54 | `findByApprovalStatusOrderByCreatedAtDesc` 를 직접 부른다 |

## 14. 범위 밖 — ②·③ 단계

**② 운영** — '내 스터디' 탭과 **'관리하기' 모달**. 이 모달은 상태에 따라 다른 것을 연다:

| 스터디 상태 | 스터디장이 보는 것 | 일반 멤버가 보는 것 |
|---|---|---|
| `모집 중` | 신청 승인·반려 목록 + 우측 하단 **'모집 완료'** | (신청 대기 카드만, 모달 없음) |
| `진행 중` | `출석` / `정보` 탭 + '종료' | 자기 주차별 출석 현황 |
| `종료` | '내 스터디'에서 사라짐 | 사라짐 |

함께 들어가는 것: 스터디장이 신청을 승인·반려(`STUDY_APPLICANT_MANAGE` 에
소유자 조건 추가), `StudyWeek.takenAt` 과 `StudyAttendance` 신설(존재=출석),
첫 저장 +24h 편집 창, 주차 추가·삭제 API(D14), 반려 신청 '삭제하기'(D11).

① 이 만들어 둔 `close-recruiting`·`finish` 에 버튼만 붙인다.

**③ 임원 도구** — 관리자 '스터디 관리' 탭, 모집 토글 UI, 표(스터디명·스터디장·인원·
출석률·상태·상세·삭제), 상세 편집 **화면**(상태 편집 API 자체는 D12 로 ① 에 있다),
멤버 직접 추가
(진행 중 합류 시 지난 주차는 결석 — 존재=출석 모델에서 저절로 성립한다).

**단계 경계가 초안에서 바뀌었다.** 상태를 움직이는 API 셋(`close-recruiting`,
`finish`, 일괄 편집의 `status`)과 `StudyAccess` 가 ② ③ 에서 ① 로 올라왔다.
토글이 전이를 몰지 않게 되면서, 상태 기계를 완성하는 일이 곧 ① 의 일이 되었기
때문이다. ②·③ 은 그 위에 화면을 붙인다.

**아예 안 하는 것** — 스터디 채팅·파일 공유·과제 제출. 이번 재설계에 없다.
