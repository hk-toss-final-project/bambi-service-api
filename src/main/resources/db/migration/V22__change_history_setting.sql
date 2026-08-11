-- =============================================================
-- V22__change_history_setting.sql — 변경점(Delta) 추적 계정 설정 (2026-08-10 김기용 요청)
-- -------------------------------------------------------------
-- 델타 리포트를 요청 단위 토글에서 **계정 단위 설정**으로 전환한다.
-- PATCH /api/users/me/settings 로 변경, GET /api/auth/me 로 조회 (V17 설정 패턴 그대로).
-- 온디맨드 생성 시 이 값을 change_history_enabled 로 agent 에 싣는다.
--
-- 기본 FALSE: 델타 경로는 일반 생성보다 LLM 호출이 많다(팩트 추출·종합·파급효과 워커) —
-- 비용이 더 드는 경로는 사용자가 명시적으로 켠다(김기용 트레이드오프 안내 08-10).
--
-- ⚠️ 엔티티 @Column 과 타입·기본값 정합 필수 (V15 CHAR(64) vs varchar → ddl-auto=validate 502 교훈):
--   change_history_enabled BOOLEAN ↔ User.changeHistoryEnabled (boolean) 기본 false
--
-- 번호: V19 → V21 → **V22 로 두 번 옮겼다**(2026-08-10). 같은 함정을 세 번 밟았다.
--   1차: #76(Wiki·리포트 상태 폴링)이 먼저 머지되며 V19__wiki_build_operations ·
--        V20__generation_pending_lifecycle 을 선점 → V21 로 이동.
--   2차: 그 뒤 #79(스크랩 Wiki Outbox)가 머지되며 V21__scrap_wiki_outbox 를 선점 → V22 로 이동.
--        "#74 가 V21, #77 이 V22" 로 합의했었지만, 합의에 없던 #79 가 먼저 들어와 V21 을 가져갔다.
--   파일명이 달라 git 은 충돌로 안 잡고 mergeable 도 clean 이다. 그대로 머지했으면 배포에서
--   Flyway 가 같은 버전 2개를 거부해 백엔드가 기동에 실패했을 것이다(ddl-auto=validate).
--
--   ⚠️ 합의로는 못 막는다는 것이 이번 교훈이다. 합의 밖의 PR 이 언제든 번호를 가져간다.
--   머지 직전에 main 과 **모든 오픈 PR** 의 번호를 다시 대조할 것 — PR 을 연 시점이나 합의한
--   시점이 아니라 **머지하는 시점** 기준이어야 한다. 순서도 본다: 낮은 번호가 나중에 오면
--   Flyway out-of-order 로 막힌다.
--   ⚠️ 2026-08-10 기준 #77(펜딩↔카드 연결)도 아직 V21 에 앉아 있어 main 과 충돌한다.
--      이 PR 이 V22 를 가져가므로 #77 은 V23 으로 올려야 한다.
-- =============================================================

ALTER TABLE service.users
    ADD COLUMN change_history_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN service.users.change_history_enabled IS
    '변경점(Delta) 추적 리포트 계정 설정. true 면 온디맨드 생성 요청에 change_history_enabled 를 실어 보낸다. 기본 false(비용 큰 경로는 명시적 opt-in).';
