-- 스터디 승인축을 생애축으로 접는 마이그레이션 (PostgreSQL)
--
-- 대상: feat/study-lifecycle — 스터디 ① 상태 기계와 모집
-- 설계: docs/superpowers/specs/2026-09-15-study-lifecycle-design.md §11
--
-- study.approval_status(PENDING/APPROVED/REJECTED)와 파생값이던 status 를
-- 저장 컬럼 study.status(PENDING/REJECTED/RECRUITING/ONGOING/FINISHED) 하나로 접는다.
--
-- ── 왜 컬럼 생성까지 손인가 ──────────────────────────────────────────────
-- ddl-auto: update 는 컬럼 추가를 자동으로 한다. 그런데 이번엔 추가도 손이다 —
-- 배포가 develop push 한 번에 compose pull + up 이라(.github/workflows/image.yml),
-- 컬럼 생성과 트래픽 수용이 같은 컨테이너 기동이다. "ddl-auto 가 만든 직후, 새 코드가
-- 받기 전"이라는 창이 존재하지 않는다. 그래서 컬럼을 먼저 만들고 채운 뒤 머지한다.
-- Hibernate 의 update 는 이미 있는 컬럼에 대해서는 아무 DDL 도 내지 않는다.
--
-- ── 실행 시점 ───────────────────────────────────────────────────────────
--   1단계 — BE PR 을 develop 에 머지하기 **전에**
--   2단계 — 머지·배포가 끝나고 구버전 인스턴스가 없는 것을 확인한 **뒤에**
--
-- 각 단계를 한 트랜잭션으로 실행한다. 멱등하다 — 두 번 돌려도 결과가 같다.
--
-- ── 여기서 다루지 않는 것 ───────────────────────────────────────────────
-- 같은 배포가 만드는 새 테이블 둘(study_week, study_recruitment)은 손댈 것이 없다.
-- 빈 테이블을 새로 만드는 일이라 NOT NULL 문제가 없고, ddl-auto: update 가
-- 기동하면서 제약까지 제대로 만든다. 새 컬럼 study.place / study.contact 도
-- nullable 이라 자동으로 붙는다.


-- ========== 1단계: BE PR 을 develop 에 머지하기 전에 ==========

-- not null 도, check 제약도 붙이지 않는다.
--  * not null: 행이 있는 테이블에 붙이면 Postgres 가 거부한다. Hibernate 의
--    SchemaUpdate 는 그 예외를 로그 한 줄로 삼킨 뒤 기동을 끝내므로, 컬럼이 없는
--    채로 트래픽을 받아 study 질의가 전부 500 이 된다.
--  * check: Hibernate 6.2+ 는 enum 컬럼을 만들 때 값 목록 check 를 같이 만들지만,
--    이미 있는 컬럼에 뒤늦게 붙이지는 않는다. 손으로 붙이면 나중에 enum 값이 늘 때
--    update 가 그 제약을 고쳐 주지 않아 INSERT 가 막힌다 (README '알려진 한계').
ALTER TABLE study ADD COLUMN IF NOT EXISTS status varchar(255);

-- 승인된 것은 진행 중으로 본다. 지난 학기 것들은 사실 FINISHED 에 가깝지만, 틀리면
-- 목록에서 사라져 눈에 안 띈다. ONGOING 으로 두면 '전체' 칩에 남아 있으니 임원이
-- 보고 일괄 편집으로 내릴 수 있다. 안 보이는 쪽으로 틀리는 것보다 보이는 쪽으로
-- 틀리는 편이 고치기 쉽다.
--
-- AND status IS NULL 이 멱등성을 만든다. 없으면 배포 뒤 이 파일을 한 번 더 돌렸을 때
-- 그동안 스터디장과 임원이 옮겨 놓은 상태가 전부 approval_status 로 되감긴다 —
-- 종료한 스터디가 다시 진행 중이 되고, 그 사실은 아무 데도 안 남는다.
UPDATE study SET status = 'PENDING'
    WHERE approval_status = 'PENDING'  AND status IS NULL;
UPDATE study SET status = 'REJECTED'
    WHERE approval_status = 'REJECTED' AND status IS NULL;
UPDATE study SET status = 'ONGOING'
    WHERE approval_status = 'APPROVED' AND status IS NULL;

-- 0 이 아니면 배포하지 않는다. 남은 NULL 은 status in (...) 필터에 하나도 걸리지 않아
-- 그 스터디들이 목록에서 조용히 사라진다.
SELECT count(*) AS must_be_zero FROM study WHERE status IS NULL;


-- ========== 2단계: 배포 후, 코드가 옛 컬럼을 읽지 않는 것을 확인한 뒤 ==========

-- 먼저 지우면 구버전 인스턴스가 뜨는 동안 매핑이 깨진다.
--
-- 2단계를 먼저 돌려 컬럼이 없어진 뒤 1단계를 다시 돌리면 UPDATE 가 approval_status 를
-- 못 찾아 에러로 멈춘다 — 그때는 아무것도 바뀌지 않으므로 안전하다.
ALTER TABLE study DROP COLUMN IF EXISTS approval_status;
ALTER TABLE study DROP COLUMN IF EXISTS period;
