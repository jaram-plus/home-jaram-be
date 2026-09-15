# 스터디 설계 ② — 운영과 출석

작성일 2026-09-15. 대상 브랜치 `feat/study-operations`.
전체 3단계 중 **② 단계**. ① 은 `2026-09-15-study-lifecycle-design.md`, ③ 은 §16 에
범위만 적어 둔다.

## 1. 문제

① 이 상태 기계를 만들고 그것을 움직이는 손잡이까지 주었다. `close-recruiting`,
`finish`, 신청 승인·반려에 소유자 조건, 커리큘럼 주차, 모집 토글이 전부 돌아간다.
그런데 **그 손잡이가 붙을 화면이 없다.** 스터디장은 API 를 직접 부르지 않는 한
자기 스터디의 모집을 닫을 수도, 종료할 수도 없다.

더 앞선 구멍이 있다.

- **스터디장이 자기 스터디의 신청 목록을 볼 길이 없다.** `GET /api/studies/applicants`
  는 임원용 전체 목록(`STUDY_APPLICANT_MANAGE`)이고, `StudyDetail.roster` 는 D10 에
  따라 승인 여부를 **의도적으로 숨긴다**. ① 은 승인·반려 손잡이만 주고 목록을 주지
  않았다. 누구를 승인할지 모르는 채로 승인 버튼만 있다.
- **출석이 없다.** `StudyWeek` 은 ① 이 만들어 두었지만 아무도 그 주차에 누가 왔는지
  기록하지 않는다. 스터디가 `ONGOING` 으로 들어간 뒤에 시스템이 보는 것이 없다.
- **주차가 개설 시점에 굳어 있다.** D14 가 "주차는 도중에 늘고 준다"를 정했지만 편집
  API 는 ② 로 미뤄졌다 — 출석이 없으면 삭제 제한(`takenAt != null`)을 검사할 대상이
  없기 때문이다. 이제 대상이 생긴다.
- **반려당한 사람이 재신청할 수 없다.** D11 이 '삭제하기'로 신청 행을 하드 삭제해
  `(studyId, applicantId)` 유니크를 푸는 것으로 정했는데, 그 삭제 API 가 없다.
- **'내 활동' 화면이 계약과 어긋나 있다.** `MyActivityView` 가 `item.message`·`tone`·
  `badge` 를 읽는데 계약 `MyApp`/`MyStudy` 는 `{id, studyId, title, status, reason}`
  을 준다. ② 이전부터 있던 어긋남이고, 이 단계가 그 화면을 지나간다.

② 단계는 **스터디가 굴러가는 동안 일어나는 일 전부** — 신청을 받고, 출석을 찍고,
커리큘럼을 조정하고, 그것들을 보는 화면까지다. 임원 도구(③)는 다음 단계다.

## 2. 결정 요약

번호는 ① 의 D1~D15 를 잇는다. 근거는 각 절에 있다.

| # | 항목 | 결정 |
|---|---|---|
| D16 | 출석 기록 주체 | **스터디장이 주차별 명단에서 체크**한다. 출석 코드를 쓰지 않는다 |
| D17 | 출석 쓰기 모양 | **주차 단위 전체 교체** — `PUT .../weeks/{weekNo}/attendance {present:[…]}` |
| D18 | 출석 행이 매이는 곳 | `studyId + weekNo` 가 아니라 **`weekId`** |
| D19 | 편집 창 | 첫 저장 **+24h**. 지나면 `409`. `STUDY_EDIT` 을 가진 임원은 창을 무시한다 |
| D20 | 출석률 분모 | **`takenAt != null` 인 주차 수.** 아직 안 찍은 주차는 결석이 아니다 |
| D21 | 출석 대상 | 승인된 신청자 **+ 스터디장** |
| D22 | 탭 | '내 활동' 을 **'내 스터디'** 로 개명하고 카드 화면으로 다시 그린다. 탭 수는 셋 그대로 |
| D23 | 관계 | `LEADER`/`MEMBER`/`APPLIED`/`REJECTED` 를 **일급 값**으로 응답에 싣는다 |
| D24 | `/my` 응답 | 두 배열(`apps`·`studies`)을 **단일 `items` 배열**로 접는다. `MyApp`·`MyStudy` 폐기 |
| D25 | 반려 신청 삭제 | `DELETE /api/studies/applicants/{id}`. 본인만, `REJECTED` 만 |
| D26 | 스터디장의 신청 목록 | `GET /api/studies/{id}/applicants` **신설** |
| D27 | 이행 SQL | **없다.** 새 테이블은 빈 신설이고 새 컬럼은 nullable 이다 |

