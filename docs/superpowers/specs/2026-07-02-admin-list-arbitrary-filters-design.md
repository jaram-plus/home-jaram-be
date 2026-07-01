# Admin 목록 임의 필터 (UC-A1 갭) — 설계

백로그 §6-2 항목: `GET /api/admin/{resource}` 가 계약상 `grade·cohort·status·department` 등
임의 쿼리 필터 키를 받아야 하나, 현재 `tab·q·sort·page·size` 만 구현. 임의 필터 키 미적용
(`AdminResourceService.list`). 이 문서는 그 갭을 메우는 최소 변경 설계.

계약: `docs/api/openapi.yaml` `GET /api/admin/{resource}` — description 에
"임의 필터 키(grade·cohort·status·department 등)는 쿼리 파라미터로 전달, 값은 와이어 enum
키(UPPER_SNAKE)" 명시. 명명 파라미터로는 `tab/q/sort/page/size` 만 선언.

## 확정된 사실 (FE 대조)

FE `home-jaram-fe/src/features/admin/admin.data.js` · `admin.api.js` 검증 결과:

- **필터는 단일 선택** (`options: ['전체', ...]`, '전체'=미전송). → 키당 값 1개, 키 간 AND. multi-value 없음.
- **값은 wire enum 키(UPPER_SNAKE)** — FE `toWire` 가 라벨→키 변환 후 전송. 백엔드 행의 `.name()` 과 직접 대응.
- **cohort = 정수** — 계약 `cohortBreakdown.cohort: integer`, `currentCohort: integer`. FE `toWire` 가
  `'41기'|'41' → 41` 변환(비숫자 '외부' 등은 통과). 필터 쿼리도 `toWire` 경유 → `cohort=41` 로 전송.
- member 필터 키: `grade·cohort·status·department`. `status` = 활동축(ACTIVE/ON_LEAVE/WITHDRAWN),
  승인축(PENDING/APPROVED/REJECTED)은 별개 축(신청자 전용).

## 설계

한 관심사(임의 필터)만 추가하는 최소 변경. 파일 2개: 컨트롤러 시그니처 + 서비스 필터 로직.

### 컨트롤러 (`AdminResourceController.list`)

`@RequestParam Map<String,String> allParams` 추가로 전체 쿼리 파라미터 캡처.
예약 키 `{tab, q, sort, page, size}` 제거 후 나머지를 `filters` 맵으로 서비스에 전달.
기존 타입 파라미터(tab/q/sort/page/size)는 그대로 유지(값 파싱·기본값 담당).

```
list(resource, tab, q, sort, page, size, allParams):
    filters = allParams - {tab, q, sort, page, size}
    return service.list(resource, tab, q, sort, page, size, filters)
```

(`@RequestParam Map` 은 키당 첫 값만 담음 — 단일 선택이라 무방.)

### 서비스 (`AdminResourceService.list`)

기존 `.filter(matchesQuery)` 체인에 `.filter(matchesFilters)` 추가. 행은 이미 `Map<String,Object>`
투영이라 리소스 무관 범용 매칭.

```
alias(key):   key == "cohort" ? "gen" : key
matchesFilters(row, filters):
    for (key, val) in filters:
        rowKey = alias(key)
        if !row.containsKey(rowKey): continue          # 없는 키 → no-op (전체 통과)
        rowVal = row.get(rowKey)
        if rowVal == null: return false
        if rowVal is List: if none of list equalsIgnoreCase(val): return false
        else if !String.valueOf(rowVal).equalsIgnoreCase(val): return false
    return true                                         # 모든 필터 통과 = AND
```

동작:
- `?grade=REGULAR&cohort=39` → grade=REGULAR **AND** gen=39
- `?status=ACTIVE` → 활동 회원만
- 없는 키 / 행에 없는 필드 → 무시(전체 반환)

### 범위 밖 (별 백로그 항목)

- **seminar/study status 필터** — 행에 lifecycle status 필드 미투영(seminar 는 backend status enum
  자체 없음, study 는 approvalStatus 만). §6-2 "Study ONGOING" 및 행 스키마(§6-1)와 얽힘. no-op 처리.
- **contrib `type` 필터** — contrib 는 member category 파생, `type` 필드 미투영. 범위 밖.
- **export `filters`** — 별 §6-2 항목.

## 테스트 (TDD, `AdminResourceTest`)

- grade 단일 필터 → 해당 등급만
- status 단일 필터(활동축) → 해당 상태만
- cohort 필터 `"41"` → gen==41 만 (alias + 문자열 비교)
- 복합 `grade` + `cohort` → AND
- 없는 키(`foo=bar`) → 전체 통과(no-op)
- 빈 필터 → 기존과 동일(전체)

## 완료 기준

`./gradlew test` green + 위 테스트 통과. 계약 A1 임의 필터 키 동작.
