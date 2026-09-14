# 학기 전환 재등록 설계

**작성일:** 2026-09-07
**선행 문서:** `2026-08-01-member-model-realignment-design.md`, `2026-07-20-member-axis-dedup-design.md`
**선행 작업:** BE PR #8 (`feat/settings-period`) — 학기 계산(`AdminSettings.autoTerm`)이 여기 들어 있다. 먼저 머지되어야 한다.

## 1. 목표

학기가 넘어가면 활동 회원이 자동으로 '재등록 필요'가 되고, 재등록하지 않은 회원은
다음 학기에 정리된다. 지금은 학기가 바뀌어도 아무 일도 일어나지 않아, 명단이 몇 해에
걸쳐 떠난 사람을 그대로 안고 있다.

곁들여 본인 탈퇴 경로를 만들고, 탈퇴한 회원의 개인정보를 6개월 뒤 파기한다.

## 2. 범위

| | 포함 | 제외 |
|---|---|---|
| 도메인 | `MemberStatus.REREGISTER` 신설, 회원 3칸·설정 2칸 추가 | 신학기 자동 승급(`autoPromote`) — 별개 기능 |
| 잡 | 하루 한 번 도는 스윕 (학기 전환 + 파기) | 다중 인스턴스 락 (멱등이라 불필요) |
| 계약 | 상태 enum 값, `PendingMember` 확장, 엔드포인트 3개 | — |
| FE | 인원 관리 기본 필터, 가입 신청·승인 탭, 프로필 탈퇴 버튼 | **재등록 팝업 — 다음 스펙** |

재등록 팝업(화면 중앙 공지)은 이 문서가 정한 상태 위에 얹으면 되므로 분리한다.

## 3. 결정과 근거

설계 중 갈린 지점과 고른 이유를 남긴다. 나중에 "왜 이렇게 했나"를 다시 묻지 않기 위해서다.

| 갈림길 | 선택 | 이유 |
|---|---|---|
| 상태를 어디에 둘까 | `MemberStatus`에 값 하나 추가 | 상태가 DB에 박혀 있어 운영자가 표만 봐도 안다. 축을 늘리지 않는다 |
| 휴학생도 대상인가 | **면제** | 한 칸에 값 하나만 들어가므로 "휴학이면서 재등록 필요"를 못 쓴다. `previousStatus` 칸을 더하면 활동축에 '지금 값'과 '예전 값'이 같이 살게 된다 |
| 재등록 완료 시점 | 신청 → 임원 승인 (2단계) | 가입 승인과 같은 모양. 임원의 '재등록' 버튼이 제 역할을 한다 |
| 무엇을 지우나 | 개인정보 파기, 이력 보존 | 임기 이력과 출석·신청 기록의 참조가 끊기지 않는다. 6개월 보관 기간 관점과도 맞는다 |
| 재등록 전 권한 | 조회 O, 신청 X | 팝업을 닫아도 재등록할 이유가 남는다 |
| 스윕 주기 | 하루 한 번 | 학기 전환만 있으면 주 1회로 충분하다. 주기를 정하는 건 탈퇴 6개월 파기 쪽이고, 그 정밀도의 하한이 하루다 |

## 4. 상태 모델

```java
public enum MemberStatus { ACTIVE, ON_LEAVE, REREGISTER, WITHDRAWN }
```

`@Enumerated(STRING)`이라 값을 끼워 넣어도 저장된 데이터에 영향이 없다.

```
ACTIVE ──(학기 전환 스윕)──> REREGISTER ──(임원 승인)──> ACTIVE
                                 │
                                 ├─(임원 삭제)────> 파기
                                 └─(다음 전환)────> 파기

ACTIVE/ON_LEAVE ──(본인 탈퇴)──> WITHDRAWN ──(6개월)──> 파기
```

전환 대상은 셋을 모두 만족하는 회원이다.

```
status == ACTIVE
grade != OB
현직 임기 없음 (currentTerm().isEmpty())
```

휴학·OB·현직 임원은 손대지 않는다. 휴학생은 복학할 때 임원이 상태를 `ACTIVE`로 바꿔
주어야 다음 전환부터 대상이 된다.

### 4.1 추가 칼럼

`member` (3칸, 전부 nullable):

| 칸 | 뜻 |
|---|---|
| `reregisterRequestedAt` | 재등록 신청 시각. `null`이면 미신청 |
| `withdrawnAt` | 탈퇴 시각. 6개월 파기의 기준 |
| `purgedAt` | 개인정보 파기 시각. 이력만 남은 회원 표시 |

`admin_settings` (2칸):