## 3. 관계 — 이 단계의 축

'내 스터디'의 모든 분기는 **내가 이 스터디와 무슨 사이인가** × **스터디가 지금
어디인가** 둘로 결정된다. 그래서 관계를 파생시켜 화면에 던지지 않고, 서버가 이름을
붙여 내려보낸다(D23).

| 값 | 뜻 | 어디서 나오나 |
|---|---|---|
| `LEADER` | 내가 개설했다 | `study.leader_id == me` |
| `MEMBER` | 내 신청이 승인됐다 | `study_application.status == APPROVED` |
| `APPLIED` | 내 신청이 대기 중이다 | `… == PENDING` |
| `REJECTED` | 내 신청이 반려됐다 | `… == REJECTED` |

한 사람이 한 스터디에 대해 갖는 관계는 **언제나 하나**다. 개설자는 자기 스터디에
지원할 수 없고(① `LEADER_SELF`), 신청은 `(studyId, applicantId)` 유니크다.

`ApplyState`(① §5)와 헷갈리지 않아야 한다. `ApplyState` 는 **둘러보기 화면에서
신청 버튼이 무엇을 말할지**를 정하는 값이고, `relation` 은 **내 스터디 화면에서
이 카드가 무엇을 보여줄지**를 정하는 값이다. 뜻이 겹치는 자리가 있지만
(`JOINED` ⊃ `LEADER`+`MEMBER`), 합치면 한쪽의 필요가 다른 쪽을 왜곡한다 —
`ApplyState` 는 스터디장과 참여자를 구분할 이유가 없고, `relation` 은 반드시 구분한다.

**`FINISHED` 스터디는 어느 관계든 '내 스터디'에 오지 않는다**(① §14). 끝난 것이
계속 쌓이면 이 화면이 이력 목록이 된다.

## 4. 출석 — 존재가 곧 출석이다

세미나의 `Attendance`(`seminar/Attendance.java`)가 이미 이 모양이다. 같은 모양을
쓴다 — 출석/결석을 boolean 으로 저장하면 "아직 안 찍음"을 표현할 세 번째 값이
필요해지고, 그 값이 없는 행과 `false` 인 행의 차이를 누구도 기억하지 못한다.

```java
@Entity
@Table(name = "study_attendance",
        uniqueConstraints = @UniqueConstraint(columnNames = {"week_id", "member_id"}))
public class StudyAttendance {
    @Id private String id;
    @Column(name = "week_id")   private String weekId;
    @Column(name = "member_id") private String memberId;
    private Instant at;
}
```

**`weekId` 에 맨다**(D18). `studyId + weekNo` 로 매면 주차 번호가 곧 출석의 주소가
되는데, 번호는 화면이 보여주는 표시값이지 신원이 아니다. D14 가 번호 재배열을
기각했으므로 지금 당장 번호가 흔들릴 일은 없지만, 표시값에 기록을 매는 구조는
언젠가 재배열을 허용하는 순간 조용히 틀린다. 직접 참조가 더 싸고 `seminar/Attendance`
와도 같은 모양이다.

**`StudyWeek` 에 `takenAt` 을 더한다** — `Instant`, nullable.

| 값 | 뜻 |
|---|---|
| `null` | 이 주차의 출석을 **한 번도 저장하지 않았다** |
| 시각 | 그 시각에 처음 저장했다. 편집 창의 기점이다 |

`takenAt` 이 있고 출석 행이 하나도 없는 주차는 **전원 결석**이다. `takenAt` 이
`null` 인 주차는 결석이 아니라 **아직 일어나지 않은 일**이다. 이 구분이 D20 의
근거다 — 출석률 분모는 `takenAt != null` 인 주차 수다. 전체 주차로 나누면 8주
스터디의 1주차를 마친 모두가 출석률 12%로 보인다.

