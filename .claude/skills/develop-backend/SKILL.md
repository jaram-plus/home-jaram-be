---
name: develop-backend
description: Use when implementing or extending any backend feature/endpoint in jaram-be (the Spring Boot app) — adding an API operation, wiring a UC-xx usecase, a controller/service/repository/entity, error handling, or contract tests. Covers the contract-first workflow (which superpowers skills to use when), the OpenAPI sync step, and this repo's layered conventions. Reach for this whenever the task is "build/add/implement <something> in the backend", even if the OpenAPI contract isn't mentioned explicitly.
---

# Develop a feature in jaram-be

jaram-be is a Spring Boot 3.4 / Java 21 / Gradle backend, package root `com.jaram.be`.
Its defining rule is **the contract is law**: `docs/api/openapi.yaml` (OpenAPI 3.1) is the
single source of truth. That file is a **symlink into the FE repo** (`home-jaram-fe`) — the
frontend authors the contract, the backend conforms to it. You don't invent endpoints or
payload shapes; you read them from the contract and make the code match exactly.

This skill has two jobs:
1. **Orchestrate** — point you to the right superpowers skill at each stage (spec → plan → implement → verify).
2. **Encode this repo's conventions** so the code you write looks like the code that's already here.

## Workflow: where each superpowers skill fits

Don't do this freehand. The repo already keeps specs in `docs/superpowers/specs/` and phased
plans in `docs/superpowers/plans/` — follow that grain.

