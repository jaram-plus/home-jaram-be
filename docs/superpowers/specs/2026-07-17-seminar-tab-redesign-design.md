# 세미나 탭 개선 — 설계

FE 세미나 목록 화면(탭 순서·카드 클릭 상세·출석 카운트다운·종료 카드 칩) 개선 요청. 4개 항목 중
2개(카운트다운, 칩)는 백엔드가 오늘 노출하지 않는 데이터가 필요해 두 저장소에 걸친 작업이다.

- BE: `jaram-be` (본 저장소, cwd)
- FE: `home-jaram-fe` (형제 저장소, OpenAPI 계약 소유 — `docs/api/openapi.yaml` 심링크 원본)

## 배경 / 확정된 사실

- `Seminar.status`는 저장되지 않고 매 요청마다 파생: `SeminarStatus.of(startsAt, now, windowMinutes)`
  → UPCOMING/ONGOING/ENDED. `windowMinutes`는 `seminar.attendance-window-minutes`(기본 120,
  `application.yml` 미설정 → 기본값 사용 중), **서버 내부 값이며 API로 노출된 적 없음**.
- 출석(`Attendance`)은 상태 enum이 없다 — row 존재 자체가 "출석함"이고 `at`(체크인 시각)만 저장.
  결석은 row 부재로만 추론.
- `GET /api/seminars`는 `permitAll`, 컨트롤러가 파라미터를 전혀 받지 않아 **호출자 컨텍스트 없음**
  → 개인화 필드가 없다. FE는 "출석함" 상태를 로컬 state(`useState({})`, 새로고침 시 소실)로만 흉내.
- 동일한 패턴이 `study` 도메인엔 이미 있다: `StudyController.list(@AuthenticationPrincipal CurrentMember me)`
  → `StudyService.deriveApply(..., userId)`가 `userId == null`이면 `null` 반환. `/api/studies`도
  `/api/seminars`와 같은 줄에서 `permitAll`이라, 토큰이 있으면 필터가 파싱해 principal을 채워주고
  없으면 컨트롤러 파라미터가 `null`로 들어온다 — 그대로 재사용 가능한 선례.
- `GET /api/seminars/{id}/roster`는 `OFFICER` 전용이며 `sid`(학번) 포함. 신규 "참석자 미리보기"는
  일반 회원에게 열되 `sid`는 빼야 하므로 별개 엔드포인트로 분리한다(§설계).
- `spring.jpa.hibernate.ddl-auto: update` — 마이그레이션 스크립트 디렉터리 자체가 없다. 컬럼 추가는
  엔티티에 필드만 추가하면 됨.
- FE `ListView.jsx`에 이미 탭 3개가 있다(`전체/예정/종료`, 100% 클라이언트 필터링, 서버 파라미터 없음).
  `SeminarCard.jsx`는 카드 클릭 핸들러가 없고(순수 표시), 칩은 `STATUS_BADGE[status]`를 그대로 씀.
  모달 UI 패턴(`ModalShell` + `AttendModal`/`CreateModal`)이 이미 있어 재사용 가능.
- 기존에 발견된 별개 갭: FE 계약은 이미 `Seminar.capacity` → `target: TargetGrade[]`(공개 대상 등급)로
  이동했으나 이 백엔드는 아직 미구현. **이 작업과 무관, 손대지 않는다.**

## 요구사항 (사용자 확인 완료)

1. 탭 순서: 예정 → 종료 → 전체 (현재: 전체 → 예정 → 종료)
2. 카드 클릭 → **모달**로 세부 정보 (`ModalShell` 재사용). 포함 항목(전부 선택됨):
   - 리스트 정보 확대 표시 (제목/발표자/장소/시간/토픽/상태/발표자료 링크 — 이미 있는 필드)
   - 상세 설명 텍스트 (신규 필드, 백엔드 추가 필요)
   - 내 출석 기록(체크인 시각, 있을 때만)
   - 참석자 명단 미리보기 (신규 엔드포인트, `sid` 제외)
3. ONGOING일 때 버튼 아래 "출석 인정까지 N분 남음" — 분 단위 텍스트, 서버가 절대 마감 시각을 주고
   클라이언트가 계산/틱(폴링 아님).
