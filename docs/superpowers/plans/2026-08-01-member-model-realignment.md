# 회원 모델 계약 재정립 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 두 차례의 회원 도메인 리팩터링 결과(gen 정수화, 임기 이력, contributor, faculty/phone)를 OpenAPI 계약과 BE DTO에 반영한다.

**Architecture:** 표현 계층만 바꾼다. 엔티티·리포지토리·DB 스키마는 전혀 손대지 않는다 — 저장된 값은 이미 옳고, 밖으로 내보내는 모양만 틀렸다. 각 태스크는 계약(OpenAPI) 수정 → 테스트 사본 동기화 → BE DTO/매핑 수정 → 테스트 순으로 진행한다.

**Tech Stack:** Spring Boot 3.4, Java 21, Gradle, JPA/Hibernate, Bean Validation, Jackson, RestAssured + Testcontainers(postgres:16-alpine), swagger-request-validator 2.43.0

**설계 문서:** `docs/superpowers/specs/2026-08-01-member-model-realignment-design.md`

## Global Constraints

- **엔티티·리포지토리·마이그레이션은 수정하지 않는다.** `Member.gen`은 이미 `Integer`, `faculty`·`phone`·`contributor`·`member_term`도 모두 존재한다. DB 마이그레이션은 없다.
- **계약 파일 `docs/api/openapi.yaml`은 FE 저장소로의 심링크다** (`/home/ksb/Dev/home-jaram/home-jaram-fe/docs/api/openapi.yaml`). 이 파일을 편집하면 FE 저장소의 작업 트리가 바뀌고, BE 저장소의 `git status`에는 나타나지 않는다. FE 저장소 커밋은 Task 7에서 한 번에 처리한다.
- **계약을 편집한 직후 반드시 `./scripts/sync-openapi.sh`를 실행한다.** 계약 테스트는 사본 `src/test/resources/openapi/openapi.yaml`을 읽는다. 이 사본은 BE 저장소에 커밋되므로 각 태스크의 커밋에 포함된다.
- **enum 와이어 값의 대소문자를 바꾸지 않는다.** `Authority`=`MEMBER`/`OFFICER`(대문자), people 탭 키=`exec`/`contrib`/`grad`(소문자).
- 에러 응답은 항상 `GlobalExceptionHandler` 봉투 `{code, message, fieldErrors}`. Bean Validation 실패 = 422 `VALIDATION`.
- 커밋은 Conventional Commit. 각 태스크의 테스트가 통과한 시점에 커밋한다.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`를 붙인다.

### 테스트 실행 전제 (이 호스트 한정)

호스트에는 JDK 17과 Docker Engine 29만 있다. 최초 1회 아래 init 스크립트를 만든다:

```bash
cat > /tmp/docker-api.init.gradle <<'EOF'
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'api.version', '1.44'
    }
}
EOF
```

이후 모든 테스트는 이 형태로 실행한다:

```bash
cd /home/ksb/Dev/home-jaram/home-jaram-be
export JAVA_HOME=/home/ksb/.local/jdk-21; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon -I /tmp/docker-api.init.gradle test --tests '<ClassName>'
```

이 플랜에서 `RUN <ClassName>`이라고 쓰면 위 명령을 뜻한다. 시작 시점 기준 전체 스위트는 211건 그린이므로, 예상하지 않은 실패는 곧 회귀다.

## File Structure

| 파일 | 책임 | 태스크 |
|---|---|---|
| `docs/api/openapi.yaml` (심링크) | 계약 원본. `SignupRequest`·`MeProfile`·`MeUpdateRequest`·`PersonMember` 수정, `MemberTerm` 신설, admin `tab` 설명 | 1–6 |
| `src/test/resources/openapi/openapi.yaml` | 계약 테스트가 읽는 사본. sync 스크립트로만 갱신 | 1–6 |
| `auth/dto/SignupRequest.java` | 가입 요청 DTO. `gen`을 `Integer`로 | 1 |
| `auth/AuthService.java` | 가입 처리. `parseInt` 제거 | 1 |
| `member/dto/MemberTermResponse.java` | **신설.** `MemberTerm`의 와이어 표현. `me`·`people` 양쪽이 쓰므로 공용 `member` 패키지에 둔다 | 3 |
| `me/dto/MeProfile.java` | 본인 프로필 응답 DTO | 2, 3, 4 |
| `me/dto/MeUpdateRequest.java` | 프로필 수정 요청 DTO | 5 |
| `me/MeService.java` | 프로필 조회/수정 매핑 | 2, 3, 4, 5 |
| `people/dto/PersonMember.java` | 사람들 카드 DTO | 2, 3 |
| `people/PeopleService.java` | 사람들 탭 매핑 | 2, 3 |
| `admin/AdminResourceService.java` | admin 목록 탭 필터 | 6 |

---

### Task 1: `SignupRequest.gen`을 정수로

**Files:**
- Modify: `docs/api/openapi.yaml` — `SignupRequest.gen` (사본 기준 L1110-1114)
- Modify: `src/test/resources/openapi/openapi.yaml` (sync 스크립트로)
- Modify: `src/main/java/com/jaram/be/auth/dto/SignupRequest.java:23-25`
- Modify: `src/main/java/com/jaram/be/auth/AuthService.java:57`
- Test: `src/test/java/com/jaram/be/auth/SignupTest.java`
- Test: `src/test/java/com/jaram/be/contract/AuthContractTest.java:37`

**Interfaces:**
- Produces: `SignupRequest.gen()` → `Integer` (이전 `String`). `AuthService.signup`만 호출한다.

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`SignupTest.java`의 `valid()`에서 `gen`을 정수로 바꾸고, 검증 테스트 두 개를 추가한다.

```java
    private Map<String, Object> valid() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "홍길동");
        m.put("studentId", "2023012345");
        m.put("email", "hong@hanyang.ac.kr");
        m.put("password", "passw0rd!");
        m.put("gen", 41);
        m.put("faculty", "컴퓨터학부");
        m.put("phone", "010-1234-5678");
        m.put("enrolled", true);
        return m;
    }
