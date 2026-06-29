# JARAM BE Phase 1 — Foundation & Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Spring Boot backend with security, global error handling, the Member model, and all auth/admin-approval endpoints (UC-A1..A5), each verified against the OpenAPI contract.

**Architecture:** Standard Spring Boot layered structure — Controller (1:1 with OpenAPI operations, DTO = OpenAPI schema) → Service (transactions, domain rules) → Repository (Spring Data JPA) → Entity. Auth is stateless JWT via a `Bearer` filter. All errors serialize through one `@RestControllerAdvice` into the single error envelope.

**Tech Stack:** Spring Boot 3.4.x, Java 21, Gradle (Groovy DSL), Spring Data JPA, PostgreSQL (Testcontainers for tests), Spring Security, JJWT (io.jsonwebtoken), Bean Validation, BCrypt, JUnit 5, REST-assured, swagger-request-validator (Atlassian) for contract assertions.

## Global Constraints

- **Repo:** This plan runs in **this repo** (`home-jaram-be`, package `com.jaram.be`), already scaffolded as a Spring Boot project (`build.gradle`, `settings.gradle`, `JaramBeApplication`). Repo root is the working directory; all paths below are relative to it.
- **Contract is law:** `docs/api/openapi.yaml` (OpenAPI 3.1, already present in this repo) is the single source of truth. Controller request/response DTOs MUST match its schemas exactly.
- **Java package root:** `com.jaram.be`.
- **Java version:** 21. **Spring Boot:** 3.4.1. **Build:** Gradle Groovy DSL (`build.gradle`).
- **Base URL / port:** `http://localhost:8080` (Spring default).
- **Enum wire values are fixed by FE** (verbatim): `Authority` = `MEMBER`/`OFFICER`; `MemberCategory` = `exec`/`contrib`/`grad`; `Member.status` internal `PENDING`/`ACTIVE`/`REJECTED`.
- **Error envelope (verbatim):** `{ "code": STRING, "message": STRING, "fieldErrors": { field: msg } | null }`. `fieldErrors` only on 422.
- **Status↔code map (fixed, FE depends on it):** login unregistered → 404 `NOT_FOUND`; login pending → 403 `PENDING`; login bad creds → 401 `INVALID`; signup dup email → 409 `EMAIL_TAKEN`; token invalid/expired → 401 (FE clears session); forbidden → 403 `FORBIDDEN`; validation → 422 `VALIDATION` (+`fieldErrors`); server → 5xx `SERVER`.
- **Server validation rules (server is authoritative):** email = format + `@hanyang.ac.kr` domain; studentId = `^\d{8,10}$`, unique; password = ≥8 chars with ≥1 letter, ≥1 digit, ≥1 symbol; name required; reject `reason` required.
- **Commit discipline:** Conventional Commits, one commit per task end. Frequent commits. DRY / YAGNI / TDD.

---

## Contract decisions locked for P1 (resolve spec gaps)

These are not in the OpenAPI response list but are required by `§6` acceptance criteria — fixed here so tasks are unambiguous:

1. **Duplicate studentId on signup** → `422 VALIDATION` with `fieldErrors.studentId` (the contract only names `EMAIL_TAKEN` for email; studentId reuses the validation channel). Duplicate email keeps `409 EMAIL_TAKEN`.
2. **Password-reset token lifetime** → 30 minutes. Single-use (`usedAt` set on consume).
3. **Reset-request mail** → delivered through a `ResetMailSender` interface; P1 ships a logging implementation (no SMTP). Real SMTP is a later phase. `reset-request` always returns 200 regardless of email existence (enumeration defense).
4. **JWT** → HS256, subject = member id, claim `authority`, claim `name`, `email`. TTL 12h. Secret from `JWT_SECRET` env (dev default in `application.yml`).
5. **Member entity carries all spec §4.1 fields now** (category, title, department, gen, bio, githubUrl, blogUrl) even though P2 consumes them — avoids a later schema migration. Defaults: `authority=MEMBER`, `status=PENDING`, `category=contrib`.

---

## File Structure

```
build.gradle, settings.gradle                    # Gradle config (Groovy DSL, exists)
src/main/resources/application.yml               # datasource, jwt, jpa
src/main/resources/openapi/openapi.yaml          # contract copy (for tests; from docs/api/openapi.yaml)
src/main/java/com/jaram/be/
  JaramBeApplication.java                        # @SpringBootApplication
  common/
    ErrorResponse.java                            # error envelope DTO
    ApiException.java                             # carries http status + code + message
    GlobalExceptionHandler.java                   # @RestControllerAdvice
  member/
    Member.java                                   # entity
    Authority.java MemberCategory.java MemberStatus.java   # enums
    MemberRepository.java
  auth/
    PasswordResetToken.java                        # entity
    PasswordResetTokenRepository.java
    ResetMailSender.java LoggingResetMailSender.java
    dto/  (LoginRequest/Response, SignupRequest, UserSummary, PasswordResetRequest, PasswordResetConfirm)
    AuthService.java
    AuthController.java
  admin/
    dto/ PendingMember.java RejectRequest.java
    AdminMemberService.java
    AdminMemberController.java
  security/
    JwtProvider.java
    JwtAuthFilter.java
    SecurityConfig.java
    RestAuthEntryPoint.java RestAccessDeniedHandler.java
    CurrentMember.java                             # @AuthenticationPrincipal holder
src/test/java/com/jaram/be/...                    # mirrors main
src/test/resources/                                # Testcontainers config
```

---

### Task 1: Add dependencies + context smoke test

> **Already scaffolded** (do NOT recreate): `settings.gradle` (`rootProject.name = 'jaram-be'`), `build.gradle` (Boot 3.4.1, depmgmt 1.1.7, group `com.jaram`, Java 21, `spring-boot-starter-web`), the Gradle wrapper (`gradlew`), and `src/main/java/com/jaram/be/JaramBeApplication.java`. This task **extends** them.

**Files:**
- Modify: `build.gradle` (add JPA/security/validation/jjwt/test deps)
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/com/jaram/be/JaramBeApplicationTests.java`
- Create: `src/test/resources/application.yml`

**Interfaces:**
- Consumes: existing scaffold (main class, wrapper, base build).
- Produces: a bootable Spring context; Testcontainers PostgreSQL base for all later integration tests.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/JaramBeApplicationTests.java`:
```java
package com.jaram.be;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class JaramBeApplicationTests {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void contextLoads() { }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests JaramBeApplicationTests`
Expected: FAIL — Testcontainers/Postgres/REST-assured deps not on classpath yet.

- [ ] **Step 3: Extend the existing `build.gradle`**

