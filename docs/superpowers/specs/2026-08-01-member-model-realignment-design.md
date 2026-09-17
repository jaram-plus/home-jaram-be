# 회원 모델 계약 재정립 설계

**작성일:** 2026-08-01
**선행 문서:** `2026-07-20-member-axis-dedup-design.md`, `2026-07-20-member-term-model-design.md`

## 1. 목표

회원 도메인은 두 차례 리팩터링(중복 축 제거 → 임기 모델)으로 정리됐지만, 그 결과가
와이어 계약에 반영되지 않았다. 지금 계약은 리팩터링 이전의 모습을 보고 있다.

- `gen`이 곳에 따라 정수이기도 하고 `'41'`·`'41기'` 문자열이기도 하다.
- `member_term`에 임기 이력이 쌓이는데, FE는 `role` 문자열 한 줄만 받는다.
- `contributor` 컬럼이 생겼지만 응답 어디에도 없다.
- `faculty`·`phone`은 가입 때 받아 저장만 하고 다시 꺼내 볼 경로가 없다.
- 탭 이름이 사람들 페이지는 `grad`, admin 목록은 `graduate`로 갈라져 있다.

이 문서는 다섯 가지를 계약과 BE 양쪽에서 맞춘다. **도메인 모델과 DB 스키마는 손대지
않는다** — 저장된 값은 이미 옳고, 밖으로 내보내는 모양만 틀렸다.

## 2. 범위

| | 포함 | 제외 |
|---|---|---|
| 계약 | `SignupRequest`·`MeProfile`·`PersonMember`·`MeUpdateRequest` 수정, `MemberTerm` 스키마 신설, admin `tab` 값 통일 | 새 엔드포인트 |
| BE | 위 DTO와 매핑 코드 | 엔티티·리포지토리·마이그레이션 |
| FE | — | FE 렌더링 코드 (별도 작업, §8 참조) |

DB 마이그레이션은 **없다**. `Member.gen`은 이미 `Integer`, `faculty`·`phone`·
`contributor`·`member_term`도 모두 존재한다.

## 3. gen 정수 통일

`gen`은 기수를 세는 수다. 지금 세 군데가 문자열이고, 그중 둘은 `"기"` 접미사까지
서버가 붙여 보낸다.

| 위치 | 현재 | 변경 후 |
|---|---|---|
| `SignupRequest.gen` | `string` `^\d+$` (`'41'`) | `integer`, `minimum: 1` |
| `MeProfile.gen` | `[string,'null']` (`'41기'`) | `[integer,'null']` |
| `PersonMember.gen` | `[string,'null']` (`'41기'`) | `[integer,'null']` |
| `AdminSettings.currentGen` | `integer` | 변경 없음 |
| `DashboardStats.genBreakdown[].gen` | `integer` | 변경 없음 |
| admin 목록 행의 `gen` | `integer` | 변경 없음 |

**`"기"` 접미사는 FE로 넘어간다.** 표시 형식은 표시 계층의 일이다. 지금은 admin
목록만 정수를 받아 FE가 알아서 붙이고 있고, 사람들 카드와 프로필만 서버가 붙여
준다 — 같은 값을 두 가지 방식으로 내보내고 있는 셈이다.

`PersonMember.gen`의 이중 파생 규칙(재학 중 = 가입 기수, OB = 학번에서 파생한 입학
기수)은 의도된 동작이므로 `PeopleService.displayGen()`을 그대로 둔다. 계약의
description도 그대로 유효하다.

## 4. faculty / phone

가입 때 필수로 받아 저장하지만 어떤 응답에도 없어 본인조차 확인할 수 없다.
`MeProfile`에 추가한다.

**공개 `PersonMember`에는 넣지 않는다.** 학부는 몰라도 전화번호는 개인정보이고,
사람들 페이지는 비로그인 공개 경로다. 둘을 같은 취급으로 묶어 본인 전용 응답에만
싣는다.

| 필드 | `MeProfile` | `MeUpdateRequest` |
|---|---|---|
| `faculty` | 노출 | **없음 (읽기 전용)** |
| `phone` | 노출 | 수정 가능 |

`faculty`를 읽기 전용으로 두면 오타로 가입한 회원의 학부를 고칠 경로가 어디에도
없어진다(admin 일괄 편집에도 `faculty` 필드가 없다). 감수하는 한계이며, 필요해지면
admin 편집 필드로 추가하는 것이 자연스러운 후속이다.

### 4.1 PATCH 의미

`phone`은 가입 필수 항목이라 빈 값이 될 수 없다. 반면 기존 `bio`/`githubUrl`/
`blogUrl`은 비우는 것이 정상 동작이다. 규칙이 갈린다.

```
phone == null          → 미변경 (필드를 보내지 않은 것으로 본다)
phone 이 공백뿐        → 422 VALIDATION
그 외                  → 저장
```

레코드 DTO는 "필드 없음"과 "명시적 null"을 구분하지 못하므로 null을 미변경으로
읽는다. 검증은 `@Pattern(regexp = ".*\\S.*")` — Bean Validation은 null을 통과시키므로
위 규칙이 어노테이션 하나로 표현되고, 실패는 기존 `VALIDATION` + `fieldErrors`
경로를 그대로 탄다.