```

`missingNewFieldsReturns422` 아래에 추가:

```java
    @Test
    void missingGenReturns422() {
        Map<String, Object> bad = valid();
        bad.remove("gen");
        given().contentType("application/json").body(bad)
                .when().post("/api/auth/signup")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void zeroGenReturns422() {
        Map<String, Object> bad = valid();
        bad.put("gen", 0);
        given().contentType("application/json").body(bad)
                .when().post("/api/auth/signup")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }
```

`gen`이 문자열 `"41"`인 경우를 테스트하지 않는 이유: Jackson이 숫자 문자열을 `Integer`로 강제 변환하므로 그런 테스트는 절대 실패하지 않는다. 문자열 거부는 Step 6의 계약 테스트(요청 검증)가 맡는다.

- [ ] **Step 2: 실행해서 실패를 확인한다**

RUN `SignupTest`
Expected: `zeroGenReturns422` FAIL — 현재 `@Pattern("^\\d+$")`가 `"0"`을 통과시켜 201이 돌아온다. `missingGenReturns422`는 이미 통과한다(회귀 방지용).

- [ ] **Step 3: 계약의 `SignupRequest.gen`을 정수로 바꾼다**

`docs/api/openapi.yaml`에서 아래 블록을

```yaml
        gen:
          type: string
          pattern: '^\d+$'
          example: '41'
          description: 기수(숫자만). 신입생은 가입 연도 기준 FE 자동 산출.
```

이렇게 바꾼다:

```yaml
        gen:
          type: integer
          minimum: 1
          example: 41
          description: 기수. 신입생은 가입 연도 기준 FE 자동 산출.
```

- [ ] **Step 4: 계약 사본을 동기화한다**

```bash
./scripts/sync-openapi.sh
git diff src/test/resources/openapi/openapi.yaml
```

Expected: `Synced contract -> ...` 와 위 `gen` 블록만 담긴 diff.

- [ ] **Step 5: `SignupRequest`와 `AuthService`를 고친다**

`SignupRequest.java` — `gen` 컴포넌트를 교체한다:

```java
        @NotNull(message = "기수를 입력해 주세요.")
        @Positive(message = "기수는 1 이상의 숫자여야 합니다.")
        Integer gen,
```

import를 정리한다 — `jakarta.validation.constraints.Positive`를 추가한다. `NotNull`은 이미 import되어 있고, `Pattern`·`NotBlank`는 다른 필드가 계속 쓰므로 남긴다.

`AuthService.java:57`:

```java
        m.setGen(req.gen());
```

(주석 `// @Pattern ^\d+$ guarantees parseable`은 함께 지운다.)

- [ ] **Step 6: 계약 테스트의 요청 본문을 고친다**

`AuthContractTest.java:37`의 `"gen", "41"` → `"gen", 41`:

```java
                .body(Map.of("name", "홍길동", "studentId", "2023012345",
                        "email", "hong@hanyang.ac.kr", "password", "passw0rd!",
                        "gen", 41, "faculty", "컴퓨터학부",
                        "phone", "010-1234-5678", "enrolled", true))
```

- [ ] **Step 7: 테스트를 실행해 통과를 확인한다**

RUN `SignupTest`
RUN `AuthContractTest`
Expected: 둘 다 PASS.

- [ ] **Step 8: 커밋한다**

```bash
git add src/test/resources/openapi/openapi.yaml \
        src/main/java/com/jaram/be/auth/dto/SignupRequest.java \
        src/main/java/com/jaram/be/auth/AuthService.java \
        src/test/java/com/jaram/be/auth/SignupTest.java \
        src/test/java/com/jaram/be/contract/AuthContractTest.java
git commit -m "$(cat <<'EOF'
refactor(auth): 가입 요청의 gen을 정수로 받는다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `MeProfile.gen`·`PersonMember.gen`을 정수로 (`"기"` 접미사 제거)

**Files:**
- Modify: `docs/api/openapi.yaml` — `MeProfile.gen`(사본 L1041), `PersonMember.gen`(사본 L1148-1151)
- Modify: `src/test/resources/openapi/openapi.yaml` (sync 스크립트로)
- Modify: `src/main/java/com/jaram/be/me/dto/MeProfile.java:19`
- Modify: `src/main/java/com/jaram/be/me/MeService.java:12-15,53`
- Modify: `src/main/java/com/jaram/be/people/dto/PersonMember.java:3,7`
- Modify: `src/main/java/com/jaram/be/people/PeopleService.java:75`
- Test: `src/test/java/com/jaram/be/me/MeTest.java:53`
- Test: `src/test/java/com/jaram/be/people/PeopleTest.java:77,126`

**Interfaces:**
- Consumes: 없음 (Task 1과 독립)
- Produces: `MeProfile.gen()` → `Integer`, `PersonMember.gen()` → `Integer`. Task 3·4가 이 두 레코드에 필드를 더 추가한다.

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`MeTest.java:53`:

```java
                .body("gen", equalTo(41))
```

`PeopleTest.java:77`:

```java
                .body("contrib.groups[0].members[0].gen", equalTo(38))
```

`PeopleTest.java:74`의 주석 `// gen rendered as "{n}기"; contrib/grad single unnamed group`을 `// gen은 정수로 그대로 나간다; contrib/grad는 이름 없는 단일 그룹`으로 바꾼다.

`PeopleTest.java:126`:

```java
                .body("grad.groups[0].members[0].gen", equalTo(37));   // 2021 - 1984
```

- [ ] **Step 2: 실행해서 실패를 확인한다**

RUN `MeTest`
RUN `PeopleTest`
Expected: `getReturnsOwnProfile` FAIL (`expected: 41, actual: "41기"`), `returnsActiveMembersGroupedByTab`·`graduateGenComesFromStudentIdPrefix` FAIL (같은 이유).

- [ ] **Step 3: 계약의 두 `gen`을 정수로 바꾼다**

`docs/api/openapi.yaml`의 `MeProfile`에서:

```yaml
        gen: { type: [integer, 'null'], example: 41 }
```

`PersonMember`에서:

```yaml
        gen:
          type: [integer, 'null']
          example: 41
          description: 표시 기수. 재학 중에는 가입 기수, OB 는 학번에서 파생한 입학 기수를 쓴다.
```

- [ ] **Step 4: 계약 사본을 동기화한다**

```bash
./scripts/sync-openapi.sh
git diff src/test/resources/openapi/openapi.yaml
```

Expected: 위 두 블록만 바뀐 diff.

- [ ] **Step 5: `MeProfile`과 `MeService`를 고친다**

`MeProfile.java:19`의 `String gen,` → `Integer gen,`

`MeService.java:53`의 `m.getGen() == null ? null : m.getGen() + "기",` → `m.getGen(),`

`MeService.java:12-15`의 클래스 javadoc을 교체한다:

```java
/**
 * GET/PATCH /api/me: the authenticated member's own profile. gen goes out as a
 * plain integer — the "기" suffix is FE's to render. Profile edits touch only
 * bio/github/blog.
 */
```

- [ ] **Step 6: `PersonMember`와 `PeopleService`를 고친다**

`PersonMember.java` 전체:

```java
package com.jaram.be.people.dto;

// Matches OpenAPI schema PersonMember. gen is the display 기수 as a plain integer, or null.
public record PersonMember(
        String name,
        String role,
        Integer gen,
        String bio,
        String githubUrl,
        String blogUrl
) { }
```

`PeopleService.java:70-79`의 `toCard`:

```java
    private PersonMember toCard(Member m) {
        return new PersonMember(
                m.getName(),
                roleLabel(m),
                displayGen(m),
                m.getBio(),
                m.getGithubUrl(),
                m.getBlogUrl());
    }
```

(지역 변수 `Integer gen`은 더 이상 쓰이지 않으므로 사라진다.)

- [ ] **Step 7: 테스트를 실행해 통과를 확인한다**

RUN `MeTest`
RUN `PeopleTest`
RUN `MeContractTest`
RUN `PeopleContractTest`
Expected: 넷 다 PASS. 두 계약 테스트는 소스 수정이 필요 없다 — 응답을 새 스키마로 검증하므로 타입이 어긋나면 여기서 잡힌다.

- [ ] **Step 8: 커밋한다**

```bash
git add src/test/resources/openapi/openapi.yaml \
        src/main/java/com/jaram/be/me/dto/MeProfile.java \
        src/main/java/com/jaram/be/me/MeService.java \
        src/main/java/com/jaram/be/people/dto/PersonMember.java \
        src/main/java/com/jaram/be/people/PeopleService.java \
        src/test/java/com/jaram/be/me/MeTest.java \
        src/test/java/com/jaram/be/people/PeopleTest.java
git commit -m "$(cat <<'EOF'
refactor: 응답의 gen을 정수로 내보낸다

"기" 접미사는 표시 계층의 일이므로 FE로 넘긴다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 임기(`terms`)를 계약에 반영

**Files:**
- Modify: `docs/api/openapi.yaml` — `MemberTerm` 스키마 신설, `MeProfile`·`PersonMember`에 `terms` 추가
- Modify: `src/test/resources/openapi/openapi.yaml` (sync 스크립트로)
- Create: `src/main/java/com/jaram/be/member/dto/MemberTermResponse.java`
- Modify: `src/main/java/com/jaram/be/me/dto/MeProfile.java`
- Modify: `src/main/java/com/jaram/be/me/MeService.java`
- Modify: `src/main/java/com/jaram/be/people/dto/PersonMember.java`
- Modify: `src/main/java/com/jaram/be/people/PeopleService.java`
- Test: `src/test/java/com/jaram/be/me/MeTest.java`
- Test: `src/test/java/com/jaram/be/people/PeopleTest.java`

**Interfaces:**
- Consumes: Task 2의 `MeProfile.gen`/`PersonMember.gen`(`Integer`)
- Produces:
  - `com.jaram.be.member.dto.MemberTermResponse(MemberDepartment department, MemberTitle title, int startGen, Integer endGen)`
  - `static MemberTermResponse MemberTermResponse.of(MemberTerm t)`
  - `MeProfile.terms()` → `List<MemberTermResponse>`, `PersonMember.terms()` → `List<MemberTermResponse>`

- [ ] **Step 1: 실패하는 테스트를 작성한다 (`MeTest`)**

`MeTest.java`의 import에 추가:

```java
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTitle;
```

`patchAnonymousReturns401` 아래에 추가:

```java
    @Test
    void getReturnsTermsOldestFirst() {
        Member m = members.findByEmail("hong@hanyang.ac.kr").orElseThrow();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 40);
        m.endCurrentTerm(40);
        m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 41);
        members.saveAndFlush(m);

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/me")
                .then().statusCode(200)
                .body("terms.size()", equalTo(2))
                .body("terms[0].department", equalTo("ACADEMIC"))
                .body("terms[0].title", equalTo("LEAD"))
                .body("terms[0].startGen", equalTo(40))
                .body("terms[0].endGen", equalTo(40))
                .body("terms[1].department", equalTo("LEADERSHIP"))
                .body("terms[1].title", equalTo("PRESIDENT"))
                .body("terms[1].startGen", equalTo(41))
                .body("terms[1].endGen", nullValue());
    }

    @Test
    void getReturnsEmptyTermsWhenNoTermExists() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/me")
                .then().statusCode(200)
                .body("terms", hasSize(0));
    }