**출석 대상은 승인된 신청자 + 스터디장이다**(D21). 스터디장도 자기 스터디에 나온다.
명단에서 빼면 "스터디장은 늘 출석"이라는 암묵 규칙이 생기고, 그것이 참인지 아무도
확인하지 않는다.

**출석 명단에 학번을 싣지 않는다.** 이름과 기수면 격자가 성립한다. ① §7 이 상세
모달의 명단에 마스킹을 요구한 것은 그 화면이 **지원자 전체**를 보이기 때문이고,
출석 격자는 이미 같은 스터디에 속한 사람들끼리 보는 화면이다. 필요 없는 값을 실어
보내면 언젠가 샌다.

## 5. 출석을 쓰는 모양 — 주차 단위 전체 교체 (D17)

```
PUT /api/studies/{id}/weeks/{weekNo}/attendance
{ "present": ["memberId", …] }
→ 204
```

서버는 그 주차의 출석 행을 전부 지우고 `present` 만큼 다시 만든다. 그리고
`takenAt` 이 `null` 이면 지금 시각을 박는다.

**전체 교체인 이유.** 체크 해제가 자연스럽게 표현된다. 개별 토글
(`POST`/`DELETE .../{memberId}`)로 하면 10명 체크에 10번 호출이고, 네 번째에서
끊기면 화면과 서버가 갈라진 채 남는다 — 그 상태를 되돌릴 화면이 따로 필요해진다.
스터디 격자를 통째로 받는 안(`PUT .../attendance {weeks:[…]}`)도 기각했다. 편집
창은 주차마다 다르게 닫히는데 요청이 하나면 **부분 거절을 표현할 길이 없다**.

`present` 에 그 스터디의 출석 대상이 아닌 id 가 섞여 오면 `422` 다. 조용히 무시하면
화면이 저장에 성공했다고 믿고 잘못된 명단을 계속 보여준다.

## 6. 편집 창 — 규칙 하나가 저장과 삭제를 다 덮는다 (D19)

스터디장은 **`takenAt == null` 이거나 `now < takenAt + 24h`** 일 때만 그 주차를
건드릴 수 있다. 지나면:

```json
409 { "code": "ATTENDANCE_LOCKED", "message": "출석 수정 기간이 지났습니다. 임원에게 요청하세요." }
```

**403 이 아니라 409 다.** 스터디장은 이 주차에 대한 권한을 갖고 있다 — 시간이
지났을 뿐이다. 403 으로 내면 화면이 "권한이 없습니다"를 띄우고, 그것은 틀린 설명이다.

`STUDY_EDIT` 을 가진 임원은 창을 무시한다. `@PreAuthorize` 는 "스터디장 **또는**
임원"만 가르므로 창 검사는 서비스가 한다 — 컨트롤러가 `me.can(Permission.STUDY_EDIT)`
를 넘긴다(`CurrentMember.can` 이 이미 있다). 서비스가 `SecurityContextHolder` 를
직접 읽지 않는다. 그렇게 하면 서비스 테스트가 보안 컨텍스트를 세워야 한다.

**이 규칙이 주차 삭제도 덮는다.** 출석이 기록된 주차의 삭제는 곧 그 출석의 삭제이므로
같은 창을 쓴다. 규칙을 둘로 나눌 이유가 없다.

## 7. 주차 편집 — D14 를 집행한다

D14 가 규칙을 이미 정했다. ② 는 그것을 API 로 옮길 뿐, 규칙을 다시 열지 않는다.

| 동작 | 규칙 | 엔드포인트 |
|---|---|---|
| 제목·내용 수정 | **언제나 가능.** 출석이 기록된 주차도 마찬가지 | `PUT .../weeks/{weekNo}` |
| 주차 추가 | **맨 뒤에만.** `weekNo = max + 1` | `POST .../weeks` |
| 주차 삭제 | **맨 뒤에서만.** 출석이 기록된 주차(`takenAt != null`)는 `409` | `DELETE .../weeks/{weekNo}` |

**번호를 재배열하지 않는다.** 중간 삭제를 허용하면 뒤 번호를 당길지 정해야 하는데,
당기면 이미 출석이 기록된 "3주차"가 가리키던 모임이 슬그머니 바뀌고, 안 당기면
`[1,2,4]` 같은 구멍이 생겨 화면의 "가장 빠른 빈 주차"(§9)가 흔들린다. 맨 뒤에서만
늘리고 줄이는 규칙 하나로 실제 시나리오가 다 덮인다 — **한 주 쉼**은 뒤에 한 주
더하기, **일찍 접음**은 뒤에서 자르기다.

