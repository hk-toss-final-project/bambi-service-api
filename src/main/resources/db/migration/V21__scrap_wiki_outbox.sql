-- 스크랩 트랜잭션과 Agent Wiki 반영 사이의 전달 보장을 위한 DB Outbox.
CREATE TABLE service.scrap_wiki_outbox (
    id BIGSERIAL PRIMARY KEY,
    source_event_id UUID NOT NULL UNIQUE,
    related_source_event_id UUID REFERENCES service.scrap_wiki_outbox(source_event_id),
    user_id BIGINT NOT NULL REFERENCES service.users(id) ON DELETE CASCADE,
    card_id BIGINT NOT NULL REFERENCES service.cards(id) ON DELETE CASCADE,
    action VARCHAR(10) NOT NULL CHECK (action IN ('ADD', 'REMOVE')),
    external_content_id VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    agent_job_id VARCHAR(200),
    source_document_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    CHECK (
        (action = 'ADD' AND related_source_event_id IS NULL)
        OR (action = 'REMOVE' AND related_source_event_id IS NOT NULL)
    )
);

CREATE INDEX idx_scrap_wiki_outbox_claim
    ON service.scrap_wiki_outbox(status, next_attempt_at, id)
    WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_scrap_wiki_outbox_card_order
    ON service.scrap_wiki_outbox(user_id, card_id, id);

-- 배포 전에 이미 보관된 Agent 발행 카드는 첫 폴링에서 Wiki 편입한다.
INSERT INTO service.scrap_wiki_outbox (
    source_event_id, user_id, card_id, action, external_content_id
)
SELECT gen_random_uuid(), scrap.user_id, scrap.card_id, 'ADD', card.external_content_id
FROM service.scraps AS scrap
JOIN service.cards AS card ON card.id = scrap.card_id
WHERE card.external_content_id IS NOT NULL
  AND btrim(card.external_content_id) <> '';