Replace the `dependencies { … }` block (and add the `dependencyManagement` block) so `build.gradle` reads:
```groovy
plugins {
	id 'java'
	id 'org.springframework.boot' version '3.4.1'
	id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.jaram'
version = '0.0.1-SNAPSHOT'

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation 'org.springframework.boot:spring-boot-starter-web'
	implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
	implementation 'org.springframework.boot:spring-boot-starter-security'
	implementation 'org.springframework.boot:spring-boot-starter-validation'
	runtimeOnly 'org.postgresql:postgresql'

	implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
	runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
	runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'

	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testImplementation 'org.springframework.security:spring-security-test'
	testImplementation 'org.springframework.boot:spring-boot-testcontainers'
	testImplementation 'org.testcontainers:junit-jupiter'
	testImplementation 'org.testcontainers:postgresql'
	testImplementation 'io.rest-assured:rest-assured:5.5.0'
	testImplementation 'com.atlassian.oai:swagger-request-validator-restassured:2.43.0'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

dependencyManagement {
	imports {
		mavenBom 'org.testcontainers:testcontainers-bom:1.20.2'
	}
}

tasks.named('test') {
	useJUnitPlatform()
}
```

`settings.gradle` already holds `rootProject.name = 'jaram-be'` and `JaramBeApplication.java` already exists — leave both as-is. The Gradle wrapper (`./gradlew`) is already committed; do not regenerate.

- [ ] **Step 4: Create application config**

`src/main/resources/application.yml`:
```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/jaram}
    username: ${DB_USER:jaram}
    password: ${DB_PASSWORD:jaram}
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate.format_sql: true
    open-in-view: false

jwt:
  secret: ${JWT_SECRET:dev-only-secret-change-me-min-32-bytes-long!!}
  ttl-seconds: 43200
  reset-ttl-seconds: 1800
```

`src/test/resources/application.yml`:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
jwt:
  secret: test-secret-test-secret-test-secret-32bytes
  ttl-seconds: 43200
  reset-ttl-seconds: 1800
```

(`JaramBeApplication.java` already exists from the scaffold — no change needed.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests JaramBeApplicationTests`
Expected: PASS (context loads against Testcontainers Postgres).

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/main/resources/application.yml src/test
git commit -m "chore: add spring deps and testcontainers smoke test"
```

---

### Task 2: Error envelope + global exception handler

**Files:**
- Create: `src/main/java/com/jaram/be/common/ErrorResponse.java`
- Create: `src/main/java/com/jaram/be/common/ApiException.java`
- Create: `src/main/java/com/jaram/be/common/GlobalExceptionHandler.java`
- Test: `src/test/java/com/jaram/be/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces:
  - `ErrorResponse(String code, String message, Map<String,String> fieldErrors)` — record.
  - `ApiException(HttpStatus status, String code, String message)` extends `RuntimeException`; getters `getStatus()`, `getCode()`.
  - `GlobalExceptionHandler` maps `ApiException` → its status/code; `MethodArgumentNotValidException` → 422 `VALIDATION` + `fieldErrors`; any other `Exception` → 500 `SERVER`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/common/GlobalExceptionHandlerTest.java`:
```java
package com.jaram.be.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void apiExceptionMapsToEnvelope() {
        var ex = new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "이미 가입 신청된 이메일입니다.");
        var handler = new GlobalExceptionHandler();
        var resp = handler.handleApi(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().code()).isEqualTo("EMAIL_TAKEN");
        assertThat(resp.getBody().fieldErrors()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests GlobalExceptionHandlerTest`
Expected: FAIL — `ErrorResponse`, `ApiException`, `GlobalExceptionHandler` not defined.

- [ ] **Step 3: Implement**

`ErrorResponse.java`:
```java
package com.jaram.be.common;

import java.util.Map;

public record ErrorResponse(String code, String message, Map<String, String> fieldErrors) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }
}
```

`ApiException.java`:
```java
package com.jaram.be.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
```

`GlobalExceptionHandler.java`:
```java
package com.jaram.be.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("VALIDATION", "입력값을 확인해 주세요.", fields));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("SERVER", "일시적인 오류가 발생했습니다."));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests GlobalExceptionHandlerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jaram/be/common src/test/java/com/jaram/be/common
git commit -m "feat: add error envelope and global exception handler"
```

---

### Task 3: Member entity + enums + repository

**Files:**
- Create: `src/main/java/com/jaram/be/member/Authority.java`
- Create: `src/main/java/com/jaram/be/member/MemberCategory.java`
- Create: `src/main/java/com/jaram/be/member/MemberStatus.java`
- Create: `src/main/java/com/jaram/be/member/Member.java`
- Create: `src/main/java/com/jaram/be/member/MemberRepository.java`
- Test: `src/test/java/com/jaram/be/member/MemberRepositoryTest.java`

**Interfaces:**
- Produces:
  - enums `Authority{MEMBER,OFFICER}`, `MemberCategory{exec,contrib,grad}` (lowercase wire = enum name), `MemberStatus{PENDING,ACTIVE,REJECTED}`.
  - `Member` entity, `String id` (UUID), fields per §4.1; builder-free, plain setters; factory `Member.newPending(name, studentId, email, passwordHash)`.
  - `MemberRepository extends JpaRepository<Member,String>` with `Optional<Member> findByEmail(String)`, `boolean existsByEmail(String)`, `boolean existsByStudentId(String)`, `List<Member> findByStatus(MemberStatus)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/member/MemberRepositoryTest.java`:
```java
package com.jaram.be.member;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class MemberRepositoryTest extends PostgresTest {

    @Autowired MemberRepository repo;

    @Test
    void savesAndQueriesByEmailAndStatus() {
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        repo.save(m);

        assertThat(repo.existsByEmail("hong@hanyang.ac.kr")).isTrue();
        assertThat(repo.existsByStudentId("2023012345")).isTrue();
        assertThat(repo.findByStatus(MemberStatus.PENDING)).hasSize(1);
        assertThat(repo.findByEmail("hong@hanyang.ac.kr")).isPresent();
    }
}
```

Also create the shared Testcontainers base `src/test/java/com/jaram/be/support/PostgresTest.java`:
```java
package com.jaram.be.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class PostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests MemberRepositoryTest`
Expected: FAIL — `Member`/enums/`MemberRepository` not defined.

- [ ] **Step 3: Implement enums + entity + repository**

`Authority.java`:
```java
package com.jaram.be.member;
public enum Authority { MEMBER, OFFICER }
```

`MemberCategory.java`:
```java
package com.jaram.be.member;
// enum name == JSON wire value (exec/contrib/grad)
public enum MemberCategory { exec, contrib, grad }
```

`MemberStatus.java`:
```java
package com.jaram.be.member;
public enum MemberStatus { PENDING, ACTIVE, REJECTED }
```

`Member.java`:
```java
package com.jaram.be.member;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "member",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = "email"),
           @UniqueConstraint(columnNames = "studentId")
       })