맨 뒤가 아닌 주차에 `DELETE` 를 부르면 `409 WEEK_NOT_LAST` 다. 마지막 한 주차는
지울 수 없다 — D5 가 최소 1주차를 요구한다(`409 WEEK_MIN`).

`FINISHED` 스터디는 주차도 출석도 **읽기 전용**이다(`409 STUDY_FINISHED`). 끝난
스터디의 기록이 나중에 바뀌면 그 기록을 근거로 한 것이 전부 흔들린다.

## 8. 반려 신청 삭제 — D11 을 집행한다 (D25)

```
DELETE /api/studies/applicants/{id}  → 204
```

본인이, `REJECTED` 인, 자기 신청만 지운다. 행을 하드 삭제해
`(studyId, applicantId)` 유니크를 푼다.

**경로가 `/applicants/{id}` 인 이유.** ① 이 이미 그 자리를 "신청 id"로 쓰고 있다
(`POST /api/studies/applicants/{id}/approve`). `applications` 라는 두 번째 이름을
만들면 같은 것을 두 이름으로 부르게 되고, 어느 쪽이 맞는지 계약을 읽는 사람이
매번 확인해야 한다.

**새 분기가 필요 없다.** ① 의 `deriveApply` 가 신청 기록을 보고 반려자에게 `CLOSED`
를 내고 있었다(`rejectedApplicantSeesApplyClosedNotOpen`). 기록이 사라지면 같은
함수가 `OPEN` 을 낸다. 삭제가 재신청을 여는 것이지, 재신청 경로가 따로 생기는 것이
아니다.

`PENDING` 이나 `APPROVED` 인 신청을 지우려 하면 `409 NOT_REJECTED` 다. 승인된 신청을
본인이 지울 수 있으면 그것은 탈퇴이고, 탈퇴는 이 단계에 없다(§16).

## 9. 화면 — '내 스터디'

① §14 가 이 화면을 이렇게 적었다: **'내 스터디' 탭과 '관리하기' 모달. 모달은 상태에
따라 다른 것을 연다. 종료된 스터디는 사라진다.** 상태 행 목록이 아니라 **스터디 카드
화면**이다. 지금의 `MyActivityView`(신청 현황·개설 목록을 두 그룹의 한 줄짜리 행으로
쌓는 화면)를 늘리는 것이 아니라 대체한다.

**탭 이름을 '내 활동' → '내 스터디' 로 바꾼다**(D22). 화면이 실제로 스터디 카드가
되므로 이름이 내용을 말한다. 탭 수는 `스터디 · 내 스터디 · 관리` 로 그대로 셋이다.

### 카드

```
+------------------------------------------+
| [스터디장]                      [모집 중] |   <- 관계 칩(좌) · 상태 배지(우)
| 알고리즘 스터디                           |
| (알고리즘) (Python)                       |
| ---------------------------------------- |
| 신청 2건이 기다리고 있습니다              |   <- 관계 x 상태가 정하는 한 줄
|                            [ 관리하기 ]   |
+------------------------------------------+
```

**관계 칩이 이 화면의 구조다.** 왜 이 카드가 저 카드와 다르게 생겼는지를 설명하는
유일한 값이므로, 장식이 아니라 정보다.

| 관계 | 상태 | 가운데 줄 | 버튼 |
|---|---|---|---|
| `LEADER` | `PENDING` | 개설 승인을 기다리는 중입니다 | — |
| `LEADER` | `REJECTED` | 반려 사유 | — |
| `LEADER` | `RECRUITING` | 신청 **N건**이 기다리고 있습니다 / 새 신청이 없습니다 | **관리하기** |
| `LEADER` | `ONGOING` | **N주차**까지 출석을 기록했습니다 (전체 M주) | **관리하기** |
| `MEMBER` | `RECRUITING` | 참여가 확정됐습니다. 곧 시작합니다 | — |
| `MEMBER` | `ONGOING` | 출석 **N회** / 기록된 M주차 | **출석 보기** |
| `APPLIED` | `RECRUITING` | 신청이 검토 중입니다 | — |
| `REJECTED` | — | 반려 사유 | **삭제하기** |

