-- =============================================================
-- V13__report_type.sql — 생성 유형(report_type) 저장 (2026-08-06 팀 합의)
-- -------------------------------------------------------------
-- 값: MORNING_BRIEFING(아침 브리핑) | ON_DEMAND(즉시 생성). CHECK 제약은 걸지 않는다 —
--   agent(소라 게이트웨이)가 claim items[].report_type 을 단계적으로 추가하는 중이라
--   필드 부재/신규 값에도 발행 트랜잭션이 깨지면 안 된다(관용 파싱, 없으면 NULL).
-- 기존 생성분은 NULL 유지(백필 안 함) — 프론트는 NULL 을 "유형 미상"으로 다룬다.
--
-- cards 에도 중복 저장하는 이유: CardResponse 는 리포트 조인 없이도 서빙되는 경로가 있고
--   (즉시 카드 저장 응답 등), 피드/상세 경로도 Report 를 publicId 매핑용으로만 읽는다.
--   claim upsert 가 카드·리포트를 항상 같이 쓰므로 발행 시점 복제가 조회 시 조인보다 싸다.
-- notifications 에도 저장: 알림은 claim(발행) 시점에 만들어져 그때 값을 알 수 있다 —
--   조회 시 리포트 재조인 없이 알림 행에서 바로 내린다.
-- =============================================================

ALTER TABLE service.reports       ADD COLUMN report_type VARCHAR(30);
ALTER TABLE service.cards         ADD COLUMN report_type VARCHAR(30);
ALTER TABLE service.notifications ADD COLUMN report_type VARCHAR(30);
