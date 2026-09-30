---
name: develop-backend
description: jaram-be(Spring Boot)에 API 엔드포인트나 기능을 구현·수정하는 절차. 계약 동기화 → 테스트 먼저 → 계층 구현 → 권한 → 스키마 → 검증을 매번 같은 순서로 진행한다.
when_to_use: 백엔드에 기능·엔드포인트를 추가/구현/수정할 때, 컨트롤러·서비스·엔티티·권한을 건드릴 때, 기존 엔드포인트에 계약·권한 테스트만 추가할 때, FE가 openapi.yaml을 바꿔 BE를 맞춰야 할 때. 이런 요청이면 코드를 탐색하기 전에 먼저 부른다. 로컬 실행·스모크 테스트는 run-backend, 여러 저장소에 걸친 기획은 write-spec을 쓴다.
---

# 백엔드 기능 구현 절차

규칙(계약 symlink, enum 대소문자, 권한 누락 시 동작, 마이그레이션)은 CLAUDE.md에 있다. 이 skill은 그 규칙을 지키는 순서다.

## 1. 시작할 때
- `./scripts/sync-openapi.sh` → `git diff src/test/resources/openapi/openapi.yaml`로 바뀐 계약을 본다.
- 바로 `./gradlew test --tests '*ContractTest'`. 모두 `ApiLoadException`으로 실패하면 FE YAML 파싱 오류다. FE에 알리고, 급하면 사본만 최소 수정한다(파싱 안 되는 사본은 커밋하지 않는다).
- 만들 것: operation은 `docs/api/openapi.yaml`, 결정은 계획 저장소 `home-jaram/specs/<날짜>-<기능>/design.md`, 할 일은 `tasks.md`.
- 계약·도메인·권한이 바뀌는데 spec이 없으면 멈추고 루트 세션에서 `write-spec`으로 먼저 쓴다.
- `docs/superpowers/`는 배경 참고용이다. 코드와 다르면 코드가 맞다.

## 2. 테스트 먼저 (task마다)
- 인수 조건마다 성공 + 에러 분기를 먼저 쓰고 실패를 확인한다.
- 권한이 걸린 엔드포인트는 허용 역할 하나, 403 역할 하나 이상을 테스트한다.
- 행위자는 `support.Actors`로 만든다(`token(Role.X)`, `officer()`, `member()`). DB에 없는 id로 찍은 토큰에는 권한이 없다.
- 새 operation마다 `com.jaram.be.contract`에 계약 테스트를 하나 둔다(`OpenApiValidationFilter("openapi/openapi.yaml")`).
- 본뜰 예: 저장소 `MemberRepositoryTest`, 엔드포인트 `SignupTest`, 계약 `StudyContractTest`, 순수 단위 `JwtProviderTest`.

## 3. 구현
- Controller: operation과 1:1. 경로·메서드·DTO(`record` + `@Valid`)를 스키마와 똑같이 맞춘다. 201/204는 `void` + `@ResponseStatus`(`AuthController.signup`). 로그인 사용자는 `@AuthenticationPrincipal CurrentMember me`.
- Service: `@Transactional`. 오류는 모두 `ApiException(status, code, message[, fieldErrors])`로 던진다.
- 에러 코드는 계약의 에러 응답을 따른다. 기존 코드를 먼저 찾아 쓰고(`grep -rn 'new ApiException' src/main`), 새 코드는 FE 계약에 먼저 넣는다.
- 신청류 동작은 대상 조회 **전에** `eligibility.requireActive(memberId)`를 부른다. 순서가 바뀌면 재등록 대상에게 403 대신 404가 나간다.
- Entity: 정적 팩토리(`Member.newPending`), `protected` 기본 생성자, `String id` = UUID, Lombok 없음. 검증 정규식은 `SignupRequest`에서 복사한다.

## 4. 권한 (핸들러마다)
- 역할로 판단: `@PreAuthorize("hasAuthority('<Permission>')")`. 새 권한이면 `Permission` 추가 → `Policy` 표에 부여 → 같은 문자열로 애너테이션.
- 본인 리소스로 판단: 기능 패키지의 `*Access` 빈을 쓴다. 예: `@PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")`.
- 공개·로그인만 필요: `AuthorizationCoverageTest`의 `PUBLIC` / `AUTHENTICATED_ONLY`에 적는다. 공개면 `SecurityConfig`에 `permitAll()` 매처도 추가한다. 역할 매처는 `SecurityConfig`에 두지 않는다.

## 5. 스키마
- 데이터 이관, 제약 변경, 기존 컬럼 NOT NULL이 필요하면 `docs/migrations/`에 SQL을 두고 `design.md`에 배포 전/후 실행 시점을 적는다.
- 구현 중 설계가 바뀌면 코드보다 `design.md`를 먼저 고친다.

## 6. 끝낼 때
- `./gradlew test` 전체 통과(계약 테스트, `AuthorizationCoverageTest` 포함).
- task 하나당 커밋 하나. 로컬에서 띄워 확인할 때는 `run-backend`.