**버튼 이름이 관계에 따라 다르다.** 멤버에게 '관리하기'는 거짓말이다 — 관리할 것이
없고 자기 출석을 볼 뿐이다. 모달 컴포넌트는 하나지만 이름은 둘이다.

### 배치 — 두 구역

**위: 내 스터디** — `LEADER`·`MEMBER`·`APPLIED`. 지금 살아 있는 관계다. 정렬은
**내가 할 일이 있는 것부터**: 대기 신청이 있는 `LEADER` → 안 찍은 주차가 있는
`LEADER` → 나머지 `LEADER` → `MEMBER` → `APPLIED`. 같은 묶음 안에서는 최신순.

**아래: 지난 신청** — `REJECTED` 만. 반려된 신청은 내 스터디가 아니라 이력이라,
같은 그리드에 섞으면 탭 이름이 거짓이 된다. 각 카드에 **삭제하기**(§8) — 확인
문구에 "삭제하면 이 스터디에 다시 신청할 수 있습니다"를 쓴다. 그것이 이 버튼을
누르는 이유인데, 안 쓰면 그냥 지우는 것으로 읽힌다. 비어 있으면 구역을 숨긴다.

**빈 화면**: "아직 참여 중인 스터디가 없습니다" + '스터디 둘러보기' 버튼으로 첫 탭에
보낸다. 빈 목록만 두면 다음에 뭘 해야 하는지가 화면에 없다.

## 10. 모달 셋

### A. 스터디장 · 모집 중 — 신청 관리

헤더에 제목 · 상태 배지 · `3/8명`(D15 의 `정원 / 희망`). 본문은 두 묶음이다.

- **대기 중인 신청** — 이름 · 기수 · 지원동기. 각 행에 [승인] [반려]. 반려는 사유를
  받는다(기존 `ManageView` 의 반려 패턴을 그대로 쓴다).
- **참여 확정 N명** — 이름 · 기수만. 되돌릴 손잡이는 이 단계에 없다(§16).

우측 하단 **[모집 완료]** → 확인 한 단계 → `POST /{id}/close-recruiting`.
확인을 두는 이유는 되돌릴 손잡이가 임원에게만 있기 때문이다(D12).

데이터: `GET /api/studies/{id}/applicants`(D26).

### B. 스터디장 · 진행 중 — 출석 / 정보

**출석 탭.** 상단에 주차 막대(1..n, `takenAt != null` 인 주차는 채움). 기본 선택은
**가장 빠른 빈 주차** — D14 가 이미 이 이름을 쓴다. 선택한 주차의 출석 대상 명단을
체크박스로 띄우고 [저장]. 저장된 주차는 `takenAt` 과 **남은 편집 시간**을 같이
보여주고, 창이 닫히면 읽기 전용으로 잠그면서 "임원에게 요청하세요"를 쓴다 —
버튼만 죽이고 이유를 안 쓰면 고장으로 보인다.

**정보 탭.** 커리큘럼 주차 목록. 제목·내용은 언제나 수정 가능. 맨 뒤에
[+ 주차 추가], **맨 뒤 주차에만** [삭제] — 출석이 기록돼 있으면 비활성에 이유를
붙인다(§7).

우측 하단 **[종료]** → 확인 한 단계 → `POST /{id}/finish`. 확인 문구에 **"종료하면
'내 스터디'에서 사라집니다"** 를 쓴다. 화면에서 사라지는 것이 이 버튼의 진짜 결과인데,
안 쓰면 눌러 보고 나서야 안다.

데이터: `GET /api/studies/{id}/attendance`.

### C. 멤버 · 진행 중 — 내 출석

읽기 전용. 상단에 `출석 5 / 기록된 6주차`(D20 의 분모), 아래에 주차별 한 줄:
`3주차 자료구조 — 출석` / `— 결석` / `— 아직`. **아직 기록되지 않은 주차를 결석으로
쓰지 않는다.**

데이터: `GET /api/studies/{id}/attendance/me`.

## 11. 엔드포인트

### 새로

