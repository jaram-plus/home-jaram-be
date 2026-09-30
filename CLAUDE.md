# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

JARAM(자람) 홈페이지 백엔드. Spring Boot 3.4 / Java 21 / Gradle, 패키지 루트 `com.jaram.be`(기능별 패키지). 기능 구현 절차는 `develop-backend` skill, 로컬 실행은 `run-backend` skill.

## 브랜치 · 커밋
- PR 대상은 `develop`이다.
- 커밋 제목은 `type(scope): 한국어 평서문` 형식이다. 예: `feat(study): 스터디원을 내보낸다`.

## API 계약
- `docs/api/openapi.yaml`은 형제 저장소 `../home-jaram-fe`를 가리키는 **symlink**다. FE가 계약의 소유자이므로 이 파일은 고치지 않는다. 계약에 문제가 있으면 FE에서 고친다.
- 테스트는 이 symlink가 아니라 사본 `src/test/resources/openapi/openapi.yaml`을 읽는다. 계약이 바뀌면 `./scripts/sync-openapi.sh`를 실행한 뒤 `./gradlew test --tests '*ContractTest'`로 확인한다.
- worktree 안에서는 이 symlink가 `.claude/worktrees/home-jaram-fe`를 거쳐 해석된다. 그 링크가 실제 FE 저장소를 가리키지 않으면 sync가 실패한다.
- enum이 JSON으로 오가는 값의 대소문자는 FE가 정한 그대로 따른다. 소문자 값을 쓰는 enum은 상수 이름도 소문자로 둔다.

## 권한
- 모든 컨트롤러 핸들러에는 `@PreAuthorize("hasAuthority('<Permission>')")`를 붙인다. 붙이지 않을 핸들러는 `AuthorizationCoverageTest`의 `PUBLIC` 또는 `AUTHENTICATED_ONLY` 목록에 이름을 적는다. 둘 다 빠지면 `anyRequest().authenticated()` 때문에 로그인한 누구나 접근할 수 있게 된다.
- 어떤 역할이 어떤 권한을 갖는지는 `security/authz/Policy`의 표에 있다. `Permission` 이름을 바꾸면 애너테이션의 문자열도 함께 바꿔야 하는데, 컴파일러는 이를 잡지 못한다.
- "본인 스터디만 수정" 같은 리소스 단위 조건은 기능 패키지의 `*Access` 클래스(예: `StudyAccess`)에 둔다.

## 검증 · DB
- 테스트는 `./gradlew test`, 한 클래스만 돌릴 때는 `./gradlew test --tests <Class>`로 실행한다. Testcontainers를 쓰므로 Docker가 필요하다.
- `JWT_SECRET`에는 기본값이 없다. 로컬에서 실행할 때도 반드시 넣어야 한다. 설정하지 않으면 앱이 뜨지 않는데, 이는 의도된 동작이다.
- 스키마는 `ddl-auto: update`로 관리하며 Flyway는 없다. Hibernate가 처리하지 못하는 변경(데이터 이관, 제약 변경, NOT NULL 추가)은 `docs/migrations/`에 수동 SQL로 남긴다. 작성 규칙은 그 폴더의 README를 따른다.
- 여러 저장소에 걸친 기능 spec은 상위 계획 저장소의 `specs/`에 있다.