```

- [ ] **Step 2: 실패하는 테스트를 작성한다 (`PeopleTest`)**

`pastOfficerKeepsRoleWithFormerPrefix`(L107-117)에 종료 임기 검증을 덧붙인다:

```java
    @Test
    void pastOfficerKeepsRoleWithFormerPrefix() {
        Member m = active("박선배", "2021000001", "senior@jaram.net", 37);
        m.setGrade(MemberGrade.OB);
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        m.endCurrentTerm(41);
        members.save(m);

        given().when().get("/api/people").then().statusCode(200)
                .body("grad.groups[0].members[0].role", equalTo("전 학술부장"))
                .body("grad.groups[0].members[0].terms.size()", equalTo(1))
                .body("grad.groups[0].members[0].terms[0].department", equalTo("ACADEMIC"))
                .body("grad.groups[0].members[0].terms[0].title", equalTo("LEAD"))
                .body("grad.groups[0].members[0].terms[0].startGen", equalTo(41))
                .body("grad.groups[0].members[0].terms[0].endGen", equalTo(41));
    }
```

`graduateGenComesFromStudentIdPrefix`(L119-127)에 빈 배열 검증을 덧붙인다:

```java
        given().when().get("/api/people").then().statusCode(200)
                .body("grad.groups[0].members[0].gen", equalTo(37))   // 2021 - 1984
                .body("grad.groups[0].members[0].terms", hasSize(0));