| 메서드 | 경로 | 게이트 | 응답 |
|---|---|---|---|
| `GET` | `/api/studies/{id}/applicants` | `isLeader` or `STUDY_APPLICANT_MANAGE` | `StudyApplicantList` |
| `GET` | `/api/studies/{id}/attendance` | `isLeader` or `STUDY_EDIT` | `AttendanceBoard` |
| `PUT` | `/api/studies/{id}/weeks/{weekNo}/attendance` | `isLeader` or `STUDY_EDIT` | `204` |
| `GET` | `/api/studies/{id}/attendance/me` | `isMember` | `MyAttendance` |
| `POST` | `/api/studies/{id}/weeks` | `isLeader` or `STUDY_EDIT` | `201` `StudyWeek` |
| `PUT` | `/api/studies/{id}/weeks/{weekNo}` | `isLeader` or `STUDY_EDIT` | `204` |
| `DELETE` | `/api/studies/{id}/weeks/{weekNo}` | `isLeader` or `STUDY_EDIT` | `204` |
| `DELETE` | `/api/studies/applicants/{id}` | `isApplicant` | `204` |

### 바뀌는 것

| 경로 | 변경 |
|---|---|
| `GET /api/studies/my` | 응답이 `MyActivity{apps[],studies[]}` → `MyStudyList{items[]}` (D24) |

## 12. 권한 — 새 Permission 은 없다

① 과 같은 원칙이다. 게이트는 언제나 **`소유자 조건 or hasAuthority(...)`** 모양이고,
`Permission` 열거형은 손대지 않는다. `StudyAccess` 에 두 메서드가 는다.

```java
/** 승인된 신청자이거나 스터디장. 출석 조회의 소유자 조건. */
public boolean isMember(String studyId, Authentication auth)

/** 그 신청의 본인. 경로 변수가 신청 id 다 — isLeaderOfApplication 과 같은 함정. */
public boolean isApplicant(String applicationId, Authentication auth)
```

`isApplicant` 의 경로 변수는 **스터디 id 가 아니라 신청 id** 다. ① 이
`isLeaderOfApplication` 에서 밟은 것과 같은 자리다 — 스터디 id 로 착각하면 언제나
`false` 가 되어 아무도 자기 신청을 지우지 못하고, 403 만 나오고 이유는 안 보인다.

**새 엔드포인트 8개는 ① 이 만든 `AuthorizationCoverageTest` 에 기본적으로 걸린다.**
게이트를 달지 않으면 테스트가 깨진다. `PUBLIC`/`AUTHENTICATED_ONLY` 목록에 이름을
더하지 않는다 — 8개 전부 게이트를 갖는다.

`GET /api/studies/my` 는 이미 `AUTHENTICATED_ONLY` 에 `StudyController#my` 로 적혀
있다. 응답 모양만 바뀌므로 그대로 둔다.

## 13. 계약 응답 스키마

계약 PR 이 BE 보다 먼저 머지되어야 하므로(§14) 응답 이름을 여기서 굳힌다.

### `MyStudyList` — `GET /api/studies/my` (D24)

```json
{ "items": [
  { "id": "…", "title": "알고리즘 스터디", "fields": ["알고리즘", "Python"],
    "status": "RECRUITING", "relation": "LEADER",
    "leader": "이준호", "leaderGen": 40, "schedule": "매주 화 19:00",
    "applicationId": null, "reason": null,
    "pendingApplicants": 2,
    "weeksTaken": null, "weeksTotal": 8, "myAttended": null }
] }
```

| 필드 | 타입 | 채워지는 때 |
|---|---|---|
| `relation` | `LEADER\|MEMBER\|APPLIED\|REJECTED` | 언제나 |
| `applicationId` | `string?` | `APPLIED`·`REJECTED` — 삭제하기가 쓰는 id |
| `reason` | `string?` | 개설 반려(`LEADER` + 상태 `REJECTED`) 또는 신청 반려(`REJECTED`) |
| `pendingApplicants` | `int?` | `LEADER` + `RECRUITING` |
| `weeksTaken`·`weeksTotal` | `int?` | `ONGOING` |
| `myAttended` | `int?` | `MEMBER` + `ONGOING` |

