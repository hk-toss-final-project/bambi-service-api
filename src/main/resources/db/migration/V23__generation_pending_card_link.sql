-- =============================================================
-- V23__generation_pending_card_link.sql — 펜딩 ↔ 완성 카드 연결 (2026-08-10)
-- -------------------------------------------------------------
-- 완료 전환만으로는 프론트가 "처리중" 카드를 완성 카드로 바꿔 끼울 수 없다.
-- 어느 카드가 됐는지를 같이 줘야 한다(우석 요청).
--
-- card_public_id 는 카드의 대외 식별자(UUID)다 — 순번 id 는 노출하지 않는 V1 설계 그대로.
-- 완료 전까지는 NULL 이고, agent 자체 생성 경로처럼 매칭되는 펜딩이 없는 카드는
-- 애초에 이 테이블에 행이 없다.
--
-- 번호 조율: main 이 V22(#74 change_history_setting)까지 사용 중. 08-11 우석 지적으로 V21→V23. flyway out-of-order 켜져 있음(application.yml).
-- ⚠️ 마이그레이션 추가 전 열린 PR 의 번호를 확인할 것 — 파일명이 다르면 git 이 충돌을
--    알리지 않아 양쪽 다 초록으로 머지되고 배포에서 Flyway 가 죽는다(08-08 V17 중복 사례).
-- =============================================================

ALTER TABLE service.generation_pendings
    ADD COLUMN card_public_id UUID;

COMMENT ON COLUMN service.generation_pendings.card_public_id IS
    '이 접수가 만들어낸 카드의 public_id. 완료 전엔 NULL. 프론트가 처리중 슬롯을 완성 카드로 교체하는 데 쓴다.';