```

- [ ] **Step 3: 실행해서 실패를 확인한다**

RUN `MeTest`
RUN `PeopleTest`
Expected: 새/수정된 4개 테스트 FAIL — 응답에 `terms` 키가 없어 `terms.size()`가 null이거나 `hasSize`가 실패한다.

- [ ] **Step 4: 계약에 `MemberTerm` 스키마를 추가한다**

`docs/api/openapi.yaml`의 `MemberStatus` 정의 바로 뒤, `MeProfile` 앞에 넣는다:

```yaml
    MemberTerm:
      type: object
      description: 한 회원이 한 직책을 맡은 기간. 오래된 순(startGen ASC)으로 정렬해 보낸다.
      required: [department, title, startGen]
      properties:
        department: { $ref: '#/components/schemas/MemberDepartment' }
        title: { $ref: '#/components/schemas/MemberTitle' }
        startGen: { type: integer }
        endGen: { type: [integer, 'null'], description: null 이면 현직 }
```

- [ ] **Step 5: 계약의 `MeProfile`·`PersonMember`에 `terms`를 추가한다**

`MeProfile`의 `title` 줄 바로 뒤:

```yaml
        terms:
          type: array
          description: 임기 이력(오래된 순). 임기가 없으면 빈 배열.
          items: { $ref: '#/components/schemas/MemberTerm' }
```

`PersonMember`의 `gen` 블록 바로 뒤에 같은 블록을 넣는다. **두 스키마 모두 `required`는 건드리지 않는다** — 서버가 항상 배열을 보내지만, 기존 최소 집합(`[id, name, email, authority]`, `[name, role]`)을 유지한다.

- [ ] **Step 6: 계약 사본을 동기화한다**

```bash
./scripts/sync-openapi.sh
git diff src/test/resources/openapi/openapi.yaml
```

Expected: `MemberTerm` 신설 + `terms` 두 곳 추가만 담긴 diff.

- [ ] **Step 7: `MemberTermResponse`를 만든다**

Create `src/main/java/com/jaram/be/member/dto/MemberTermResponse.java`:

```java
package com.jaram.be.member.dto;

import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTerm;
import com.jaram.be.member.MemberTitle;

/**
 * OpenAPI 스키마 MemberTerm 의 와이어 표현. 임기 안에서는 department·title 이 항상
 * 있고, endGen 이 null 이면 현직이다. me·people 양쪽이 쓰므로 공용 member 패키지에 둔다.
 */
public record MemberTermResponse(
        MemberDepartment department,
        MemberTitle title,
        int startGen,
        Integer endGen) {

    public static MemberTermResponse of(MemberTerm t) {
        return new MemberTermResponse(t.getDepartment(), t.getTitle(), t.getStartGen(), t.getEndGen());
    }
}
```

- [ ] **Step 8: `MeProfile`과 `MeService`에 `terms`를 연결한다**

`MeProfile.java` 전체:

```java
package com.jaram.be.me.dto;

import com.jaram.be.member.Authority;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.member.dto.MemberTermResponse;

import java.util.List;