**두 배열을 한 배열로 접는 이유.** 화면이 관계 하나로 정렬하고 분기하는데(§9),
응답이 `apps`·`studies` 로 나뉘어 오면 화면이 받아서 다시 합쳐야 한다. 그리고 카드에
필요한 숫자(대기 신청 수, 기록된 주차 수, 내 출석 수)를 지금 응답이 하나도 주지
않는다 — 세 개를 두 배열에 나눠 붙이는 것보다 관계를 일급으로 올리는 편이 짧다.
`MyApp`·`MyStudy` 는 폐기하고 `MyStudyItem` 하나가 대신한다.

### `StudyApplicantList` — `GET /api/studies/{id}/applicants` (D26)

```json
{ "pending":  [ { "applicationId": "…", "name": "김민수", "gen": 41, "motive": "…" } ],
  "approved": [ { "applicationId": "…", "name": "박서연", "gen": 40, "motive": null } ] }
```

두 묶음으로 나눠 내려보낸다 — 모달이 그 모양으로 그린다(§10 A). 반려는 싣지 않는다.
`approved` 의 `motive` 는 `null` 이다. 승인이 끝난 사람의 지원동기를 계속 보여줄
이유가 없다.

이 응답에는 **학번이 없다.** 스터디장이 신청자를 가려내는 데 쓰는 것은 이름·기수·
지원동기다. ① §7 의 마스킹은 상세 모달의 명단에 필요했지만, 여기는 애초에 싣지
않는 편이 짧다.

### `AttendanceBoard` — `GET /api/studies/{id}/attendance`

```json
{ "weeks": [ { "weekNo": 1, "title": "완전탐색",
               "takenAt": "2026-09-15T10:00:00Z", "editable": false } ],
  "members": [ { "memberId": "…", "name": "이준호", "gen": 40, "leader": true,
                 "present": [1, 2] } ] }
```

`editable` 은 **호출한 사람 기준**이다 — 임원이 부르면 전부 `true`, 스터디장이
부르면 창 안의 주차만 `true`. 화면이 `takenAt` 에 24h 를 더해 스스로 계산하게 두면
시계 차이로 화면과 서버가 다른 답을 낸다.

`members[].leader` 는 격자에서 스터디장을 표시하기 위한 것이다. 정렬은 ① §7 과 같이
기수 → 이름이고, 스터디장이 맨 앞이다.

### `MyAttendance` — `GET /api/studies/{id}/attendance/me`

```json
{ "attended": 5, "taken": 6,
  "weeks": [ { "weekNo": 1, "title": "완전탐색", "state": "PRESENT" } ] }
```

`state` 는 `PRESENT` / `ABSENT` / `NOT_TAKEN`. 화면이 `takenAt` 의 유무로 세 번째
상태를 유추하게 두지 않는다 — 유추는 매번 같은 실수를 부른다.

### 주차 편집 요청

```
POST /api/studies/{id}/weeks       { "title": "…", "content": "…" }   → 201 StudyWeek
PUT  /api/studies/{id}/weeks/{n}   { "title": "…", "content": "…" }   → 204
```

`weekNo` 를 본문으로 받지 않는다. 추가는 언제나 `max + 1` 이고(§7), 수정은 경로가
이미 말한다. 받으면 본문과 경로가 어긋났을 때 무엇을 믿을지 정해야 한다.

## 14. 스키마 이행과 배포 순서

**이행 SQL 이 없다**(D27). ① 이 손으로 SQL 을 쓰게 만든 것은 `study.status` 가
**행이 있는 테이블의 새 컬럼**이었기 때문이다. Postgres 가 `NOT NULL` 추가를 거부하고
Hibernate 의 `SchemaUpdate` 가 그 예외를 로그 한 줄로 삼킨 뒤 기동을 끝내므로, 컬럼
없이 트래픽을 받는 창이 생긴다.

② 가 만드는 것은 그 조건에 하나도 해당하지 않는다.

| 대상 | 왜 자동으로 되나 |
|---|---|
| `study_attendance` | **빈 테이블 신설.** `ddl-auto: update` 가 제약까지 만든다 |
| `study_week.taken_at` | **nullable 컬럼 추가.** Postgres 가 즉시 허용하고 기본값 채우기가 없다 |

`docs/migrations/` 에 파일을 두지 않는다. 할 일이 없는 파일을 두면 다음 배포 때
"이건 돌려야 하나"를 매번 확인하게 된다.