4. ENDED 카드 칩: 로그인 + 출석함 → "출석" / 로그인 + 결석 → "결석" / 비로그인 → "종료" 유지
   (비로그인은 개인화 불가 — FE가 자신의 로그인 상태로 분기, 서버는 별도 신호 안 줌).

## 설계

### Backend (`jaram-be`)

**`Seminar.java`** — 컬럼 추가:
```java
@Column(columnDefinition = "TEXT")
private String description;   // nullable
```
`create(...)` 팩토리·getter/setter에 `description` 추가.

**`SeminarCreateRequest`** — `description`(nullable String) 필드 추가 (title 등과 동일하게 옵션 취급).

**`SeminarResponse`** — 필드 3개 추가 (끝에):
```java
public record SeminarResponse(
        ..., Integer capacity,
        String description,          // nullable, 그대로 echo
        String attendanceClosesAt,   // 파생: startsAt + windowMinutes, ISO-8601
        String attendedAt            // 파생: 호출자 기준, 없으면 null (미출석 또는 비로그인)
) { }
```

**`SeminarService`**:
- `list()` → `list(String callerId)`, `.map(s -> toResponse(s, callerId))`.
- `toResponse(Seminar s)` → `toResponse(Seminar s, String callerId)`:
  ```java
  Instant closesAt = s.getStartsAt().plus(Duration.ofMinutes(windowMinutes));
  String attendedAt = callerId == null ? null :
      attendances.findBySeminarIdAndMemberId(s.getId(), callerId)
                 .map(a -> formatTime(a.getAt())).orElse(null);
  // SeminarResponse(..., s.getDescription(), closesAt.toString(), attendedAt)
  ```
  (`study`의 `deriveApply(..., userId)`와 동일 구조 — `callerId == null` 조기 반환.)
- `create(...)`도 `toResponse(saved, createdById)`로 호출부만 맞춘다(생성 직후엔 항상 null이지만
  시그니처 일관성 유지).
- 신규 메서드:
  ```java
  @Transactional(readOnly = true)
  public AttendeePreviewResponse attendeePreview(String seminarId) {
      seminars.findById(seminarId).orElseThrow(/* 기존 roster()와 동일한 404 */);
      List<Attendance> rows = attendances.findBySeminarIdOrderByAtAsc(seminarId);
      Map<String, Member> byId = /* roster()와 동일한 조회 */;
      List<AttendeePreviewEntry> list = rows.stream()
          .map(a -> new AttendeePreviewEntry(
              Optional.ofNullable(byId.get(a.getMemberId())).map(Member::getName).orElse(null),
              formatTime(a.getAt())))
          .toList();
      return new AttendeePreviewResponse(list.size(), list);
  }
  ```

**신규 DTO** (`dto/AttendeePreviewEntry.java`, `dto/AttendeePreviewResponse.java`) — `RosterEntry`/
`RosterResponse`와 동일한 record 스타일, `sid` 없음:
```java
public record AttendeePreviewEntry(String name, String at) { }
public record AttendeePreviewResponse(int count, List<AttendeePreviewEntry> list) { }
```

**`SeminarController`**:
```java
@GetMapping
public List<SeminarResponse> list(@AuthenticationPrincipal CurrentMember me) {
    return service.list(me == null ? null : me.id());
}

@GetMapping("/{id}/attendees")
public AttendeePreviewResponse attendees(@PathVariable String id) {
    return service.attendeePreview(id);
}
```

**`SecurityConfig`**: 변경 없음. `/api/seminars/{id}/attendees`는 기존 `permitAll`(정확히
`"/api/seminars"` 문자열만 매치, `/**` 아님) 대상이 아니고 `roster`의 officer 전용 매처와도
다른 경로라, 기존 마지막 규칙 `.anyRequest().authenticated()`에 자동으로 걸린다 — 로그인만
하면 되고 `OFFICER` 권한은 불필요하므로 그대로 둔다.