public record MeProfile(
        String id,
        String name,
        String studentId,
        String email,
        Authority authority,
        MemberGrade grade,            // enum name, nullable (승인 전)
        MemberStatus status,          // 활동축 enum name
        MemberDepartment department,  // 현직 임기에서 파생, nullable
        MemberTitle title,            // 현직 임기에서 파생, nullable
        List<MemberTermResponse> terms,   // 임기 이력(오래된 순), 없으면 빈 배열
        Integer gen,
        String bio,
        String githubUrl,
        String blogUrl) {
}
```

`MeService.java`의 `toProfile`에서 `m.getTitle(),` 다음 줄에 추가:

```java
                m.getTerms().stream().map(MemberTermResponse::of).toList(),
```

import에 `com.jaram.be.member.dto.MemberTermResponse`를 추가한다.

- [ ] **Step 9: `PersonMember`와 `PeopleService`에 `terms`를 연결한다**

`PersonMember.java` 전체:

```java
package com.jaram.be.people.dto;

import com.jaram.be.member.dto.MemberTermResponse;

import java.util.List;

// Matches OpenAPI schema PersonMember. gen is the display 기수 as a plain integer, or null.
public record PersonMember(
        String name,
        String role,
        Integer gen,
        List<MemberTermResponse> terms,   // 임기 이력(오래된 순), 없으면 빈 배열
        String bio,
        String githubUrl,
        String blogUrl
) { }
```

`PeopleService.java`의 `toCard`:

```java
    private PersonMember toCard(Member m) {
        return new PersonMember(
                m.getName(),
                roleLabel(m),
                displayGen(m),
                m.getTerms().stream().map(MemberTermResponse::of).toList(),
                m.getBio(),
                m.getGithubUrl(),
                m.getBlogUrl());
    }
```

import에 `com.jaram.be.member.dto.MemberTermResponse`를 추가한다.

- [ ] **Step 10: 테스트를 실행해 통과를 확인한다**

RUN `MeTest`
RUN `PeopleTest`
RUN `MeContractTest`
RUN `PeopleContractTest`
Expected: 넷 다 PASS. `MeContractTest`의 회원은 임기가 없으므로 빈 배열이, `PeopleContractTest`의 두 회원은 현직 임기가 있으므로 `MemberTerm` 스키마가 실제로 검증된다.

- [ ] **Step 11: 커밋한다**

```bash
git add src/test/resources/openapi/openapi.yaml \
        src/main/java/com/jaram/be/member/dto/MemberTermResponse.java \
        src/main/java/com/jaram/be/me/dto/MeProfile.java \
        src/main/java/com/jaram/be/me/MeService.java \
        src/main/java/com/jaram/be/people/dto/PersonMember.java \
        src/main/java/com/jaram/be/people/PeopleService.java \
        src/test/java/com/jaram/be/me/MeTest.java \
        src/test/java/com/jaram/be/people/PeopleTest.java
git commit -m "$(cat <<'EOF'
feat: 임기 이력을 프로필·사람들 응답에 실어 보낸다

role 문자열은 그대로 유지한다 — 4단 폴백은 서버 규칙이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `MeProfile`에 `faculty`·`phone`·`contributor` 노출

**Files:**
- Modify: `docs/api/openapi.yaml` — `MeProfile`에 세 필드 추가
- Modify: `src/test/resources/openapi/openapi.yaml` (sync 스크립트로)
- Modify: `src/main/java/com/jaram/be/me/dto/MeProfile.java`
- Modify: `src/main/java/com/jaram/be/me/MeService.java`
- Test: `src/test/java/com/jaram/be/me/MeTest.java`
- Test: `src/test/java/com/jaram/be/people/PeopleTest.java`

**Interfaces:**
- Consumes: Task 3의 `MeProfile`(terms 포함)
- Produces: `MeProfile.faculty()` → `String`, `.phone()` → `String`, `.contributor()` → `boolean`

- [ ] **Step 1: 실패하는 테스트를 작성한다 (`MeTest`)**

`MeTest.setup()`의 `m.setGithubUrl(...)` 다음에 추가:

```java
        m.setFaculty("컴퓨터학부");
        m.setPhone("010-1234-5678");
        m.setContributor(true);
```

새 테스트를 추가한다:

```java
    @Test
    void getReturnsFacultyPhoneAndContributor() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/me")
                .then().statusCode(200)
                .body("faculty", equalTo("컴퓨터학부"))
                .body("phone", equalTo("010-1234-5678"))
                .body("contributor", equalTo(true));
    }
```

- [ ] **Step 2: 실패하는 테스트를 작성한다 (`PeopleTest`)**

공개 카드에 개인정보가 새지 않는지 확인한다. `emptyDatabaseReturnsEmptyGroups` 앞에 추가:

```java
    @Test
    void personCardOmitsFacultyAndPhone() {
        Member m = active("박나눔", "2023000020", "priv@hanyang.ac.kr", 41);
        m.setContributor(true);
        m.setFaculty("컴퓨터학부");
        m.setPhone("010-1234-5678");
        members.save(m);

        given().when().get("/api/people").then().statusCode(200)
                .body("contrib.groups[0].members[0]", not(hasKey("faculty")))
                .body("contrib.groups[0].members[0]", not(hasKey("phone")));
    }
```

`hasKey`는 `org.hamcrest.Matchers.*` 와일드카드 import(L19)에 이미 포함된다.

- [ ] **Step 3: 실행해서 실패를 확인한다**

