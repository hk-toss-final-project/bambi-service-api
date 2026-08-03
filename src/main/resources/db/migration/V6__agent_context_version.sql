-- =============================================================
-- V6__agent_context_version.sql — 사용자별 agent 컨텍스트 동기화 버전
-- -------------------------------------------------------------
-- agent 계약 §4.3: context_version 은 사용자별 단조 증가 정수여야 하고, 같거나 작은 값을
-- 재전송하면 agent 가 STALE_CONTEXT_VERSION(409) 으로 거부한다. 이 컬럼이 그 버전의 원천.
-- 가입 시 0 → 첫 동기화에서 +1 하여 1 로 전송, 이후 컨텍스트 변경마다 +1.
-- (기존 버그: 항상 1 하드코딩 → 두 번째 동기화부터 무조건 STALE.)
--
-- 마이그레이션 번호: V6 = 소라 agent_context_version(이 파일), V7 = 스크랩(영현), V8 = 우석 users.bio.
-- Flyway: 앞 버전 수정 금지, 추가만.
-- =============================================================

ALTER TABLE service.users
    ADD COLUMN agent_context_version INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN service.users.agent_context_version IS
    'agent 컨텍스트 동기화 버전(단조 증가). 동기화 때마다 +1 후 그 값으로 PUT /users/{id}/context.';