**openapi.yaml** (FE 소유 원본 `home-jaram-fe/docs/api/openapi.yaml`에서 수정 후
`./scripts/sync-openapi.sh`로 본 저장소에 반영):
```yaml
    Seminar:
      properties:
        ...
        description: { type: [string, 'null'], description: 세미나 상세 설명 }
        attendanceClosesAt: { type: string, format: date-time, description: '출석 인정 마감 시각 (startsAt + 출석창, 서버 파생)' }
        attendedAt: { type: [string, 'null'], description: '내 출석 시각 표시(예 19:02). 미출석/비로그인이면 null' }
    SeminarCreateRequest:
      properties:
        ...
        description: { type: [string, 'null'] }
    AttendeePreviewEntry:
      type: object
      required: [at]
      properties:
        name: { type: [string, 'null'] }
        at: { type: string, description: 출석 시각 표시 (예 '19:02') }
    AttendeePreviewResponse:
      type: object
      required: [count, list]
      properties:
        count: { type: integer }
        list: { type: array, items: { $ref: '#/components/schemas/AttendeePreviewEntry' } }
  /api/seminars/{id}/attendees:
    get:
      tags: [seminar]
      summary: 참석자 미리보기 (신규)
      description: 로그인한 회원 누구나 조회. sid(학번) 미노출 — officer 전용 roster와 구분.
      security: [{ bearerAuth: [] }]
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
      responses:
        '200':
          description: 참석자 미리보기
          content:
            application/json:
              schema: { $ref: '#/components/schemas/AttendeePreviewResponse' }
```

### Frontend (`home-jaram-fe`)

- **`views/ListView.jsx`**: `FILTERS` 배열을 `[예정, 종료, 전체]` 순으로 재배열. `pass()` 로직은
  키 기반이라 변경 불필요. 기본 선택 탭은 `SeminarPage.jsx`의 `useState('all')` 그대로 유지
  (표시 순서만 바뀌고 최초 선택은 안 바뀜).
- **`views/SeminarCard.jsx`**:
  - 카드 루트 `div`에 `onClick={() => onOpenDetail(seminar)}` 추가. 출석 버튼엔
    `onClick={(e) => { e.stopPropagation(); onAttend(seminar); }}`로 전파 차단.
  - 칩: `status === 'ENDED'`일 때만 분기 —
    ```js
    const endedLabel = !isLoggedIn ? STATUS_BADGE.ENDED
      : seminar.attendedAt ? { label: '출석', tone: 'seal' }
      : { label: '결석', tone: 'neutral' };
    const badge = seminar.status === 'ENDED' ? endedLabel : STATUS_BADGE[seminar.status];
    ```
    (`isLoggedIn`은 기존 `useAuthStore`에서 가져옴 — 이미 `SeminarPage.jsx`가 admin 판별에 씀.)
  - `canAttend`(ONGOING && !attended)일 때만 버튼 아래 카운트다운 문구 렌더.
- **신규 `useAttendanceCountdown.js`**:
  ```js
  export function useAttendanceCountdown(closesAt) {
    const [now, setNow] = useState(() => Date.now());
    useEffect(() => {
      const t = setInterval(() => setNow(Date.now()), 30_000);
      return () => clearInterval(t);
    }, []);
    const ms = new Date(closesAt).getTime() - now;
    return Math.max(0, Math.ceil(ms / 60000)); // 남은 분
  }
  ```
  카드에서: `출석 인정까지 {mins}분 남음` (0이면 문구 숨김 — 다음 목록 refetch에서 ENDED로 전환됨,
  실시간 상태 전환 없음을 명시적으로 받아들임 §엣지 케이스).
- **신규 `views/DetailModal.jsx`** (`ModalShell` 재사용, `AttendModal`과 같은 구조):
  리스트 필드 확대 표시 + `description`(있을 때) + "내 출석 기록"(`attendedAt` 있을 때만,
  "당신은 {attendedAt}에 출석했습니다") + "참석자" 섹션(아래).
  - 참석자 섹션은 `useAttendeePreview(seminar.id, { enabled: open && isLoggedIn })`로 모달이
    열릴 때만 지연 조회. 비로그인이면 API 호출 없이 "로그인하면 참석자를 볼 수 있어요" 안내만.
