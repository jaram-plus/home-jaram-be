-- 회원 데이터 리팩터링 마이그레이션 (PostgreSQL)
--
-- 대상 커밋
--   cfee29e  refactor: 회원 데이터의 중복 축 제거 (직책·권한·활동)   — P8
--   2ce072a  refactor: 임원 이력을 기수 단위 임기로 저장              — A
--
-- 이 프로젝트는 ddl-auto: update 로 스키마를 관리하고 마이그레이션 도구가 없다.
-- Hibernate 는 테이블·컬럼 추가만 하고 이름 변경·삭제·데이터 이관은 하지 않으므로
-- 아래 SQL 을 손으로 실행해야 한다. 두 배포분이 아직 한 번도 적용된 적이 없어
-- 하나의 스크립트로 합쳤다. P8 → A 순서가 강제된다 (§2 가 §1 이 고친 enum 값을 읽는다).
--
-- ── 실행 시점 ──────────────────────────────────────────────────────────
-- 새 코드로 애플리케이션을 기동한 **직후, 트래픽을 열기 전에** 실행한다.
--   · 기동 후여야 하는 이유 — member_term 테이블과 member.contributor 컬럼을
--     Hibernate 가 그때 만든다.
--   · 트래픽 전이어야 하는 이유 — §1 전까지 member.title 에는 구 enum 이름
--     ('ACADEMIC_LEAD' 등)이 남아 있어 회원을 읽는 요청이 변환 오류로 실패한다.
--
-- 전체를 한 트랜잭션으로 실행한다. 실패하면 아무것도 적용되지 않는다.

BEGIN;

-- ── 0. 사전 점검 ──────────────────────────────────────────────────────
-- 아래 두 쿼리를 먼저 따로 실행해 결과를 확인한다. 자동 중단 장치가 아니다.
--
--   -- (a) 부서 없는 직책 보유자. §1 실행 후 0 이어야 한다. 0 이 아니면 그 회원은
--   --     §2 에서 임기를 받지 못하고 직책이 사라진다.
--   SELECT id, name, student_id, title, department
--     FROM member WHERE title IS NOT NULL AND department IS NULL;
--
--   -- (b) 임기 시작 기수로 쓸 값. 0 이나 NULL 이면 admin_settings 를 먼저 채운다.
--   SELECT current_cohort FROM admin_settings WHERE id = 'SINGLETON';

-- ── 1. P8 — MemberTitle enum 축소 ────────────────────────────────────
-- 9개 값(부서명+직위)을 5개(직위만)로 줄이고 부서는 department 가 담당한다.
-- PRESIDENT / VICE_PRESIDENT / SERVER_ADMIN 은 이름이 그대로라 손대지 않는다.
UPDATE member SET title = 'LEAD'
 WHERE title IN ('ACADEMIC_LEAD', 'PR_LEAD', 'FINANCE_LEAD');

UPDATE member SET title = 'STAFF'
 WHERE title IN ('ACADEMIC_MEMBER', 'PR_MEMBER', 'FINANCE_MEMBER');

-- SERVER_ADMIN 은 부서 없이 저장돼 있었다. 새 조합 규칙(INFRA×SERVER_ADMIN)에 맞춘다.
UPDATE member SET department = 'INFRA'
 WHERE title = 'SERVER_ADMIN' AND department IS NULL;

