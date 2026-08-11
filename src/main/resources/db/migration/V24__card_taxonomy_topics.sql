-- =============================================================
-- V24__card_taxonomy_topics.sql — 카드↔taxonomy 토픽 매핑 (추천 피드 매칭, 2026-08-11 확정)
-- -------------------------------------------------------------
-- 추천 피드 문제: 사용자 관심사(interests.taxonomy_topic_id)와 카드 태그(card_interest_tags, 자유문자열)
-- 사이에 공통 식별자가 없어 exact match 가 0건 → Empty. 카드에도 taxonomy topic_key 를 붙여
-- "뷰어 관심 topic/category ∩ 카드 topic" 매칭이 되게 한다(계약 A안: 인용 원본 파생 + topic 폴백, LLM 미사용).
--
-- 채우는 주체: Agent(발행 스냅샷 taxonomy_topic_ids/taxonomy_version, 소라). Service 는 저장·매칭만.
-- 값은 interest_topics.topic_key 와 같은 어휘(예: 'ai_ml','economy'). 매칭은 활성 taxonomy 기준 topic_key 동등.
--
-- ⚠️ 마이그레이션↔엔티티 정합(V15 CHAR(64) 502 교훈):
--   card_taxonomy_topics.topic_id  VARCHAR(50)  ↔ Card.taxonomyTopicIds  (@ElementCollection String, length=50)
--   cards.taxonomy_version         VARCHAR(50)  ↔ Card.taxonomyVersion   (String, nullable)
-- card_interest_tags(V5) 저장 패턴을 그대로 미러링한다(카드 소유 값, 카드 삭제 시 CASCADE).
--
-- 번호: V19(wiki_build_operations, #76)와 충돌해 V24 로 renumber. main 이 V23 까지 차 있어 다음 빈 번호 = V24.
--   (out-of-order 는 늦게 온 낮은 번호를 허용하는 것이지 중복 번호를 허용하는 게 아니다 — 소라 지적.)
-- Flyway out-of-order ON. 앞 버전 수정 금지.
-- =============================================================

-- 카드가 파생된 taxonomy 버전(어느 스냅샷 기준 topic_key 인지). 롤아웃 전 카드는 null.
ALTER TABLE service.cards
    ADD COLUMN taxonomy_version VARCHAR(50);

-- 카드↔topic_key (0..N). 매칭은 이 집합과 뷰어 관심 topic 의 교집합.
CREATE TABLE service.card_taxonomy_topics (
    card_id     BIGINT NOT NULL REFERENCES service.cards(id) ON DELETE CASCADE,
    topic_id    VARCHAR(50) NOT NULL,
    PRIMARY KEY (card_id, topic_id)
);

-- 추천 매칭은 topic_id 로 카드를 뒤지지 않고(카드→topic 방향) 피드 카드들의 topic 을 배치 로딩하므로
-- 별도 topic_id 인덱스는 두지 않는다(PK 로 card_id 조회 충분). 필요 시 후속에서 추가.

COMMENT ON TABLE service.card_taxonomy_topics IS
    '카드가 매핑되는 taxonomy topic_key 집합. Agent 발행 스냅샷 taxonomy_topic_ids 에서 채운다. 추천 매칭용.';
COMMENT ON COLUMN service.cards.taxonomy_version IS
    '카드 topic_id 가 파생된 taxonomy 버전(interest_taxonomy_versions.version). 롤아웃 전 카드는 null.';