기존 세 필드가 null일 때 값을 지우는 현재 동작은 유지한다. 같은 요청 안에서 필드마다
null의 의미가 다른 것은 일관되지 않지만, 필수 항목과 선택 항목의 성질 차이에서
오는 것이다. 셋을 미변경 의미로 바꾸면 프로필 소개를 지울 방법이 사라진다.

**전화번호 형식 검증은 추가하지 않는다.** 가입 쪽에도 형식 규칙이 없어(`@NotBlank`
뿐, 계약에도 `pattern` 없음), 수정에만 넣으면 "가입은 되는데 수정은 거부되는"
데이터가 생긴다. 형식을 강제하려면 가입·수정·기존 데이터 정리를 함께 다뤄야 하는
별건이다.

## 5. 임기(MemberTerm) 계약 반영

`member_term`에는 "38기 학술부장 → 39기 부회장" 같은 이력이 쌓이는데, FE는
`role` 문자열 한 줄만 받아 "전 학술부장"까지밖에 못 그린다.

임기 배열을 구조화해 내보내되, **`role` 문자열은 유지한다.** `role`은 4단 폴백
(현직 라벨 → `"전 " + 최근 라벨` → 등급 라벨 → `""`)을 거친 결과라 클라이언트가
`terms`만으로 재현하려면 그 규칙을 옮겨 심어야 한다. 서버가 이미 계산하는 값을
계속 보내고, `terms`는 더 풍부한 표시를 원하는 화면이 쓰도록 더한다.

```yaml
MemberTerm:
  type: object
  required: [department, title, startGen]
  properties:
    department: { $ref: '#/components/schemas/MemberDepartment' }
    title:      { $ref: '#/components/schemas/MemberTitle' }
    startGen:   { type: integer }
    endGen:     { type: [integer, 'null'], description: null 이면 현직 }
```

`department`·`title`은 임기에서 NOT NULL이다(임기는 곧 직책을 맡은 기간). 두
스키마가 `'null'`을 포함하는 것은 `Member`의 파생 게터 쪽 사정이므로, `MemberTerm`
안에서는 값이 항상 있다는 사실을 `required`로 표현한다.

정렬은 엔티티의 `@OrderBy("startGen ASC")` 그대로 — 오래된 순이다. 최근 순으로
보여줄 화면은 FE가 뒤집는다.

`terms`는 `MeProfile`·`PersonMember` 어느 쪽에서도 `required`에 넣지 않는다. 서버는
항상 배열을 보내며(임기가 없으면 `[]`), `required`는 두 스키마의 기존 최소 집합
(`[id, name, email, authority]`, `[name, role]`)을 유지한다.

`MeProfile`의 기존 `department`/`title` 평면 필드는 남긴다. 현직 임기에서 파생한
값이라 `terms`와 중복이지만, 제거는 이 작업과 독립적인 별도의 파괴적 변경이다.

## 6. contributor 노출과 탭 이름 통일

`MeProfile.contributor: boolean`을 추가한다. 회원 자신이 기여자로 등록됐는지
확인할 수 있어야 한다.

`exec`(현직 임기 유무)와 `grad`(`grade == OB`)는 **노출하지 않는다.** 둘 다
이미 응답에 있는 값(`title`/`terms`, `grade`)에서 그대로 나오므로, 필드를 더하면
어긋날 수 있는 두 번째 진실원이 생긴다. 앞선 두 리팩터링이 없앤 것이 정확히 그
종류의 중복이다. `contributor`만 파생 근거가 없어 저장되는 값이고, 따라서
노출해야 할 값이다.

탭 이름은 `grad`로 통일한다.

| | 현재 | 변경 후 |
|---|---|---|
| `PeopleResponse` 키 | `exec` / `contrib` / `grad` | 변경 없음 |
| `GET /api/admin/list?resource=members&tab=` | `member` / `exec` / `contrib` / `graduate` | `member` / `exec` / `contrib` / **`grad`** |

같은 집합을 가리키는 이름이 둘일 이유가 없다. admin 쪽을 사람들 페이지에 맞추는
것은 후자가 스키마 키(변경 비용이 큼)인 반면 전자는 쿼리 문자열 값이기 때문이다.

## 7. 변경 파일

### 계약 (`home-jaram-fe` 저장소, 심링크 경유)

| 스키마 | 변경 |
|---|---|
| `SignupRequest` | `gen` → `integer`, `minimum: 1` |
| `MeProfile` | `gen` → `[integer,'null']`; `faculty`(읽기 전용 명시)·`phone`·`contributor`·`terms` 추가 |
| `MeUpdateRequest` | `phone` 추가 |
| `PersonMember` | `gen` → `[integer,'null']`; `terms` 추가 |
| `MemberTerm` | 신설 |
| `/api/admin/list` | `tab` 설명의 `graduate` → `grad` (2곳) |

편집 후 `./scripts/sync-openapi.sh`로 테스트 사본을 갱신한다.