1. **Sync the contract first.** Run `./scripts/sync-openapi.sh`. FE may have changed
   `docs/api/openapi.yaml`; the contract tests load a *copy* at
   `src/main/resources/openapi/openapi.yaml`, and that copy must match. See [Contract sync](#contract-sync).

2. **Read the contract and the design spec.** Find the path(s) you're implementing in
   `docs/api/openapi.yaml` and the matching usecase (UC-xx) + acceptance criteria in
   `docs/superpowers/specs/2026-06-29-jaram-backend-design.md`. The spec also fixes the
   domain model, enum wire values, error-code map, and validation rules — treat it as binding.

3. **For anything non-trivial, plan before coding.** If there's no plan covering this work,
   invoke **superpowers:writing-plans** to produce one under `docs/superpowers/plans/`. The
   existing P1 plan (`2026-06-29-be-p1-foundation-auth.md`) is the template: per-task Files /
   Interfaces / TDD steps. If you're still deciding *what* to build (not just how), start with
   **superpowers:brainstorming**.

4. **Implement task-by-task with TDD.** Use **superpowers:executing-plans** (or
   **superpowers:subagent-driven-development**) to work through the plan. Every task is
   test-first — invoke **superpowers:test-driven-development**. Write the failing test, see it
   fail, implement, see it pass, commit. See [Conventions](#conventions) and [Testing](#testing).

5. **Verify against the contract.** Each endpoint needs a contract test (see [Testing](#testing)).
   A feature is done only when its OpenAPI path is implemented AND its contract test passes.

## Conventions

Standard Spring Boot layered structure, **package-by-feature**. Each existing feature package
(`auth`, `admin`, `member`, `security`, `common`) holds its own Controller / Service /
Repository / entity / `dto/`. New features get their own package (`seminar`, `study`, `people`)
following the same shape. Keep each layer's responsibility answerable in one line:

```
Controller  ── 1:1 with an OpenAPI operation. Request/response DTO == OpenAPI schema. @Valid, status codes.
Service     ── @Transactional. Domain rules: state transitions, authorization, derived fields. Throws ApiException.
Repository  ── Spring Data JPA interface. Derived query methods.
Entity      ── Persistence model. Static factory, no Lombok, plain getters.
```

### Controller
- `@RestController` + `@RequestMapping("/api/...")`, constructor injection (no field `@Autowired`).
- One handler per OpenAPI operation; the path/verb must match the contract exactly.
- Request bodies are `record` DTOs in the feature's `dto/` package, annotated `@Valid`.
- Return the response DTO directly (Jackson serializes). For 201/204-style operations return
  `void` and set `@ResponseStatus(HttpStatus.CREATED)` — see `AuthController.signup`.
- For authenticated endpoints, inject the principal: `@AuthenticationPrincipal CurrentMember me`
  (`CurrentMember(id, name, email, authority)`, populated by `JwtAuthFilter`).

### Service
- `@Service`, `@Transactional` on mutating methods, constructor injection.
- Encodes domain rules and **throws `ApiException(HttpStatus, code, message)`** for every
  business error — never return error DTOs by hand. The global handler serializes it.
- Use the field-level form `new ApiException(status, code, message, Map.of("field","msg"))`
  when the error belongs to a specific input (e.g. duplicate studentId → 422 `VALIDATION`).

### Entity & enums
- `@Entity` with a static factory (e.g. `Member.newPending(...)`), `protected` no-arg
  constructor, `String id` = `UUID.randomUUID().toString()`. Plain getters; setters only where
  state legitimately changes. No Lombok.
- **Enum wire values are fixed by FE — do not change casing.** Some are UPPER_CASE on the wire
  (`Authority` = `MEMBER`/`OFFICER`), some lower (`MemberCategory` = `exec`/`contrib`/`grad`).
  Where the wire value is lowercase, the Java enum constant is *named* lowercase so
  `@Enumerated(EnumType.STRING)` round-trips without a converter. Check the spec's enum table
  before adding one.

### Error model (fixed — FE depends on these)
All errors serialize through `GlobalExceptionHandler` (`@RestControllerAdvice`) into one
envelope: `{ code, message, fieldErrors }`. `fieldErrors` is non-null only on 422. Status↔code
map (authoritative copy in the spec §7):

| Situation | HTTP | code |
|---|---|---|
| login unregistered | 404 | `NOT_FOUND` |
| login pending/rejected | 403 | `PENDING` |
| login bad credentials | 401 | `INVALID` |
| signup duplicate email | 409 | `EMAIL_TAKEN` |
| attendance code wrong/closed | 400 | `INVALID_CODE` |
| token invalid/expired | 401 | `UNAUTHORIZED` |
| forbidden (wrong role) | 403 | `FORBIDDEN` |
| bean-validation failure | 422 | `VALIDATION` (+ `fieldErrors`) |
| uncaught | 5xx | `SERVER` |

### Security & authorization
- Route authorization lives in `SecurityConfig.filterChain` (declarative `authorizeHttpRequests`),
  **not** in controllers. When you add an endpoint, add its matcher there: `permitAll()` for
  public reads, `hasAuthority("OFFICER")` for officer-only, else `authenticated()`.
- Stateless JWT (HS256) via `JwtAuthFilter`. 401 → `RestAuthEntryPoint`, 403 →
  `RestAccessDeniedHandler`, both writing the standard envelope.
- The three-tier model is public / member (`authority=MEMBER`, `status=ACTIVE`) / officer
  (`authority=OFFICER`) — see spec §5.

### Validation (server is authoritative)
Bean Validation on DTO records (`@NotBlank`, `@Pattern`, `@Email`). The fixed rules: email =
format + `@hanyang.ac.kr`; studentId = `^\d{8,10}$`, unique; password = ≥8 chars with ≥1
letter, ≥1 digit, ≥1 symbol; name/motive required; rejection `reason` required. Copy the exact
regexes from `SignupRequest` rather than re-deriving them.

## Testing

Three patterns already in the repo — match the closest one:

- **Repository test** — `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)`, extends
  `support.PostgresTest` (shared Testcontainers Postgres base). See `MemberRepositoryTest`.
- **Endpoint / usecase test** — `@SpringBootTest(webEnvironment = RANDOM_PORT)` + RestAssured,
  extends `PostgresTest`, `@LocalServerPort int port`. One test per acceptance criterion
  (success + each error branch). See `SignupTest`, `LoginTest`, `SecurityAccessTest`.
- **Contract test** — lives in `com.jaram.be.contract`. Same `@SpringBootTest` + RestAssured
  setup, but attaches `new OpenApiValidationFilter("openapi/openapi.yaml")` via `.filter(...)`
  so the real response is validated against the OpenAPI schema. Add one per new operation. See
  `AuthContractTest`.

Pure-unit tests (no Spring context) are fine where there's no I/O — see `JwtProviderTest`,
`GlobalExceptionHandlerTest` (constructs the handler directly).

Run focused: `./gradlew test --tests <ClassName>` ; contract suite: `./gradlew test --tests '*ContractTest'`.
To run the app locally and smoke-test, use the **run-backend** skill.

## Contract sync

`docs/api/openapi.yaml` is a symlink into the FE repo (the source of truth). The contract tests
load a copy at `src/main/resources/openapi/openapi.yaml` because a symlink outside the build
tree isn't reliably on the test classpath. They drift when FE edits the contract.

`./scripts/sync-openapi.sh` copies source → test copy (no-op if already in sync). Run it at the
start of any backend work and again whenever you hear FE changed the contract. After syncing,
review `git diff src/main/resources/openapi/openapi.yaml` so you know what changed, then make
the code conform.

**Always run the contract suite right after a sync** (`./gradlew test --tests '*ContractTest'`).
The sync is a blind `cp`, so it faithfully propagates any mistake in FE's file — including YAML
that the Atlassian parser rejects at load time (e.g. an unquoted flow-scalar description
containing a comma fails with `ApiLoadException ... is unexpected`, and that one bad spec load
cascades into *every* contract test failing). Two rules when this happens:

- **Don't edit `docs/api/openapi.yaml`** — it's a symlink into the FE repo and FE owns it.
  Contract bugs get fixed at the source by FE; report them upstream.
- The test copy (`src/main/resources/openapi/openapi.yaml`) is BE-owned. If you need the build
  green before FE fixes their file, you may apply the minimal fix to the *copy* only (and note
  it diverges until FE catches up) — but never commit a copy that fails to parse.

## Definition of done

- OpenAPI path implemented; request/response DTOs match the schema exactly.
- All acceptance criteria for the usecase covered by tests (success + error branches).
- Contract test added and passing; full `./gradlew test` green.
- Route authorization added to `SecurityConfig` if the endpoint isn't public.
- Conventional Commit per task (DRY / YAGNI; commit when each task's tests pass).