| 칸 | 뜻 |
|---|---|
| `lastRolloverYear` | 마지막으로 전환을 실행한 학년도 |
| `lastRolloverTerm` | 그 학기 (1\|2) |

둘 다 `null`이면 아직 한 번도 돌지 않았다는 뜻이다. 처리는 §8을 본다.

### 4.2 학기 값 객체

`com.jaram.be.admin`에 둔다. 학기를 아는 건 이미 이 패키지다.

```java
record Semester(int year, int term) implements Comparable<Semester> {
    static Semester autoAt(LocalDate on) {
        int m = on.getMonthValue();
        if (m >= 3 && m <= 8) return new Semester(on.getYear(), 1);
        if (m >= 9)           return new Semester(on.getYear(), 2);
        return new Semester(on.getYear() - 1, 2);   // 1~2월은 직전 2학기의 연장
    }
}
```

**1~2월 처리가 이 설계에서 가장 조용한 함정이다.** `AdminSettings.autoTerm`은 1월에
`2`를 돌려주는데, 연도를 그냥 `today.getYear()`로 붙이면 2025년 10월은 `(2025, 2)`,
2026년 1월은 `(2026, 2)`가 되어 **1월 1일에 학기가 넘어간 것으로 판정된다.** 전원이
재등록 대상이 된다. 학기 경계는 3월 1일과 9월 1일이지 1월 1일이 아니다.

**스윕은 `Semester.autoAt`만 쓴다.** 설정 탭의 학기 override는 표시와 임기 전환에만
쓰고 스윕은 보지 않는다 — 4월에 실수로 2학기를 눌렀다고 사람이 지워지면 안 된다.

## 5. 스윕

`@EnableScheduling`이 새로 붙는다. 이 프로젝트의 첫 스케줄러다.

```java
@Scheduled(cron = "0 0 4 * * *")   // 매일 04:00
public void sweep() { sweep(LocalDate.now()); }

void sweep(LocalDate today) { ... }   // 테스트가 부르는 진입점
```

시계를 직접 읽는 메서드와 오늘을 인자로 받는 메서드를 나눈다. `AdminSettingsPeriodTest`가
이미 쓰는 방식이고, 안 그러면 3월 1일과 9월 1일에만 깨지는 테스트가 된다.

```
now = Semester.autoAt(today)
last = 설정의 lastRollover

1. last == null:
     lastRollover = now 로 초기화하고 전환은 건너뛴다   ← §8
2. now > last:
     a. status == REREGISTER 인 회원을 파기          ← 먼저
     b. 전환 대상(§4)을 REREGISTER 로
     c. lastRollover = now
3. status == WITHDRAWN 이고 withdrawnAt <= today.minusMonths(6) 이고
   purgedAt == null 인 회원을 파기
```

**a가 b보다 먼저**라서 "한 학기를 더 못 넘긴다"가 자연스럽게 나온다. 회원마다 등록
학기를 저장할 필요가 없다.

비교는 `now > last`다. `!=`가 아니다 — 시계가 뒤로 갔을 때 전환이 다시 돌면 안 된다.

전환이 실제로 일하는 건 1년에 두 번이고, 나머지 날은 설정 로우 한 줄을 읽고 끝난다.
서버가 며칠 꺼져 있어도 켜질 때 밀린 전환을 따라잡는다. 인스턴스가 여럿이면 같은 날
여러 번 돌 수 있지만, `lastRollover` 비교가 멱등해서 무해하다.

## 6. 파기

이력 유무로 갈린다. 이력이 있다는 건 `contributor == true || !terms.isEmpty()`다.

| 조건 | 처리 |
|---|---|
| 이력 있음 | 이름·`gen`·임기 이력을 남기고 나머지 개인정보를 지운다. `purgedAt` 기록 |
| 이력 없음 | 행 삭제 |
| 스터디 리더 | **건너뛴다** |

지우는 칸: `studentId` `email` `passwordHash` `phone` `faculty` `bio` `githubUrl` `blogUrl`.

`email`이 `null`이 되면 `findByEmail`로 찾히지 않아 로그인이 막힌다. `passwordHash`도
비운다. `studentId`·`email`은 UNIQUE 제약이 걸려 있지만 PostgreSQL은 `NULL`을 중복으로
보지 않아 여러 행이 동시에 비어 있어도 된다.

행을 삭제하는 경우 참조 정리가 필요하다. 기존 `AdminBatchExecutor.delete`가 `attendance`와
`study_application`을 지우는데, **`schedule_slot.memberId`와 `seminar.createdById`는
손대지 않는다.** 이 프로젝트는 회원을 가리키는 참조 대부분이 FK 없는 `varchar`라 DB가
막아 주지 않는다. 지금은 삭제가 드물어 드러나지 않지만 스윕이 돌면 정기적으로 생기므로,
이 두 곳을 `null`로 끊는 처리를 더한다. 세미나 자체는 남긴다.