**계약 PR 이 먼저다.** `home-jaram-fe` 의 `docs/api/openapi.yaml` 이 두 레포의 단일
계약이고(BE 는 symlink 로 읽는다), `OpenApiValidationFilter` 가 **선언되지 않은 응답
필드를 거부**한다. 계약 CI 는 브랜치 이름으로 짝을 찾으므로 양쪽 브랜치 이름을
`feat/study-operations` 로 맞춘다.

**① 이 먼저 머지되어야 한다.** ② 의 코드는 ① 의 `StudyAccess`·`StudyWeek`·
`StudyStatus` 위에 선다. 순서는 ①계약 → ①BE → ②계약 → ②BE 다.

## 15. 테스트

**권한 그물이 공짜로 8개를 잡는다.** ① 의 `AuthorizationCoverageTest` 는 기본값이
뒤집혀 있어, 새 핸들러가 `@PreAuthorize` 없이 들어오면 **그 자체로 실패**한다.
새 엔드포인트마다 게이트 존재를 확인하는 테스트를 따로 쓸 필요가 없다. 따로 쓰는
것은 게이트가 **틀리게** 붙은 경우다.

| 대상 | 무엇을 본다 |
|---|---|
| 편집 창 | 창 안 저장 성공 / 창 밖 `409 ATTENDANCE_LOCKED` / 임원은 창 밖에서도 성공 |
| `takenAt` | 첫 저장에 박히고 **두 번째 저장에 갱신되지 않는다** (갱신하면 창이 무한히 연장된다) |
| 전원 결석 | `present: []` 저장 후 `takenAt != null`, 출석 0 — "안 찍음"과 구분된다 |
| 출석 대상 | 남의 id 를 `present` 에 섞으면 `422`, 아무것도 저장되지 않는다 |
| 출석률 분모 | 8주 중 2주만 기록된 상태에서 분모가 2 다 (D20) |
| 주차 삭제 | 맨 뒤 성공 / 중간 `409 WEEK_NOT_LAST` / 출석 있으면 `409` / 마지막 하나 `409 WEEK_MIN` |
| 주차 추가 | `weekNo = max + 1`, 빈칸이 생기지 않는다 |
| `FINISHED` | 주차·출석 쓰기가 전부 `409 STUDY_FINISHED` |
| 반려 삭제 | 본인+`REJECTED` 성공 → 같은 스터디에 재신청 성공 / `PENDING` 은 `409` / 남의 신청은 `403` |
| `isApplicant` | 경로 변수가 신청 id 임을 확인한다 — 스터디 id 로 읽으면 언제나 `false` 가 되는 자리 |
| `relation` | 네 관계가 각각 맞게 나오고, 한 스터디에 관계가 둘 붙지 않는다 |
| `FINISHED` 제외 | 종료된 스터디가 `/my` 의 어느 관계로도 오지 않는다 |
| 계약 | 새 응답 4종이 스펙과 맞는다. `OpenApiValidationFilter` 가 응답 **본문 모양**은 보지 않으므로(① 의 발견) 배열/객체 단언을 테스트가 직접 쓴다 |

## 16. 범위 밖

**③ 임원 도구** — 관리자 '스터디 관리' 탭, 모집 토글 UI, 표(스터디명·스터디장·인원·
출석률·상태·상세·삭제), 상세 편집 화면, 멤버 직접 추가. 상태 편집 API 자체는 D12 로
① 에 있다.

**이 단계에 넣지 않는 것**

- **승인 되돌리기** — 승인된 참여자를 스터디장이 내보내는 손잡이. 출석 기록이 이미
  붙은 사람을 지울 때 그 기록을 어떻게 할지가 따로 결정이라, 출석이 자리를 잡은 뒤에
  정한다.
- **스터디 탈퇴** — 멤버가 스스로 나가는 길. 위와 같은 결정을 공유한다.
- **개설 신청 취소** — `PENDING` 인 내 개설 신청을 내가 접는 것. 반려 삭제(D11)와
  모양이 비슷하지만 대상이 `study` 행이라 주차·신청까지 같이 지워야 한다.
- **출석 코드** — 멤버가 직접 찍는 방식(D16 에서 기각). 필요해지면 세미나의
  `SeminarController#attend` 가 이미 그 모양을 갖고 있다.
- **알림** — 신청이 들어왔다·승인됐다·출석이 기록됐다를 알리는 것. 이 재설계 전체에
  없다.