RUN `MeTest`
RUN `PeopleTest`
Expected: `getReturnsFacultyPhoneAndContributor` FAIL (세 키 모두 없음). `personCardOmitsFacultyAndPhone`은 통과한다 — `PersonMember`에 애초에 그 필드가 없기 때문이며, 이 테스트는 앞으로 새지 않도록 막는 회귀 방지선이다.

- [ ] **Step 4: 계약의 `MeProfile`에 세 필드를 추가한다**

`gen` 줄 바로 뒤에 넣는다:

```yaml
        faculty: { type: [string, 'null'], example: 컴퓨터학부, description: 학부. 읽기 전용 — MeUpdateRequest 로 수정할 수 없다. }
        phone: { type: [string, 'null'], example: 010-1234-5678 }
        contributor: { type: boolean, description: 기여자 등록 여부 }
```

`required`는 건드리지 않는다.

- [ ] **Step 5: 계약 사본을 동기화한다**

```bash
./scripts/sync-openapi.sh
git diff src/test/resources/openapi/openapi.yaml
```

Expected: 위 세 줄만 추가된 diff.

- [ ] **Step 6: `MeProfile`과 `MeService`를 고친다**

`MeProfile.java`의 `Integer gen,` 다음 세 줄을 삽입한다:

```java
        Integer gen,
        String faculty,               // 읽기 전용 — MeUpdateRequest 에는 없다
        String phone,
        boolean contributor,
        String bio,
```

`MeService.java`의 `toProfile`에서 `m.getGen(),` 다음 세 줄을 삽입한다:

```java
                m.getGen(),
                m.getFaculty(),
                m.getPhone(),
                m.isContributor(),
                m.getBio(),
```

- [ ] **Step 7: 테스트를 실행해 통과를 확인한다**

RUN `MeTest`
RUN `PeopleTest`
RUN `MeContractTest`
Expected: 셋 다 PASS.

- [ ] **Step 8: 커밋한다**

```bash
git add src/test/resources/openapi/openapi.yaml \
        src/main/java/com/jaram/be/me/dto/MeProfile.java \
        src/main/java/com/jaram/be/me/MeService.java \
        src/test/java/com/jaram/be/me/MeTest.java \
        src/test/java/com/jaram/be/people/PeopleTest.java
git commit -m "$(cat <<'EOF'
feat(me): 프로필에 faculty·phone·contributor 를 노출한다

전화번호는 개인정보이므로 공개 사람들 카드에는 싣지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `PATCH /api/me`로 `phone` 수정

**Files:**
- Modify: `docs/api/openapi.yaml` — `MeUpdateRequest`에 `phone` 추가
- Modify: `src/test/resources/openapi/openapi.yaml` (sync 스크립트로)
- Modify: `src/main/java/com/jaram/be/me/dto/MeUpdateRequest.java`
- Modify: `src/main/java/com/jaram/be/me/MeService.java:29-35`
- Test: `src/test/java/com/jaram/be/me/MeTest.java`

**Interfaces:**
- Consumes: Task 4의 `MeProfile.faculty()`/`.phone()`
- Produces: `MeUpdateRequest.phone()` → `String` (null = 미변경)

**의미 규칙 (설계 §4.1):**

```
phone == null    → 미변경 (필드를 보내지 않은 것으로 본다)
phone 이 공백뿐  → 422 VALIDATION
그 외            → 저장
```

`bio`/`githubUrl`/`blogUrl`의 "null이면 값을 지운다" 동작은 그대로 둔다. 필수 항목(`phone`)과 선택 항목(나머지)의 성질 차이에서 오는 의도된 비대칭이다. **전화번호 형식 검증은 넣지 않는다** — 가입 쪽에 형식 규칙이 없어서(`@NotBlank`뿐) 수정에만 넣으면 고칠 수 없는 데이터가 생긴다.

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`MeTest.java`에 추가한다:

```java
    @Test
    void patchUpdatesPhone() {
        Map<String, Object> body = new HashMap<>();
        body.put("phone", "010-9999-0000");
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(body)
                .when().patch("/api/me")
                .then().statusCode(200)
                .body("phone", equalTo("010-9999-0000"));
    }

    @Test
    void patchWithoutPhoneKeepsIt() {
        Map<String, Object> body = new HashMap<>();
        body.put("bio", "소개만 바꾼다");
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(body)
                .when().patch("/api/me")
                .then().statusCode(200)
                .body("bio", equalTo("소개만 바꾼다"))
                .body("phone", equalTo("010-1234-5678"));
    }

    @Test
    void patchBlankPhoneReturns422() {
        Map<String, Object> body = new HashMap<>();
        body.put("phone", "   ");
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(body)
                .when().patch("/api/me")
                .then().statusCode(422)
                .body("code", equalTo("VALIDATION"))
                .body("fieldErrors.phone", notNullValue());
    }

    @Test
    void patchCannotChangeFaculty() {
        Map<String, Object> body = new HashMap<>();
        body.put("faculty", "전자공학부");
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json").body(body)
                .when().patch("/api/me")
                .then().statusCode(200)
                .body("faculty", equalTo("컴퓨터학부"));
    }