public class Member {

    @Id
    private String id;

    private String name;
    private String studentId;
    private String email;
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Authority authority = Authority.MEMBER;

    private String title;        // 직책 표시 텍스트
    private String department;    // 부서 (exec 그룹용)

    @Enumerated(EnumType.STRING)
    private MemberCategory category = MemberCategory.contrib;

    private Integer gen;          // 기수 정수 (응답은 "{gen}기")
    @Column(length = 1000)
    private String bio;
    private String githubUrl;
    private String blogUrl;

    @Enumerated(EnumType.STRING)
    private MemberStatus status = MemberStatus.PENDING;

    private Instant createdAt = Instant.now();

    protected Member() { }

    public static Member newPending(String name, String studentId, String email, String passwordHash) {
        Member m = new Member();
        m.id = UUID.randomUUID().toString();
        m.name = name;
        m.studentId = studentId;
        m.email = email;
        m.passwordHash = passwordHash;
        m.authority = Authority.MEMBER;
        m.category = MemberCategory.contrib;
        m.status = MemberStatus.PENDING;
        m.createdAt = Instant.now();
        return m;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getStudentId() { return studentId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String h) { this.passwordHash = h; }
    public Authority getAuthority() { return authority; }
    public MemberStatus getStatus() { return status; }
    public void setStatus(MemberStatus s) { this.status = s; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`MemberRepository.java`:
```java
package com.jaram.be.member;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, String> {
    Optional<Member> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByStudentId(String studentId);
    List<Member> findByStatus(MemberStatus status);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests MemberRepositoryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jaram/be/member src/test/java/com/jaram/be
git commit -m "feat: add member entity, enums, and repository"
```

---

### Task 4: PasswordResetToken entity + repository

**Files:**
- Create: `src/main/java/com/jaram/be/auth/PasswordResetToken.java`
- Create: `src/main/java/com/jaram/be/auth/PasswordResetTokenRepository.java`
- Test: `src/test/java/com/jaram/be/auth/PasswordResetTokenRepositoryTest.java`

**Interfaces:**
- Produces:
  - `PasswordResetToken` entity: `String id`, `String memberId`, `String token` (unique), `Instant expiresAt`, `Instant usedAt`(nullable). Factory `issue(String memberId, String token, Instant expiresAt)`. Methods `isConsumable(Instant now)` → not used and not expired; `consume(Instant now)` sets `usedAt`.
  - `PasswordResetTokenRepository extends JpaRepository<PasswordResetToken,String>` with `Optional<PasswordResetToken> findByToken(String)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/auth/PasswordResetTokenRepositoryTest.java`:
```java
package com.jaram.be.auth;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class PasswordResetTokenRepositoryTest extends PostgresTest {

    @Autowired PasswordResetTokenRepository repo;

    @Test
    void consumableUntilUsedOrExpired() {
        Instant now = Instant.now();
        var t = PasswordResetToken.issue("m1", "tok-123", now.plus(30, ChronoUnit.MINUTES));
        repo.save(t);

        var found = repo.findByToken("tok-123").orElseThrow();
        assertThat(found.isConsumable(now)).isTrue();
        assertThat(found.isConsumable(now.plus(31, ChronoUnit.MINUTES))).isFalse(); // expired

        found.consume(now);
        assertThat(found.isConsumable(now)).isFalse(); // used
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests PasswordResetTokenRepositoryTest`
Expected: FAIL — types not defined.

- [ ] **Step 3: Implement**

`PasswordResetToken.java`:
```java
package com.jaram.be.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_reset_token",
       uniqueConstraints = @UniqueConstraint(columnNames = "token"))
public class PasswordResetToken {

    @Id
    private String id;
    private String memberId;
    private String token;
    private Instant expiresAt;
    private Instant usedAt;

    protected PasswordResetToken() { }

    public static PasswordResetToken issue(String memberId, String token, Instant expiresAt) {
        PasswordResetToken t = new PasswordResetToken();
        t.id = UUID.randomUUID().toString();
        t.memberId = memberId;
        t.token = token;
        t.expiresAt = expiresAt;
        return t;
    }

    public boolean isConsumable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void consume(Instant now) { this.usedAt = now; }

    public String getMemberId() { return memberId; }
    public String getToken() { return token; }
}
```

`PasswordResetTokenRepository.java`:
```java
package com.jaram.be.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {
    Optional<PasswordResetToken> findByToken(String token);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests PasswordResetTokenRepositoryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jaram/be/auth src/test/java/com/jaram/be/auth
git commit -m "feat: add password reset token entity and repository"
```

---

### Task 5: JwtProvider (generate/validate)

**Files:**
- Create: `src/main/java/com/jaram/be/security/JwtProvider.java`
- Test: `src/test/java/com/jaram/be/security/JwtProviderTest.java`

**Interfaces:**
- Produces:
  - `JwtProvider` (Spring `@Component`, constructed from `@Value("${jwt.secret}")` and `@Value("${jwt.ttl-seconds}")`).
  - `String generate(String memberId, String name, String email, Authority authority)`.
  - `JwtClaims parse(String token)` → record `JwtClaims(String memberId, String name, String email, Authority authority)`; throws `JwtException` on invalid/expired.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/security/JwtProviderTest.java`:
```java
package com.jaram.be.security;

import com.jaram.be.member.Authority;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class JwtProviderTest {

    private final JwtProvider provider =
            new JwtProvider("test-secret-test-secret-test-secret-32bytes", 43200);

    @Test
    void roundTripsClaims() {
        String token = provider.generate("m1", "홍길동", "hong@hanyang.ac.kr", Authority.OFFICER);
        var claims = provider.parse(token);

        assertThat(claims.memberId()).isEqualTo("m1");
        assertThat(claims.authority()).isEqualTo(Authority.OFFICER);
        assertThat(claims.email()).isEqualTo("hong@hanyang.ac.kr");
    }

    @Test
    void rejectsTamperedToken() {
        String token = provider.generate("m1", "n", "e@hanyang.ac.kr", Authority.MEMBER);
        assertThatThrownBy(() -> provider.parse(token + "x")).isInstanceOf(JwtException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests JwtProviderTest`
Expected: FAIL — `JwtProvider` not defined.

- [ ] **Step 3: Implement**

`JwtProvider.java`:
```java
package com.jaram.be.security;

import com.jaram.be.member.Authority;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtProvider {

    public record JwtClaims(String memberId, String name, String email, Authority authority) { }

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtProvider(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.ttl-seconds}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String generate(String memberId, String name, String email, Authority authority) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(memberId)
                .claim("name", name)
                .claim("email", email)
                .claim("authority", authority.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    public JwtClaims parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new JwtClaims(
                c.getSubject(),
                c.get("name", String.class),
                c.get("email", String.class),
                Authority.valueOf(c.get("authority", String.class)));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests JwtProviderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jaram/be/security/JwtProvider.java src/test/java/com/jaram/be/security
git commit -m "feat: add JWT provider with generate and parse"
```

---

### Task 6: Security config + JWT filter + 401/403 handlers

**Files:**
- Create: `src/main/java/com/jaram/be/security/CurrentMember.java`
- Create: `src/main/java/com/jaram/be/security/JwtAuthFilter.java`
- Create: `src/main/java/com/jaram/be/security/RestAuthEntryPoint.java`
- Create: `src/main/java/com/jaram/be/security/RestAccessDeniedHandler.java`
- Create: `src/main/java/com/jaram/be/security/SecurityConfig.java`
- Test: `src/test/java/com/jaram/be/security/SecurityAccessTest.java`

**Interfaces:**
- Consumes: `JwtProvider.parse` (Task 5), `Authority` (Task 3).
- Produces:
  - `CurrentMember(String id, String name, String email, Authority authority)` — the authentication principal.
  - `SecurityConfig` bean `SecurityFilterChain`: stateless; `permitAll` for `POST /api/auth/**`, `GET /api/people`, `GET /api/seminars`, `GET /api/studies`; `hasAuthority("OFFICER")` for `/api/admin/**`, `/api/studies/pending`, `/api/studies/applicants/**`, `/api/seminars/*/roster`, `POST /api/seminars`, `/api/studies/*/approve`, `/api/studies/*/reject`; everything else `authenticated()`. Also bean `PasswordEncoder` (BCrypt).
  - 401 → `RestAuthEntryPoint` writes `ErrorResponse("UNAUTHORIZED", ...)`; 403 → `RestAccessDeniedHandler` writes `ErrorResponse("FORBIDDEN", "접근 권한이 없습니다.")`.
  - JWT authorities are exposed as `SimpleGrantedAuthority(authority.name())` so `hasAuthority("OFFICER")` matches.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/security/SecurityAccessTest.java`:
```java
package com.jaram.be.security;

import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import com.jaram.be.member.Authority;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityAccessTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired JwtProvider jwt;

    @BeforeEach void setup() { RestAssured.port = port; }

    @Test
    void adminEndpointWithoutTokenReturns401WithEnvelope() {
        given().when().get("/api/admin/members/pending")
                .then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
    }

    @Test
    void adminEndpointWithMemberTokenReturns403Forbidden() {
        String token = jwt.generate("m1", "n", "e@hanyang.ac.kr", Authority.MEMBER);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/admin/members/pending")
                .then().statusCode(403).body("code", equalTo("FORBIDDEN"));
    }
}
```

> Note: this test depends on the admin controller (Task 9) being mapped. If running tasks strictly in order, mark these two test methods `@Disabled("enabled in Task 9")` until the controller exists, then remove `@Disabled` in Task 9. The filter/handler wiring itself is exercised by the 401 path (no controller needed for the entrypoint).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests SecurityAccessTest`
Expected: FAIL — security classes not defined.

- [ ] **Step 3: Implement principal + handlers**

`CurrentMember.java`:
```java
package com.jaram.be.security;

import com.jaram.be.member.Authority;

public record CurrentMember(String id, String name, String email, Authority authority) { }
```

`RestAuthEntryPoint.java`:
```java
package com.jaram.be.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaram.be.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RestAuthEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res,
                         AuthenticationException ex) throws IOException {
        res.setStatus(HttpStatus.UNAUTHORIZED.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getWriter(),
                ErrorResponse.of("UNAUTHORIZED", "인증이 필요합니다."));
    }
}
```

`RestAccessDeniedHandler.java`:
```java
package com.jaram.be.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaram.be.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res,
                       AccessDeniedException ex) throws IOException {
        res.setStatus(HttpStatus.FORBIDDEN.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getWriter(),
                ErrorResponse.of("FORBIDDEN", "접근 권한이 없습니다."));
    }
}
```

- [ ] **Step 4: Implement the JWT filter**

`JwtAuthFilter.java`:
```java
package com.jaram.be.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwt;

    public JwtAuthFilter(JwtProvider jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var claims = jwt.parse(header.substring(7));
                var principal = new CurrentMember(
                        claims.memberId(), claims.name(), claims.email(), claims.authority());
                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority(claims.authority().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // invalid token → leave unauthenticated → entrypoint returns 401
            }
        }
        chain.doFilter(req, res);
    }
}
```

- [ ] **Step 5: Implement security config**

`SecurityConfig.java`:
```java
package com.jaram.be.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtProvider jwtProvider,
                                           RestAuthEntryPoint entryPoint,
                                           RestAccessDeniedHandler deniedHandler) throws Exception {
        http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(reg -> reg
                .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/people", "/api/seminars", "/api/studies").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority("OFFICER")
                .requestMatchers(HttpMethod.GET, "/api/studies/pending", "/api/studies/applicants").hasAuthority("OFFICER")
                .requestMatchers("/api/studies/applicants/**").hasAuthority("OFFICER")
                .requestMatchers(HttpMethod.POST, "/api/seminars").hasAuthority("OFFICER")
                .requestMatchers(HttpMethod.GET, "/api/seminars/*/roster").hasAuthority("OFFICER")
                .requestMatchers(HttpMethod.POST, "/api/studies/*/approve", "/api/studies/*/reject").hasAuthority("OFFICER")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(deniedHandler))
            .addFilterBefore(new JwtAuthFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests SecurityAccessTest`
Expected: the 401 test PASSES now. (The 403 test remains `@Disabled` until Task 9 maps the admin controller.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/jaram/be/security src/test/java/com/jaram/be/security
git commit -m "feat: add JWT auth filter, security config, and 401/403 envelopes"
```

---

### Task 7: Signup (UC-A1)

**Files:**
- Create: `src/main/java/com/jaram/be/auth/dto/SignupRequest.java`
- Create: `src/main/java/com/jaram/be/auth/AuthService.java` (signup method)
- Create: `src/main/java/com/jaram/be/auth/AuthController.java` (signup mapping)
- Test: `src/test/java/com/jaram/be/auth/SignupTest.java`

**Interfaces:**
- Consumes: `MemberRepository` (Task 3), `PasswordEncoder` (Task 6).
- Produces:
  - `SignupRequest` record with Bean Validation: `name` `@NotBlank`; `studentId` `@Pattern("^\\d{8,10}$")`; `email` `@Email` + `@Pattern(".*@hanyang\\.ac\\.kr$")`; `password` `@Pattern` enforcing ≥8 with letter+digit+symbol.
  - `AuthService.signup(SignupRequest)` → throws `ApiException(409,"EMAIL_TAKEN",…)` if email exists; `ApiException(422,"VALIDATION",…)` style is handled by bean validation, but duplicate studentId throws a `MethodArgumentNotValidException`-equivalent via manual check → throw `ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"VALIDATION",…)` carrying `fieldErrors` (see note).
  - `AuthController` `POST /api/auth/signup` → 201 no body.

> **fieldErrors for duplicate studentId:** `ApiException` as defined in Task 2 has no `fieldErrors`. Extend it minimally: add a second constructor `ApiException(HttpStatus, String code, String message, Map<String,String> fieldErrors)` and a `getFieldErrors()` getter (default null), and update `GlobalExceptionHandler.handleApi` to pass `ex.getFieldErrors()` into the body. Do this in Step 3 below.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/auth/SignupTest.java`:
```java
package com.jaram.be.auth;

import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignupTest extends PostgresTest {

    @LocalServerPort int port;
    @BeforeEach void setup() { RestAssured.port = port; }

    private Map<String, Object> valid() {
        return Map.of("name", "홍길동", "studentId", "2023012345",
                "email", "hong@hanyang.ac.kr", "password", "passw0rd!");
    }

    @Test
    void signupReturns201() {
        given().contentType("application/json").body(valid())
                .when().post("/api/auth/signup")
                .then().statusCode(201);
    }

    @Test
    void duplicateEmailReturns409EmailTaken() {
        given().contentType("application/json").body(valid()).post("/api/auth/signup");
        var second = Map.of("name", "김철수", "studentId", "2023099999",
                "email", "hong@hanyang.ac.kr", "password", "passw0rd!");
        given().contentType("application/json").body(second)
                .when().post("/api/auth/signup")
                .then().statusCode(409).body("code", equalTo("EMAIL_TAKEN"));
    }

    @Test
    void nonHanyangEmailReturns422() {
        var bad = Map.of("name", "홍길동", "studentId", "2023012345",
                "email", "hong@gmail.com", "password", "passw0rd!");
        given().contentType("application/json").body(bad)
                .when().post("/api/auth/signup")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests SignupTest`
Expected: FAIL — no signup endpoint.

- [ ] **Step 3: Extend ApiException for fieldErrors, then implement DTO + service + controller**

Edit `ApiException.java` — add field + constructor + getter:
```java
import java.util.Map;
// ...
private final Map<String, String> fieldErrors;

public ApiException(HttpStatus status, String code, String message) {
    this(status, code, message, null);
}
public ApiException(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
    super(message);
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
}
public Map<String, String> getFieldErrors() { return fieldErrors; }
```

Edit `GlobalExceptionHandler.handleApi` body line:
```java
.body(new ErrorResponse(ex.getCode(), ex.getMessage(), ex.getFieldErrors()));
```

`SignupRequest.java`:
```java
package com.jaram.be.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SignupRequest(
        @NotBlank(message = "이름을 입력해 주세요.")
        String name,

        @Pattern(regexp = "^\\d{8,10}$", message = "학번은 8~10자리 숫자여야 합니다.")
        String studentId,

        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Pattern(regexp = ".*@hanyang\\.ac\\.kr$", message = "한양대 이메일(@hanyang.ac.kr)만 사용할 수 있습니다.")
        String email,

        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$",
                 message = "비밀번호는 8자 이상이며 영문·숫자·기호를 각각 포함해야 합니다.")
        String password
) { }
```

`AuthService.java`:
```java
package com.jaram.be.auth;

import com.jaram.be.auth.dto.SignupRequest;
import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AuthService {

    private final MemberRepository members;
    private final PasswordEncoder encoder;

    public AuthService(MemberRepository members, PasswordEncoder encoder) {
        this.members = members;
        this.encoder = encoder;
    }

    @Transactional
    public void signup(SignupRequest req) {
        if (members.existsByEmail(req.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "이미 가입 신청된 이메일입니다.");
        }
        if (members.existsByStudentId(req.studentId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION", "입력값을 확인해 주세요.",
                    Map.of("studentId", "이미 등록된 학번입니다."));
        }
        members.save(Member.newPending(
                req.name(), req.studentId(), req.email(), encoder.encode(req.password())));
    }
}
```

`AuthController.java`:
```java
package com.jaram.be.auth;

import com.jaram.be.auth.dto.SignupRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public void signup(@Valid @RequestBody SignupRequest req) {
        auth.signup(req);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests SignupTest`
Expected: PASS (all three).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jaram/be/auth src/main/java/com/jaram/be/common src/test/java/com/jaram/be/auth/SignupTest.java
git commit -m "feat: add signup endpoint with validation and EMAIL_TAKEN handling"
```

---

### Task 8: Login (UC-A2)

**Files:**
- Create: `src/main/java/com/jaram/be/auth/dto/LoginRequest.java`
- Create: `src/main/java/com/jaram/be/auth/dto/UserSummary.java`
- Create: `src/main/java/com/jaram/be/auth/dto/LoginResponse.java`
- Modify: `src/main/java/com/jaram/be/auth/AuthService.java` (add `login`)
- Modify: `src/main/java/com/jaram/be/auth/AuthController.java` (add mapping)
- Test: `src/test/java/com/jaram/be/auth/LoginTest.java`

**Interfaces:**
- Consumes: `MemberRepository`, `PasswordEncoder`, `JwtProvider` (Task 5).
- Produces:
  - `LoginRequest(String email, String password)` (`@Email`, `@NotBlank`).
  - `UserSummary(String id, String name, String email, Authority authority)`.
  - `LoginResponse(String accessToken, UserSummary user)`.
  - `AuthService.login(LoginRequest)` → `LoginResponse`. Branches: unknown email → `ApiException(404,"NOT_FOUND")`; `status==PENDING` → `ApiException(403,"PENDING")`; `status==REJECTED` → `ApiException(403,"PENDING")` (rejected also blocked; reuse PENDING code per FE branch — see note); bad password → `ApiException(401,"INVALID")`; success → JWT + summary.

> **REJECTED note:** §7 only fixes codes for unregistered/pending/bad-creds. A REJECTED member must not log in. FE only has a PENDING branch, so map REJECTED → 403 `PENDING` (same "승인을 기다리는 중" UX). Locked for P1.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/auth/LoginTest.java`:
```java
package com.jaram.be.auth;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    private Member active(String email) {
        Member m = Member.newPending("홍길동", "2023012345", email, encoder.encode("passw0rd!"));
        m.setStatus(MemberStatus.ACTIVE);
        return members.save(m);
    }

    @Test
    void loginSuccessReturnsTokenAndUser() {
        active("hong@hanyang.ac.kr");
        given().contentType("application/json")
                .body(Map.of("email", "hong@hanyang.ac.kr", "password", "passw0rd!"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .body("accessToken", notNullValue())
                .body("user.email", equalTo("hong@hanyang.ac.kr"))
                .body("user.authority", equalTo("MEMBER"));
    }

    @Test
    void unknownEmailReturns404() {
        given().contentType("application/json")
                .body(Map.of("email", "nobody@hanyang.ac.kr", "password", "passw0rd!"))
                .when().post("/api/auth/login")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    @Test
    void pendingMemberReturns403() {
        members.save(Member.newPending("대기", "2023011111", "wait@hanyang.ac.kr", encoder.encode("passw0rd!")));
        given().contentType("application/json")
                .body(Map.of("email", "wait@hanyang.ac.kr", "password", "passw0rd!"))
                .when().post("/api/auth/login")
                .then().statusCode(403).body("code", equalTo("PENDING"));
    }

    @Test
    void wrongPasswordReturns401() {
        active("hong@hanyang.ac.kr");
        given().contentType("application/json")
                .body(Map.of("email", "hong@hanyang.ac.kr", "password", "wrongpass!9"))
                .when().post("/api/auth/login")
                .then().statusCode(401).body("code", equalTo("INVALID"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests LoginTest`
Expected: FAIL — no login endpoint.

- [ ] **Step 3: Implement DTOs**

`LoginRequest.java`:
```java
package com.jaram.be.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Email @NotBlank String email,
        @NotBlank String password) { }
```

`UserSummary.java`:
```java
package com.jaram.be.auth.dto;

import com.jaram.be.member.Authority;

public record UserSummary(String id, String name, String email, Authority authority) { }
```

`LoginResponse.java`:
```java
package com.jaram.be.auth.dto;

public record LoginResponse(String accessToken, UserSummary user) { }
```

- [ ] **Step 4: Add `login` to AuthService**

Add to `AuthService` (inject `JwtProvider jwt` via constructor):
```java
import com.jaram.be.auth.dto.LoginRequest;
import com.jaram.be.auth.dto.LoginResponse;
import com.jaram.be.auth.dto.UserSummary;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.security.JwtProvider;
// constructor now: AuthService(MemberRepository members, PasswordEncoder encoder, JwtProvider jwt)

@Transactional(readOnly = true)
public LoginResponse login(LoginRequest req) {
    Member m = members.findByEmail(req.email())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "등록된 회원 정보가 없습니다."));
    if (m.getStatus() != MemberStatus.ACTIVE) {
        throw new ApiException(HttpStatus.FORBIDDEN, "PENDING", "가입 승인을 기다리는 중입니다.");
    }
    if (!encoder.matches(req.password(), m.getPasswordHash())) {
        throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID", "이메일 또는 비밀번호가 일치하지 않습니다.");
    }
    String token = jwt.generate(m.getId(), m.getName(), m.getEmail(), m.getAuthority());
    return new LoginResponse(token, new UserSummary(m.getId(), m.getName(), m.getEmail(), m.getAuthority()));
}
```

- [ ] **Step 5: Add login mapping to AuthController**

```java
import com.jaram.be.auth.dto.LoginRequest;
import com.jaram.be.auth.dto.LoginResponse;

@PostMapping("/login")
public LoginResponse login(@Valid @RequestBody LoginRequest req) {
    return auth.login(req);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests LoginTest`
Expected: PASS (all four).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/jaram/be/auth src/test/java/com/jaram/be/auth/LoginTest.java
git commit -m "feat: add login endpoint with status and credential branching"
```

---

### Task 9: Admin member approval (UC-A5)

**Files:**
- Create: `src/main/java/com/jaram/be/admin/dto/PendingMember.java`
- Create: `src/main/java/com/jaram/be/admin/dto/RejectRequest.java`
- Create: `src/main/java/com/jaram/be/admin/AdminMemberService.java`
- Create: `src/main/java/com/jaram/be/admin/AdminMemberController.java`
- Modify: `src/test/java/com/jaram/be/security/SecurityAccessTest.java` (remove `@Disabled` on the 403 test)
- Test: `src/test/java/com/jaram/be/admin/AdminMemberTest.java`

**Interfaces:**
- Consumes: `MemberRepository` (Task 3), security `hasAuthority("OFFICER")` (Task 6).
- Produces:
  - `PendingMember(String id, String name, String studentId, String email, String createdAt)` — `createdAt` is ISO-8601 string.
  - `RejectRequest(@NotBlank String reason)`.
  - `AdminMemberService.listPending()` → `List<PendingMember>` (status PENDING); `approve(String id)` sets ACTIVE; `reject(String id, String reason)` sets REJECTED. Unknown id → `ApiException(404,"NOT_FOUND")`.
  - `AdminMemberController`: `GET /api/admin/members/pending`, `POST /api/admin/members/{id}/approve`, `POST /api/admin/members/{id}/reject`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/admin/AdminMemberTest.java`:
```java
package com.jaram.be.admin;

import com.jaram.be.member.*;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminMemberTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;

    private String officerToken;

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
        officerToken = jwt.generate("officer-1", "임원", "officer@hanyang.ac.kr", Authority.OFFICER);
    }

    private Member pending(String email, String sid) {
        return members.save(Member.newPending("대기자", sid, email, "hash"));
    }

    @Test
    void officerSeesPendingListAndApproves() {
        Member p = pending("wait@hanyang.ac.kr", "2023011111");

        given().header("Authorization", "Bearer " + officerToken)
                .when().get("/api/admin/members/pending")
                .then().statusCode(200).body("size()", equalTo(1))
                .body("[0].email", equalTo("wait@hanyang.ac.kr"));

        given().header("Authorization", "Bearer " + officerToken)
                .when().post("/api/admin/members/" + p.getId() + "/approve")
                .then().statusCode(200);

        org.assertj.core.api.Assertions.assertThat(
                members.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void rejectWithoutReasonReturns422() {
        Member p = pending("wait2@hanyang.ac.kr", "2023022222");
        given().header("Authorization", "Bearer " + officerToken)
                .contentType("application/json").body(Map.of())
                .when().post("/api/admin/members/" + p.getId() + "/reject")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests AdminMemberTest`
Expected: FAIL — admin classes not defined.

- [ ] **Step 3: Implement DTOs**

`PendingMember.java`:
```java
package com.jaram.be.admin.dto;

public record PendingMember(String id, String name, String studentId, String email, String createdAt) { }
```

`RejectRequest.java`:
```java
package com.jaram.be.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectRequest(@NotBlank(message = "거절 사유를 입력해 주세요.") String reason) { }
```

- [ ] **Step 4: Implement service**

`AdminMemberService.java`:
```java
package com.jaram.be.admin;

import com.jaram.be.admin.dto.PendingMember;
import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminMemberService {

    private final MemberRepository members;

    public AdminMemberService(MemberRepository members) { this.members = members; }

    @Transactional(readOnly = true)
    public List<PendingMember> listPending() {
        return members.findByStatus(MemberStatus.PENDING).stream()
                .map(m -> new PendingMember(m.getId(), m.getName(), m.getStudentId(),
                        m.getEmail(), m.getCreatedAt().toString()))
                .toList();
    }

    @Transactional
    public void approve(String id) { load(id).setStatus(MemberStatus.ACTIVE); }

    @Transactional
    public void reject(String id, String reason) { load(id).setStatus(MemberStatus.REJECTED); }

    private Member load(String id) {
        return members.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "회원을 찾을 수 없습니다."));
    }
}
```

> `reason` is accepted for the contract but not persisted on Member in P1 (no rejection-reason column for members in §4.1). Validation that `reason` is present still runs. If member-side rejection reason storage is needed later, add a column then.

- [ ] **Step 5: Implement controller**

`AdminMemberController.java`:
```java
package com.jaram.be.admin;

import com.jaram.be.admin.dto.PendingMember;
import com.jaram.be.admin.dto.RejectRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/members")
public class AdminMemberController {

    private final AdminMemberService service;

    public AdminMemberController(AdminMemberService service) { this.service = service; }

    @GetMapping("/pending")
    public List<PendingMember> pending() { return service.listPending(); }

    @PostMapping("/{id}/approve")
    public void approve(@PathVariable String id) { service.approve(id); }

    @PostMapping("/{id}/reject")
    public void reject(@PathVariable String id, @Valid @RequestBody RejectRequest req) {
        service.reject(id, req.reason());
    }
}
```

- [ ] **Step 6: Enable the deferred security test**

Remove `@Disabled` from `adminEndpointWithMemberTokenReturns403Forbidden` in `SecurityAccessTest`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew test --tests AdminMemberTest --tests SecurityAccessTest`
Expected: PASS (all).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/jaram/be/admin src/test/java/com/jaram/be/admin src/test/java/com/jaram/be/security/SecurityAccessTest.java
git commit -m "feat: add officer member approval endpoints (UC-A5)"
```

---

### Task 10: Password reset request + confirm (UC-A3, UC-A4)

**Files:**
- Create: `src/main/java/com/jaram/be/auth/dto/PasswordResetRequest.java`
- Create: `src/main/java/com/jaram/be/auth/dto/PasswordResetConfirm.java`
- Create: `src/main/java/com/jaram/be/auth/ResetMailSender.java`
- Create: `src/main/java/com/jaram/be/auth/LoggingResetMailSender.java`
- Modify: `src/main/java/com/jaram/be/auth/AuthService.java` (add `requestReset`, `confirmReset`)
- Modify: `src/main/java/com/jaram/be/auth/AuthController.java` (add mappings)
- Test: `src/test/java/com/jaram/be/auth/PasswordResetTest.java`

**Interfaces:**
- Consumes: `MemberRepository`, `PasswordResetTokenRepository` (Task 4), `PasswordEncoder`, `@Value("${jwt.reset-ttl-seconds}")`.
- Produces:
  - `PasswordResetRequest(@Email @NotBlank String email)`.
  - `PasswordResetConfirm(@NotBlank String token, @Pattern(... same password rule ...) String password)`.
  - `ResetMailSender.send(String email, String token)` interface; `LoggingResetMailSender` logs it.
  - `AuthService.requestReset(PasswordResetRequest)` → always returns void/200; if member exists, issue token (random UUID, expiresAt = now + reset-ttl) and call mail sender.
  - `AuthService.confirmReset(PasswordResetConfirm)` → load token; if not consumable → `ApiException(400,"INVALID","...")`; else set member passwordHash, consume token.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/jaram/be/auth/PasswordResetTest.java`:
```java
package com.jaram.be.auth;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasswordResetTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired PasswordResetTokenRepository tokens;
    @Autowired PasswordEncoder encoder;

    @BeforeEach void setup() {
        RestAssured.port = port;
        tokens.deleteAll();
        members.deleteAll();
    }

    @Test
    void resetRequestAlwaysReturns200EvenForUnknownEmail() {
        given().contentType("application/json").body(Map.of("email", "ghost@hanyang.ac.kr"))
                .when().post("/api/auth/password/reset-request")
                .then().statusCode(200);
    }

    @Test
    void resetConfirmChangesPassword() {
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", encoder.encode("oldpass!9"));
        m.setStatus(MemberStatus.ACTIVE);
        members.save(m);
        var t = PasswordResetToken.issue(m.getId(), "tok-abc", Instant.now().plus(30, ChronoUnit.MINUTES));
        tokens.save(t);

        given().contentType("application/json")
                .body(Map.of("token", "tok-abc", "password", "newpass!9"))
                .when().post("/api/auth/password/reset")
                .then().statusCode(200);

        Member updated = members.findById(m.getId()).orElseThrow();
        assertThat(encoder.matches("newpass!9", updated.getPasswordHash())).isTrue();
    }

    @Test
    void resetConfirmWithBadTokenReturns400() {
        given().contentType("application/json")
                .body(Map.of("token", "nope", "password", "newpass!9"))
                .when().post("/api/auth/password/reset")
                .then().statusCode(400).body("code", equalTo("INVALID"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests PasswordResetTest`
Expected: FAIL — endpoints/types not defined.

- [ ] **Step 3: Implement DTOs + mail sender**

`PasswordResetRequest.java`:
```java
package com.jaram.be.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(@Email @NotBlank String email) { }
```

`PasswordResetConfirm.java`:
```java
package com.jaram.be.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PasswordResetConfirm(
        @NotBlank String token,
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$",
                 message = "비밀번호는 8자 이상이며 영문·숫자·기호를 각각 포함해야 합니다.")
        String password) { }
```

`ResetMailSender.java`:
```java
package com.jaram.be.auth;

public interface ResetMailSender {
    void send(String email, String token);
}
```

`LoggingResetMailSender.java`:
```java
package com.jaram.be.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingResetMailSender implements ResetMailSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingResetMailSender.class);

    @Override
    public void send(String email, String token) {
        log.info("[password-reset] would email {} a reset link with token {}", email, token);
    }
}
```

- [ ] **Step 4: Add reset methods to AuthService**

Inject `PasswordResetTokenRepository tokens`, `ResetMailSender mailSender`, and `@Value("${jwt.reset-ttl-seconds}") long resetTtlSeconds` via the constructor. Add:
```java
import com.jaram.be.auth.dto.PasswordResetConfirm;
import com.jaram.be.auth.dto.PasswordResetRequest;
import java.time.Instant;
import java.util.UUID;

@Transactional
public void requestReset(PasswordResetRequest req) {
    members.findByEmail(req.email()).ifPresent(m -> {
        String token = UUID.randomUUID().toString();
        tokens.save(PasswordResetToken.issue(
                m.getId(), token, Instant.now().plusSeconds(resetTtlSeconds)));
        mailSender.send(m.getEmail(), token);
    });
    // always succeeds (enumeration defense)
}

@Transactional
public void confirmReset(PasswordResetConfirm req) {
    Instant now = Instant.now();
    PasswordResetToken t = tokens.findByToken(req.token())
            .filter(x -> x.isConsumable(now))
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID", "유효하지 않거나 만료된 토큰입니다."));
    Member m = members.findById(t.getMemberId())
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID", "유효하지 않은 토큰입니다."));
    m.setPasswordHash(encoder.encode(req.password()));
    t.consume(now);
}
```

- [ ] **Step 5: Add mappings to AuthController**

```java
import com.jaram.be.auth.dto.PasswordResetConfirm;
import com.jaram.be.auth.dto.PasswordResetRequest;

@PostMapping("/password/reset-request")
public void resetRequest(@Valid @RequestBody PasswordResetRequest req) {
    auth.requestReset(req);
}

@PostMapping("/password/reset")
public void resetConfirm(@Valid @RequestBody PasswordResetConfirm req) {
    auth.confirmReset(req);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests PasswordResetTest`
Expected: PASS (all three).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/jaram/be/auth src/test/java/com/jaram/be/auth/PasswordResetTest.java
git commit -m "feat: add password reset request and confirm endpoints"
```

---

### Task 11: Contract validation test (OpenAPI conformance)

**Files:**
- Create: `src/main/resources/openapi/openapi.yaml` (copy from this repo's `docs/api/openapi.yaml`)
- Test: `src/test/java/com/jaram/be/contract/AuthContractTest.java`

**Interfaces:**
- Consumes: every auth/admin endpoint built above; the OpenAPI contract.
- Produces: a REST-assured test asserting live responses validate against the OpenAPI schema via `swagger-request-validator-restassured`.

- [ ] **Step 1: Copy the contract into the repo**

Run:
```bash
mkdir -p src/main/resources/openapi
cp docs/api/openapi.yaml src/main/resources/openapi/openapi.yaml
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/jaram/be/contract/AuthContractTest.java`:
```java
package com.jaram.be.contract;

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.support.PostgresTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthContractTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired MemberRepository members;

    private final OpenApiValidationFilter validation =
            new OpenApiValidationFilter("openapi/openapi.yaml");

    @BeforeEach void setup() {
        RestAssured.port = port;
        members.deleteAll();
    }

    @Test
    void signupResponseMatchesContract() {
        given().filter(validation)
                .contentType("application/json")
                .body(Map.of("name", "홍길동", "studentId", "2023012345",
                        "email", "hong@hanyang.ac.kr", "password", "passw0rd!"))
                .when().post("/api/auth/signup")
                .then().statusCode(201);
    }

    @Test
    void loginErrorResponseMatchesContract() {
        given().filter(validation)
                .contentType("application/json")
                .body(Map.of("email", "ghost@hanyang.ac.kr", "password", "passw0rd!"))
                .when().post("/api/auth/login")
                .then().statusCode(404);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests AuthContractTest`
Expected: FAIL first if contract file missing/path wrong; fix the resource path, then it should pass once responses conform.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests AuthContractTest`
Expected: PASS — responses validate against `openapi.yaml`. If validation reports a mismatch, fix the DTO/response to match the contract (the contract wins).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: ALL tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/openapi/openapi.yaml src/test/java/com/jaram/be/contract
git commit -m "test: add OpenAPI contract conformance tests for auth"
```

---

## Self-Review

**1. Spec coverage (P1 = §12.1):**
- Spring setup → Task 1. ✅
- Security + JWT → Tasks 5, 6. ✅
- Global error handler → Task 2. ✅
- Member entity → Task 3. ✅
- signup (UC-A1) → Task 7. ✅
- login (UC-A2) → Task 8. ✅
- password reset (UC-A3, UC-A4) → Task 10. ✅
- UC-A5 admin approval → Task 9. ✅
- Contract test (§11) → Task 11. ✅
- Error code map (§7): NOT_FOUND/PENDING/INVALID (Task 8), EMAIL_TAKEN/VALIDATION (Task 7), FORBIDDEN/401 (Task 6), SERVER (Task 2). ✅

**2. Placeholder scan:** No "TBD"/"add error handling"/"write tests for the above" — every code step shows full code. Two deliberate, documented deferrals (REJECTED→PENDING mapping; member rejection-reason not persisted) are locked decisions, not placeholders. ✅

**3. Type consistency:** `ApiException` gains a 4-arg constructor in Task 7 used by Tasks 7/8/9/10 — signature consistent. `JwtProvider.JwtClaims` record used in Task 6 matches Task 5. `Member.newPending`, `setStatus`, `getPasswordHash` used consistently across Tasks 3/8/9/10. `PasswordResetToken.issue/isConsumable/consume` consistent across Tasks 4/10. `CurrentMember` defined Task 6 (unused by services in P1 — it is the principal for P2+ authenticated reads; harmless to keep). ✅

---

## Out of scope for P1 (later phases)

- P2 people (`GET /api/people`), P3 seminar, P4 study endpoints — separate plans.
- Real SMTP mail (currently logging stub).
- DB migrations (Flyway) — P1 uses `ddl-auto=update`; introduce Flyway before production.
- `openapi-typescript` FE type generation (§11 optional).

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-29-be-p1-foundation-auth.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

Note: execution happens in **this repo** (`home-jaram-be`), already scaffolded. No repo creation needed.
