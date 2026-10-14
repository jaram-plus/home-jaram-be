-- 승인 대기 회원 등급 백필 (PostgreSQL)
--
-- 배경
--   등급을 정하는 시점이 승인(AdminMemberService.approve)에서 가입 신청
--   (AuthService.signup)으로 옮겨 갔다. 그래서 approve 는 더 이상 등급을
--   채우지 않는다. 이 변경 이전에 신청해 아직 PENDING 으로 남아 있는 회원은
--   grade 가 NULL 인데, 그대로 승인하면 등급 없는 회원이 된다.
--
--   이 사람들이 신입생이었는지 재학생이었는지는 기록이 없다. 신청 당시 적용되던
--   규칙(gen == 현재 기수 → NEWCOMER, 그 외 ASSOCIATE)을 그대로 한 번 적용한다.
--
-- 이 프로젝트는 ddl-auto: update 로 스키마를 관리하고 마이그레이션 도구가 없다.
-- 컬럼(member.grade)은 이미 존재하므로 스키마 변경은 없고, 데이터 이관만 한다.
--
-- ── 실행 시점 ──────────────────────────────────────────────────────────
-- 새 코드를 배포한 뒤, 남아 있는 가입 신청을 승인하기 전에 실행한다.
-- 멱등하므로 여러 번 실행해도 두 번째부터는 0건이 갱신된다.
--
-- ※ 아래 42 는 2026년 기준 현재 기수(연도 - 1984)다. 다른 해에 실행한다면
--   그 해의 기수로 바꿔야 한다.

BEGIN;

-- 사전 점검 — 갱신될 건수. 따로 실행해 눈으로 확인한다.
--   SELECT count(*) FROM member WHERE grade IS NULL;

UPDATE member
   SET grade = CASE WHEN gen = 42 THEN 'NEWCOMER' ELSE 'ASSOCIATE' END
 WHERE grade IS NULL;

-- 사후 점검 — 0 이어야 한다.
--   SELECT count(*) FROM member WHERE grade IS NULL;

COMMIT;