```

`patchClearsFieldsWithNull`(L80-91)은 그대로 둔다 — 그 요청에는 `phone` 키가 없으므로 `phone`은 미변경이고, 기존 세 필드는 계속 지워져야 한다.

- [ ] **Step 2: 실행해서 실패를 확인한다**

RUN `MeTest`
Expected:
- `patchUpdatesPhone` FAIL — `MeUpdateRequest`에 `phone`이 없어 무시되고, 응답의 `phone`은 `010-1234-5678` 그대로다.
- `patchBlankPhoneReturns422` FAIL — 200이 돌아온다.
- `patchWithoutPhoneKeepsIt`·`patchCannotChangeFaculty`는 통과한다(회귀 방지선).

- [ ] **Step 3: 계약의 `MeUpdateRequest`에 `phone`을 추가한다**

```yaml
    MeUpdateRequest:
      type: object
      description: >
        모두 선택 항목. bio/githubUrl/blogUrl 은 null 을 보내면 값이 지워지고,
        phone 은 null 이면 미변경이다 (가입 필수 항목이라 빈 값이 될 수 없다).
        faculty 는 읽기 전용이므로 여기에 없다.
      properties:
        bio: { type: [string, 'null'], maxLength: 500 }
        githubUrl: { type: [string, 'null'], format: uri }
        blogUrl: { type: [string, 'null'], format: uri }
        phone: { type: [string, 'null'], example: 010-9999-0000, description: 공백만 보내면 422. null 이면 미변경. }
```

- [ ] **Step 4: 계약 사본을 동기화한다**

```bash
./scripts/sync-openapi.sh
git diff src/test/resources/openapi/openapi.yaml
```

Expected: `MeUpdateRequest`에 description과 `phone`만 추가된 diff.

- [ ] **Step 5: `MeUpdateRequest`에 `phone`을 추가한다**

`MeUpdateRequest.java` 전체:

```java
package com.jaram.be.me.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 모두 선택 항목. bio/githubUrl/blogUrl 은 null 이면 값을 지우고, phone 은 null 이면
 * 미변경이다 — 가입 필수 항목이라 빈 값이 될 수 없기 때문. faculty 는 읽기 전용이라 없다.
 */
public record MeUpdateRequest(
        @Size(max = 500) String bio,
        String githubUrl,
        String blogUrl,
        // Bean Validation 은 null 을 통과시키므로 "null=미변경, 공백=422"가 이 한 줄로 표현된다.
        @Pattern(regexp = ".*\\S.*", message = "휴대전화 번호를 입력해 주세요.") String phone) {
}
```

- [ ] **Step 6: `MeService.update`에서 `phone`을 조건부로 반영한다**

```java
    @Transactional
    public MeProfile update(String memberId, MeUpdateRequest req) {
        Member m = find(memberId);
        m.setBio(req.bio());
        m.setGithubUrl(req.githubUrl());
        m.setBlogUrl(req.blogUrl());
        if (req.phone() != null) m.setPhone(req.phone());   // null = 미변경
        return toProfile(m);
    }
```

클래스 javadoc의 마지막 문장을 갱신한다:

```java
/**
 * GET/PATCH /api/me: the authenticated member's own profile. gen goes out as a
 * plain integer — the "기" suffix is FE's to render. Profile edits touch
 * bio/github/blog/phone; faculty is read-only.
 */
```

- [ ] **Step 7: 테스트를 실행해 통과를 확인한다**

RUN `MeTest`
RUN `MeContractTest`
Expected: 둘 다 PASS. `MeTest`는 기존 6건 + Task 3·4가 더한 3건 + 여기서 더한 4건 = 13건.
`MeContractTest.patchMeResponseMatchesContract`가 새 `MeUpdateRequest` 스키마로 요청까지 검증한다.

- [ ] **Step 8: 커밋한다**

```bash
git add src/test/resources/openapi/openapi.yaml \
        src/main/java/com/jaram/be/me/dto/MeUpdateRequest.java \
        src/main/java/com/jaram/be/me/MeService.java \
        src/test/java/com/jaram/be/me/MeTest.java
git commit -m "$(cat <<'EOF'
feat(me): 프로필에서 전화번호를 수정할 수 있게 한다

phone 은 null 이면 미변경, 공백뿐이면 422. 학부는 읽기 전용으로 남긴다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: admin 목록 탭 이름을 `grad`로 통일

**Files:**
- Modify: `docs/api/openapi.yaml` — `/api/admin/list` 의 `tab` 설명 2곳 (사본 기준 L606, L614)
- Modify: `src/test/resources/openapi/openapi.yaml` (sync 스크립트로)
- Modify: `src/main/java/com/jaram/be/admin/AdminResourceService.java:159`
- Test: `src/test/java/com/jaram/be/admin/AdminResourceTest.java`

**Interfaces:**
- Consumes: 없음 (Task 1–5와 독립)
- Produces: `GET /api/admin/members?tab=grad`가 `grade == OB`인 회원을 거른다. `tab=graduate`는 더 이상 인식되지 않고 `default -> true`로 떨어져 전체를 반환한다.

- [ ] **Step 1: 실패하는 테스트를 작성한다**

설계 문서 §9는 "`AdminResourceTest` — `tab=graduate` → `tab=grad`"라고 적었지만, 실제로는 졸업 탭을 검증하는 테스트가 **없다**(`AdminResourceTest:71`은 `tab=exec`만 본다). 그래서 수정이 아니라 신규 테스트를 쓴다.

`AdminResourceTest.java`의 import에 `com.jaram.be.member.MemberGrade`가 이미 있는지 확인하고(`approved()`가 `MemberGrade.ASSOCIATE`를 쓰므로 있다), `listFiltersMembersByTabAndQuery` 아래에 추가한다:

```java
    @Test
    void listFiltersMembersByGradTab() {
        Member ob = approved("정졸업", "2023000003");
        ob.setGrade(MemberGrade.OB);
        members.saveAndFlush(ob);
        approved("김재학", "2023000004");   // ASSOCIATE

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members?tab=grad")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].name", equalTo("정졸업"));
    }
```

