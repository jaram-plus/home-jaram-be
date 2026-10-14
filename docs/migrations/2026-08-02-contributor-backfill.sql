-- 기여자 플래그 백필 (PostgreSQL)
--
-- 배경
--   Member.assignTerm 이 이제 contributor 를 켠다. 그 변경 이전에 임기를 받은
--   회원은 플래그가 꺼진 채로 남아 있어, 관리 화면 기여자 탭과 공개 인원 소개에
--   나오지 않는다. 임기 이력이 있는 회원을 한 번에 기여자로 올린다.
--
-- 이 프로젝트는 ddl-auto: update 로 스키마를 관리하고 마이그레이션 도구가 없다.
-- 컬럼(member.contributor)과 테이블(member_term)은 이미 존재하므로 스키마 변경은
-- 없고, 데이터 이관만 한다.
--
-- ── 실행 시점 ──────────────────────────────────────────────────────────
-- 새 코드를 배포한 뒤 아무 때나 실행해도 된다. 멱등하므로 여러 번 실행해도
-- 두 번째부터는 0건이 갱신된다.

BEGIN;

-- 사전 점검 — 갱신될 건수. 따로 실행해 눈으로 확인한다.
--   SELECT count(*) FROM member
--    WHERE contributor = false AND id IN (SELECT member_id FROM member_term);

UPDATE member SET contributor = true
 WHERE contributor = false
   AND id IN (SELECT DISTINCT member_id FROM member_term);

COMMIT;
