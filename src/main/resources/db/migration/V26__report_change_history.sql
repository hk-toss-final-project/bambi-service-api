-- =============================================================
-- V26__report_change_history.sql — 리포트 본문 폼 신호 보존 (2026-08-11 김기용 계약)
-- -------------------------------------------------------------
-- agent 발행 payload 의 change_history_enabled 를 리포트에 그대로 보존한다.
-- 프론트가 본문(body)을 "변경점(Delta) 4단 폼"으로 렌더할지 "기존 자유 형식"으로
-- 렌더할지 고르는 유일한 기준이다 — 본문 헤더 문자열을 파싱해 추측하게 하면 안 된다
-- (agent docs/service-integration-guide.md §4).
--
-- 계정 설정인 users.change_history_enabled(V22)와 **다른 값이다.** 저쪽은 "앞으로
-- 생성할 때 델타 경로를 쓸지"이고, 이쪽은 "이미 저장된 이 본문이 어떤 폼인지"다.
-- 사용자가 설정을 끈 뒤에도 과거 델타 리포트는 델타 폼으로 남아 있어야 하므로,
-- 계정 설정으로 렌더링을 판단하면 그 카드들이 전부 깨진다.
--
-- 기본 FALSE: 이 컬럼이 없던 기존 리포트는 전부 자유 형식 본문이다. agent 도 필드
-- 미도착 스냅샷을 false 로 다루기로 계약에 명시했다(PublishItem 참조).
--
-- ⚠️ 엔티티 @Column 과 타입·기본값 정합 필수 (V15 CHAR(64) 교훈):
--   change_history_enabled BOOLEAN ↔ Report.changeHistoryEnabled (boolean) 기본 false
--
-- ⚠️ 번호: 머지 직전에 main 과 **모든 오픈 PR** 의 번호를 다시 대조할 것 (V22 교훈).
--   2026-08-11 확인 시점 main 최신 = V25__report_cover_image, 원격 브랜치 전체에
--   V26 선점 없음. 합의 밖 PR 이 V26 을 가져가면 이 파일을 올려야 한다.
-- =============================================================

ALTER TABLE service.reports
    ADD COLUMN change_history_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN service.reports.change_history_enabled IS
    '이 리포트 body 가 변경점(Delta) 4단 폼인지 여부. true 면 "이번에 달라진 점/보고서 내용/주목할 점/타임라인" 구조, false 면 기존 자유 형식. 발행 스냅샷의 change_history_enabled 를 그대로 보존한 값이며, 계정 설정(users.change_history_enabled)과 혼용하지 않는다.';