스터디 리더는 지금도 삭제가 막혀 있다("스터디 리더인 회원은 삭제할 수 없습니다").
스윕은 건너뛰고 `REREGISTER`인 채로 남긴다. 그러면 가입 신청·승인 탭에 계속 보여
임원이 직접 판단한다. 다음 전환에서 다시 시도하지만 결과는 같고 부작용은 없다.

**파기된 회원은 어떤 목록에도 나오지 않는다.** 이력을 남기는 목적은 임기 기록과
출석·신청 기록의 참조가 끊기지 않는 것이지 공개 노출이 아니다.

### 6.1 삭제 의미는 하나다

임원이 누르는 삭제(가입 신청·승인 탭의 '삭제', 인원 관리 탭의 행 삭제)도 위와 같은
규칙을 쓴다. `AdminBatchExecutor.delete`의 `members` 갈래가 무조건 행을 지우던 것을,
이력이 있으면 파기하고 없으면 지우도록 바꾼다.

**이건 기존 동작의 변경이다.** 지금까지는 임기 이력이 있는 회원을 삭제하면 이력까지
사라졌다. 그대로 두면 "임원이 누른 삭제"와 "스윕이 한 파기"가 같은 버튼 이름으로 다르게
동작하게 되고, 어느 쪽이 돌았느냐에 따라 기여자 목록이 달라진다. 규칙은 하나여야 한다.

스터디 리더 차단은 그대로 둔다.

## 7. 조회 규칙

| 화면 | 어디서 거르나 | 지금 | 바뀐 뒤 |
|---|---|---|---|
| 사람들 탭 | `PeopleService.list` | `status != WITHDRAWN` | `status in (ACTIVE, ON_LEAVE) && purgedAt == null` |
| 인원 관리 | 서버가 `purgedAt`, **FE**가 나머지 | `approval == APPROVED` (FE) | 서버가 `purgedAt != null`을 빼고, FE가 기본 `status in (ACTIVE, ON_LEAVE)` |
| 가입 신청·승인 | `AdminMemberService.listPending` | `approval == PENDING` | 위 + `status == REREGISTER` |

`AdminResourceService.list`는 `tab`·`q`·`sort`·`page`만 처리하고 등급·기수·상태 필터는
FE가 전부 받아서 건다(`admin.api.js:158-166` 주석에 명시). 그러니 서버는 `REREGISTER`
행을 **계속 내려줘야 하고**, 빼는 건 `purgedAt != null`뿐이다.

FE의 기본 필터 규칙:

- 상태 필터가 '전체'이거나 없으면 → 활동·휴학만
- '재등록'을 고르면 → 재등록만
- '탈퇴'를 고르면 → 탈퇴만

'전체'에서도 재등록·탈퇴가 안 보인다. 명시적으로 골라야 나온다.

`PeopleService`가 쓰는 `status != WITHDRAWN`은 `!=` 비교라 값이 늘어도 컴파일 에러가
나지 않는다. 이 자리를 놓치면 재등록 필요 회원이 사람들 탭에 계속 노출된다. 조회 규칙
셋 모두 테스트로 못 박는다.

## 8. 배포와 마이그레이션

DDL은 `ddl-auto: update`가 만든다. 추가되는 5칸은 전부 신규 nullable이라 기존 행에
영향이 없고, **백필 SQL이 필요 없다.**

위험은 한 곳뿐이다. `lastRollover`가 `null`인 채로 첫 스윕이 돌면 "마지막 전환 학기를
모른다"가 "지금 전환해야 한다"로 읽혀 **전원이 재등록 필요가 된다.** 그래서 §5의 1번이
초기화만 하고 전환을 건너뛴다. 규칙이 실제로 도는 건 다음 학기 경계부터다.

기존 회원의 상태는 손대지 않는다. `ACTIVE`인 회원이 다음 3월 1일 또는 9월 1일에
처음으로 `REREGISTER`가 된다.

## 9. 계약 변경

FE `docs/api/openapi.yaml`이 단일 진실원이다. BE 쪽 사본은 심링크이므로 건드리지 않는다.

```
MemberStatus     ← REREGISTER 추가
PendingMember    ← kind (SIGNUP|REREGISTER), requestedAt ([string,'null'], 미신청이면 null)

POST /api/me/reregister                    회원 재등록 신청 → 204
POST /api/me/withdraw                      본인 탈퇴 → 204
POST /api/admin/members/{id}/reregister    임원 승인 → 204
```

