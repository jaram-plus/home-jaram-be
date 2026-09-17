-- 활동축 체크 제약에 REREGISTER 추가 (PostgreSQL)
--
-- 배경
--   MemberStatus 에 REREGISTER 가 생겼다(feat(member): 재등록 상태와 학기 전환
--   대상 판정을 더한다). 학기 전환 스윕(MemberLifecycleService)이 활동 회원을
--   이 값으로 넘긴다.
--
--   이 프로젝트는 ddl-auto: update 로 스키마를 관리하고 마이그레이션 도구가 없다.
--   Hibernate 는 새 컬럼(reregister_requested_at, withdrawn_at, purged_at)은
--   추가하지만 **이미 있는 체크 제약은 고치지 않는다**. 그래서 기존 DB 의
--   member_status_check 에는 REREGISTER 가 빠진 채로 남고, 스윕이 도는 순간
--   대상 회원 전원이 이렇게 실패한다.
--
--     ERROR: new row for relation "member" violates check constraint
--            "member_status_check"
--
--   새로 만드는 DB 는 Hibernate 가 처음부터 네 값으로 만들어 해당 없다.
--   이미 떠 있던 DB 에만 필요하다.
--
-- ── 실행 시점 ──────────────────────────────────────────────────────────
-- 스윕이 처음 돌기 전이면 배포 전이든 후든 상관없다. 허용값을 넓히기만 하므로
-- 구 코드가 돌고 있는 동안 실행해도 안전하다 — 구 코드는 REREGISTER 를 쓰지
-- 않는다. 스윕은 기동 후 하루 한 번 도니, 배포와 같이 처리하는 편이 확실하다.
--
-- 멱등하다. 여러 번 실행해도 결과가 같다.

BEGIN;

-- 사전 점검 — 현재 허용값과 제약 이름. 따로 실행해 눈으로 확인한다.
--   SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conrelid = 'member'::regclass AND contype = 'c';
--
-- 이름 'member_status_check' 는 지금 쓰는 Hibernate(6.6 / Boot 3.4)가 enum 컬럼에
-- 붙이는 규칙(<table>_<column>_check)을 그대로 따른 것이다. 버전을 올린 뒤에는
-- 규칙이 달라질 수 있으니 위 쿼리로 실제 이름을 먼저 확인할 것 — 이름이 어긋나면
-- 아래 DROP 이 조용히 지나가고 옛 제약이 그대로 남는다.

ALTER TABLE member DROP CONSTRAINT IF EXISTS member_status_check;

ALTER TABLE member ADD CONSTRAINT member_status_check
    CHECK (status IN ('ACTIVE', 'ON_LEAVE', 'REREGISTER', 'WITHDRAWN'));

COMMIT;

-- ── 검증 ──────────────────────────────────────────────────────────────
-- 위 사전 점검 쿼리를 다시 실행해 네 값이 모두 들어갔는지 본다. 제약 이름은
-- Hibernate 가 붙이는 것과 같게 유지했다 — 다음 배포에서 중복 생성되지 않는다.