> `develop-backend` 스킬은 "`docs/api/openapi.yaml`을 편집하지 말 것 — FE 소유"라고
> 규정한다. 이번에는 사용자가 두 저장소를 모두 소유하고 계약 변경을 명시적으로
> 지시했으므로 예외로 진행한다.

### BE

| 파일 | 변경 |
|---|---|
| `auth/dto/SignupRequest.java` | `String gen` → `Integer gen`, `@Pattern` → `@NotNull @Positive` |
| `auth/AuthService.java` | `Integer.parseInt(req.gen())` → `req.gen()` |
| `member/dto/MemberTermResponse.java` | **신설** — `(MemberDepartment department, MemberTitle title, int startGen, Integer endGen)` |
| `me/dto/MeProfile.java` | `gen` → `Integer`; `faculty`·`phone`·`contributor`·`terms` 추가 |
| `me/dto/MeUpdateRequest.java` | `@Pattern(".*\\S.*") String phone` 추가 |
| `me/MeService.java` | `+ "기"` 제거, 신규 필드 매핑, `phone` 조건부 반영, 클래스 주석 갱신 |
| `people/dto/PersonMember.java` | `gen` → `Integer`; `terms` 추가, 주석 갱신 |
| `people/PeopleService.java` | `toCard`에서 `gen + "기"` 제거, `terms` 매핑 |
| `admin/AdminResourceService.java` | `case "graduate"` → `case "grad"` |

`MemberTermResponse`를 `member` 패키지에 두는 것은 `me`와 `people` 양쪽이 쓰기
때문이다. 어느 한쪽 `dto/`에 두면 다른 쪽이 남의 기능 패키지를 참조하게 된다.

## 8. 파괴적 변경과 배포

`gen` 타입 변경과 `"기"` 접미사 이동은 **BE와 FE가 함께 배포되어야** 한다. BE만
나가면 FE가 `'41기'`를 기대하는 자리에 `41`이 들어가고, FE만 나가면 `41기`에서
접미사를 한 번 더 붙인다. admin `tab=graduate`를 보내던 FE 호출도 빈 목록을 받는다.

FE 렌더링 코드 수정은 이 작업 범위 밖이며, 계약 변경과 짝지어 별도로 처리한다.
계약이 먼저 바뀌면 FE는 자신이 무엇을 맞춰야 하는지 계약에서 읽을 수 있다.

## 9. 테스트

계약 동기화 후 기존 계약 테스트가 새 스키마로 검증한다. 실패하면 그것이 곧 회귀다
(현재 전체 스위트 211건 그린).

**수정**

- `MeTest` — `body("gen", equalTo("41기"))` → `equalTo(41)`
- `PeopleTest` — `equalTo("38기")`/`equalTo("37기")` → `equalTo(38)`/`equalTo(37)`
- `AuthContractTest`·`SignupTest` — `"gen", "41"` → `"gen", 41`
- `AdminResourceTest` — `tab=graduate` → `tab=grad`

**신규**

- `MeTest` — `MeProfile`에 `faculty`·`phone`·`contributor`가 실린다
- `MeTest` — PATCH가 `phone`을 수정한다 / `phone` 생략 시 값이 유지된다 /
  공백 `phone`은 422 `VALIDATION`
- `MeTest` — PATCH 요청 본문에 `faculty`를 넣어도 학부가 바뀌지 않는다
- `MeTest`·`PeopleTest` — 현직 1건 + 종료 1건을 가진 회원의 `terms`가 순서·`endGen`
  까지 맞게 나오고, 임기 없는 회원은 빈 배열이다
- `PeopleTest` — `PersonMember`에 `phone`·`faculty`가 **없다**
- `SignupTest` — `gen` 누락 시 422, `gen: 0` 시 422

마지막 항목이 `gen: "41"`(문자열)에 대한 테스트가 아닌 것은 Jackson이 숫자 문자열을
`Integer`로 강제 변환하기 때문이다. 문자열 거부는 계약 테스트의 요청 검증이 맡는다.

## 10. 결정 기록

- **`gen`은 정수, `"기"`는 FE** — 기수는 수이고 접미사는 표시다. admin 경로가 이미
  정수를 보내고 있어, 정수 쪽으로 맞추는 것이 예외를 없애는 방향이다.
- **`faculty` 읽기 전용** — 학부는 본인이 수시로 바꿀 값이 아니다. 수정 경로를 열지
  않으면 검증할 것도 없다.
- **`phone` 형식 검증 없음** — 가입에 규칙이 없는데 수정에만 두면 고칠 수 없는
  데이터가 생긴다. 형식 강제는 양쪽을 함께 다루는 별건이다.
- **`role` 유지** — 4단 폴백은 서버 규칙이다. `terms`를 준다고 그 규칙을 FE로
  옮기면 같은 계산이 두 곳에 생긴다.
- **`exec`/`grad`는 노출하지 않음** — 응답에 이미 있는 값에서 파생된다. 필드로
  더하면 두 번째 진실원이 된다. `contributor`만 저장되는 값이라 노출한다.
- **admin `tab`을 `grad`로** — 스키마 키보다 쿼리 문자열 값을 바꾸는 쪽이 싸다.