- **`seminar.api.js`**: `getAttendeePreview(id)` 추가 (`client.get('/api/seminars/${id}/attendees')`).
- **`seminar.queries.js`**: `useAttendeePreview(id, options)` 추가 (`queryKey: ['seminar-attendees', id]`).
- **`SeminarPage.jsx`**: `detailSeminar` state 추가(`useState(null)`), `openDetail`/닫기 핸들러,
  `<DetailModal>` 렌더 (다른 모달들과 동일한 위치).
- **`views/CreateModal.jsx` / `useForm.js`**: `description` textarea 필드 추가(다른 옵션 필드와
  동일하게 폼 기본값 `''` → 전송 시 `opt()`로 빈 문자열은 null 처리, `seminar.api.js`
  `createSeminar` payload에도 `description: opt(form.description)` 추가).

## 엣지 케이스

- **비로그인 + ENDED**: `attendedAt`은 항상 null(서버가 callerId 없어 애초에 조회 자체를 안 함).
  칩은 "종료"로 폴백 — 이건 서버 신호가 아니라 FE가 자기 로그인 상태로 직접 분기한 결과.
- **정확히 마감 경계**: `SeminarStatus.of`가 이미 `startsAt+window` 포함(inclusive) 처리를
  검증됨(`SeminarStatusTest`). `attendanceClosesAt`도 같은 계산식이라 카운트다운이 0에 닿는
  순간과 서버가 ENDED로 넘기는 순간이 항상 일치. 다만 화면상 ONGOING→ENDED 전환은 다음
  react-query refetch 전까진 반영 안 됨(폴링 없음, 기존 목록 갱신 정책 그대로).
- **ONGOING인데 이미 출석함**: 버튼이 이미 "출석 완료"(비활성) → 카운트다운 문구 자체를
  숨긴다(더 이상 의미 없음).
- **참석자 미리보기 개수 제한 없음**: 현재 동아리 규모 가정, 페이지네이션/캡 없이 전체 반환.
- **설명 미입력**: nullable, 카드/모달 모두 값 없으면 해당 섹션 자체를 렌더하지 않음.

## 범위 밖

- `capacity` → `target: TargetGrade[]` 계약 드리프트 (기존에 발견된 별개 갭, 이 작업 무관)
- 서버 사이드 탭 필터링/페이지네이션 (여전히 클라이언트 필터링 유지)
- 실시간 상태 전환 push/polling (react-query 기본 refetch에 의존)
- 최초 선택 탭 변경 (순서만 바뀜, 기본값은 여전히 `all`)
- 발표자료 링크(`materialUrl`) 실제 연결 — 카드의 `href="#"` 플레이스홀더는 기존 이슈, 이번
  스코프 아님(단, 모달에는 있는 값이니 그대로 노출은 함)

## 테스트 계획

**BE (TDD, `SeminarServiceTest`/`SeminarControllerTest` 확장)**:
- `callerId == null` → `attendedAt` null (비로그인 목록 조회)
- `callerId`가 출석한 세미나 → `attendedAt` = 체크인 시각 문자열
- `callerId`가 출석 안 한 ENDED 세미나 → `attendedAt` null
- `attendanceClosesAt` == `startsAt + windowMinutes` 정확성
- `GET /api/seminars/{id}/attendees`: 토큰 없으면 401, 있으면 200 + 응답에 `sid` 키 자체가 없음
- 기존 `SeminarStatusTest` 회귀(경계값 변경 없음) 그대로 green 확인

**FE**:
- `SeminarCard`: 카드 클릭 → 모달 오픈, 출석 버튼 클릭 → 모달 안 열림(전파 차단 확인)
- 칩 3분기: ended+attendedAt 있음 → "출석" / ended+없음(로그인) → "결석" / ended+비로그인 → "종료"
- 카운트다운: 렌더 후 언마운트 시 `clearInterval` 호출(누수 없음), 0 이하일 때 문구 미표시
- 탭 DOM 순서: 예정/종료/전체

## 완료 기준

- `./gradlew test` green (BE, 위 신규 케이스 포함)
- FE 빌드/린트 green
- 수동 시나리오: 로그인/비로그인 각각으로 예정·진행중(카운트다운 포함)·종료(출석/결석/종료 칩) 카드 확인,
  카드 클릭 모달에서 설명·내 출석 기록·참석자 미리보기 확인
- `./scripts/sync-openapi.sh` 실행 후 두 저장소 계약 diff 없음
