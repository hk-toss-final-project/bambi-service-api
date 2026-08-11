-- =============================================================
-- V27__blocked_wiki_tags.sql — 발견 관심사 숨기기 (2026-08-11 우석)
-- -------------------------------------------------------------
-- [AI가 최근 발견한 관심사]에서 원하지 않는 후보를 지울 수 있게 한다.
-- 지금까지는 "무시" 상태를 저장할 곳이 없어 버튼 자체를 만들지 않았다(새로고침이면
-- 되돌아오는 가짜 동작 금지). 이 테이블이 그 저장소다.
--
-- 키가 id 가 아니라 **이름**인 이유: agent 태그 id 는 위키 재계산 때마다 새로 발급되므로
-- 다음 빌드에서 같은 주제가 다른 id 로 돌아온다. 우리 도메인의 공통 어휘도 이름이다
-- (topics[]·관심사 name 전부 이름 문자열, agent 계약 §interest_id 대응표 참고).
-- 대소문자·공백 차이로 새는 것을 막으려 정규화된 이름(lower(btrim))으로 저장한다.
--
-- 되돌리기 = 행 삭제(하드). soft delete 를 쓰지 않는 이유는 "숨김 해제"가 이력을 남길
-- 가치가 없고, 같은 이름을 다시 숨길 때 유니크 충돌만 만들기 때문이다.
--
-- 번호: main 최대 V26 다음 = V27.
-- =============================================================

CREATE TABLE service.blocked_wiki_tags (
    user_id     BIGINT NOT NULL REFERENCES service.users(id) ON DELETE CASCADE,
    tag_name    VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, tag_name)
);

COMMENT ON TABLE service.blocked_wiki_tags IS
    '사용자가 [AI가 최근 발견한 관심사]에서 숨긴 태그 이름(정규화 저장). 위키 태그 응답에서 제외하고 agent blocked_interest_ids 로도 전달한다.';