- [ ] **Step 2: 실행해서 실패를 확인한다**

RUN `AdminResourceTest`
Expected: `listFiltersMembersByGradTab` FAIL — `"grad"`가 `switch`에 없어 `default -> true`로 떨어지고 2건이 돌아온다.

- [ ] **Step 3: `AdminResourceService`의 탭 이름을 바꾼다**

`AdminResourceService.java:159`:

```java
            case "grad" -> m.getGrade() == MemberGrade.OB;
```

- [ ] **Step 4: 계약의 `tab` 설명 두 곳을 고친다**

`docs/api/openapi.yaml` L606:

```
        리소스별 행을 페이지 단위로 반환. members는 tab(member/exec/contrib/grad)으로 세부 구분.
```

L614:

```yaml
        - { name: tab, in: query, required: false, schema: { type: string }, description: 'members 세부(member/exec/contrib/grad)' }
```

- [ ] **Step 5: 계약 사본을 동기화한다**

```bash
./scripts/sync-openapi.sh
git diff src/test/resources/openapi/openapi.yaml
```

Expected: 위 두 줄만 바뀐 diff.

- [ ] **Step 6: 테스트를 실행해 통과를 확인한다**

RUN `AdminResourceTest`
Expected: PASS.

- [ ] **Step 7: 커밋한다**

```bash
git add src/test/resources/openapi/openapi.yaml \
        src/main/java/com/jaram/be/admin/AdminResourceService.java \
        src/test/java/com/jaram/be/admin/AdminResourceTest.java
git commit -m "$(cat <<'EOF'
refactor(admin): 졸업 탭 이름을 grad 로 통일한다

사람들 페이지 스키마 키(grad)에 admin 쿼리 값을 맞춘다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 전체 검증과 계약 변경의 FE 저장소 커밋

**Files:**
- Modify: `/home/ksb/Dev/home-jaram/home-jaram-fe/docs/api/openapi.yaml` (Task 1–6에서 이미 편집됨, 커밋만 남음)

**Interfaces:**
- Consumes: Task 1–6의 모든 변경

- [ ] **Step 1: 전체 스위트를 돌린다**

```bash
cd /home/ksb/Dev/home-jaram/home-jaram-be
export JAVA_HOME=/home/ksb/.local/jdk-21; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon -I /tmp/docker-api.init.gradle test
```

Expected: 0 failures. 시작 시점 211건에 이 플랜이 더한 테스트(SignupTest 2, MeTest 7, PeopleTest 1, AdminResourceTest 1 = 11건)를 합쳐 222건. 예상 밖의 실패는 회귀이므로 고치고 다시 돌린다.

- [ ] **Step 2: 계약 사본과 원본이 일치하는지 확인한다**

```bash
./scripts/sync-openapi.sh
```

Expected: `Already in sync: ...`. 다른 출력이 나오면 어느 태스크가 sync를 빠뜨린 것이므로, 사본을 커밋에 반영하고 Step 1을 다시 돌린다.

- [ ] **Step 3: FE 저장소의 계약 변경을 검토한다**

```bash
git -C /home/ksb/Dev/home-jaram/home-jaram-fe diff docs/api/openapi.yaml
```

Expected: `SignupRequest.gen`, `MeProfile`(gen·terms·faculty·phone·contributor), `MeUpdateRequest.phone`, `PersonMember`(gen·terms), `MemberTerm` 신설, `tab` 설명 2곳 — 그리고 그 외에는 아무것도 없어야 한다.

- [ ] **Step 4: FE 저장소에 커밋한다**

FE 저장소가 기본 브랜치에 있으면 먼저 브랜치를 판다.

```bash
git -C /home/ksb/Dev/home-jaram/home-jaram-fe add docs/api/openapi.yaml
git -C /home/ksb/Dev/home-jaram/home-jaram-fe commit -m "$(cat <<'EOF'
contract: 회원 모델 재정립 반영

gen 정수 통일, 임기(MemberTerm) 구조화, MeProfile 에 faculty·phone·contributor
노출, MeUpdateRequest 에 phone 추가, admin tab 값 grad 로 통일.

BE와 함께 배포해야 하는 파괴적 변경이다 — FE 렌더링에서 "기" 접미사를 직접
붙이고, tab=graduate 호출을 tab=grad 로 바꿔야 한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: 사용자에게 배포 제약을 알린다**

`gen` 타입 변경과 `"기"` 접미사 이동, `tab=graduate` 폐기는 **BE와 FE가 함께 배포되어야 한다.** FE 렌더링 코드 수정은 이 플랜의 범위 밖이며 별도로 처리한다. 푸시 여부는 사용자에게 확인한다.

---

## 이 플랜이 다루지 않는 것

설계 문서에 명시된 범위 밖 항목들이다. 구현 중에 손대지 않는다.

- FE 렌더링 코드 (`"기"` 접미사 부착, `terms` 표시, `tab=grad` 호출)
- `MeProfile`의 평면 `department`/`title` 제거 — `terms`와 중복이지만 별개의 파괴적 변경이다
- `exec`/`grad` 불리언 필드 노출 — 응답에 이미 있는 값(`title`/`terms`, `grade`)에서 파생되므로 두 번째 진실원을 만들지 않는다
- 전화번호 형식 검증 — 가입·수정·기존 데이터 정리를 함께 다뤄야 하는 별건이다
- `faculty` 수정 경로 (admin 편집 필드 추가) — 필요해지면 자연스러운 후속 작업이다