-- ── 2. A — 현재 직책을 현직 임기로 이관 ──────────────────────────────
-- 시작 기수는 admin_settings.current_cohort, 미설정(0/NULL)이면 올해 기준으로
-- 계산한다. AdminBatchExecutor.currentGen() 과 같은 규칙이다.
--
-- 과거 임기는 DB 에 존재한 적이 없어 복원할 수 없다. "지금 재직 중"이 아는
-- 전부이므로 모든 기존 임원의 시작 기수가 현재 기수가 된다. 근사값이다.
--
-- gen_random_uuid() 는 PostgreSQL 13+ 내장이다. 그 이하라면 먼저
-- CREATE EXTENSION IF NOT EXISTS pgcrypto; 를 실행한다.
INSERT INTO member_term (id, member_id, department, title, start_gen, end_gen)
SELECT gen_random_uuid()::text,
       m.id,
       m.department,
       m.title,
       COALESCE(
           (SELECT NULLIF(s.current_cohort, 0) FROM admin_settings s WHERE s.id = 'SINGLETON'),
           EXTRACT(YEAR FROM now())::int - 1984
       ),
       NULL
  FROM member m
 WHERE m.title IS NOT NULL
   AND m.department IS NOT NULL;

-- ── 3. A — member_category 를 파생 술어로 이관 ───────────────────────
-- exec 는 이관하지 않는다. §2 가 만든 현직 임기 유무가 그 자리를 대신한다.
-- regular 도 이관하지 않는다. 계약에 없던 내부 기본값이었다.
UPDATE member SET contributor = true
 WHERE id IN (SELECT member_id FROM member_category WHERE category = 'contrib');

UPDATE member SET grade = 'OB'
 WHERE id IN (SELECT member_id FROM member_category WHERE category = 'grad');

COMMIT;

-- ── 4. 검증 ──────────────────────────────────────────────────────────
-- §5 정리 전에 아래를 확인한다. 여기까지는 되돌릴 수 있다(구 컬럼이 그대로 있다).
--
--   -- 구 enum 이름 잔존 0건
--   SELECT count(*) FROM member
--    WHERE title NOT IN ('PRESIDENT','VICE_PRESIDENT','LEAD','STAFF','SERVER_ADMIN')
--      AND title IS NOT NULL;
--
--   -- 직책 보유자 수 == 현직 임기 수
--   SELECT (SELECT count(*) FROM member WHERE title IS NOT NULL AND department IS NOT NULL) AS 직책,
--          (SELECT count(*) FROM member_term WHERE end_gen IS NULL)                          AS 현직;
--
--   -- 한 회원에 현직 임기는 최대 하나 (불변식). 결과 0행이어야 한다.
--   SELECT member_id, count(*) FROM member_term WHERE end_gen IS NULL
--    GROUP BY member_id HAVING count(*) > 1;
--
--   -- 카테고리 이관 대조
--   SELECT (SELECT count(*) FROM member_category WHERE category = 'contrib') AS 구_contrib,
--          (SELECT count(*) FROM member WHERE contributor)                    AS 신_contrib,
--          (SELECT count(*) FROM member_category WHERE category = 'grad')     AS 구_grad,
--          (SELECT count(*) FROM member WHERE grade = 'OB')                   AS 신_grad;
--
-- 마지막 대조에서 신_grad 가 더 클 수 있다. grad 카테고리 없이 등급만 OB 인
-- 회원이 이전부터 있었다면 정상이다.
--
-- 애플리케이션 확인: /api/people 의 exec 탭 인원과 관리자 임원진 탭 인원이
-- 마이그레이션 전과 같은지 본다.

-- ── 5. 정리 (선택, 되돌릴 수 없음) ───────────────────────────────────
-- 아래 컬럼·테이블은 새 코드가 더 이상 읽지 않는다. 전부 nullable 이라
-- 남겨 둬도 INSERT 를 깨뜨리지 않는다. §4 검증을 마치고 며칠 지켜본 뒤
-- 별도로 실행하기를 권한다. 실행 전 백업이 있어야 한다.
--
--   ALTER TABLE member DROP COLUMN title, DROP COLUMN department;  -- → member_term
--   DROP TABLE member_category;                                    -- → contributor / grade
--   ALTER TABLE member DROP COLUMN authority;                      -- → 임기 유무에서 파생 (P8)
--   ALTER TABLE member DROP COLUMN enrolled;                       -- → status 가 단일 진실원 (P8)