- `POST /api/me/reregister` — `status != REREGISTER`면 409. `reregisterRequestedAt = now`.
- `POST /api/me/withdraw` — `status = WITHDRAWN`, `withdrawnAt = now`. 현직 임기가 있으면 409(임기를 먼저 정리해야 한다).
- `POST /api/admin/members/{id}/reregister` — `status = ACTIVE`, `reregisterRequestedAt = null`. `/approve`와 같은 모양.

- 재등록 신청은 멱등하다. 이미 신청했으면 시각을 덮지 않고 204를 돌려준다.

삭제는 새 엔드포인트를 만들지 않는다. 승인 탭의 삭제 버튼은 기존
`PATCH /api/admin/members:batch`의 `deletes`를 쓰고, 그 경로가 §6.1대로 바뀐다.

`MeProfile`에는 이미 `status`가 있어 팝업이 나중에 읽을 값은 계약 변경 없이 그대로 쓴다.

인원 관리 표의 상태 select에서 `REREGISTER`는 **고를 수 없다.** `AdminBatchExecutor`의
`updateMember` 화이트리스트에서 이 값만 거부한다("재등록 상태는 직접 지정할 수
없습니다"). 손으로 고르면 스윕과 어긋난 상태가 만들어진다.

권한은 기존 규칙이 그대로 덮는다 — `/api/admin/**`는 `OFFICER`, `/api/me/**`는
`authenticated`.

## 10. 신청 차단

`status == REREGISTER`면 403 (`REREGISTRATION_REQUIRED`, "재등록이 승인되어야 이용할 수
있습니다"). 대상은 회원용 쓰기 다섯 곳이다.

```
POST /api/seminars/{id}/attend                     출석
POST /api/schedules/{id}/slots/{index}/claim       슬롯 점유
POST /api/schedules/{id}/slots/{index}/seminar     세미나 제출
POST /api/studies                                  스터디 개설
POST /api/studies/{id}/apply                       스터디 신청
```

슬롯 점유 **취소**(`DELETE /api/schedules/{id}/slots/{index}`)와 프로필 수정
(`PATCH /api/me`)은 막지 않는다. 조회는 전부 열려 있다. 임원 전용 경로는 대상이 아니다
— 현직 임원은 애초에 전환에서 면제된다.

검사는 각 서비스 진입부에서 한다. 경로 기반 필터로 하면 위 다섯 개와 그 예외를
`SecurityConfig`에 문자열로 다시 적어야 하고, 경로가 바뀔 때 조용히 어긋난다.

## 11. 테스트

스윕은 `sweep(LocalDate today)`를 직접 불러 검증한다.

- 전환 규칙 표: 활동·휴학·OB·현직 임원 × 전환 전후
- 학기 경계: 2월 28일 → 3월 1일, 8월 31일 → 9월 1일에 전환된다
- **1월 1일에는 전환되지 않는다** (§4.2의 함정)
- 멱등성: 같은 학기에 두 번 돌려도 한 번만 전환한다
- 밀린 전환: 두 학기를 건너뛴 뒤 첫 실행
- `lastRollover == null` 초기화가 전환을 일으키지 않는다
- 신청 → 승인 → `ACTIVE` 복귀, `reregisterRequestedAt`이 비워진다
- 파기: 이력 있음(칸이 비워지고 임기가 남는다) / 이력 없음(행이 사라진다) / 스터디 리더(남는다)
- 파기 후 `schedule_slot.memberId`와 `seminar.createdById`가 끊긴다
- 탈퇴 6개월 경계: `withdrawnAt + 6개월` 하루 전에는 살아 있고, 그날에는 파기된다
- 조회 세 화면, 신청 차단 다섯 곳, 상태 select의 `REREGISTER` 거부

## 12. 열린 문제

**설정 탭의 학기 표시와 스윕의 학기가 1~2월에 어긋난다.** `AdminSettingsResponse`는
`semesterYear`로 `today.getYear()`를 그대로 보내므로, 2026년 1월에 화면은
"2026년 2학기"라고 하는데 스윕은 `(2025, 2)`로 본다. 학기 경계 판정은 스윕 쪽이 옳다.

이 문서에서는 고치지 않는다. 표시 값을 바꾸면 설정 화면과 계약이 함께 움직여야 하고,
그건 PR #8이 다룬 범위다. `Semester.autoAt`을 도입한 뒤 `AdminSettingsResponse`가
그것을 쓰도록 맞추는 걸 후속으로 남긴다.
